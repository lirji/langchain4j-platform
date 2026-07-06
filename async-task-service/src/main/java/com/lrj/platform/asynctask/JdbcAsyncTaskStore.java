package com.lrj.platform.asynctask;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

@Component
@ConditionalOnProperty(name = "app.async-task.store", havingValue = "jdbc")
public class JdbcAsyncTaskStore extends AsyncTaskStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcAsyncTaskStore.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public JdbcAsyncTaskStore(DataSource asyncTaskDataSource,
                              ObjectMapper mapper,
                              @Value("${app.async-task.task-ttl:PT24H}") Duration ttl) {
        super(ttl);
        this.jdbc = new JdbcTemplate(asyncTaskDataSource);
        this.mapper = mapper;
        this.ttl = ttl;
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
        Optional<AsyncTask> current = get(taskId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        AsyncTask updated = updater.apply(current.get());
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
        return Optional.of(updated);
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
