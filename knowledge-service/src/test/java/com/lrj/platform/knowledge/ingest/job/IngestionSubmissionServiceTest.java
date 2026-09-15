package com.lrj.platform.knowledge.ingest.job;

import com.lrj.platform.knowledge.authz.NoopKnowledgeAuthz;
import com.lrj.platform.knowledge.lifecycle.DocumentInfo;
import com.lrj.platform.knowledge.lifecycle.InMemoryDocumentRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionSubmissionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");
    private final InMemoryDocumentSourceStore sources = new InMemoryDocumentSourceStore();
    private final InMemoryIngestionJobStore jobs = new InMemoryIngestionJobStore();
    private final InMemoryDocumentRegistry registry = new InMemoryDocumentRegistry();
    private final IngestionSubmissionService service = new IngestionSubmissionService(
            sources,
            jobs,
            registry,
            new NoopKnowledgeAuthz(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            Set.of(IngestionSink.VECTOR, IngestionSink.REGISTRY));

    @Test
    void storesSourceAndExplicitSecurityContextBeforeCreatingDurableJob() throws Exception {
        IngestionJob job = service.submit(command("same-key", "acme"));

        assertThat(job.status()).isEqualTo(IngestionStatus.RECEIVED);
        assertThat(job.scopes()).containsExactly("ingest");
        assertThat(job.department()).isEqualTo("acme_engineering");
        assertThat(job.traceId()).isEqualTo("trace-1");
        assertThat(job.displayName()).isEqualTo("guide.txt");
        assertThat(job.category()).isEqualTo("manual");
        assertThat(job.newDocument()).isTrue();
        assertThat(job.source().objectKey())
                .startsWith("acme/doc-1/v1/sha256-")
                .endsWith("/source");
        assertThat(sources.open("acme", job.source()).readAllBytes())
                .isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void replacementMustAdvanceRegistryVersion() {
        registry.put(new DocumentInfo(
                "doc-1", "acme", "guide.txt", "text/plain",
                5, 1, 2, NOW, "manual"));

        assertThatThrownBy(() -> service.submit(command("wrong-version", "acme")))
                .isInstanceOf(IngestionJobConflictException.class)
                .hasMessageContaining("advance");
    }

    @Test
    void duplicateSubmissionReturnsOriginalJobAndTenantCannotReadIt() throws Exception {
        IngestionJob first = service.submit(command("same-key", "acme"));
        IngestionJob duplicate = service.submit(command("same-key", "acme"));

        assertThat(duplicate.jobId()).isEqualTo(first.jobId());
        assertThatThrownBy(() -> service.get("globex", first.jobId()))
                .isInstanceOf(IngestionJobNotFoundException.class);
    }

    @Test
    void replayRejectsDifferentContentMetadataAndOwner() throws Exception {
        var original = command("same-key", "acme");
        var first = service.submit(original);
        var changed = new IngestionSubmissionService.SubmitCommand(original.idempotencyKey(),
                original.tenantId(), original.userId(), original.scopes(), original.department(), original.traceId(),
                original.documentId(), original.displayName(), original.category(), original.documentVersion(),
                original.contentType(), "different".getBytes());
        assertThatThrownBy(() -> service.submit(changed)).isInstanceOf(IngestionJobConflictException.class);
        var changedMetadata = new IngestionSubmissionService.SubmitCommand(original.idempotencyKey(),
                original.tenantId(), original.userId(), original.scopes(), original.department(), original.traceId(),
                original.documentId(), original.displayName(), "other", original.documentVersion(),
                original.contentType(), original.content());
        assertThatThrownBy(() -> service.submit(changedMetadata)).isInstanceOf(IngestionJobConflictException.class);
        assertThatThrownBy(() -> service.get("acme", "bob", first.jobId()))
                .isInstanceOf(IngestionJobNotFoundException.class);
        // 即使新版本已提交，同指纹重放也必须返回旧任务，而非再次要求版本加一。
        registry.put(new DocumentInfo("doc-1", "acme", "guide.txt", "text/plain", 5, 1, 1, NOW, "manual"));
        assertThat(service.submit(original).jobId()).isEqualTo(first.jobId());
    }

    @Test
    void concurrentUniqueKeyFallbackStillChecksFingerprintAndNeverDeletesWinnerSource() throws Exception {
        var accepted = service.submit(command("same-key", "acme"));
        var racingStore = org.mockito.Mockito.mock(IngestionJobStore.class);
        org.mockito.Mockito.when(racingStore.findByIdempotency("acme", "same-key"))
                .thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.when(racingStore.createOrGet(org.mockito.ArgumentMatchers.any())).thenReturn(accepted);
        var concurrent = new IngestionSubmissionService(sources, racingStore, registry, new NoopKnowledgeAuthz(),
                Clock.fixed(NOW, ZoneOffset.UTC), Set.of(IngestionSink.VECTOR));
        var cmd = command("same-key", "acme");
        var changed = new IngestionSubmissionService.SubmitCommand(cmd.idempotencyKey(), cmd.tenantId(),
                cmd.userId(), cmd.scopes(), cmd.department(), cmd.traceId(), cmd.documentId(), "changed.txt",
                cmd.category(), cmd.documentVersion(), cmd.contentType(), cmd.content());
        assertThatThrownBy(() -> concurrent.submit(changed)).isInstanceOf(IngestionJobConflictException.class);
        assertThat(sources.open("acme", accepted.source()).readAllBytes()).isEqualTo(cmd.content());
    }

    @Test
    void manualRetryRequiresOwnerAndPreservesCompletedSinks() throws Exception {
        var job = service.submit(command("retry", "acme"));
        var started = IngestionJobStateMachine.start(job, NOW);
        var failed = IngestionJobStateMachine.preparationFailed(started, "PREPARATION_FAILED", NOW);
        jobs.save(IngestionJobStateMachine.manualReview(failed, NOW), job.revision());
        assertThatThrownBy(() -> service.retry("acme", "bob", Set.of("ingest"), job.jobId()))
                .isInstanceOf(IngestionJobNotFoundException.class);
        assertThatThrownBy(() -> service.retry("acme", "alice", Set.of(), job.jobId()))
                .isInstanceOf(IngestionAuthorizationException.class);
        assertThat(service.retry("acme", "alice", Set.of("ingest"), job.jobId()).status())
                .isEqualTo(IngestionStatus.PROCESSING);
    }

    private IngestionSubmissionService.SubmitCommand command(String key, String tenant) {
        return new IngestionSubmissionService.SubmitCommand(
                key,
                tenant,
                "alice",
                Set.of("ingest"),
                "acme_engineering",
                "trace-1",
                "doc-1",
                "guide.txt",
                "manual",
                1,
                "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));
    }
}
