package com.lrj.platform.knowledge.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.migrations.SchemaMigrationRunner;
import com.lrj.platform.migrations.SchemaName;
import com.lrj.platform.security.TenantContext;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static com.lrj.platform.knowledge.operations.KnowledgeOperations.*;

/** 从权限、修订和证据过期验证业务不变量，不以客户端passed字段代替真实检查。 */
class KnowledgeOperationsServiceTest {
    JdbcKnowledgeOperationsStore store;
    KnowledgeOperationsService service;
    KnowledgePublicationService publication;
    com.lrj.platform.knowledge.ingest.job.IngestionJobStore jobs;
    com.lrj.platform.knowledge.ingest.job.IngestionSubmissionService submissions;
    com.lrj.platform.knowledge.lifecycle.InMemoryDocumentRegistry registry;
    final TenantContext.Tenant admin = new TenantContext.Tenant("tenant-" + java.util.UUID.randomUUID(), "editor", Set.of("ingest"), "dept");
    final Draft draft = new Draft("退款政策", "三天内退款", null, List.of(new Check("退款多久", "三天")));
    @BeforeEach void prepare() {
        var ds = OperationsTestDatabase.create();
        store = new JdbcKnowledgeOperationsStore(ds, new ObjectMapper());
        service = new KnowledgeOperationsService(store, Clock.systemUTC());
        jobs = new com.lrj.platform.knowledge.ingest.job.JdbcIngestionJobStore(ds, new ObjectMapper());
        registry = new com.lrj.platform.knowledge.lifecycle.InMemoryDocumentRegistry();
        submissions = new com.lrj.platform.knowledge.ingest.job.IngestionSubmissionService(
                new com.lrj.platform.knowledge.ingest.job.InMemoryDocumentSourceStore(), jobs, registry,
                new com.lrj.platform.knowledge.authz.NoopKnowledgeAuthz(), Clock.systemUTC(),
                Set.of(com.lrj.platform.knowledge.ingest.job.IngestionSink.VECTOR, com.lrj.platform.knowledge.ingest.job.IngestionSink.REGISTRY));
        publication = new KnowledgePublicationService(service, store, submissions, jobs, registry, Clock.systemUTC());
    }
    @Test void publicationRequiresEvidenceAndWaitsForRealJobReady() {
        var c = service.create(admin, new CandidateInput("publish", null, draft));
        assertThatThrownBy(() -> publication.approve(admin, c.id(), c.version())).isInstanceOf(OperationsException.class);
        var evaluated = service.evaluate(admin, c.id(), 0);
        var approved = publication.approve(admin, c.id(), evaluated.version());
        var publishing = publication.publish(admin, c.id(), approved.version());
        assertThat(publishing.status()).isEqualTo(Status.PUBLISHING);
        var job = jobs.find(admin.tenantId(), publishing.release().jobId()).orElseThrow();
        assertThat(job.documentId()).isEqualTo(c.documentId());
        var same = publication.publish(admin, c.id(), publishing.version());
        assertThat(same.release().jobId()).isEqualTo(job.jobId());
        assertThat(jobs.findRunnable(100).stream().filter(j -> j.tenantId().equals(admin.tenantId()))).hasSize(1);
        var now = java.time.Instant.now();
        var ready = com.lrj.platform.knowledge.ingest.job.IngestionJobStateMachine.start(job, now);
        for (var sink : job.requiredSinks()) {
            ready = com.lrj.platform.knowledge.ingest.job.IngestionJobStateMachine.sinkSucceeded(
                    com.lrj.platform.knowledge.ingest.job.IngestionJobStateMachine.beginSink(ready, sink, now), sink, now);
        }
        jobs.save(ready, job.revision());
        registry.put(new com.lrj.platform.knowledge.lifecycle.DocumentInfo(c.documentId(), admin.tenantId(),
                draft.title(), "text/plain", 30, 1, 1, now, null));
        var published = publication.sync(admin, c.id(), same.version());
        assertThat(published.status()).isEqualTo(Status.PUBLISHED);
        var probed = publication.probe(admin, c.id(), published.version(), "test-only", (actor, token, candidate) ->
                List.of(new CheckResult("退款多久", true, "published_document_retrieved")));
        assertThat(probed.probe().kind()).isEqualTo("RETRIEVAL_PROBE");
        var rollback = publication.rollbackDraft(admin, c.id(), "rollback-key");
        assertThat(rollback.documentId()).isEqualTo(c.documentId());
        assertThat(rollback.status()).isEqualTo(Status.DRAFT);
        assertThat(rollback.rollbackOf()).isEqualTo(c.id());
        assertThat(rollback.evaluation()).isNull();
        assertThat(publication.rollbackDraft(admin, c.id(), "rollback-key").id()).isEqualTo(rollback.id());
        var rbEval = service.evaluate(admin, rollback.id(), rollback.version());
        var rbApproved = publication.approve(admin, rollback.id(), rbEval.version());
        var rbPublishing = publication.publish(admin, rollback.id(), rbApproved.version());
        assertThat(rbPublishing.release().documentVersion()).isEqualTo(2);

        assertThatThrownBy(() -> service.edit(admin, c.id(), published.version(), draft)).isInstanceOf(OperationsException.class);
    }

    @Test void publicationCannotBeResumedByAnUnrelatedEditor() {
        var c = service.create(admin, new CandidateInput("publish", null, draft));
        var evaluated = service.evaluate(admin, c.id(), c.version());
        var approved = publication.approve(admin, c.id(), evaluated.version());
        var publishing = publication.publish(admin, c.id(), approved.version());
        var other = new TenantContext.Tenant(admin.tenantId(), "other-editor", Set.of("ingest"));
        assertThatThrownBy(() -> publication.publish(other, c.id(), publishing.version())).isInstanceOf(OperationsException.class);
    }

    @Test void evaluationBindsContentAndEditsInvalidateIt() {
        var c = service.create(admin, new CandidateInput("key", null, draft));
        var evaluated = service.evaluate(admin, c.id(), c.version());
        assertThat(evaluated.status()).isEqualTo(Status.EVALUATED);
        assertThat(evaluated.evaluation().kind()).isEqualTo("CONTENT_CONTRACT");
        assertThat(evaluated.evaluation().passed()).isEqualTo(1);
        var changed = service.edit(admin, c.id(), evaluated.version(),
                new Draft("退款政策", "七天内退款", null, draft.checks()));
        assertThat(changed.evaluation()).isNull();
        assertThat(changed.status()).isEqualTo(Status.DRAFT);
        assertThat(service.evaluate(admin, c.id(), changed.version()).evaluation().successful()).isFalse();
        assertThatThrownBy(() -> service.edit(admin, c.id(), 0, draft)).isInstanceOf(OperationsException.class);
    }
    @Test void feedbackCanBeSubmittedByViewerButManagementCannot() {
        var viewer = new TenantContext.Tenant(admin.tenantId(), "reader", Set.of("chat"));
        var input = new FeedbackInput("key", "退款多久", Reason.NO_ANSWER, "查不到", null);
        var feedback = service.feedback(viewer, input);
        assertThat(feedback.userId()).isEqualTo("reader");
        assertThatThrownBy(() -> service.create(viewer, new CandidateInput("key", null, draft)))
                .isInstanceOf(OperationsException.class);
        var c = service.create(admin, new CandidateInput("key", feedback.id(), draft));
        assertThat(c.feedbackId()).isEqualTo(feedback.id());
        assertThatThrownBy(() -> service.get(new TenantContext.Tenant("other", "editor", Set.of("ingest")), c.id()))
                .isInstanceOf(OperationsException.class);
    }
    @Test void failedJobIsRecoveredWithoutCreatingAnotherJob() {
        var c = service.create(admin, new CandidateInput("failed", null, draft));
        var evaluated = service.evaluate(admin, c.id(), 0);
        var approved = publication.approve(admin, c.id(), evaluated.version());
        var publishing = publication.publish(admin, c.id(), approved.version());
        var job = jobs.find(admin.tenantId(), publishing.release().jobId()).orElseThrow();
        var now = java.time.Instant.now();
        var failed = com.lrj.platform.knowledge.ingest.job.IngestionJobStateMachine.preparationFailed(
                com.lrj.platform.knowledge.ingest.job.IngestionJobStateMachine.start(job, now), "test failure", now);
        jobs.save(failed, job.revision());
        var synced = publication.sync(admin, c.id(), publishing.version());
        assertThat(synced.status()).isEqualTo(Status.PUBLISH_FAILED);
        var retried = publication.publish(admin, c.id(), synced.version());
        assertThat(retried.release().jobId()).isEqualTo(job.jobId());
        assertThat(retried.status()).isEqualTo(Status.PUBLISHING);
    }
    @Test void concurrentApprovalHasOneWinnerAndOneAudit() throws Exception {
        var c = service.create(admin, new CandidateInput("concurrent", null, draft));
        var evaluated = service.evaluate(admin, c.id(), 0);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> approval = () -> {
                start.await();
                try { publication.approve(admin, c.id(), evaluated.version()); return true; }
                catch (OperationsException expected) { return false; }
            };
            var one = executor.submit(approval); var two = executor.submit(approval); start.countDown();
            assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder(true, false);
            assertThat(service.history(admin, c.id(), 20).stream().filter(a -> a.action().equals("APPROVE"))).hasSize(1);
        }
    }

    @Test void lostSubmissionAcknowledgementRecoversDurableIntentWithSameJob() throws Exception {
        var interrupted = org.mockito.Mockito.spy(submissions);
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(invocation -> {
            Object job = invocation.callRealMethod();
            if (!once.getAndSet(true)) throw new java.io.IOException("acknowledgement lost after commit");
            return job;
        }).when(interrupted).submit(org.mockito.ArgumentMatchers.any());
        var failing = new KnowledgePublicationService(service, store, interrupted, jobs, registry, Clock.systemUTC());
        var c = service.create(admin, new CandidateInput("lost", null, draft));
        var evaluated = service.evaluate(admin, c.id(), 0);
        var approved = failing.approve(admin, c.id(), evaluated.version());
        assertThatThrownBy(() -> failing.publish(admin, c.id(), approved.version())).isInstanceOf(OperationsException.class);
        var durable = service.get(admin, c.id());
        assertThat(durable.status()).isEqualTo(Status.PUBLISHING);
        assertThat(durable.release().jobId()).isNull();
        var recovered = publication.publish(admin, c.id(), durable.version());
        assertThat(recovered.release().jobId()).isNotBlank();
        assertThat(jobs.findRunnable(100).stream().filter(j -> j.tenantId().equals(admin.tenantId()))).hasSize(1);
    }

}
