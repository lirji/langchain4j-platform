package com.lrj.platform.knowledge.ingest.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/**
 * 持久化任务轮询器。多实例可同时运行，实际任务与 sink 抢占由 revision 条件更新保证。
 */
public class IngestionWorkerLoop {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorkerLoop.class);

    private final IngestionJobStore store;
    private final IngestionJobWorker worker;
    private final IngestionReconciler reconciler;
    private final int batchSize;

    public IngestionWorkerLoop(
            IngestionJobStore store,
            IngestionJobWorker worker,
            IngestionReconciler reconciler,
            int batchSize
    ) {
        this.store = Objects.requireNonNull(store);
        this.worker = Objects.requireNonNull(worker);
        this.reconciler = Objects.requireNonNull(reconciler);
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${app.rag.ingestion.worker-poll-delay:1000}",
            initialDelayString = "${app.rag.ingestion.worker-initial-delay:1000}"
    )
    public void poll() {
        try {
            reconciler.reconcile();
            for (IngestionJob job : store.findRunnable(batchSize)) {
                worker.process(job.tenantId(), job.jobId());
            }
        } catch (RuntimeException ex) {
            log.error("knowledge ingestion worker poll failed", ex);
        }
    }
}
