package com.lrj.platform.knowledge.ingest.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

/** 独立有界执行池；持久数据库是等待队列，线程真正运行后才申请跨副本租约。 */
public class IngestionWorkerLoop implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(IngestionWorkerLoop.class);
    private final IngestionJobWorker worker;
    private final IngestionReconciler reconciler;
    private final int batchSize;
    private final ThreadPoolExecutor executor;
    private final Semaphore slots;
    private final Duration shutdownTimeout;
    private volatile boolean closed;
    private io.micrometer.core.instrument.MeterRegistry meters;

    public IngestionWorkerLoop(IngestionJobStore store, IngestionJobWorker worker,
                               IngestionReconciler reconciler, int batchSize) {
        this(store,worker,reconciler,batchSize,new IngestionJobProperties());
    }

    /** 零等待队列避免在内存持有任务和租约；拒绝不退回提交线程执行。 */
    public IngestionWorkerLoop(IngestionJobStore store, IngestionJobWorker worker,
                               IngestionReconciler reconciler, int batchSize, IngestionJobProperties properties) {
        Objects.requireNonNull(store);
        this.worker = Objects.requireNonNull(worker);
        this.reconciler = Objects.requireNonNull(reconciler);
        properties.validateRecovery();
        if (batchSize < 1) throw new IngestionResourceException(IngestionResourceError.ADMISSION_CONFIG);
        this.batchSize = batchSize;
        int count = properties.getWorkerConcurrency();
        slots = new Semaphore(count);
        shutdownTimeout = properties.getWorkerShutdownTimeout();
        executor = new ThreadPoolExecutor(count,count,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),
                Thread.ofPlatform().daemon().inheritInheritableThreadLocals(false).name("knowledge-ingestion-",0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 低基数线程池监控；数据库积压/等待指标仍由既有 ingestion 指标提供。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMeters(io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
        io.micrometer.core.instrument.Gauge.builder("knowledge.ingestion.executor.active",executor,ThreadPoolExecutor::getActiveCount).register(meters);
        io.micrometer.core.instrument.Gauge.builder("knowledge.ingestion.executor.available",slots,Semaphore::availablePermits).register(meters);
    }

    /** 调度线程只做短维护和提交，不执行文档解析或模型调用。 */
    @Scheduled(fixedDelayString = "${app.rag.ingestion.worker-poll-delay:1000}",
            initialDelayString = "${app.rag.ingestion.worker-initial-delay:1000}")
    public void poll() {
        if (closed) return;
        try {
            reconciler.reconcile();
            for (int index = 0; index < batchSize && !closed; index++) {
                if (!slots.tryAcquire()) break;
                try {
                    executor.execute(IngestionExecutionContext.wrap(() -> {
                        long started = System.nanoTime();
                        try { if (!closed) worker.processNext(); }
                        catch (RuntimeException failure) { log.error("knowledge ingestion execution failed",failure); }
                        finally {
                            slots.release();
                            if (meters != null) meters.timer("knowledge.ingestion.executor.duration").record(System.nanoTime()-started,TimeUnit.NANOSECONDS);
                        }
                    }));
                } catch (RuntimeException failure) {
                    slots.release();
                    if (!(failure instanceof RejectedExecutionException)) throw failure;
                    if (meters != null) meters.counter("knowledge.ingestion.executor.rejected").increment();
                    // 未运行的任务没有从数据库领取，下一次轮询可安全重试。
                    break;
                }
            }
        } catch (RuntimeException failure) { log.error("knowledge ingestion worker poll failed",failure); }
    }

    /** Spring 先销毁依赖 worker 的本对象；超时中断后未完成租约由持久恢复链路接管。 */
    @Override
    public void close() {
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeout.toMillis(),TimeUnit.MILLISECONDS)) executor.shutdownNow();
        } catch (InterruptedException failure) {
            executor.shutdownNow(); Thread.currentThread().interrupt();
        }
    }
}
