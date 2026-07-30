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
public class IngestionJobWorker {

    private final IngestionJobStore store;
    private final IngestionDocumentPreparer preparer;
    private final IngestionSinkProcessor processor;
    private final IngestionTaskLifecycle lifecycle;
    private final Clock clock;

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
        this.store = Objects.requireNonNull(store);
        this.preparer = Objects.requireNonNull(preparer);
        this.processor = Objects.requireNonNull(processor);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.clock = Objects.requireNonNull(clock);
    }

    public boolean process(String tenantId, String jobId) {
        IngestionJob current = store.find(tenantId, jobId).orElse(null);
        if (current == null) {
            return false;
        }
        try {
            synchronizeWithContext(current, true);
            if (current.status() == IngestionStatus.RECEIVED) {
                current = store.save(
                        IngestionJobStateMachine.start(current, clock.instant()),
                        current.revision());
            }
            if (current.status() != IngestionStatus.PROCESSING) {
                return false;
            }
            PreparedIngestionDocument prepared;
            try {
                prepared = prepareWithContext(current);
            } catch (Exception ex) {
                current = store.save(
                        IngestionJobStateMachine.preparationFailed(
                                current, safeMessage(ex), clock.instant()),
                        current.revision());
                synchronizeWithContext(current, false);
                return true;
            }
            while (current.status() == IngestionStatus.PROCESSING) {
                IngestionSink next = current.sinks().entrySet().stream()
                        .filter(entry -> entry.getValue() == IngestionSinkState.PENDING)
                        .map(java.util.Map.Entry::getKey)
                        .findFirst()
                        .orElse(null);
                if (next == null) {
                    return current.status() == IngestionStatus.READY;
                }
                IngestionJob running = store.save(
                        IngestionJobStateMachine.beginSink(current, next, clock.instant()),
                        current.revision());
                try {
                    processWithContext(running, prepared, next);
                    current = store.save(
                            IngestionJobStateMachine.sinkSucceeded(
                                    running, next, clock.instant()),
                            running.revision());
                    synchronizeWithContext(current, false);
                } catch (Exception ex) {
                    current = store.save(
                            IngestionJobStateMachine.sinkFailed(
                                    running, next, safeMessage(ex), clock.instant()),
                            running.revision());
                    synchronizeWithContext(current, false);
                    return true;
                }
            }
            return true;
        } catch (IngestionJobConflictException ignored) {
            return false;
        }
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
