package com.lrj.platform.knowledge.ingest.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 把可恢复任务重新置为 PROCESSING。真正执行 sink 的 worker 仍需先通过 store revision 抢占，
 * 本类不做无条件副作用重试。
 */
public class IngestionReconciler {

    private final IngestionJobStore store;
    private final Clock clock;
    private final Duration processingTimeout;
    private final int batchSize;

    public IngestionReconciler(
            IngestionJobStore store,
            Clock clock,
            Duration processingTimeout,
            int batchSize
    ) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
        this.processingTimeout = Objects.requireNonNull(processingTimeout);
        if (processingTimeout.isNegative() || processingTimeout.isZero() || batchSize < 1) {
            throw new IllegalArgumentException("positive timeout and batchSize are required");
        }
        this.batchSize = batchSize;
    }

    public int reconcile() {
        Instant now = clock.instant();
        int reconciled = 0;
        for (IngestionJob job : store.findRecoverable(now.minus(processingTimeout), batchSize)) {
            IngestionJob candidate = job.status() == IngestionStatus.PROCESSING
                    ? IngestionJobStateMachine.resumeStaleProcessing(job, now)
                    : IngestionJobStateMachine.retry(job, now);
            try {
                store.save(candidate, job.revision());
                reconciled++;
            } catch (IngestionJobConflictException ignored) {
                // Another worker/reconciler advanced this job; never overwrite its state.
            }
        }
        return reconciled;
    }
}
