package com.lrj.platform.knowledge.ingest.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.knowledge.authz.NoopKnowledgeAuthz;
import com.lrj.platform.knowledge.ingest.job.*;
import com.lrj.platform.knowledge.lifecycle.*;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.lrj.platform.protocol.knowledge.KnowledgeSourceFeed.*;

/** 使用真实检查点和真实入库提交器；模型/索引完成通过任务状态机模拟，不伪称模型验收。 */
class KnowledgeSyncServiceTest {
    private final javax.sql.DataSource db = SyncTestSupport.database();
    private final SyncTestSupport.MutableClock clock = new SyncTestSupport.MutableClock();
    private final ObjectMapper json = new ObjectMapper();
    private final KnowledgeSyncStore store = new KnowledgeSyncStore(db, json, clock);
    private final HttpKnowledgeSource http = mock(HttpKnowledgeSource.class);
    private final DocumentRegistry registry = new InMemoryDocumentRegistry();
    private final IngestionJobStore jobs = new JdbcIngestionJobStore(db, json);
    private final IngestionSubmissionService submissions = spy(new IngestionSubmissionService(new InMemoryDocumentSourceStore(), jobs,
            registry, new NoopKnowledgeAuthz(), clock, Set.of(IngestionSink.VECTOR, IngestionSink.REGISTRY)));
    private final KnowledgeSyncProperties.Source source = SyncTestSupport.source("tenant-" + UUID.randomUUID());
    private KnowledgeSyncService service = new KnowledgeSyncService(store, http, submissions, registry, new NoopKnowledgeAuthz(), new com.lrj.platform.audit.AuditLogger(json));
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void waitsForReadyAndResumesSnapshotAfterRestartWithoutRefetch() {
        when(http.fetch(source, "")).thenReturn(page(1, "first", "c1"));
        service.poll(source);
        assertThat(store.get(source).cursorValue).isEmpty();
        assertThat(service.status(source).status()).isEqualTo("SYNCING");
        String jobId = service.status(source).pendingJobId();
        ready(jobId);
        service = new KnowledgeSyncService(new KnowledgeSyncStore(db, json, clock), http, submissions, registry, new NoopKnowledgeAuthz(), new com.lrj.platform.audit.AuditLogger(json));
        clock.advance(); service.poll(source);
        assertThat(store.get(source).cursorValue).isEqualTo("c1");
        assertThat(store.get(source).pendingJson).isNull();
        verify(http, times(1)).fetch(source, "");
        assertThat(service.status(source).status()).isEqualTo("IDLE");
        assertThat(TenantContext.captureRaw()).isNull();
    }
    @Test void duplicateContentSkipsEmbeddingAndAToBToAUsesNewVersions() throws Exception {
        ingest(1, "A", "", "c1");
        when(http.fetch(source, "c1")).thenReturn(page(2, "A", "c2"));
        clock.advance(); service.poll(source);
        verify(submissions, times(2)).submit(any()); // 首次提交和READY确认重放。
        assertThat(store.get(source).cursorValue).isEqualTo("c2");
        ingest(3, "B", "c2", "c3");
        ingest(4, "A", "c3", "c4");
        assertThat(registry.list(source.tenantId())).singleElement().satisfies(info -> assertThat(info.version()).isEqualTo(3));
    }
    @Test void reusedRevisionBlocksButExplicitDeleteRetiresDocument() {
        ingest(1, "first", "", "c1");
        when(http.fetch(source, "c1")).thenReturn(page(1, "changed", "c2"));
        clock.advance(); service.poll(source);
        assertThat(service.status(source).errorCode()).isEqualTo("SOURCE_REVISION_REUSED");
        assertThat(store.get(source).cursorValue).isEqualTo("c1");
        when(http.fetch(source, "c1")).thenReturn(new Page(List.of(new Item("faq1", 2, null, null, true)), "c2"));
        clock.advance(); service.poll(source);
        assertThat(service.status(source).errorCode()).isNull();
        assertThat(store.get(source).cursorValue).isEqualTo("c2");
        assertThat(registry.list(source.tenantId())).isEmpty();
    }
    @Test void lostSubmissionResponseReusesOriginalJobAndDoesNotRefetch() throws Exception {
        when(http.fetch(source, "")).thenReturn(page(1, "first", "c1"));
        doAnswer(invocation -> { invocation.callRealMethod(); throw new java.io.IOException("lost response"); })
                .doCallRealMethod().when(submissions).submit(any());
        service.poll(source);
        assertThat(service.status(source).errorCode()).isEqualTo("SYNC_UNAVAILABLE");
        clock.advance(); service.poll(source);
        String jobId = service.status(source).pendingJobId();
        assertThat(jobId).isNotNull(); ready(jobId);
        clock.advance(); service.poll(source);
        assertThat(store.get(source).cursorValue).isEqualTo("c1");
        verify(http, times(1)).fetch(source, "");
        assertThat(jobs.countsByStatus().get(IngestionStatus.READY)).isGreaterThanOrEqualTo(1);
    }
    @Test void failedJobPreservesPageUntilExistingRecoveryCompletes() {
        when(http.fetch(source, "")).thenReturn(page(1, "first", "c1")); service.poll(source);
        String jobId = service.status(source).pendingJobId();
        var job = jobs.find(source.tenantId(), jobId).orElseThrow();
        jobs.save(IngestionJobStateMachine.manualReview(job, clock.instant()), job.revision());
        clock.advance(); service.poll(source);
        assertThat(service.status(source).errorCode()).isEqualTo("INGESTION_REQUIRES_ATTENTION");
        assertThat(store.get(source).cursorValue).isEmpty();
        submissions.retry(source.tenantId(), source.userId(), Set.of("ingest"), jobId);
        ready(jobId); clock.advance(); service.poll(source);
        assertThat(store.get(source).cursorValue).isEqualTo("c1");
    }
    @Test void sourceFailureRestoresCallingContextAndDoesNotSubmit() throws Exception {
        var actor = new TenantContext.Tenant("caller", "alice", Set.of("ingest")); TenantContext.set(actor);
        when(http.fetch(source, "")).thenThrow(new SyncFailure("SOURCE_HTTP_ERROR")); service.poll(source);
        assertThat(TenantContext.current()).isEqualTo(actor);
        assertThat(service.status(source).errorCode()).isEqualTo("SOURCE_HTTP_ERROR");
        verify(submissions, never()).submit(any());
    }
    @Test void entirePageMustFinishBeforeCursorMoves() {
        when(http.fetch(source, "")).thenReturn(new Page(List.of(new Item("faq1", 1, "one", "A", false),
                new Item("faq2", 1, "two", "B", false)), "c1"));
        service.poll(source); ready(service.status(source).pendingJobId());
        clock.advance(); service.poll(source);
        assertThat(service.status(source).remaining()).isEqualTo(1);
        assertThat(store.get(source).cursorValue).isEmpty();
        ready(service.status(source).pendingJobId()); clock.advance(); service.poll(source);
        assertThat(store.get(source).cursorValue).isEqualTo("c1");
    }
    @Test void externalEditOrDeletionStopsSyncInsteadOfOverwriting() {
        ingest(1, "first", "", "c1");
        registry.remove(source.tenantId(), registry.list(source.tenantId()).getFirst().docId());
        when(http.fetch(source, "c1")).thenReturn(page(2, "changed", "c2"));
        clock.advance(); service.poll(source);
        assertThat(service.status(source).errorCode()).isEqualTo("DOCUMENT_VERSION_CHANGED");
        assertThat(store.get(source).cursorValue).isEqualTo("c1");
    }
    @Test void deleteTombstoneBlocksLateWorkerAndRecreationUsesNewDocumentId() {
        ingest(1, "first", "", "c1");
        var original = registry.list(source.tenantId()).getFirst();
        when(http.fetch(source, "c1")).thenReturn(new Page(List.of(new Item("faq1", 2, null, null, true)), "c2"));
        clock.advance(); service.poll(source);
        assertThat(registry.get(source.tenantId(), original.docId())).isEmpty();
        assertThat(service.document(source, "faq1").deleted()).isTrue();
        assertThat(service.document(source, "faq1").documentId()).isEqualTo(original.docId());
        assertThat(service.document(source, "faq1").externalId()).isEqualTo("faq1");
        assertThat(registry.commitVersion(original)).isFalse();
        assertThat(registry.commitVersion(new DocumentInfo(original.docId(), original.tenantId(), "late", "text/plain", 1, 1,
                2, clock.instant(), null))).isFalse();
        ingest(3, "restored", "c2", "c3");
        assertThat(registry.list(source.tenantId())).singleElement().satisfies(info -> {
            assertThat(info.docId()).isNotEqualTo(original.docId()); assertThat(info.version()).isEqualTo(1);
        });
    }
    @Test void authorizationRevocationFailureKeepsCursorAndRetriesAfterTombstone() {
        ingest(1, "first", "", "c1");
        var auth = mock(com.lrj.platform.knowledge.authz.KnowledgeAuthz.class);
        when(auth.checkDocument(anyString(), anyString(), anyString(), eq("edit"))).thenReturn(true);
        doThrow(new IllegalStateException("dependency down")).doNothing().when(auth).onDocumentDeleted(anyString(), anyString());
        service = new KnowledgeSyncService(store, http, submissions, registry, auth, new com.lrj.platform.audit.AuditLogger(json));
        when(http.fetch(source, "c1")).thenReturn(new Page(List.of(new Item("faq1", 2, null, null, true)), "c2"));
        clock.advance(); service.poll(source);
        assertThat(store.get(source).cursorValue).isEqualTo("c1");
        assertThat(registry.list(source.tenantId())).isEmpty();
        clock.advance(); service.poll(source);
        assertThat(store.get(source).cursorValue).isEqualTo("c2");
        verify(auth, times(1)).checkDocument(anyString(), anyString(), anyString(), eq("edit"));
        verify(auth, times(2)).onDocumentDeleted(anyString(), anyString());
    }
    @Test void deleteWithoutEditPermissionDoesNotHideDocument() {
        ingest(1, "first", "", "c1");
        var auth = mock(com.lrj.platform.knowledge.authz.KnowledgeAuthz.class);
        service = new KnowledgeSyncService(store, http, submissions, registry, auth, new com.lrj.platform.audit.AuditLogger(json));
        when(http.fetch(source, "c1")).thenReturn(new Page(List.of(new Item("faq1", 2, null, null, true)), "c2"));
        clock.advance(); service.poll(source);
        assertThat(service.status(source).errorCode()).isEqualTo("INGESTION_FORBIDDEN");
        assertThat(registry.list(source.tenantId())).hasSize(1);
        assertThat(store.get(source).cursorValue).isEqualTo("c1");
    }
    @Test void caseDistinctTenantsWithSameSourceAndExternalIdCreateIndependentJobs() {
        var other = SyncTestSupport.source(source.tenantId().toUpperCase());
        when(http.fetch(source, "")).thenReturn(page(1, "first", "c1"));
        when(http.fetch(other, "")).thenReturn(page(1, "other", "c1"));
        service.poll(source); service.poll(other);
        String firstJob = service.status(source).pendingJobId(), otherJob = service.status(other).pendingJobId();
        assertThat(firstJob).isNotNull(); assertThat(otherJob).isNotNull().isNotEqualTo(firstJob);
        assertThat(jobs.find(source.tenantId(), firstJob).orElseThrow().documentId())
                .isNotEqualTo(jobs.find(other.tenantId(), otherJob).orElseThrow().documentId());
    }
    private Page page(long revision, String content, String cursor) { return new Page(List.of(new Item("faq1", revision, "FAQ", content, false)), cursor); }
    private void ingest(long revision, String content, String before, String after) {
        when(http.fetch(source, before)).thenReturn(page(revision, content, after));
        clock.advance(); service.poll(source); ready(service.status(source).pendingJobId()); clock.advance(); service.poll(source);
    }
    private void ready(String id) {
        var job = jobs.find(source.tenantId(), id).orElseThrow();
        var next = job.status() == IngestionStatus.RECEIVED ? IngestionJobStateMachine.start(job, clock.instant()) : job;
        for (var sink : List.of(IngestionSink.VECTOR, IngestionSink.REGISTRY))
            next = IngestionJobStateMachine.sinkSucceeded(IngestionJobStateMachine.beginSink(next, sink, clock.instant()), sink, clock.instant());
        registry.commitVersion(new DocumentInfo(job.documentId(), job.tenantId(), job.displayName(), "text/plain", 1, 1,
                (int) job.documentVersion(), clock.instant(), null));
        jobs.save(next, job.revision());
    }
}
