package com.lrj.platform.asynctask;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * {@link AsyncTaskStore} 的 JDBC 持久化实现（{@code app.async-task.store=jdbc}），用裸 {@link JdbcTemplate}
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

    private final JdbcTemplate jdbc;
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
        this.jdbc = new JdbcTemplate(asyncTaskDataSource);
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
        jdbc.queryForList("""
                SELECT TASK_ID, TENANT_ID, USER_ID, KIND, STATUS, INPUT_JSON, RESULT_JSON,
                       ERROR_TEXT, WEBHOOK_URL, CREATED_AT, UPDATED_AT, FINISHED_AT,
                       LEASE_OWNER_ID, LEASE_EXPIRES_AT, LEASE_EPOCH
                FROM ASYNC_TASK WHERE 1=0""");
        log.info("ASYNC_TASK schema verified");
    }

    @Override
    public void put(AsyncTask task) {
        txTemplate.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO ASYNC_TASK
                    (TASK_ID, TENANT_ID, USER_ID, KIND, STATUS, INPUT_JSON, RESULT_JSON, ERROR_TEXT, WEBHOOK_URL, CREATED_AT, UPDATED_AT, FINISHED_AT, LEASE_OWNER_ID, LEASE_EXPIRES_AT, LEASE_EPOCH)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
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
        List<AsyncTask> rows = jdbc.query("SELECT * FROM ASYNC_TASK WHERE TASK_ID=?",
                this::mapTask, taskId);
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
            jdbc.update("""
                    UPDATE ASYNC_TASK
                    SET STATUS=?, RESULT_JSON=?, ERROR_TEXT=?, UPDATED_AT=?, FINISHED_AT=?, LEASE_OWNER_ID=?, LEASE_EXPIRES_AT=?
                    WHERE TASK_ID=?""",
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
        return jdbc.query("""
                SELECT * FROM ASYNC_TASK
                WHERE TENANT_ID=?
                ORDER BY CREATED_AT DESC""",
                this::mapTask, tenantId);
    }

    @Override
    public List<AsyncTask> listByOwner(String tenantId, String userId) {
        return jdbc.query("""
                SELECT * FROM ASYNC_TASK
                WHERE TENANT_ID=? AND USER_ID=?
                ORDER BY CREATED_AT DESC""",
                this::mapTask, tenantId, userId);
    }

    @Override
    public long countByStatus(AsyncTaskStatus status) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ASYNC_TASK WHERE STATUS=?",
                Long.class,
                status.name());
        return count == null ? 0 : count;
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
        String placeholders = String.join(",", java.util.Collections.nCopies(kinds.size(), "?"));
        List<Object> args = new java.util.ArrayList<>(kinds);
        args.add(AsyncTaskStatus.PENDING.name());
        args.add(pendingCutoff.toEpochMilli());
        args.add(AsyncTaskStatus.RUNNING.name());
        args.add(runningCutoff.toEpochMilli());
        args.add(runningCutoff.toEpochMilli());
        args.add(Math.max(1, limit));
        List<String> ids = jdbc.queryForList("""
                SELECT TASK_ID FROM ASYNC_TASK
                WHERE KIND IN (%s)
                  AND ((STATUS=? AND CREATED_AT < ?)
                    OR (STATUS=? AND
                      ((LEASE_EXPIRES_AT IS NOT NULL AND LEASE_EXPIRES_AT < ?)
                       OR (LEASE_EXPIRES_AT IS NULL AND UPDATED_AT < ?))))
                ORDER BY CREATED_AT
                LIMIT ?""".formatted(placeholders), String.class, args.toArray());
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
        int removed = jdbc.update("DELETE FROM ASYNC_TASK WHERE FINISHED_AT IS NOT NULL AND FINISHED_AT < ?", cutoff);
        if (removed > 0) {
            log.info("async task jdbc cleanup removed {} expired tasks ttl={}", removed, ttl);
        }
    }

    private AsyncTask mapTask(ResultSet rs, int rowNum) throws SQLException {
        return new AsyncTask(
                rs.getString("TASK_ID"),
                rs.getString("TENANT_ID"),
                rs.getString("USER_ID"),
                rs.getString("KIND"),
                AsyncTaskStatus.valueOf(rs.getString("STATUS")),
                map(rs.getString("INPUT_JSON")),
                object(rs.getString("RESULT_JSON")),
                rs.getString("ERROR_TEXT"),
                rs.getString("WEBHOOK_URL"),
                instant(rs.getLong("CREATED_AT")),
                instant(rs.getLong("UPDATED_AT")),
                instantNullable(rs, "FINISHED_AT"),
                nullableString(rs, "LEASE_OWNER_ID"),
                instantNullable(rs, "LEASE_EXPIRES_AT"),
                rs.getLong("LEASE_EPOCH"));
    }

    private Optional<AsyncTask> getForUpdate(String taskId) {
        return jdbc.query("SELECT * FROM ASYNC_TASK WHERE TASK_ID=? FOR UPDATE",
                this::mapTask, taskId).stream().findFirst();
    }

    private void persistMutable(AsyncTask updated) {
        jdbc.update("""
                UPDATE ASYNC_TASK
                SET STATUS=?, RESULT_JSON=?, ERROR_TEXT=?, UPDATED_AT=?, FINISHED_AT=?,
                    LEASE_OWNER_ID=?, LEASE_EXPIRES_AT=?, LEASE_EPOCH=?
                WHERE TASK_ID=?""",
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

    private static Instant instantNullable(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static String nullableString(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return rs.wasNull() ? null : value;
    }
}
