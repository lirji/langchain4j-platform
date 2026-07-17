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
import java.util.function.UnaryOperator;

/**
 * {@link AsyncTaskStore} 的 JDBC 持久化实现（{@code app.async-task.store=jdbc}），用裸 {@link JdbcTemplate}
 * 直连 MySQL 管理 {@code ASYNC_TASK} 表（表结构以 {@code CREATE TABLE IF NOT EXISTS}/{@code ALTER TABLE} 字面量
 * 在 {@code init()} 内演进）。覆写增改查、租约与清理，其中「非终态→终态」的状态提交在同一事务内原子写入一条
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

    public JdbcAsyncTaskStore(DataSource asyncTaskDataSource,
                              ObjectMapper mapper,
                              @Value("${app.async-task.task-ttl:PT24H}") Duration ttl,
                              @Qualifier("asyncTaskTransactionManager") PlatformTransactionManager txManager,
                              ObjectProvider<AsyncTaskLifecycleOutbox> lifecycleOutbox) {
        super(ttl);
        this.jdbc = new JdbcTemplate(asyncTaskDataSource);
        this.mapper = mapper;
        this.ttl = ttl;
        this.txTemplate = new TransactionTemplate(txManager);
        this.lifecycleOutbox = lifecycleOutbox.getIfAvailable();
        init();
    }

    private void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ASYNC_TASK (
                  TASK_ID VARCHAR(128) NOT NULL PRIMARY KEY,
                  TENANT_ID VARCHAR(128) NOT NULL,
                  USER_ID VARCHAR(128) NOT NULL,
                  KIND VARCHAR(128) NOT NULL,
                  STATUS VARCHAR(32) NOT NULL,
                  INPUT_JSON MEDIUMTEXT,
                  RESULT_JSON MEDIUMTEXT,
                  ERROR_TEXT VARCHAR(2048),
                  WEBHOOK_URL VARCHAR(1024),
                  CREATED_AT BIGINT NOT NULL,
                  UPDATED_AT BIGINT NOT NULL,
                  FINISHED_AT BIGINT,
                  LEASE_OWNER_ID VARCHAR(128),
                  LEASE_EXPIRES_AT BIGINT,
                  INDEX IDX_ASYNC_TASK_TENANT_CREATED (TENANT_ID, CREATED_AT),
                  INDEX IDX_ASYNC_TASK_FINISHED (FINISHED_AT),
                  INDEX IDX_ASYNC_TASK_LEASE (STATUS, LEASE_EXPIRES_AT)
                )""");
        addColumnIfMissing("LEASE_OWNER_ID VARCHAR(128)");
        addColumnIfMissing("LEASE_EXPIRES_AT BIGINT");
        log.info("ASYNC_TASK table ready");
    }

    private void addColumnIfMissing(String definition) {
        try {
            jdbc.execute("ALTER TABLE ASYNC_TASK ADD COLUMN " + definition);
        } catch (Exception ignored) {
            // MySQL has no portable IF NOT EXISTS for older versions; duplicate-column errors are harmless here.
        }
    }

    @Override
    public void put(AsyncTask task) {
        jdbc.update("""
                INSERT INTO ASYNC_TASK
                (TASK_ID, TENANT_ID, USER_ID, KIND, STATUS, INPUT_JSON, RESULT_JSON, ERROR_TEXT, WEBHOOK_URL, CREATED_AT, UPDATED_AT, FINISHED_AT, LEASE_OWNER_ID, LEASE_EXPIRES_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
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
                millis(task.leaseExpiresAt()));
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
            Optional<AsyncTask> current = get(taskId);
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
            return Optional.of(updated);
        });
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
        long now = Instant.now().toEpochMilli();
        jdbc.update("""
                UPDATE ASYNC_TASK
                SET STATUS=?, UPDATED_AT=?, FINISHED_AT=NULL, LEASE_OWNER_ID=?, LEASE_EXPIRES_AT=?
                WHERE TASK_ID=?
                  AND STATUS NOT IN (?, ?, ?)
                  AND (LEASE_OWNER_ID IS NULL OR LEASE_OWNER_ID='' OR LEASE_OWNER_ID=? OR LEASE_EXPIRES_AT < ?)""",
                AsyncTaskStatus.RUNNING.name(),
                now,
                workerId,
                millis(leaseExpiresAt),
                taskId,
                AsyncTaskStatus.SUCCEEDED.name(),
                AsyncTaskStatus.FAILED.name(),
                AsyncTaskStatus.CANCELLED.name(),
                workerId,
                now);
        return get(taskId);
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
                instantNullable(rs, "LEASE_EXPIRES_AT"));
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
