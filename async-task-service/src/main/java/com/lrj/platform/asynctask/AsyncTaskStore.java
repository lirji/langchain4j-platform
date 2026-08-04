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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 异步任务存储的默认内存实现（{@code app.async-task.store=in-memory}，缺省即启用），基于
 * {@link ConcurrentHashMap}。提供增改查、按租户与 owner 列举、worker 租约（lease）抢占与到期回收，以及按 TTL
 * 定时清理终态任务。JDBC 变体 {@link JdbcAsyncTaskStore} 继承本类并覆写各方法；租约/状态迁移的纯函数工具
 * （{@link #withStatus}、{@link #withLease}）由两种实现共享。
 */
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

    public MutationResult transition(String taskId,
                                     String tenantId,
                                     String workerId,
                                     AsyncTaskStatus target,
                                     Object result,
                                     String error) {
        return transition(taskId, tenantId, workerId, null, target, result, error);
    }

    public MutationResult transition(String taskId,
                                     String tenantId,
                                     String workerId,
                                     Long leaseEpoch,
                                     AsyncTaskStatus target,
                                     Object result,
                                     String error) {
        MutationResult[] outcome = new MutationResult[1];
        tasks.computeIfPresent(taskId, (ignored, task) -> {
            if (!tenantId.equals(task.tenantId())
                    || task.status().isTerminal()
                    || (target != AsyncTaskStatus.CANCELLED
                        && !leaseOwnedBy(task, workerId, leaseEpoch))) {
                outcome[0] = new MutationResult(task, false);
                return task;
            }
            AsyncTask updated = withStatus(task, target, result, error);
            outcome[0] = new MutationResult(updated, true);
            return updated;
        });
        return outcome[0] == null ? new MutationResult(null, false) : outcome[0];
    }

    public Optional<AsyncTask> lease(String taskId, String workerId, Instant leaseExpiresAt) {
        return Optional.ofNullable(lease(taskId, workerId, leaseExpiresAt, null).task());
    }

    public LeaseResult lease(
            String taskId,
            String workerId,
            Instant leaseExpiresAt,
            Long expectedLeaseEpoch) {
        Instant now = Instant.now();
        LeaseResult[] outcome = new LeaseResult[1];
        tasks.computeIfPresent(taskId, (ignored, task) -> {
            if (task.status().isTerminal()
                    || !leaseAvailableFor(task, workerId, expectedLeaseEpoch, now)) {
                outcome[0] = new LeaseResult(task, false);
                return task;
            }
            long epoch = expectedLeaseEpoch == null
                    ? Math.addExact(task.leaseEpoch(), 1L)
                    : task.leaseEpoch();
            AsyncTask leased = withLease(task, workerId, leaseExpiresAt, epoch);
            outcome[0] = new LeaseResult(leased, true);
            return leased;
        });
        return outcome[0] == null ? new LeaseResult(null, false) : outcome[0];
    }

    /**
     * Executes one side effect while the task's exact lease owner and fencing epoch are still
     * active. The in-memory implementation serializes this action with lease takeover on the
     * task key; the JDBC implementation holds the task row lock in the surrounding transaction.
     */
    public <T> LeaseActionResult<T> withActiveLease(
            String taskId,
            String tenantId,
            String workerId,
            long leaseEpoch,
            Supplier<T> action) {
        AtomicReference<LeaseActionResult<T>> outcome = new AtomicReference<>();
        tasks.computeIfPresent(taskId, (ignored, task) -> {
            if (!tenantId.equals(task.tenantId())
                    || task.status().isTerminal()
                    || !leaseOwnedBy(task, workerId, leaseEpoch)) {
                outcome.set(new LeaseActionResult<>(task, null, false));
                return task;
            }
            outcome.set(new LeaseActionResult<>(task, action.get(), true));
            return task;
        });
        LeaseActionResult<T> result = outcome.get();
        return result == null ? new LeaseActionResult<>(null, null, false) : result;
    }

    public List<AsyncTask> listByTenant(String tenantId) {
        return tasks.values().stream()
                .filter(task -> tenantId.equals(task.tenantId()))
                .sorted(Comparator.comparing(AsyncTask::createdAt).reversed())
                .toList();
    }

    public List<AsyncTask> listByOwner(String tenantId, String userId) {
        return tasks.values().stream()
                .filter(task -> tenantId.equals(task.tenantId()) && userId.equals(task.userId()))
                .sorted(Comparator.comparing(AsyncTask::createdAt).reversed())
                .toList();
    }

    /** Returns the process-local count for the in-memory store. JDBC overrides this with a
     * database count so every replica reports the same central queue state. */
    public long countByStatus(AsyncTaskStatus status) {
        return tasks.values().stream()
                .filter(task -> task.status() == status)
                .count();
    }

    public List<AsyncTask> failOrphans(Set<String> kinds,
                                       Instant pendingCutoff,
                                       Instant runningCutoff,
                                       int limit,
                                       String error) {
        return tasks.values().stream()
                .filter(task -> kinds.contains(task.kind()))
                .filter(task -> isOrphan(task, pendingCutoff, runningCutoff))
                .sorted(Comparator.comparing(AsyncTask::createdAt))
                .limit(Math.max(1, limit))
                .map(task -> transitionOrphan(task.taskId(), pendingCutoff, runningCutoff, error))
                .filter(Optional::isPresent)
                .map(Optional::get)
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
                status.isTerminal() ? null : task.leaseExpiresAt(),
                task.leaseEpoch());
    }

    static AsyncTask withLease(AsyncTask task, String workerId, Instant leaseExpiresAt) {
        long epoch = task.leaseEpoch() == 0 ? 1 : task.leaseEpoch();
        return withLease(task, workerId, leaseExpiresAt, epoch);
    }

    static AsyncTask withLease(
            AsyncTask task,
            String workerId,
            Instant leaseExpiresAt,
            long leaseEpoch) {
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
                leaseExpiresAt,
                leaseEpoch);
    }

    static boolean leaseAvailableFor(AsyncTask task, String workerId, Instant now) {
        return leaseAvailableFor(task, workerId, null, now);
    }

    static boolean leaseAvailableFor(
            AsyncTask task,
            String workerId,
            Long expectedLeaseEpoch,
            Instant now) {
        if (expectedLeaseEpoch != null) {
            return leaseOwnedBy(task, workerId, expectedLeaseEpoch, now);
        }
        return task.leaseOwnerId() == null
                || task.leaseOwnerId().isBlank()
                || (task.leaseExpiresAt() != null && !task.leaseExpiresAt().isAfter(now));
    }

    private Optional<AsyncTask> transitionOrphan(String taskId,
                                                 Instant pendingCutoff,
                                                 Instant runningCutoff,
                                                 String error) {
        AsyncTask[] changed = new AsyncTask[1];
        tasks.computeIfPresent(taskId, (ignored, task) -> {
            if (!isOrphan(task, pendingCutoff, runningCutoff)) {
                return task;
            }
            changed[0] = withStatus(task, AsyncTaskStatus.FAILED, null, error);
            return changed[0];
        });
        return Optional.ofNullable(changed[0]);
    }

    static boolean isOrphan(AsyncTask task, Instant pendingCutoff, Instant runningCutoff) {
        return (task.status() == AsyncTaskStatus.PENDING && task.createdAt().isBefore(pendingCutoff))
                || (task.status() == AsyncTaskStatus.RUNNING
                    && (task.leaseExpiresAt() == null
                        ? task.updatedAt().isBefore(runningCutoff)
                        : task.leaseExpiresAt().isBefore(runningCutoff)));
    }

    static boolean leaseOwnedBy(AsyncTask task, String workerId) {
        return leaseOwnedBy(task, workerId, null);
    }

    static boolean leaseOwnedBy(AsyncTask task, String workerId, Long leaseEpoch) {
        return leaseOwnedBy(task, workerId, leaseEpoch, Instant.now());
    }

    private static boolean leaseOwnedBy(
            AsyncTask task,
            String workerId,
            Long leaseEpoch,
            Instant now) {
        return workerId != null
                && task.leaseOwnerId() != null
                && task.leaseOwnerId().equals(workerId)
                && (leaseEpoch == null || task.leaseEpoch() == leaseEpoch)
                && task.leaseExpiresAt() != null
                && task.leaseExpiresAt().isAfter(now);
    }

    public record MutationResult(AsyncTask task, boolean changed) {
    }

    public record LeaseResult(AsyncTask task, boolean acquired) {
    }

    public record LeaseActionResult<T>(AsyncTask task, T value, boolean executed) {
    }
}
