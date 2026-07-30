package com.lrj.platform.knowledge.ingest.job;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryIngestionJobStoreTest {

    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Test
    void createIsIdempotentPerTenantAndKey() {
        InMemoryIngestionJobStore store = new InMemoryIngestionJobStore();
        IngestionJob first = store.createOrGet(job("job-1", "acme", "same"));
        IngestionJob duplicate = store.createOrGet(job("job-2", "acme", "same"));
        IngestionJob otherTenant = store.createOrGet(job("job-3", "globex", "same"));

        assertThat(duplicate.jobId()).isEqualTo(first.jobId());
        assertThat(otherTenant.jobId()).isEqualTo("job-3");
    }

    @Test
    void findNeverCrossesTenantBoundary() {
        InMemoryIngestionJobStore store = new InMemoryIngestionJobStore();
        store.createOrGet(job("job-1", "acme", "key"));

        assertThat(store.find("acme", "job-1")).isPresent();
        assertThat(store.find("globex", "job-1")).isEmpty();
    }

    @Test
    void staleRevisionCannotOverwriteNewerWorker() {
        InMemoryIngestionJobStore store = new InMemoryIngestionJobStore();
        IngestionJob received = store.createOrGet(job("job-1", "acme", "key"));
        IngestionJob started = store.save(
                IngestionJobStateMachine.start(received, NOW.plusSeconds(1)),
                received.revision());

        assertThat(started.revision()).isEqualTo(1);
        assertThatThrownBy(() -> store.save(
                IngestionJobStateMachine.start(received, NOW.plusSeconds(2)),
                received.revision()))
                .isInstanceOf(IngestionJobConflictException.class);
    }

    @Test
    void reconcilerRetriesPartialAndStaleProcessingJobs() {
        InMemoryIngestionJobStore store = new InMemoryIngestionJobStore();
        IngestionJob started = IngestionJobStateMachine.start(
                job("job-1", "acme", "one"), NOW.minusSeconds(100));
        IngestionJob partial = IngestionJobStateMachine.sinkFailed(
                IngestionJobStateMachine.beginSink(
                        started, IngestionSink.VECTOR, NOW.minusSeconds(95)),
                IngestionSink.VECTOR, "timeout", NOW.minusSeconds(90));
        IngestionJob stale = IngestionJobStateMachine.start(
                job("job-2", "acme", "two"), NOW.minusSeconds(100));
        store.createOrGet(partial);
        store.createOrGet(stale);

        IngestionReconciler reconciler = new IngestionReconciler(
                store,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30),
                10);

        assertThat(reconciler.reconcile()).isEqualTo(2);
        assertThat(store.find("acme", "job-1").orElseThrow().status())
                .isEqualTo(IngestionStatus.PROCESSING);
        assertThat(store.find("acme", "job-2").orElseThrow().revision()).isEqualTo(1);
    }

    private IngestionJob job(String jobId, String tenant, String idempotencyKey) {
        return IngestionJob.received(
                jobId,
                idempotencyKey,
                tenant,
                "user",
                Set.of("ingest"),
                "dept-1",
                "trace-" + jobId,
                "doc-" + jobId,
                jobId + ".txt",
                "manual",
                1,
                true,
                new DocumentSourceRef(
                        "knowledge", tenant + "/" + jobId, "sha256:abc", "text/plain", 12),
                Set.of(IngestionSink.VECTOR, IngestionSink.ELASTICSEARCH,
                        IngestionSink.REGISTRY),
                NOW);
    }
}
