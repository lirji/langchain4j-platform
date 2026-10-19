package com.lrj.platform.knowledge.ingest.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.lrj.platform.knowledge.ingest.job.PreparedIngestionFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PreparedIngestionRecoveryTest {
    private final InMemoryIngestionJobStore jobs = spy(new InMemoryIngestionJobStore());
    private final InMemoryDocumentSourceStore objects = spy(new InMemoryDocumentSourceStore());
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final IngestionPreparedStore results = new ObjectStorageIngestionPreparedStore(
            objects, new ObjectMapper(), new IngestionPreparedProperties(), Map.of());

    @Test
    void legacyReceivedCanStartButOldProcessingCannotBeSilentlyReprepared() {
        var received = legacy(job("old-received"));
        var processing = IngestionJobStateMachine.start(legacy(job("old-processing")), NOW);
        jobs.createOrGet(received);
        jobs.createOrGet(processing);
        var count = new AtomicInteger();
        try (var worker = worker(j -> { count.incrementAndGet(); return prepared(j); }, (j, p, s) -> {})) {
            worker.process("acme", received.jobId());
            worker.process("acme", processing.jobId());
        }
        assertThat(jobs.find("acme", received.jobId()).orElseThrow().status()).isEqualTo(IngestionStatus.READY);
        var rejected = jobs.find("acme", processing.jobId()).orElseThrow();
        assertThat(rejected.status()).isEqualTo(IngestionStatus.MANUAL_REVIEW);
        assertThat(rejected.execution().errorCode()).isEqualTo(PreparedResultError.LEGACY.code());
        assertThat(count).hasValue(1);
    }

    @Test
    void unavailableBoundObjectRetriesWithoutRepreparingOrWriting() throws Exception {
        var job = job("missing");
        var bound = results.save(job, prepared(job));
        jobs.createOrGet(job.withPreparation(bound));
        doThrow(new IOException("private endpoint")).when(objects).open(anyString(), any());
        try (var worker = worker(j -> { throw new AssertionError("must never regenerate"); },
                (j, p, s) -> fail("no side effects for an unavailable result"))) {
            worker.process("acme", job.jobId());
        }
        var failed = jobs.find("acme", job.jobId()).orElseThrow();
        assertThat(failed.preparation()).isEqualTo(bound);
        assertThat(failed.execution().errorCode()).isEqualTo(PreparedResultError.UNAVAILABLE.code());
        assertThat(failed.execution().nextRetryAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(failed.error()).doesNotContain("private endpoint");
    }

    @Test
    void lostLeaseAfterObjectWriteCannotBindOrStartAnySink() throws Exception {
        var job = jobs.createOrGet(job("lost"));
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            var current = jobs.find("acme", job.jobId()).orElseThrow();
            jobs.save(current.withExecution(current.execution().lease("replacement", NOW.plusSeconds(60))), current.revision());
            return result;
        }).when(objects).put(any());
        try (var worker = worker(PreparedIngestionFixtures::prepared, (j, p, s) -> fail("lost lease"))) {
            assertThat(worker.process("acme", job.jobId())).isFalse();
        }
        assertThat(jobs.find("acme", job.jobId()).orElseThrow().preparation()).isEqualTo(IngestionPreparation.PENDING);
        verify(objects).put(any());
        verify(objects, never()).delete(anyString(), any());
    }

    @Test
    void lostCasResponseKeepsCommittedReferenceAndLaterWorkerLoadsIt() {
        var job = jobs.createOrGet(job("unknown-commit"));
        var failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            IngestionJob current = invocation.getArgument(0);
            Object saved = invocation.callRealMethod();
            if (current.preparation() != null && current.preparation().bound() && failOnce.getAndSet(false)) {
                throw new IllegalStateException("response lost after database commit");
            }
            return saved;
        }).when(jobs).save(any(), anyLong());
        try (var worker = worker(PreparedIngestionFixtures::prepared, (j, p, s) -> fail("uncertain owner"))) {
            assertThat(worker.process("acme", job.jobId())).isFalse();
        }
        var reference = jobs.find("acme", job.jobId()).orElseThrow().preparation();
        assertThat(reference.bound()).isTrue();
        var recoveryClock = Clock.offset(clock, Duration.ofMinutes(16));
        assertThat(new IngestionReconciler(jobs, recoveryClock, new IngestionJobProperties()).reconcile()).isEqualTo(1);
        try (var restarted = new IngestionJobWorker(jobs, j -> { throw new AssertionError("must load committed result"); },
                (j, p, s) -> assertThat(p).isEqualTo(prepared(j)), new NoopIngestionTaskLifecycle(),
                recoveryClock, new IngestionJobProperties(), results)) {
            assertThat(restarted.process("acme", job.jobId())).isTrue();
        }
        assertThat(jobs.find("acme", job.jobId()).orElseThrow().preparation()).isEqualTo(reference);
    }

    private IngestionJobWorker worker(IngestionDocumentPreparer preparer, IngestionSinkProcessor processor) {
        return new IngestionJobWorker(jobs, preparer, processor, new NoopIngestionTaskLifecycle(),
                clock, new IngestionJobProperties(), results);
    }
    private static IngestionJob legacy(IngestionJob job) {
        return new IngestionJob(job.jobId(), job.idempotencyKey(), job.tenantId(), job.userId(), job.scopes(),
                job.department(), job.traceId(), job.documentId(), job.displayName(), job.category(), job.documentVersion(),
                job.newDocument(), job.revision(), job.source(), job.status(), job.sinks(), job.requiredSinks(),
                job.error(), job.createdAt(), job.updatedAt(), job.execution());
    }
}
