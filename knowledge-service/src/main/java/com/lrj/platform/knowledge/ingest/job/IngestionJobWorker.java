package com.lrj.platform.knowledge.ingest.job;

import com.lrj.platform.observability.TraceIdFilter;
import com.lrj.platform.security.TenantContext;
import org.slf4j.MDC;

import java.time.Clock;
import java.util.Objects;

/**
 * 单 job worker。每个 sink 先用 revision 抢占为 RUNNING，再执行幂等副作用，避免并发 worker
 * 同时处理同一 sink；崩溃后的 RUNNING 只由 reconciler 按显式幂等策略恢复。
 */
public class IngestionJobWorker implements AutoCloseable {

    private final IngestionJobStore store;
    private final IngestionDocumentPreparer preparer;
    private final IngestionSinkProcessor processor;
    private final IngestionTaskLifecycle lifecycle;
    private final Clock clock;
    private io.micrometer.core.instrument.MeterRegistry meters;

    /** 指标只有阶段/结果标签，不把 jobId 或租户放入时序标签。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMeters(io.micrometer.core.instrument.MeterRegistry meters) { this.meters = meters; }
    private final IngestionJobProperties properties;
    private final IngestionRetryPolicy retryPolicy;
    private final java.util.concurrent.ScheduledExecutorService heartbeats =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("ingestion-heartbeat").factory());
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(IngestionJobWorker.class);

    public IngestionJobWorker(
            IngestionJobStore store,
            IngestionDocumentPreparer preparer,
            IngestionSinkProcessor processor,
            Clock clock
    ) {
        this(store, preparer, processor, new NoopIngestionTaskLifecycle(), clock);
    }

    public IngestionJobWorker(
            IngestionJobStore store,
            IngestionDocumentPreparer preparer,
            IngestionSinkProcessor processor,
            IngestionTaskLifecycle lifecycle,
            Clock clock
    ) {
        this(store, preparer, processor, lifecycle, clock, new IngestionJobProperties());
    }

    /** 租约心跳与重试策略由同一配置控制，状态更新仍使用 revision。 */
    public IngestionJobWorker(IngestionJobStore store, IngestionDocumentPreparer preparer,
                              IngestionSinkProcessor processor, IngestionTaskLifecycle lifecycle,
                              Clock clock, IngestionJobProperties properties) {
        this.properties = properties;
        this.retryPolicy = new IngestionRetryPolicy(properties);
        this.store = Objects.requireNonNull(store);
        this.preparer = Objects.requireNonNull(preparer);
        this.processor = Objects.requireNonNull(processor);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 在解析前抢占整条任务；每个副作用前重新验证租约，过期 worker 不得推进下一阶段。 */
    public boolean process(String tenantId, String jobId) {
        IngestionJob job = store.find(tenantId, jobId).orElse(null);
        if (job == null || job.execution().leaseOwner() != null
                || (job.status() != IngestionStatus.RECEIVED && job.status() != IngestionStatus.PROCESSING)) {
            return false;
        }
        try {
            IngestionJob started = job.status() == IngestionStatus.RECEIVED
                    ? IngestionJobStateMachine.start(job, clock.instant()) : job;
            started = store.save(started.withExecution(started.execution().lease(
                    java.util.UUID.randomUUID().toString(), clock.instant().plus(properties.getProcessingTimeout()))),
                    job.revision());
            try (Lease lease = new Lease(started)) {
                PreparedIngestionDocument prepared;
                long preparationStarted = System.nanoTime();
                try {
                    synchronizeWithContext(lease.current(), true);
                    prepared = prepareWithContext(lease.current());
                    recordStage("PREPARATION", preparationStarted, "success");
                } catch (Exception ex) {
                    recordStage("PREPARATION", preparationStarted, "failure");
                    lease.fail(ex, "PREPARATION", null);
                    return true;
                }
                while (lease.current().status() == IngestionStatus.PROCESSING) {
                    IngestionSink next = lease.current().sinks().entrySet().stream()
                            .filter(entry -> entry.getValue() == IngestionSinkState.PENDING)
                            .map(java.util.Map.Entry::getKey).findFirst().orElse(null);
                    if (next == null) { return false; }
                    IngestionJob running = lease.change(j -> IngestionJobStateMachine.beginSink(j, next, clock.instant()));
                    long sinkStarted = System.nanoTime();
                    try {
                        processWithContext(running, prepared, next);
                        lease.change(j -> {
                            IngestionJob succeeded = IngestionJobStateMachine.sinkSucceeded(j, next, clock.instant());
                            return succeeded.status() == IngestionStatus.READY
                                    ? succeeded.withExecution(new IngestionExecution(j.execution().retries(), null, null, null, null, null))
                                    : succeeded;
                        });
                        recordStage(next.name(), sinkStarted, "success");
                    } catch (Exception ex) {
                        recordStage(next.name(), sinkStarted, "failure");
                        lease.fail(ex, next.name(), next);
                        return true;
                    }
                    synchronizeQuietly(lease.current());
                }
                if (meters != null && lease.current().status() == IngestionStatus.READY) {
                    meters.timer("knowledge.ingestion.commit.latency").record(
                            java.time.Duration.between(job.createdAt(), clock.instant()));
                }
                return true;
            }
        } catch (IngestionJobConflictException ignored) {
            // 心跳或阶段提交 CAS 失败意味着执行权已丢失，不能再标记失败覆盖新 worker。
            return false;
        }
    }

    private void recordStage(String stage, long started, String outcome) {
        if (meters != null) {
            meters.timer("knowledge.ingestion.stage.duration", "stage", stage, "outcome", outcome)
                    .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private void synchronizeQuietly(IngestionJob job) {
        try { synchronizeWithContext(job, false); }
        catch (RuntimeException ex) { log.warn("ingestion task mirror sync failed job={}", job.jobId(), ex); }
    }

    /** 容器关闭时停止续租；未完成任务租约到期后由其他实例恢复。 */
    @Override
    public void close() { heartbeats.shutdownNow(); }

    /** 心跳与阶段提交共用本地锁及最新 revision，避免自己的心跳造成提交冲突。 */
    private final class Lease implements AutoCloseable {
        private IngestionJob current;
        private boolean lost;
        private final java.util.concurrent.ScheduledFuture<?> heartbeat;

        Lease(IngestionJob job) {
            current = job;
            heartbeat = heartbeats.scheduleAtFixedRate(() -> {
                synchronized (this) {
                    if (current.status() != IngestionStatus.PROCESSING || lost) { return; }
                    try {
                        change(j -> j.withExecution(j.execution().lease(j.execution().leaseOwner(),
                                clock.instant().plus(properties.getProcessingTimeout()))));
                    } catch (RuntimeException ex) {
                        lost = true;
                        log.warn("ingestion lease lost job={}", current.jobId(), ex);
                    }
                }
            }, properties.getHeartbeatInterval().toMillis(), properties.getHeartbeatInterval().toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        synchronized IngestionJob current() { return current; }

        synchronized IngestionJob change(java.util.function.UnaryOperator<IngestionJob> operation) {
            if (lost || current.execution().leaseUntil() == null
                    || !current.execution().leaseUntil().isAfter(clock.instant())) {
                throw new IngestionJobConflictException("ingestion lease expired");
            }
            current = store.save(operation.apply(current), current.revision());
            return current;
        }

        synchronized void fail(Exception ex, String stage, IngestionSink sink) {
            if (ex instanceof IngestionJobConflictException) { throw (IngestionJobConflictException) ex; }
            log.warn("ingestion stage failed job={} stage={}", current.jobId(), stage, ex);
            if (meters != null) { meters.counter("knowledge.ingestion.failures", "stage", stage).increment(); }
            change(j -> retryPolicy.failed(sink == null
                    ? IngestionJobStateMachine.preparationFailed(j, "PREPARATION_FAILED", clock.instant())
                    : IngestionJobStateMachine.sinkFailed(j, sink, "SINK_FAILED", clock.instant()),
                    ex, stage, clock.instant()));
            synchronizeQuietly(current);
        }

        @Override
        public void close() { heartbeat.cancel(false); }
    }

    private void synchronizeWithContext(IngestionJob job, boolean ensure) {
        try {
            withContext(job, () -> {
                if (ensure) {
                    lifecycle.ensureTask(job);
                }
                lifecycle.synchronize(job);
                return null;
            });
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("cannot synchronize async task lifecycle", ex);
        }
    }

    private PreparedIngestionDocument prepareWithContext(IngestionJob job) throws Exception {
        return withContext(job, () -> preparer.prepare(job));
    }

    private void processWithContext(
            IngestionJob job,
            PreparedIngestionDocument prepared,
            IngestionSink sink
    ) throws Exception {
        withContext(job, () -> {
            processor.process(job, prepared, sink);
            return null;
        });
    }

    private <T> T withContext(IngestionJob job, ContextOperation<T> operation) throws Exception {
        TenantContext.Tenant previousTenant = TenantContext.captureRaw();
        String previousTrace = MDC.get(TraceIdFilter.MDC_KEY);
        try {
            TenantContext.set(new TenantContext.Tenant(
                    job.tenantId(), job.userId(), job.scopes(), job.department()));
            MDC.put(TraceIdFilter.MDC_KEY, job.traceId());
            return operation.execute();
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
            if (previousTrace == null) {
                MDC.remove(TraceIdFilter.MDC_KEY);
            } else {
                MDC.put(TraceIdFilter.MDC_KEY, previousTrace);
            }
        }
    }

    @FunctionalInterface
    private interface ContextOperation<T> {
        T execute() throws Exception;
    }

    private static String safeMessage(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
    }
}
