package com.lrj.platform.knowledge.ingest.job;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionJobStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");

    @Test
    void becomesReadyOnlyAfterEveryRequiredSinkSucceeds() {
        IngestionJob job = IngestionJobStateMachine.start(job(), NOW.plusSeconds(1));

        job = succeed(job, IngestionSink.VECTOR, NOW.plusSeconds(2));
        job = succeed(job, IngestionSink.ELASTICSEARCH, NOW.plusSeconds(3));

        assertThat(job.status()).isEqualTo(IngestionStatus.PROCESSING);

        job = succeed(job, IngestionSink.REGISTRY, NOW.plusSeconds(4));

        assertThat(job.status()).isEqualTo(IngestionStatus.PROCESSING);

        job = succeed(job, IngestionSink.GRAPH, NOW.plusSeconds(5));

        assertThat(job.status()).isEqualTo(IngestionStatus.READY);
        assertThat(job.sinks().get(IngestionSink.GRAPH)).isEqualTo(IngestionSinkState.SUCCEEDED);
    }

    @Test
    void failedSinkProducesPartialAndRetryPreservesSuccessfulSinks() {
        IngestionJob job = IngestionJobStateMachine.start(job(), NOW.plusSeconds(1));
        job = succeed(job, IngestionSink.VECTOR, NOW.plusSeconds(2));
        job = fail(job, IngestionSink.ELASTICSEARCH, "timeout", NOW.plusSeconds(3));

        assertThat(job.status()).isEqualTo(IngestionStatus.PARTIAL);
        assertThat(job.error()).isEqualTo("timeout");

        job = IngestionJobStateMachine.retry(job, NOW.plusSeconds(4));

        assertThat(job.status()).isEqualTo(IngestionStatus.PROCESSING);
        assertThat(job.sinks().get(IngestionSink.VECTOR)).isEqualTo(IngestionSinkState.SUCCEEDED);
        assertThat(job.sinks().get(IngestionSink.ELASTICSEARCH))
                .isEqualTo(IngestionSinkState.PENDING);
    }

    @Test
    void rejectsUnknownSinkAndTerminalMutation() {
        IngestionJob processing = IngestionJobStateMachine.start(job(), NOW.plusSeconds(1));

        assertThatThrownBy(() -> IngestionJobStateMachine.sinkSucceeded(
                processing, IngestionSink.AUTHORIZATION, NOW.plusSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class);

        IngestionJob ready = succeed(processing, IngestionSink.VECTOR, NOW.plusSeconds(2));
        ready = succeed(ready, IngestionSink.ELASTICSEARCH, NOW.plusSeconds(3));
        ready = succeed(ready, IngestionSink.REGISTRY, NOW.plusSeconds(4));
        ready = succeed(ready, IngestionSink.GRAPH, NOW.plusSeconds(5));
        IngestionJob terminal = ready;

        assertThatThrownBy(() -> IngestionJobStateMachine.sinkFailed(
                terminal, IngestionSink.GRAPH, "late", NOW.plusSeconds(5)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sourceReferenceRejectsTraversal() {
        assertThatThrownBy(() -> new DocumentSourceRef(
                "knowledge", "../other-tenant/file", "sha256:abc", "text/plain", 12))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private IngestionJob job() {
        return IngestionJob.received(
                "job-1",
                "acme:sha256:abc:1",
                "acme",
                "alice",
                Set.of("ingest"),
                "acme_engineering",
                "trace-1",
                "doc-1",
                "guide.txt",
                "manual",
                1,
                true,
                new DocumentSourceRef(
                        "knowledge", "acme/doc-1/1/source", "sha256:abc", "text/plain", 12),
                Set.of(
                        IngestionSink.VECTOR,
                        IngestionSink.ELASTICSEARCH,
                        IngestionSink.GRAPH,
                        IngestionSink.REGISTRY),
                NOW);
    }

    private IngestionJob succeed(IngestionJob job, IngestionSink sink, Instant now) {
        return IngestionJobStateMachine.sinkSucceeded(
                IngestionJobStateMachine.beginSink(job, sink, now), sink, now);
    }

    private IngestionJob fail(
            IngestionJob job,
            IngestionSink sink,
            String error,
            Instant now
    ) {
        return IngestionJobStateMachine.sinkFailed(
                IngestionJobStateMachine.beginSink(job, sink, now), sink, error, now);
    }
}
