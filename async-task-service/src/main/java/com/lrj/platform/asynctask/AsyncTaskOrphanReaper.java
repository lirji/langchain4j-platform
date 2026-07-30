package com.lrj.platform.asynctask;

import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.security.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.async-task.orphan.enabled", havingValue = "true")
public class AsyncTaskOrphanReaper {

    static final String ORPHAN_ERROR = "ASYNC_TASK_ORPHANED";

    private final AsyncTaskStore store;
    private final AsyncTaskEventJournal journal;
    private final AsyncTaskOrphanProperties properties;
    private final ApplicationEventPublisher events;
    private final AuditLogger audit;
    private final AsyncTaskMetrics metrics;

    public AsyncTaskOrphanReaper(AsyncTaskStore store,
                                 AsyncTaskEventJournal journal,
                                 AsyncTaskOrphanProperties properties,
                                 ApplicationEventPublisher events,
                                 AuditLogger audit) {
        this(store, journal, properties, events, audit, null);
    }

    @Autowired
    public AsyncTaskOrphanReaper(AsyncTaskStore store,
                                 AsyncTaskEventJournal journal,
                                 AsyncTaskOrphanProperties properties,
                                 ApplicationEventPublisher events,
                                 AuditLogger audit,
                                 AsyncTaskMetrics metrics) {
        this.store = store;
        this.journal = journal;
        this.properties = properties;
        this.events = events;
        this.audit = audit;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${app.async-task.orphan.scan-delay-ms:30000}",
            initialDelayString = "${app.async-task.orphan.initial-delay-ms:30000}")
    public void reap() {
        Instant now = Instant.now();
        for (AsyncTask task : store.failOrphans(
                properties.getKinds(),
                now.minus(properties.getPendingTimeout()),
                now.minus(properties.getLeaseGrace()),
                properties.getBatchSize(),
                ORPHAN_ERROR)) {
            publishWithTaskContext(task);
        }
    }

    private void publishWithTaskContext(AsyncTask task) {
        TenantContext.Tenant previous = TenantContext.captureRaw();
        try {
            TenantContext.set(new TenantContext.Tenant(task.tenantId(), task.userId(), java.util.Set.of()));
            AsyncTaskStreamEvent streamEvent = journal.append(
                    task.taskId(),
                    "status:FAILED",
                    "FAILED",
                    task,
                    null,
                    task.updatedAt());
            events.publishEvent(new AsyncTaskEvent(task, streamEvent));
            if (metrics != null) {
                metrics.orphanFailed(task.kind());
            }
            audit.record(AuditEventType.ASYNC_TASK_FINISHED, Map.of(
                    "taskId", task.taskId(),
                    "kind", task.kind(),
                    "status", task.status().name(),
                    "reason", ORPHAN_ERROR,
                    "service", "async-task-service"));
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }
}
