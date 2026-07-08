package com.lrj.platform.agent.async;

import com.lrj.platform.agent.AgentRunMapper;
import com.lrj.platform.agent.DeepAgentService;
import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

@Service
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class AgentAsyncTaskService {

    private static final Logger log = LoggerFactory.getLogger(AgentAsyncTaskService.class);

    private final AgentTaskStore store;
    private final Executor executor;
    private final DeepAgentService agent;
    private final AuditLogger audit;
    private final ApplicationEventPublisher events;
    private final ExternalAsyncTaskProperties externalProperties;
    private final ExternalAsyncTaskClient externalClient;
    private final ConcurrentMap<String, CompletableFuture<?>> futures = new ConcurrentHashMap<>();
    private final Set<String> cancelledTaskIds = ConcurrentHashMap.newKeySet();

    public AgentAsyncTaskService(AgentTaskStore store,
                                 @Qualifier("agentTaskExecutor") Executor executor,
                                 DeepAgentService agent,
                                 AuditLogger audit,
                                 ApplicationEventPublisher events) {
        this(store, executor, agent, audit, events, null, null);
    }

    @Autowired
    public AgentAsyncTaskService(AgentTaskStore store,
                                 @Qualifier("agentTaskExecutor") Executor executor,
                                 DeepAgentService agent,
                                 AuditLogger audit,
                                 ApplicationEventPublisher events,
                                 ExternalAsyncTaskProperties externalProperties,
                                 ObjectProvider<ExternalAsyncTaskClient> externalClient) {
        this.store = store;
        this.executor = executor;
        this.agent = agent;
        this.audit = audit;
        this.events = events;
        this.externalProperties = externalProperties;
        this.externalClient = externalClient == null ? null : externalClient.getIfAvailable();
    }

    public AgentAsyncTask submit(String goal) {
        return submit(goal, null);
    }

    public AgentAsyncTask submit(String goal, String webhookUrl) {
        return submit("DEEP_AGENT", input(Map.of("goal", goal), webhookUrl),
                () -> AgentRunMapper.toReply(agent.run(goal)));
    }

    public AgentAsyncTask submit(String kind, Map<String, Object> input, Supplier<Object> work) {
        return submitWithProgress(kind, input, ignored -> work.get());
    }

    public AgentAsyncTask submitWithProgress(String kind,
                                             Map<String, Object> input,
                                             Function<AgentTaskProgressSink, Object> work) {
        TenantContext.Tenant tenant = TenantContext.current();
        String taskId = UUID.randomUUID().toString();
        AgentAsyncTask task = new AgentAsyncTask(
                taskId,
                tenant.tenantId(),
                tenant.userId(),
                AgentTaskStatus.PENDING,
                input,
                null,
                null,
                Instant.now(),
                Instant.now(),
                null);
        if (externalAuthoritative()) {
            if (!externalClient.create(task)) {
                throw new IllegalStateException("async-task-service task create failed");
            }
        } else {
            store.put(task);
        }
        events.publishEvent(new AgentTaskEvent(task));
        audit.record(AuditEventType.ASYNC_TASK_SUBMITTED, Map.of("taskId", taskId, "kind", kind));

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            if (externalAuthoritative() && !externalClient.lease(taskId)) {
                log.warn("agent async task {} was not leased by this worker; execution skipped", taskId);
                futures.remove(taskId);
                return;
            }
            Optional<AgentAsyncTask> runningTask = updateAndFire(taskId, current -> current.status().isTerminal()
                    ? current
                    : current.withStatus(AgentTaskStatus.RUNNING, null, null));
            if (runningTask.map(taskState -> taskState.status() != AgentTaskStatus.RUNNING).orElse(true)) {
                futures.remove(taskId);
                return;
            }
            try {
                throwIfCancelled(taskId);
                AgentTaskProgressSink progress = progressSink(taskId);
                Object result = work.apply(progress);
                throwIfCancelled(taskId);
                Optional<AgentAsyncTask> finalTask = updateAndFire(taskId, current -> current.status() == AgentTaskStatus.CANCELLED
                        ? current
                        : current.withStatus(AgentTaskStatus.SUCCEEDED, result, null));
                if (finalTask.map(taskState -> taskState.status() == AgentTaskStatus.SUCCEEDED).orElse(false)) {
                    audit.record(AuditEventType.ASYNC_TASK_FINISHED,
                            Map.of("taskId", taskId, "kind", kind, "status", "SUCCEEDED"));
                }
            } catch (CancellationException ex) {
                Optional<AgentAsyncTask> finalTask = updateAndFire(taskId, current -> current.status().isTerminal()
                        ? current
                        : current.withStatus(AgentTaskStatus.CANCELLED, null, "cancelled by user"));
                if (finalTask.map(taskState -> taskState.status() == AgentTaskStatus.CANCELLED).orElse(false)) {
                    audit.record(AuditEventType.ASYNC_TASK_CANCELLED, Map.of("taskId", taskId));
                }
            } catch (Throwable ex) {
                String message = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                Optional<AgentAsyncTask> finalTask = updateAndFire(taskId, current -> current.status().isTerminal()
                        ? current
                        : current.withStatus(AgentTaskStatus.FAILED, null, message));
                if (finalTask.map(taskState -> taskState.status() == AgentTaskStatus.FAILED).orElse(false)) {
                    audit.record(AuditEventType.ASYNC_TASK_FINISHED, Map.of(
                            "taskId", taskId,
                            "kind", kind,
                            "status", "FAILED",
                            "error", message));
                }
                log.warn("agent async task {} failed", taskId, ex);
            } finally {
                cancelledTaskIds.remove(taskId);
                futures.remove(taskId);
            }
        }, executor);

        futures.put(taskId, future);
        if (future.isDone()) {
            futures.remove(taskId, future);
        }
        return task;
    }

    public Optional<AgentAsyncTask> get(String taskId) {
        if (externalAuthoritative()) {
            return externalClient.get(taskId);
        }
        String tenantId = TenantContext.current().tenantId();
        return store.get(taskId).filter(task -> tenantId.equals(task.tenantId()));
    }

    public List<AgentAsyncTask> listMine() {
        if (externalAuthoritative()) {
            return externalClient.listMine();
        }
        return store.listByTenant(TenantContext.current().tenantId());
    }

    public boolean cancel(String taskId) {
        Optional<AgentAsyncTask> task = get(taskId);
        if (task.isEmpty() || task.get().status().isTerminal()) {
            return false;
        }
        if (externalAuthoritative()) {
            boolean cancelled = externalClient.cancel(taskId);
            if (!cancelled) {
                return false;
            }
            cancelledTaskIds.add(taskId);
            CompletableFuture<?> future = futures.get(taskId);
            if (future != null) {
                future.cancel(true);
            }
            if (cancelled) {
                AgentAsyncTask cancelledTask = task.get().withStatus(AgentTaskStatus.CANCELLED, null, "cancelled by user");
                events.publishEvent(new AgentTaskEvent(cancelledTask));
                audit.record(AuditEventType.ASYNC_TASK_CANCELLED, Map.of("taskId", taskId));
            }
            return true;
        }
        cancelledTaskIds.add(taskId);
        CompletableFuture<?> future = futures.get(taskId);
        if (future != null) {
            future.cancel(true);
        }
        updateAndFire(taskId, current -> current.status().isTerminal()
                ? current
                : current.withStatus(AgentTaskStatus.CANCELLED, null, "cancelled by user"));
        audit.record(AuditEventType.ASYNC_TASK_CANCELLED, Map.of("taskId", taskId));
        return true;
    }

    private AgentTaskProgressSink progressSink(String taskId) {
        return new AgentTaskProgressSink() {
            @Override
            public void emit(String event, Object data) {
                AgentAsyncTaskService.this.throwIfCancelled(taskId);
                events.publishEvent(new AgentTaskProgressEvent(new AgentTaskProgress(taskId, event, data, Instant.now())));
            }

            @Override
            public boolean isCancelled() {
                return cancelledTaskIds.contains(taskId) || Thread.currentThread().isInterrupted();
            }
        };
    }

    private void throwIfCancelled(String taskId) {
        if (cancelledTaskIds.contains(taskId) || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("agent task cancelled");
        }
    }

    private Optional<AgentAsyncTask> updateAndFire(String taskId, UnaryOperator<AgentAsyncTask> updater) {
        Optional<AgentAsyncTask> updated;
        if (externalAuthoritative()) {
            updated = externalClient.get(taskId).map(updater);
            updated.ifPresent(externalClient::update);
        } else {
            updated = store.update(taskId, updater);
        }
        updated.ifPresent(task -> events.publishEvent(new AgentTaskEvent(task)));
        return updated;
    }

    private boolean externalAuthoritative() {
        return externalClient != null
                && externalProperties != null
                && externalProperties.isEnabled()
                && externalProperties.isAuthoritative();
    }

    public static Map<String, Object> input(Map<String, Object> input, String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return input;
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>(input);
        copy.put("webhookUrl", webhookUrl.trim());
        return Map.copyOf(copy);
    }
}
