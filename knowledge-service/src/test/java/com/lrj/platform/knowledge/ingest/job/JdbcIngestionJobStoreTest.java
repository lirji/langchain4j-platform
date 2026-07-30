package com.lrj.platform.knowledge.ingest.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcIngestionJobStoreTest {

    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");
    private JdbcIngestionJobStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:ingest-" + System.nanoTime()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        store = new JdbcIngestionJobStore(dataSource, new ObjectMapper());
    }

    @Test
    void persistsIdempotentlyAndIsolatesTenant() {
        IngestionJob first = store.createOrGet(job("job-1", "acme", "same"));
        IngestionJob duplicate = store.createOrGet(job("job-2", "acme", "same"));
        store.createOrGet(job("job-3", "globex", "same"));

        assertThat(duplicate.jobId()).isEqualTo(first.jobId());
        assertThat(store.find("globex", "job-1")).isEmpty();
        assertThat(store.find("globex", "job-3")).isPresent();
        IngestionJob persisted = store.find("acme", "job-1").orElseThrow();
        assertThat(persisted.scopes()).containsExactly("ingest");
        assertThat(persisted.department()).isEqualTo("dept-1");
        assertThat(persisted.traceId()).isEqualTo("trace-job-1");
    }

    @Test
    void conditionalUpdateRejectsStaleWorker() {
        IngestionJob created = store.createOrGet(job("job-1", "acme", "key"));
        IngestionJob started = store.save(
                IngestionJobStateMachine.start(created, NOW.plusSeconds(1)), 0);

        assertThat(started.revision()).isEqualTo(1);
        assertThatThrownBy(() -> store.save(
                IngestionJobStateMachine.start(created, NOW.plusSeconds(2)), 0))
                .isInstanceOf(IngestionJobConflictException.class);
        assertThat(store.find("acme", "job-1").orElseThrow().status())
                .isEqualTo(IngestionStatus.PROCESSING);
    }

    @Test
    void listsOnlyRecoverableJobs() {
        IngestionJob ready = ready(job("ready", "acme", "ready-key"));
        IngestionJob processing = IngestionJobStateMachine.start(
                job("partial", "acme", "partial-key"), NOW);
        IngestionJob partial = IngestionJobStateMachine.sinkFailed(
                IngestionJobStateMachine.beginSink(processing, IngestionSink.VECTOR, NOW),
                IngestionSink.VECTOR, "timeout", NOW);
        store.createOrGet(ready);
        store.createOrGet(partial);

        assertThat(store.findRecoverable(NOW.plusSeconds(1), 10))
                .extracting(IngestionJob::jobId)
                .containsExactly("partial");
    }

    private IngestionJob ready(IngestionJob job) {
        IngestionJob current = IngestionJobStateMachine.start(job, NOW);
        current = succeed(current, IngestionSink.VECTOR);
        current = succeed(current, IngestionSink.ELASTICSEARCH);
        return succeed(current, IngestionSink.REGISTRY);
    }

    private IngestionJob succeed(IngestionJob job, IngestionSink sink) {
        return IngestionJobStateMachine.sinkSucceeded(
                IngestionJobStateMachine.beginSink(job, sink, NOW), sink, NOW);
    }

    private IngestionJob job(String id, String tenant, String idempotencyKey) {
        return IngestionJob.received(
                id, idempotencyKey, tenant, "user",
                Set.of("ingest"), "dept-1", "trace-" + id,
                "doc-" + id, id + ".txt", "manual", 1, true,
                new DocumentSourceRef(
                        "knowledge", tenant + "/" + id, "sha256:abc", "text/plain", 12),
                Set.of(IngestionSink.VECTOR, IngestionSink.ELASTICSEARCH,
                        IngestionSink.REGISTRY),
                NOW);
    }
}
