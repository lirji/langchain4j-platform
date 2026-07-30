package com.lrj.platform.knowledge.ingest.job;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/** 纯状态机；持久化适配器必须用版本/条件更新保证并发 worker 不覆盖彼此结果。 */
public final class IngestionJobStateMachine {

    private IngestionJobStateMachine() {
    }

    public static IngestionJob start(IngestionJob job, Instant now) {
        requireStatus(job, IngestionStatus.RECEIVED);
        return copy(job, IngestionStatus.PROCESSING, job.sinks(), null, now);
    }

    public static IngestionJob sinkSucceeded(IngestionJob job, IngestionSink sink, Instant now) {
        requireMutable(job);
        requireSinkState(job, sink, IngestionSinkState.RUNNING);
        Map<IngestionSink, IngestionSinkState> states = update(job, sink, IngestionSinkState.SUCCEEDED);
        boolean ready = job.requiredSinks().stream()
                .allMatch(required -> states.get(required) == IngestionSinkState.SUCCEEDED)
                && states.values().stream().noneMatch(state ->
                        state == IngestionSinkState.PENDING
                                || state == IngestionSinkState.RUNNING);
        return copy(job, ready ? IngestionStatus.READY : IngestionStatus.PROCESSING,
                states, null, now);
    }

    public static IngestionJob sinkFailed(
            IngestionJob job,
            IngestionSink sink,
            String error,
            Instant now
    ) {
        requireMutable(job);
        requireSinkState(job, sink, IngestionSinkState.RUNNING);
        String normalized = error == null || error.isBlank() ? "sink failed" : error.trim();
        return copy(job, IngestionStatus.PARTIAL,
                update(job, sink, IngestionSinkState.FAILED), normalized, now);
    }

    public static IngestionJob retry(IngestionJob job, Instant now) {
        if (job.status() != IngestionStatus.PARTIAL && job.status() != IngestionStatus.FAILED) {
            throw new IllegalStateException("only PARTIAL or FAILED jobs can be retried");
        }
        EnumMap<IngestionSink, IngestionSinkState> states = new EnumMap<>(job.sinks());
        if (states.entrySet().stream().anyMatch(entry ->
                entry.getValue() == IngestionSinkState.FAILED && !entry.getKey().idempotent())) {
            throw new IllegalStateException("non-idempotent failed sink requires manual recovery");
        }
        states.replaceAll((sink, state) ->
                state == IngestionSinkState.FAILED ? IngestionSinkState.PENDING : state);
        return copy(job, IngestionStatus.PROCESSING, states, null, now);
    }

    public static IngestionJob resumeStaleProcessing(IngestionJob job, Instant now) {
        requireStatus(job, IngestionStatus.PROCESSING);
        EnumMap<IngestionSink, IngestionSinkState> states = new EnumMap<>(job.sinks());
        if (states.entrySet().stream().anyMatch(entry ->
                entry.getValue() == IngestionSinkState.RUNNING && !entry.getKey().idempotent())) {
            throw new IllegalStateException("non-idempotent running sink requires manual recovery");
        }
        states.replaceAll((sink, state) ->
                state == IngestionSinkState.RUNNING ? IngestionSinkState.PENDING : state);
        return copy(job, IngestionStatus.PROCESSING, states, job.error(), now);
    }

    public static IngestionJob beginSink(IngestionJob job, IngestionSink sink, Instant now) {
        requireMutable(job);
        requireSinkState(job, sink, IngestionSinkState.PENDING);
        return copy(job, IngestionStatus.PROCESSING,
                update(job, sink, IngestionSinkState.RUNNING), null, now);
    }

    public static IngestionJob preparationFailed(
            IngestionJob job,
            String error,
            Instant now
    ) {
        requireStatus(job, IngestionStatus.PROCESSING);
        String normalized = error == null || error.isBlank()
                ? "document preparation failed"
                : error.trim();
        return copy(job, IngestionStatus.FAILED, job.sinks(), normalized, now);
    }

    private static Map<IngestionSink, IngestionSinkState> update(
            IngestionJob job,
            IngestionSink sink,
            IngestionSinkState state
    ) {
        if (!job.sinks().containsKey(sink)) {
            throw new IllegalArgumentException("sink is not enabled: " + sink);
        }
        EnumMap<IngestionSink, IngestionSinkState> states = new EnumMap<>(job.sinks());
        states.put(sink, state);
        return states;
    }

    private static void requireMutable(IngestionJob job) {
        if (job.status() != IngestionStatus.PROCESSING && job.status() != IngestionStatus.PARTIAL) {
            throw new IllegalStateException("job is not processing: " + job.status());
        }
    }

    private static void requireStatus(IngestionJob job, IngestionStatus expected) {
        if (job.status() != expected) {
            throw new IllegalStateException("expected " + expected + " but was " + job.status());
        }
    }

    private static void requireSinkState(
            IngestionJob job,
            IngestionSink sink,
            IngestionSinkState expected
    ) {
        if (!job.sinks().containsKey(sink)) {
            throw new IllegalArgumentException("sink is not enabled: " + sink);
        }
        if (job.sinks().get(sink) != expected) {
            throw new IllegalStateException(
                    "expected sink " + sink + " to be " + expected);
        }
    }

    private static IngestionJob copy(
            IngestionJob job,
            IngestionStatus status,
            Map<IngestionSink, IngestionSinkState> sinks,
            String error,
            Instant now
    ) {
        return new IngestionJob(job.jobId(), job.idempotencyKey(), job.tenantId(), job.userId(),
                job.scopes(), job.department(), job.traceId(),
                job.documentId(), job.displayName(), job.category(),
                job.documentVersion(), job.newDocument(), job.revision(), job.source(), status, sinks,
                job.requiredSinks(), error, job.createdAt(), now);
    }
}
