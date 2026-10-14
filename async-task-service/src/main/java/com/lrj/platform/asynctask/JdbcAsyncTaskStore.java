package com.lrj.platform.asynctask;

import com.lrj.platform.asynctask.mapper.AsyncTaskStoreMapper;
import com.lrj.platform.asynctask.mapper.AsyncTaskStoreRow;
import com.lrj.platform.persistence.PlusMapperFactory;
import com.lrj.platform.persistence.Rows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.event.AsyncTaskLifecycleMessage;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * {@link AsyncTaskStore} 的 JDBC 持久化实现（{@code app.async-task.store=jdbc}），使用 {@code MyBatis-Plus Mapper}
 * 直连 MySQL 管理 {@code ASYNC_TASK} 表；schema 由独立版本化 migration 管理，
 * 本 store 启动时只验证 contract。覆写增改查、租约与清理，其中「非终态→终态」的状态提交在同一事务内原子写入一条
 * 生命周期事件 outbox（A1，仅 webhook transport=kafka 时注入 {@link AsyncTaskLifecycleOutbox}），供
 * {@code AsyncTaskLifecycleRelay} relay 到 Kafka。
 */
@Component
@ConditionalOnProperty(name = "app.async-task.store", havingValue = "jdbc")
public class JdbcAsyncTaskStore extends AsyncTaskStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcAsyncTaskStore.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final AsyncTaskStoreMapper db;
    private final ObjectMapper mapper;
    private final Duration ttl;
    private final TransactionTemplate txTemplate;
    /** 可空：仅 transport=kafka 时由 {@link AsyncTaskJdbcConfig} 提供 → 终态更新同事务内写生命周期事件 outbox（A1）。 */
    private final AsyncTaskLifecycleOutbox lifecycleOutbox;
    private final AsyncTaskWebhookOutbox webhookOutbox;
    private final AsyncTaskWebhookProperties webhookProperties;
    private final AsyncTaskEventJournal eventJournal;

    public JdbcAsyncTaskStore(DataSource asyncTaskDataSource,
                              ObjectMapper mapper,
                              @Value("${app.async-task.task-ttl:PT24H}") Duration ttl,
                              @Qualifier("asyncTaskTransactionManager") PlatformTransactionManager txManager,
                              ObjectProvider<AsyncTaskLifecycleOutbox> lifecycleOutbox) {
        this(asyncTaskDataSource, mapper, ttl, txManager, lifecycleOutbox, null, null, null);
    }

    @Autowired
    public JdbcAsyncTaskStore(DataSource asyncTaskDataSource,
                              ObjectMapper mapper,
                              @Value("${app.async-task.task-ttl:PT24H}") Duration ttl,
                              @Qualifier("asyncTaskTransactionManager") PlatformTransactionManager txManager,
                              ObjectProvider<AsyncTaskLifecycleOutbox> lifecycleOutbox,
                              ObjectProvider<AsyncTaskWebhookOutbox> webhookOutbox,
                              AsyncTaskWebhookProperties webhookProperties,
                              ObjectProvider<AsyncTaskEventJournal> eventJournal) {
        super(ttl);
        this.db = PlusMapperFactory.create(asyncTaskDataSource, AsyncTaskStoreMapper.class);
        this.mapper = mapper;
        this.ttl = ttl;
        this.txTemplate = new TransactionTemplate(txManager);
        this.lifecycleOutbox = lifecycleOutbox.getIfAvailable();
        this.webhookOutbox = webhookOutbox == null ? null : webhookOutbox.getIfAvailable();
        this.webhookProperties = webhookProperties;
        this.eventJournal = eventJournal == null ? null : eventJournal.getIfAvailable();
        init();
    }

    private void init() {
        db.readInit1();
        log.info("ASYNC_TASK schema verified");
    }

    @Override
    public void put(AsyncTask task) {
        txTemplate.executeWithoutResult(status -> {
            db.writePut1(
                    task.taskId(),
                    task.tenantId(),
                    task.userId(),
                    task.kind(),
                    task.status().name(),
                    json(task.input()),
                    json(task.result()),
                    task.error(),
                    task.webhookUrl(),
                    millis(task.createdAt()),
                    millis(task.updatedAt()),
                    millis(task.finishedAt()),
                    task.leaseOwnerId(),
                    millis(task.leaseExpiresAt()),
                    task.leaseEpoch());
            appendLifecycle(task);
        });
    }

    @Override
    public Optional<AsyncTask> get(String taskId) {
        List<AsyncTask> rows = Rows.map(db.readGet1(taskId), this::mapTask);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<AsyncTask> update(String taskId, UnaryOperator<AsyncTask> updater) {
        // A1：读-改-写 + 生命周期事件 outbox 写在同一事务，使「非终态→终态」的状态提交与事件行写入原子。
        return txTemplate.execute(status -> {
            Optional<AsyncTask> current = getForUpdate(taskId);
            if (current.isEmpty()) {
                return Optional.empty();
            }
            AsyncTask before = current.get();
            AsyncTask updated = updater.apply(before);
            db.writeUpdate1(
                    updated.status().name(),
                    json(updated.result()),
                    updated.error(),
                    millis(updated.updatedAt()),
                    millis(updated.finishedAt()),
                    updated.leaseOwnerId(),
                    millis(updated.leaseExpiresAt()),
                    updated.taskId());
            // 仅「本次由非终态转为终态」时入队一次（避免重复/对已终态 no-op 重写）
            if (lifecycleOutbox != null && !before.status().isTerminal() && updated.status().isTerminal()) {
                enqueueLifecycle(updated);
            }
            if (!before.status().isTerminal() && updated.status().isTerminal()) {
                enqueueWebhook(updated);
            }
            if (before.status() != updated.status()) {
                appendLifecycle(updated);
            }
            return Optional.of(updated);
        });
    }

    @Override
    public MutationResult transition(String taskId,
                                     String tenantId,
                                     String workerId,
                                     AsyncTaskStatus target,
                                     Object result,
                                     String error) {
        return transition(taskId, tenantId, workerId, null, target, result, error);
    }

    @Override
    public MutationResult transition(String taskId,
                                     String tenantId,
                                     String workerId,
                                     Long leaseEpoch,
                                     AsyncTaskStatus target,
                                     Object result,
                                     String error) {
        MutationResult outcome = txTemplate.execute(status -> {
            Optional<AsyncTask> current = getForUpdate(taskId);
            if (current.isEmpty()) {
                return new MutationResult(null, false);
            }
            AsyncTask before = current.get();
            if (!tenantId.equals(before.tenantId())
                    || before.status().isTerminal()
                    || (target != AsyncTaskStatus.CANCELLED
                        && !leaseOwnedBy(before, workerId, leaseEpoch))) {
                return new MutationResult(before, false);
            }
            AsyncTask updated = withStatus(before, target, result, error);
            persistMutable(updated);
            if (lifecycleOutbox != null && updated.status().isTerminal()) {
                enqueueLifecycle(updated);
            }
            if (updated.status().isTerminal()) {
                enqueueWebhook(updated);
            }
            appendLifecycle(updated);
            return new MutationResult(updated, true);
        });
        return outcome == null ? new MutationResult(null, false) : outcome;
    }

    /** 在 update 事务内写一条生命周期事件 outbox（快照 JSON），供 AsyncTaskLifecycleRelay relay 到 Kafka。 */
    private void enqueueLifecycle(AsyncTask task) {
        AsyncTaskLifecycleMessage msg = AsyncTaskLifecycleEventPublisher.message(task);
        try {
            lifecycleOutbox.enqueue(msg.eventId(), task.tenantId(),
                    mapper.writeValueAsString(msg), System.currentTimeMillis());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize async-task lifecycle event for " + task.taskId(), e);
        }
    }

    @Override
    public Optional<AsyncTask> lease(String taskId, String workerId, Instant leaseExpiresAt) {
        LeaseResult result = lease(taskId, workerId, leaseExpiresAt, null);
        return Optional.ofNullable(result.task());
    }

    @Override
    public LeaseResult lease(
            String taskId,
            String workerId,
            Instant leaseExpiresAt,
            Long expectedLeaseEpoch) {
        LeaseResult result = txTemplate.execute(status -> {
            Optional<AsyncTask> current = getForUpdate(taskId);
            if (current.isEmpty()) {
                return new LeaseResult(null, false);
            }
            AsyncTask before = current.get();
            if (before.status().isTerminal()
                    || !leaseAvailableFor(before, workerId, expectedLeaseEpoch, Instant.now())) {
                return new LeaseResult(before, false);
            }
            long epoch = expectedLeaseEpoch == null
                    ? Math.addExact(before.leaseEpoch(), 1L)
                    : before.leaseEpoch();
            AsyncTask leased = withLease(before, workerId, leaseExpiresAt, epoch);
            persistMutable(leased);
            if (before.status() != AsyncTaskStatus.RUNNING) {
                appendLifecycle(leased);
            }
            return new LeaseResult(leased, true);
        });
        return result == null ? new LeaseResult(null, false) : result;
    }

    @Override
    public <T> LeaseActionResult<T> withActiveLease(
            String taskId,
            String tenantId,
            String workerId,
            long leaseEpoch,
            Supplier<T> action) {
        LeaseActionResult<T> result = txTemplate.execute(status -> {
            Optional<AsyncTask> current = getForUpdate(taskId);
            if (current.isEmpty()) {
                return new LeaseActionResult<>(null, null, false);
            }
            AsyncTask task = current.get();
            if (!tenantId.equals(task.tenantId())
                    || task.status().isTerminal()
                    || !leaseOwnedBy(task, workerId, leaseEpoch)) {
                return new LeaseActionResult<>(task, null, false);
            }
            return new LeaseActionResult<>(task, action.get(), true);
        });
        return result == null ? new LeaseActionResult<>(null, null, false) : result;
    }

    @Override
    public List<AsyncTask> listByTenant(String tenantId) {
        return Rows.map(db.readListByTenant1(tenantId), this::mapTask);
    }

    @Override
    public List<AsyncTask> listByOwner(String tenantId, String userId) {
        return Rows.map(db.readListByOwner1(tenantId, userId), this::mapTask);
    }

    @Override
    public long countByStatus(AsyncTaskStatus status) {
        Long count = Rows.one(db.readCountByStatus1(status.name()));
        return count == null ? 0 : count;
    }

    @Override
    public List<DrainInventoryRow> drainInventory(String tenantId, Set<String> kinds) {
        if (tenantId == null || kinds == null || kinds.isEmpty()) {
            return List.of();
        }
        List<String> openStatuses = java.util.Arrays.stream(AsyncTaskStatus.values())
                .filter(status -> !status.isTerminal()).map(Enum::name).toList();
        List<DrainInventoryRow> rows = Rows.map(db.drainInventory(tenantId, kinds, openStatuses),
                (rs, rowNum) -> new DrainInventoryRow(rs.kind, AsyncTaskStatus.valueOf(rs.status),
                        rs.leaseOwnerId, Rows.longValue(rs.openTasks)));
        return rows.stream().sorted(DRAIN_INVENTORY_ORDER).toList();
    }

    @Override
    public List<AsyncTask> failOrphans(Set<String> kinds,
                                       Instant pendingCutoff,
                                       Instant runningCutoff,
                                       int limit,
                                       String error) {
        if (kinds.isEmpty()) {
            return List.of();
        }
        List<String> ids = db.orphanIds(
                kinds,
                pendingCutoff.toEpochMilli(),
                runningCutoff.toEpochMilli(),
                Math.max(1, limit));
        return ids.stream()
                .map(id -> failOrphan(id, pendingCutoff, runningCutoff, error))
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    @Scheduled(fixedDelayString = "${app.async-task.cleanup-delay-ms:60000}",
            initialDelayString = "${app.async-task.cleanup-initial-delay-ms:60000}")
    public void cleanup() {
        long cutoff = Instant.now().minus(ttl).toEpochMilli();
        Integer result = txTemplate.execute(status -> {
            int removed = db.writeCleanup1(cutoff);
            // 逻辑删除不会触发旧外键的ON DELETE CASCADE，因此在同一事务标记调度上下文。
            db.cleanupDispatch(cutoff);
            db.cleanupCheckpoints(cutoff);
            return removed;
        });
        int removed = result == null ? 0 : result;
        if (removed > 0) {
            log.info("async task jdbc cleanup removed {} expired tasks ttl={}", removed, ttl);
        }
    }

    private AsyncTask mapTask(AsyncTaskStoreRow rs, int rowNum) {
        return new AsyncTask(
                rs.taskId,
                rs.tenantId,
                rs.userId,
                rs.kind,
                AsyncTaskStatus.valueOf(rs.status),
                map(rs.inputJson),
                object(rs.resultJson),
                rs.errorText,
                rs.webhookUrl,
                instant(Rows.longValue(rs.createdAt)),
                instant(Rows.longValue(rs.updatedAt)),
                instantNullable(rs.finishedAt),
                rs.leaseOwnerId,
                instantNullable(rs.leaseExpiresAt),
                Rows.longValue(rs.leaseEpoch));
    }

    private Optional<AsyncTask> getForUpdate(String taskId) {
        return Rows.map(db.readGetForUpdate1(taskId), this::mapTask).stream().findFirst();
    }

    private void persistMutable(AsyncTask updated) {
        db.writePersistMutable1(
                updated.status().name(),
                json(updated.result()),
                updated.error(),
                millis(updated.updatedAt()),
                millis(updated.finishedAt()),
                updated.leaseOwnerId(),
                millis(updated.leaseExpiresAt()),
                updated.leaseEpoch(),
                updated.taskId());
    }

    private Optional<AsyncTask> failOrphan(String taskId,
                                           Instant pendingCutoff,
                                           Instant runningCutoff,
                                           String error) {
        return txTemplate.execute(status -> {
            Optional<AsyncTask> current = getForUpdate(taskId);
            if (current.isEmpty() || !isOrphan(current.get(), pendingCutoff, runningCutoff)) {
                return Optional.empty();
            }
            AsyncTask updated = withStatus(
                    current.get(), AsyncTaskStatus.FAILED, null, error);
            persistMutable(updated);
            if (lifecycleOutbox != null) {
                enqueueLifecycle(updated);
            }
            enqueueWebhook(updated);
            appendLifecycle(updated);
            return Optional.of(updated);
        });
    }

    private void enqueueWebhook(AsyncTask task) {
        if (webhookOutbox == null
                || webhookProperties == null
                || !webhookProperties.isEnabled()
                || webhookProperties.isKafkaTransport()) {
            return;
        }
        AsyncTaskWebhookNotifier.webhookUri(task.webhookUrl())
                .ifPresent(uri -> webhookOutbox.enqueue(
                        task, uri.toString(), Instant.now().toEpochMilli()));
    }

    private void appendLifecycle(AsyncTask task) {
        if (eventJournal == null) {
            return;
        }
        eventJournal.append(
                task.taskId(),
                "status:" + task.status().name(),
                task.status().name(),
                task,
                null,
                task.updatedAt());
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("failed to serialize async task JSON", ex);
        }
    }

    private Map<String, Object> map(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(value, MAP);
        } catch (Exception ex) {
            throw new IllegalArgumentException("failed to parse async task input JSON", ex);
        }
    }

    private Object object(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(value, Object.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("failed to parse async task result JSON", ex);
        }
    }

    private static Long millis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static Instant instant(long millis) {
        return Instant.ofEpochMilli(millis);
    }

    private static Instant instantNullable(Long value) {
        return value == null ? null : Instant.ofEpochMilli(value);
    }
}
