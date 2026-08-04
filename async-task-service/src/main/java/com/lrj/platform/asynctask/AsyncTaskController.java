package com.lrj.platform.asynctask;

import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskCreateRequest;
import com.lrj.platform.protocol.asynctask.AsyncTaskLeaseRequest;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatusUpdateRequest;
import com.lrj.platform.security.TenantContext;
import com.lrj.platform.security.AsyncTaskWorkerToken;
import com.lrj.platform.security.OutboundCallbackPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.web.bind.annotation.RequestAttribute;
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

/**
 * {@code /async/tasks/**} 通用异步任务中心的 REST 入口：创建、按 {@link TenantContext} tenant + owner 列举/查询、
 * 状态更新、worker 租约（lease）、取消，以及 {@code /stream} 的 SSE 流式（支持 Last-Event-ID 断点续传，
 * 委托 {@link AsyncTaskSseService}）与死信 webhook outbox 巡检。所有操作严格按当前租户隔离；每次生命周期
 * 变更都发布 {@link AsyncTaskEvent}（供 SSE 推送与 webhook 通知消费）并记审计。持久化委托可插拔的
 * {@link AsyncTaskStore}（内存/JDBC）。
 */
@RestController
public class AsyncTaskController {

    private final AsyncTaskStore store;
    private final AsyncTaskSseService sse;
    private final AuditLogger audit;
    private final ApplicationEventPublisher events;
    private final AsyncTaskWebhookOutbox webhookOutbox;
    private final AsyncTaskEventJournal eventJournal;
    private final OutboundCallbackPolicy callbackPolicy;
    private int eventMaxBytes = 262_144;

    public AsyncTaskController(AsyncTaskStore store,
                               AsyncTaskSseService sse,
                               AuditLogger audit,
                               ApplicationEventPublisher events) {
        this(store, sse, audit, events,
                (AsyncTaskWebhookOutbox) null,
                (AsyncTaskEventJournal) null,
                null);
    }

    @Autowired
    public AsyncTaskController(AsyncTaskStore store,
                               AsyncTaskSseService sse,
                               AuditLogger audit,
                               ApplicationEventPublisher events,
                               ObjectProvider<AsyncTaskWebhookOutbox> webhookOutbox,
                               ObjectProvider<AsyncTaskEventJournal> eventJournal,
                               ObjectProvider<OutboundCallbackPolicy> callbackPolicy) {
        this(store, sse, audit, events,
                webhookOutbox == null ? null : webhookOutbox.getIfAvailable(),
                eventJournal == null ? null : eventJournal.getIfAvailable(),
                callbackPolicy == null ? null : callbackPolicy.getIfAvailable());
    }

    AsyncTaskController(AsyncTaskStore store,
                        AsyncTaskSseService sse,
                        AuditLogger audit,
                        ApplicationEventPublisher events,
                        AsyncTaskWebhookOutbox webhookOutbox) {
        this(store, sse, audit, events, webhookOutbox, null, null);
    }

    AsyncTaskController(AsyncTaskStore store,
                        AsyncTaskSseService sse,
                        AuditLogger audit,
                        ApplicationEventPublisher events,
                        AsyncTaskWebhookOutbox webhookOutbox,
                        AsyncTaskEventJournal eventJournal) {
        this(store, sse, audit, events, webhookOutbox, eventJournal, null);
    }

    AsyncTaskController(AsyncTaskStore store,
                        AsyncTaskSseService sse,
                        AuditLogger audit,
                        ApplicationEventPublisher events,
                        AsyncTaskWebhookOutbox webhookOutbox,
                        AsyncTaskEventJournal eventJournal,
                        OutboundCallbackPolicy callbackPolicy) {
        this.store = store;
        this.sse = sse;
        this.audit = audit;
        this.events = events;
        this.webhookOutbox = webhookOutbox;
        this.eventJournal = eventJournal;
        this.callbackPolicy = callbackPolicy;
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
        String webhookUrl = blankToNull(request.webhookUrl());
        if (webhookUrl != null) {
            try {
                if (callbackPolicy != null) {
                    webhookUrl = callbackPolicy.requireAllowed(webhookUrl).toString();
                } else if (AsyncTaskWebhookNotifier.webhookUri(webhookUrl).isEmpty()) {
                    throw new IllegalArgumentException("invalid webhook URL");
                }
            } catch (IllegalArgumentException exception) {
                return ResponseEntity.badRequest().body(Map.of("error", "webhookUrl is not allowed"));
            }
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
                webhookUrl,
                now,
                now,
                null);
        store.put(task);
        publishLifecycle(task);
        audit.record(AuditEventType.ASYNC_TASK_SUBMITTED,
                Map.of("taskId", task.taskId(), "kind", task.kind(), "service", "async-task-service"));
        return ResponseEntity.accepted().body(task);
    }

    @GetMapping("/async/tasks")
    public List<AsyncTask> listMine() {
        TenantContext.Tenant caller = TenantContext.current();
        return store.listByOwner(caller.tenantId(), caller.userId());
    }

    @GetMapping("/async/tasks/{taskId}")
    public ResponseEntity<AsyncTask> get(@PathVariable String taskId) {
        return ownerScoped(taskId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/async/tasks/{taskId}/status")
    public ResponseEntity<?> updateStatus(@PathVariable String taskId,
                                          @RequestBody AsyncTaskStatusUpdateRequest request,
                                          @RequestAttribute(AsyncTaskWorkerAuthFilter.PRINCIPAL_ATTRIBUTE)
                                          AsyncTaskWorkerToken.Principal worker) {
        if (request == null || request.status() == null || blankToNull(request.workerId()) == null) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "status and workerId are required"));
        }
        if (!workerOwns(worker, request.workerId())) {
            return ResponseEntity.status(403).body(Map.of("error", "worker identity mismatch"));
        }
        Optional<AsyncTask> existing = workerScoped(taskId, worker);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (existing.get().status().isTerminal()) {
            return ResponseEntity.ok(existing.get());
        }
        if (request.leaseEpoch() == null || request.leaseEpoch() <= 0) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "positive leaseEpoch is required"));
        }
        if (!leaseOwnedBy(existing.get(), request.workerId(), request.leaseEpoch())) {
            return ResponseEntity.status(409).body(Map.of("error", "task lease is owned by another worker", "taskId", taskId));
        }
        AsyncTaskStore.MutationResult mutation = store.transition(
                taskId,
                TenantContext.current().tenantId(),
                request.workerId(),
                request.leaseEpoch(),
                request.status(),
                request.result(),
                request.error());
        AsyncTask task = mutation.task() == null ? existing.get() : mutation.task();
        if (!mutation.changed() && !task.status().isTerminal()) {
            return ResponseEntity.status(409).body(
                    Map.of("error", "task state changed before update", "taskId", taskId));
        }
        if (mutation.changed()) {
            publishLifecycle(task);
        }
        if (mutation.changed() && task.status().isTerminal()) {
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
                                   @RequestBody AsyncTaskLeaseRequest request,
                                   @RequestAttribute(AsyncTaskWorkerAuthFilter.PRINCIPAL_ATTRIBUTE)
                                   AsyncTaskWorkerToken.Principal worker) {
        String workerId = request == null ? null : blankToNull(request.workerId());
        if (workerId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "workerId is required"));
        }
        if (!workerOwns(worker, workerId)) {
            return ResponseEntity.status(403).body(Map.of("error", "worker identity mismatch"));
        }
        Optional<AsyncTask> existing = workerScoped(taskId, worker);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        AsyncTask current = existing.get();
        if (current.status().isTerminal()) {
            return ResponseEntity.status(409).body(Map.of("error", "terminal task cannot be leased", "taskId", taskId));
        }
        Instant leaseExpiresAt = Instant.now().plus(Duration.ofSeconds(leaseSeconds(request)));
        AsyncTaskStore.LeaseResult result = store.lease(
                taskId, workerId, leaseExpiresAt, request.leaseEpoch());
        AsyncTask leased = result.task() == null ? current : result.task();
        if (leased.status().isTerminal()) {
            return ResponseEntity.status(409).body(Map.of("error", "terminal task cannot be leased", "taskId", taskId));
        }
        if (!result.acquired()) {
            return ResponseEntity.status(409).body(leaseConflict(taskId, leased));
        }
        if (current.status() != leased.status()) {
            publishLifecycle(leased);
        }
        return ResponseEntity.ok(leased);
    }

    @DeleteMapping("/async/tasks/{taskId}")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String taskId) {
        Optional<AsyncTask> existing = ownerScoped(taskId);
        if (existing.isEmpty() || existing.get().status().isTerminal()) {
            return ResponseEntity.notFound().build();
        }
        AsyncTaskStore.MutationResult mutation = store.transition(
                taskId,
                TenantContext.current().tenantId(),
                null,
                AsyncTaskStatus.CANCELLED,
                null,
                "cancelled by user");
        if (mutation.changed()) {
            AsyncTask task = mutation.task();
            publishLifecycle(task);
            audit.record(AuditEventType.ASYNC_TASK_CANCELLED,
                    Map.of("taskId", task.taskId(), "kind", task.kind(), "service", "async-task-service"));
        }
        if (!mutation.changed()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("taskId", taskId, "cancelled", true));
    }

    @PostMapping("/async/tasks/{taskId}/events")
    public ResponseEntity<?> appendEvent(@PathVariable String taskId,
                                         @RequestBody AsyncTaskEventAppendRequest request,
                                         @RequestAttribute(AsyncTaskWorkerAuthFilter.PRINCIPAL_ATTRIBUTE)
                                         AsyncTaskWorkerToken.Principal worker) {
        if (eventJournal == null) {
            return ResponseEntity.status(503).body(Map.of("error", "task event journal is unavailable"));
        }
        if (request == null || !workerOwns(worker, request.workerId())) {
            return ResponseEntity.status(403).body(Map.of("error", "worker identity mismatch"));
        }
        Optional<AsyncTask> scoped = workerScoped(taskId, worker);
        if (scoped.isEmpty() || !AGENT_KINDS.contains(scoped.get().kind())) {
            return ResponseEntity.notFound().build();
        }
        AsyncTask task = scoped.get();
        if (task.status().isTerminal()
                || request == null
                || blankToNull(request.eventKey()) == null
                || blankToNull(request.event()) == null
                || blankToNull(request.workerId()) == null
                || request.leaseEpoch() == null
                || request.leaseEpoch() <= 0) {
            return ResponseEntity.status(task.status().isTerminal() ? 409 : 400)
                    .body(Map.of("error", task.status().isTerminal()
                            ? "terminal task rejects progress events"
                            : "eventKey, event, workerId and positive leaseEpoch are required"));
        }
        if (!PROGRESS_EVENTS.contains(request.event().trim())) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported progress event"));
        }
        if (String.valueOf(request.data()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > eventMaxBytes) {
            return ResponseEntity.status(413).body(Map.of("error", "progress event is too large"));
        }
        AsyncTaskStore.LeaseActionResult<EventAppendResult> result = store.withActiveLease(
                taskId,
                worker.tenantId(),
                request.workerId(),
                request.leaseEpoch(),
                () -> {
                    Optional<AsyncTaskStreamEvent> duplicate = eventJournal.eventsAfter(taskId, 0).stream()
                            .filter(item -> request.eventKey().trim().equals(item.eventKey()))
                            .findFirst();
                    if (duplicate.isPresent()) {
                        return new EventAppendResult(duplicate.get(), true);
                    }
                    return new EventAppendResult(eventJournal.append(
                            taskId,
                            request.eventKey().trim(),
                            request.event().trim(),
                            request.data(),
                            request.workerId().trim(),
                            Instant.now()), false);
                });
        if (!result.executed()) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", "task lease is not owned by worker"));
        }
        if (!result.value().duplicate()) {
            events.publishEvent(new AsyncTaskEvent(result.task(), result.value().event()));
        }
        return ResponseEntity.ok(result.value().event());
    }

    @GetMapping(value = "/async/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable String taskId,
                                             @RequestHeader(name = "Last-Event-ID", required = false) String lastEventIdHeader,
                                             @RequestParam(name = "lastEventId", required = false) String lastEventIdParam) {
        String lastEventId = blankToNull(lastEventIdParam) == null ? lastEventIdHeader : lastEventIdParam;
        return ownerScoped(taskId).flatMap(task -> sse.subscribe(task.taskId(), lastEventId))
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

    private Optional<AsyncTask> tenantScoped(String taskId) {
        String tenantId = TenantContext.current().tenantId();
        return store.get(taskId).filter(task -> tenantId.equals(task.tenantId()));
    }

    private Optional<AsyncTask> ownerScoped(String taskId) {
        TenantContext.Tenant caller = TenantContext.current();
        return store.get(taskId).filter(task -> caller.tenantId().equals(task.tenantId())
                && caller.userId().equals(task.userId()));
    }

    private Optional<AsyncTask> workerScoped(
            String taskId,
            AsyncTaskWorkerToken.Principal worker) {
        return store.get(taskId).filter(task -> worker.tenantId().equals(task.tenantId())
                && worker.actorUserId().equals(task.userId()));
    }

    private static boolean workerOwns(AsyncTaskWorkerToken.Principal worker, String leaseOwnerId) {
        String owner = blankToNull(leaseOwnerId);
        return owner != null
                && (owner.equals(worker.workerId())
                    || owner.equals(worker.serviceId())
                    || owner.startsWith(worker.serviceId() + "."));
    }

    private static boolean leaseOwnedBy(AsyncTask task, String workerId, Long leaseEpoch) {
        return task.leaseOwnerId() != null
                && task.leaseOwnerId().equals(blankToNull(workerId))
                && leaseEpoch != null
                && task.leaseEpoch() == leaseEpoch
                && task.leaseExpiresAt() != null
                && task.leaseExpiresAt().isAfter(Instant.now());
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

    private void publishLifecycle(AsyncTask task) {
        AsyncTaskStreamEvent streamEvent = null;
        if (eventJournal != null) {
            streamEvent = eventJournal.append(
                    task.taskId(),
                    "status:" + task.status().name(),
                    task.status().name(),
                    task,
                    null,
                    task.updatedAt());
        }
        events.publishEvent(new AsyncTaskEvent(task, streamEvent));
    }

    private static final java.util.Set<String> AGENT_KINDS = java.util.Set.of(
            "agent.run", "agent.dag", "agent.dag-plan", "agent.analyst", "agent.process");
    private static final java.util.Set<String> PROGRESS_EVENTS = java.util.Set.of(
            "dag-planned",
            "dag-levels",
            "dag-level-start",
            "dag-worker-start",
            "dag-worker-result",
            "dag-level-complete",
            "dag-synthesis-start",
            "dag-synthesis-result",
            "dag-critique",
            "dag-replan",
            "dag-replanned");

    private record EventAppendResult(AsyncTaskStreamEvent event, boolean duplicate) {
    }

    @Value("${app.async-task.event.max-bytes:262144}")
    void setEventMaxBytes(int eventMaxBytes) {
        this.eventMaxBytes = Math.max(1_024, eventMaxBytes);
    }
}
