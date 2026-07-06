package com.lrj.platform.asynctask;

import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.UnaryOperator;

@Component
@ConditionalOnProperty(name = "app.async-task.store", havingValue = "in-memory", matchIfMissing = true)
public class AsyncTaskStore {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskStore.class);

    private final ConcurrentMap<String, AsyncTask> tasks = new ConcurrentHashMap<>();
    private final Duration ttl;

    public AsyncTaskStore(@Value("${app.async-task.task-ttl:PT24H}") Duration ttl) {
        this.ttl = ttl;
    }

    public void put(AsyncTask task) {
        tasks.put(task.taskId(), task);
    }

    public Optional<AsyncTask> get(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public Optional<AsyncTask> update(String taskId, UnaryOperator<AsyncTask> updater) {
        return Optional.ofNullable(tasks.computeIfPresent(taskId, (ignored, task) -> updater.apply(task)));
    }

    public Optional<AsyncTask> lease(String taskId, String workerId, Instant leaseExpiresAt) {
        Instant now = Instant.now();
        return Optional.ofNullable(tasks.computeIfPresent(taskId, (ignored, task) -> {
            if (task.status().isTerminal() || !leaseAvailableFor(task, workerId, now)) {
                return task;
            }
            return withLease(task, workerId, leaseExpiresAt);
        }));
    }

    public List<AsyncTask> listByTenant(String tenantId) {
        return tasks.values().stream()
                .filter(task -> tenantId.equals(task.tenantId()))
                .sorted(Comparator.comparing(AsyncTask::createdAt).reversed())
                .toList();
    }

    @Scheduled(fixedDelayString = "${app.async-task.cleanup-delay-ms:60000}",
            initialDelayString = "${app.async-task.cleanup-initial-delay-ms:60000}")
    public void cleanup() {
        Instant cutoff = Instant.now().minus(ttl);
        int removed = 0;
        for (Map.Entry<String, AsyncTask> entry : tasks.entrySet()) {
            AsyncTask task = entry.getValue();
            if (task.finishedAt() != null && task.finishedAt().isBefore(cutoff)
                    && tasks.remove(entry.getKey(), task)) {
                removed++;
            }
        }
        if (removed > 0) {
            log.info("async task cleanup removed {} expired tasks ttl={}", removed, ttl);
        }
    }

    static AsyncTask withStatus(AsyncTask task, AsyncTaskStatus status, Object result, String error) {
        Instant now = Instant.now();
        return new AsyncTask(
                task.taskId(),
                task.tenantId(),
                task.userId(),
                task.kind(),
                status,
                task.input(),
                result,
                error,
                task.webhookUrl(),
                task.createdAt(),
                now,
                status.isTerminal() ? now : task.finishedAt(),
                status.isTerminal() ? null : task.leaseOwnerId(),
                status.isTerminal() ? null : task.leaseExpiresAt());
    }

    static AsyncTask withLease(AsyncTask task, String workerId, Instant leaseExpiresAt) {
        Instant now = Instant.now();
        return new AsyncTask(
                task.taskId(),
                task.tenantId(),
                task.userId(),
                task.kind(),
                AsyncTaskStatus.RUNNING,
                task.input(),
                task.result(),
                task.error(),
                task.webhookUrl(),
                task.createdAt(),
                now,
                task.finishedAt(),
                workerId,
                leaseExpiresAt);
    }

    static boolean leaseAvailableFor(AsyncTask task, String workerId, Instant now) {
        if (task.leaseOwnerId() == null || task.leaseOwnerId().isBlank()) {
            return true;
        }
        if (task.leaseOwnerId().equals(workerId)) {
            return true;
        }
        return task.leaseExpiresAt() != null && task.leaseExpiresAt().isBefore(now);
    }
}
