package com.lrj.platform.knowledge.ingest.job;

import com.lrj.platform.knowledge.authz.AuthzMode;
import com.lrj.platform.knowledge.authz.KnowledgeAuthz;
import com.lrj.platform.knowledge.authz.NoopKnowledgeAuthz;
import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.InMemoryDocumentRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IngestionContentDedupTest {
    private final InMemoryIngestionJobStore jobs = new InMemoryIngestionJobStore();
    private final InMemoryDocumentRegistry registry = new InMemoryDocumentRegistry();
    private final DocumentSourceStore sources = spy(new InMemoryDocumentSourceStore());
    private final IngestionSubmissionService service = service(new NoopKnowledgeAuthz());

    @Test
    void renamedSameBytesReusePendingWorkAndDoNotWriteAnotherSource() throws Exception {
        var first = upload(service, command("one", "tenant", "alice", "doc-1", 1, "hello"));
        var failure = duplicate(service, command("two", "tenant", "alice", "renamed", 1, "hello"));
        assertThat(failure.kind()).isEqualTo(IngestionDuplicateException.Kind.PROCESSING);
        assertThat(failure.duplicate().jobId()).isEqualTo(first.jobId());
        assertThat(failure.duplicate().documentAvailable()).isFalse();
        assertThat(jobs.findByIdempotency("tenant", "two")).isEmpty();
        verify(sources, times(1)).putFile(any(), any());
        assertThat(upload(service, command("one", "tenant", "alice", "doc-1", 1, "hello"))).isEqualTo(first);
    }

    @Test
    void otherUploaderSeesPendingResultWithoutPrivateTaskOrDocumentMetadata() throws Exception {
        var first = upload(service, command("one", "tenant", "alice", "doc-1", 1, "hello"));
        var failure = duplicate(service, command("two", "tenant", "bob", "doc-2", 1, "hello"));
        assertThat(failure.kind()).isEqualTo(IngestionDuplicateException.Kind.PROCESSING);
        assertThat(failure.duplicate()).isEqualTo(new IngestionDuplicateException.Reference(null, null, null, null, false));
        assertThatThrownBy(() -> service.get("tenant", "bob", first.jobId())).isInstanceOf(IngestionJobNotFoundException.class);
    }

    @Test
    void publishedHistoricalContentIsRecognizedWithoutBackfillOrAnotherUsersJob() throws Exception {
        var first = upload(service, command("one", "tenant", "alice", "doc-1", 1, "hello"));
        publish(first);
        var failure = duplicate(service, command("two", "tenant", "bob", "renamed", 1, "hello"));
        assertThat(failure.kind()).isEqualTo(IngestionDuplicateException.Kind.READY);
        assertThat(failure.duplicate()).isEqualTo(new IngestionDuplicateException.Reference("doc-1", "doc-1.txt", 1L, null, true));
        assertThat(jobs.countsByStatus()).containsEntry(IngestionStatus.READY, 1L);
    }

    @Test
    void publishedLegacyCopyTakesPriorityOverOlderFailedDuplicate() throws Exception {
        var first = upload(service, command("one", "tenant", "alice", "doc-1", 1, "hello"));
        publish(first);
        var old = IngestionJob.received("000-legacy", "old-key", "tenant", "alice", Set.of("ingest"), "dept", "trace",
                "old-doc", "old.txt", null, 1, true, first.source(), Set.of(IngestionSink.VECTOR), Instant.now());
        jobs.createOrGet(IngestionJobStateMachine.preparationFailed(IngestionJobStateMachine.start(old, Instant.now()), "TEST", Instant.now()));
        var failure = duplicate(service, command("new", "tenant", "alice", "new-doc", 1, "hello"));
        assertThat(failure.kind()).isEqualTo(IngestionDuplicateException.Kind.READY);
        assertThat(failure.duplicate().documentId()).isEqualTo("doc-1");
    }

    @Test
    void tenantsAndDifferentContentRemainIndependent() throws Exception {
        upload(service, command("one", "tenant", "alice", "doc-1", 1, "hello"));
        assertThat(upload(service, command("two", "other", "bob", "doc-1", 1, "hello")).tenantId()).isEqualTo("other");
        assertThat(upload(service, command("three", "tenant", "bob", "doc-2", 1, "changed")).documentId()).isEqualTo("doc-2");
    }

    @Test
    void enforceNeverRevealsUnreadableDocumentsButFindsExplicitlySharedOnes() throws Exception {
        var auth = mock(KnowledgeAuthz.class);
        when(auth.mode()).thenReturn(AuthzMode.ENFORCE);
        var secured = service(auth);
        var first = upload(secured, command("one", "tenant", "alice", "private", 1, "hello"));
        publish(first);
        var separate = upload(secured, command("two", "tenant", "bob", "bobs-copy", 1, "hello"));
        assertThat(separate.userId()).isEqualTo("bob");
        when(auth.filterReadable(eq("tenant"), eq("carol"), anySet())).thenReturn(Set.of("private"));
        var failure = duplicate(secured, command("three", "tenant", "carol", "carols-copy", 1, "hello"));
        assertThat(failure.duplicate().documentId()).isEqualTo("private");
        assertThat(failure.duplicate().jobId()).isNull();
    }

    @Test
    void privatePendingUploadsRetainOwnershipUntilSharingIsGranted() throws Exception {
        var auth = mock(KnowledgeAuthz.class);
        when(auth.mode()).thenReturn(AuthzMode.ENFORCE);
        var secured = service(auth);
        upload(secured, command("one", "tenant", "alice", "doc-1", 1, "hello"));
        assertThat(upload(secured, command("two", "tenant", "bob", "doc-2", 1, "hello")).userId()).isEqualTo("bob");
        assertThat(duplicate(secured, command("three", "tenant", "alice", "doc-3", 1, "hello")).duplicate().documentId()).isEqualTo("doc-1");
    }

    @Test
    void unchangedVersionIsRejectedAndChangedVersionCanAdvance() throws Exception {
        var first = upload(service, command("one", "tenant", "alice", "doc-1", 1, "hello"));
        publish(first);
        assertThat(duplicate(service, command("two", "tenant", "alice", "doc-1", 2, "hello")).kind())
                .isEqualTo(IngestionDuplicateException.Kind.READY);
        var updated = upload(service, command("three", "tenant", "alice", "doc-1", 2, "changed"));
        publish(updated);
        assertThat(upload(service, command("four", "tenant", "alice", "old-content", 1, "hello")).status()).isEqualTo(IngestionStatus.RECEIVED);
    }

    @Test
    void retiredAndRemovedDocumentsDoNotPermanentlyBlockReupload() throws Exception {
        var first = upload(service, command("one", "tenant", "alice", "doc-1", 1, "hello"));
        publish(first);
        registry.retireVersion("tenant", "doc-1", 1);
        var next = upload(service, command("two", "tenant", "alice", "doc-2", 1, "hello"));
        publish(next);
        registry.remove("tenant", "doc-2");
        assertThat(upload(service, command("three", "tenant", "alice", "doc-3", 1, "hello")).status()).isEqualTo(IngestionStatus.RECEIVED);
    }

    @Test
    void failedTaskOffersOriginalRecoveryInsteadOfCreatingDuplicateWork() throws Exception {
        var first = upload(service, command("one", "tenant", "alice", "doc-1", 1, "hello"));
        jobs.save(IngestionJobStateMachine.preparationFailed(IngestionJobStateMachine.start(first, Instant.now()), "TEST", Instant.now()), first.revision());
        var failure = duplicate(service, command("two", "tenant", "alice", "doc-2", 1, "hello"));
        assertThat(failure.kind()).isEqualTo(IngestionDuplicateException.Kind.ATTENTION);
        assertThat(failure.duplicate().jobId()).isEqualTo(first.jobId());
        assertThat(service.retry("tenant", "alice", Set.of("ingest"), first.jobId()).status()).isEqualTo(IngestionStatus.PROCESSING);
        assertThat(duplicate(service, command("three", "tenant", "bob", "doc-3", 1, "hello")).kind())
                .isEqualTo(IngestionDuplicateException.Kind.PROCESSING);
    }

    @Test
    void managedSourcesKeepIndependentIdentityWhileUploadsCanFindTheirPublishedContent() throws Exception {
        var first = service.submit(command("source-one", "tenant", "alice", "source-1", 1, "hello"));
        var second = service.submit(command("source-two", "tenant", "alice", "source-2", 1, "hello"));
        assertThat(second.documentId()).isEqualTo("source-2");
        publish(first);
        assertThat(duplicate(service, command("upload", "tenant", "bob", "new-file", 1, "hello")).kind())
                .isEqualTo(IngestionDuplicateException.Kind.READY);
    }

    @Test
    void sameFileNameWithDifferentBytesCanCreateIndependentDocument() throws Exception {
        upload(service, command("one", "tenant", "alice", "doc-1", 1, "hello"));
        var changed = new IngestionSubmissionService.SubmitCommand("two", "tenant", "alice", Set.of("ingest"), "dept",
                "trace", "doc-2", "doc-1.txt", null, 1, "text/plain", "changed".getBytes(StandardCharsets.UTF_8));
        assertThat(upload(service, changed).documentId()).isEqualTo("doc-2");
    }

    @Test
    void exhaustedCandidateBudgetFailsClosedInsteadOfSilentlySkippingContentCheck() throws Exception {
        var first = upload(service, command("one", "tenant", "alice", "doc-1", 1, "hello"));
        var second = command("two", "tenant", "bob", "doc-2", 1, "hello");
        jobs.createOrGet(IngestionJob.received("legacy", second.idempotencyKey(), second.tenantId(), second.userId(), second.scopes(),
                second.department(), second.traceId(), second.documentId(), second.displayName(), null, 1, true,
                first.source(), Set.of(IngestionSink.VECTOR), Instant.now()));
        var auth = mock(KnowledgeAuthz.class);
        when(auth.mode()).thenReturn(AuthzMode.ENFORCE);
        var limits = new IngestionResourceProperties(); limits.setMaxContentCandidates(1);
        var bounded = new IngestionSubmissionService(sources, jobs, registry, auth, Clock.systemUTC(), Set.of(IngestionSink.VECTOR), limits);
        assertThatThrownBy(() -> upload(bounded, command("three", "tenant", "carol", "doc-3", 1, "hello")))
                .isInstanceOfSatisfying(IngestionResourceException.class, e -> assertThat(e.error()).isEqualTo(IngestionResourceError.DEDUPLICATION_BUSY));
        assertThat(jobs.findByIdempotency("tenant", "three")).isEmpty();
    }

    private IngestionSubmissionService service(KnowledgeAuthz auth) {
        return new IngestionSubmissionService(sources, jobs, registry, auth, Clock.systemUTC(), Set.of(IngestionSink.VECTOR, IngestionSink.REGISTRY));
    }

    private IngestionDuplicateException duplicate(IngestionSubmissionService target, IngestionSubmissionService.SubmitCommand command) {
        return catchThrowableOfType(() -> upload(target, command), IngestionDuplicateException.class);
    }

    private void publish(IngestionJob job) {
        var ready = IngestionJobStateMachine.start(job, Instant.now());
        for (var sink : job.requiredSinks()) ready = IngestionJobStateMachine.sinkSucceeded(
                IngestionJobStateMachine.beginSink(ready, sink, Instant.now()), sink, Instant.now());
        jobs.save(ready, job.revision());
        registry.put(new DocumentInfo(job.documentId(), job.tenantId(), job.displayName(), "text/plain", job.source().size(),
                1, (int) job.documentVersion(), Instant.now(), null));
    }

    static IngestionJob upload(IngestionSubmissionService target, IngestionSubmissionService.SubmitCommand command) throws Exception {
        byte[] bytes = command.content();
        return target.submitStream(command.request(), new java.io.ByteArrayInputStream(bytes), bytes.length);
    }

    static IngestionSubmissionService.SubmitCommand command(String key, String tenant, String user, String doc, long version, String content) {
        return new IngestionSubmissionService.SubmitCommand(key, tenant, user, Set.of("ingest"), "dept", "trace", doc, doc + ".txt",
                null, version, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }
}
