package com.lrj.platform.asynctask;

import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskCreateRequest;
import com.lrj.platform.protocol.asynctask.AsyncTaskLeaseRequest;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatusUpdateRequest;
import com.lrj.platform.security.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
public class AsyncTaskController {

    private final AsyncTaskStore store;
    private final AsyncTaskSseService sse;
    private final AuditLogger audit;
    private final ApplicationEventPublisher events;
    private final AsyncTaskWebhookOutbox webhookOutbox;

    public AsyncTaskController(AsyncTaskStore store,
                               AsyncTaskSseService sse,
                               AuditLogger audit,
                               ApplicationEventPublisher events) {
        this(store, sse, audit, events, (AsyncTaskWebhookOutbox) null);
    }

    @Autowired
    public AsyncTaskController(AsyncTaskStore store,
                               AsyncTaskSseService sse,
                               AuditLogger audit,
                               ApplicationEventPublisher events,
                               ObjectProvider<AsyncTaskWebhookOutbox> webhookOutbox) {
        this(store, sse, audit, events, webhookOutbox == null ? null : webhookOutbox.getIfAvailable());
    }

    AsyncTaskController(AsyncTaskStore store,
                        AsyncTaskSseService sse,
                        AuditLogger audit,
                        ApplicationEventPublisher events,
                        AsyncTaskWebhookOutbox webhookOutbox) {
        this.store = store;
        this.sse = sse;
        this.audit = audit;
        this.events = events;
        this.webhookOutbox = webhookOutbox;
    }

    @PostMapping("/async/tasks")
    public ResponseEntity<?> create(@RequestBody AsyncTaskCreateRequest request) {
        if (request == null || request.kind() == null || request.kind().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "kind is required"));
        }
        TenantContext.Tenant tenant = TenantContext.current();
        Instant now = Instant.now();
        String taskId = blankToNull(request.taskId());
        if (taskId == null) {
            taskId = UUID.randomUUID().toString();
        } else if (store.get(taskId).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "task already exists", "taskId", taskId));
        }
        AsyncTask task = new AsyncTask(
                taskId,
                tenant.tenantId(),
                tenant.userId(),
                request.kind().trim(),
                AsyncTaskStatus.PENDING,
                request.input(),
                null,
                null,
                blankToNull(request.webhookUrl()),
                now,
                now,
                null);
        store.put(task);
        events.publishEvent(new AsyncTaskEvent(task));
        audit.record(AuditEventType.ASYNC_TASK_SUBMITTED,
                Map.of("taskId", task.taskId(), "kind", task.kind(), "service", "async-task-service"));
        return ResponseEntity.accepted().body(task);
    }

    @GetMapping("/async/tasks")
    public List<AsyncTask> listMine() {
        return store.listByTenant(TenantContext.current().tenantId());
    }

    @GetMapping("/async/tasks/{taskId}")
    public ResponseEntity<AsyncTask> get(@PathVariable String taskId) {
        return scoped(taskId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/async/tasks/{taskId}/status")
    public ResponseEntity<?> updateStatus(@PathVariable String taskId,
                                          @RequestBody AsyncTaskStatusUpdateRequest request) {
        if (request == null || request.status() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "status is required"));
        }
        Optional<AsyncTask> existing = scoped(taskId);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (existing.get().status().isTerminal()) {
            return ResponseEntity.ok(existing.get());
        }
        if (!leaseOwnedBy(existing.get(), request.workerId())) {
            return ResponseEntity.status(409).body(Map.of("error", "task lease is owned by another worker", "taskId", taskId));
        }
        Optional<AsyncTask> updated = store.update(taskId, task -> {
            if (!TenantContext.current().tenantId().equals(task.tenantId()) || task.status().isTerminal()) {
                return task;
            }
            return AsyncTaskStore.withStatus(task, request.status(), request.result(), request.error());
        });
        AsyncTask task = updated.orElse(existing.get());
        events.publishEvent(new AsyncTaskEvent(task));
        if (task.status().isTerminal()) {
            audit.record(AuditEventType.ASYNC_TASK_FINISHED, Map.of(
                    "taskId", task.taskId(),
                    "kind", task.kind(),
                    "status", task.status().name(),
                    "service", "async-task-service"));
        }
        return ResponseEntity.ok(task);
    }

    @PostMapping("/async/tasks/{taskId}/lease")
    public ResponseEntity<?> lease(@PathVariable String taskId,
                                   @RequestBody AsyncTaskLeaseRequest request) {
        String workerId = request == null ? null : blankToNull(request.workerId());
        if (workerId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "workerId is required"));
        }
        Optional<AsyncTask> existing = scoped(taskId);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        AsyncTask current = existing.get();
        if (current.status().isTerminal()) {
            return ResponseEntity.status(409).body(Map.of("error", "terminal task cannot be leased", "taskId", taskId));
        }
        if (!leaseAvailableFor(current, workerId)) {
            return ResponseEntity.status(409).body(leaseConflict(taskId, current));
        }
        Instant leaseExpiresAt = Instant.now().plus(Duration.ofSeconds(leaseSeconds(request)));
        Optional<AsyncTask> updated = store.lease(taskId, workerId, leaseExpiresAt);
        AsyncTask leased = updated.orElse(current);
        if (leased.status().isTerminal()) {
            return ResponseEntity.status(409).body(Map.of("error", "terminal task cannot be leased", "taskId", taskId));
        }
        if (!workerId.equals(leased.leaseOwnerId())) {
            return ResponseEntity.status(409).body(leaseConflict(taskId, leased));
        }
        events.publishEvent(new AsyncTaskEvent(leased));
        return ResponseEntity.ok(leased);
    }

    @DeleteMapping("/async/tasks/{taskId}")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String taskId) {
        Optional<AsyncTask> existing = scoped(taskId);
        if (existing.isEmpty() || existing.get().status().isTerminal()) {
            return ResponseEntity.notFound().build();
        }
        Optional<AsyncTask> updated = store.update(taskId,
                task -> AsyncTaskStore.withStatus(task, AsyncTaskStatus.CANCELLED, null, "cancelled by user"));
        updated.ifPresent(task -> {
            events.publishEvent(new AsyncTaskEvent(task));
            audit.record(AuditEventType.ASYNC_TASK_CANCELLED,
                    Map.of("taskId", task.taskId(), "kind", task.kind(), "service", "async-task-service"));
        });
        return ResponseEntity.ok(Map.of("taskId", taskId, "cancelled", true));
    }

    @GetMapping(value = "/async/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable String taskId,
                                             @RequestHeader(name = "Last-Event-ID", required = false) String lastEventIdHeader,
                                             @RequestParam(name = "lastEventId", required = false) String lastEventIdParam) {
        String lastEventId = blankToNull(lastEventIdParam) == null ? lastEventIdHeader : lastEventIdParam;
        return scoped(taskId).flatMap(task -> sse.subscribe(task.taskId(), lastEventId))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/async/webhook-outbox/dead")
    public List<AsyncTaskWebhookOutbox.InspectionRow> deadWebhookOutbox(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        if (webhookOutbox == null) {
            return List.of();
        }
        return webhookOutbox.listDead(TenantContext.current().tenantId(), Math.max(1, Math.min(200, limit)));
    }

    private Optional<AsyncTask> scoped(String taskId) {
        String tenantId = TenantContext.current().tenantId();
        return store.get(taskId).filter(task -> tenantId.equals(task.tenantId()));
    }

    private static boolean leaseOwnedBy(AsyncTask task, String workerId) {
        if (task.leaseOwnerId() == null || task.leaseOwnerId().isBlank()) {
            return true;
        }
        return task.leaseOwnerId().equals(blankToNull(workerId));
    }

    private static boolean leaseAvailableFor(AsyncTask task, String workerId) {
        if (task.leaseOwnerId() == null || task.leaseOwnerId().isBlank()) {
            return true;
        }
        if (task.leaseOwnerId().equals(workerId)) {
            return true;
        }
        return task.leaseExpiresAt() != null && task.leaseExpiresAt().isBefore(Instant.now());
    }

    private static long leaseSeconds(AsyncTaskLeaseRequest request) {
        if (request == null || request.leaseSeconds() == null) {
            return 60L;
        }
        return Math.max(1L, Math.min(3600L, request.leaseSeconds()));
    }

    private static Map<String, Object> leaseConflict(String taskId, AsyncTask task) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "task lease is owned by another worker");
        body.put("taskId", taskId);
        if (task.leaseOwnerId() != null) {
            body.put("leaseOwnerId", task.leaseOwnerId());
        }
        if (task.leaseExpiresAt() != null) {
            body.put("leaseExpiresAt", task.leaseExpiresAt());
        }
        return body;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
