package com.lrj.platform.asynctask;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality async-task metrics. Task IDs and payload text are intentionally never tags.
 */
@Component
public class AsyncTaskMetrics {

    private final MeterRegistry registry;

    public AsyncTaskMetrics(MeterRegistry registry) {
        this.registry = registry;
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
}
