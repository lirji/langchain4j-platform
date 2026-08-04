package com.lrj.platform.asynctask;

import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality async-task metrics. Task IDs and payload text are intentionally never tags.
 */
@Component
public class AsyncTaskMetrics {

    private final MeterRegistry registry;
    private final ObjectProvider<AsyncTaskStore> storeProvider;

    public AsyncTaskMetrics(
            MeterRegistry registry,
            ObjectProvider<AsyncTaskStore> storeProvider) {
        this.registry = registry;
        this.storeProvider = storeProvider;
        Gauge.builder(
                        "async_task_backlog",
                        this.storeProvider,
                        provider -> count(provider, AsyncTaskStatus.PENDING))
                .description("Centrally persisted async tasks waiting for a worker lease")
                .register(registry);
        Gauge.builder(
                        "async_task_inflight",
                        this.storeProvider,
                        provider -> count(provider, AsyncTaskStatus.RUNNING))
                .description("Centrally persisted async tasks with a running worker lease")
                .register(registry);
    }

    void eventAppended(String event, boolean duplicate) {
        registry.counter(
                "async_task_event_append_total",
                "event", event,
                "duplicate", Boolean.toString(duplicate)).increment();
    }

    void orphanFailed(String kind) {
        registry.counter("async_task_orphan_failed_total", "kind", kind).increment();
    }

    private static double count(
            ObjectProvider<AsyncTaskStore> storeProvider,
            AsyncTaskStatus status) {
        AsyncTaskStore store = storeProvider.getIfAvailable();
        return store == null ? 0 : store.countByStatus(status);
    }
}
