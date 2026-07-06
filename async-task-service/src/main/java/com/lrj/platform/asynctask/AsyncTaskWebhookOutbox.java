package com.lrj.platform.asynctask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.async-task.store", havingValue = "jdbc")
public class AsyncTaskWebhookOutbox {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskWebhookOutbox.class);

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_DELIVERED = "DELIVERED";
    static final String STATUS_DEAD = "DEAD";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AsyncTaskWebhookOutbox(DataSource asyncTaskDataSource, ObjectMapper mapper) {
        this.jdbc = new JdbcTemplate(asyncTaskDataSource);
        this.mapper = mapper;
        init();
    }

    private void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ASYNC_TASK_WEBHOOK_OUTBOX (
                  OUTBOX_ID VARCHAR(128) NOT NULL PRIMARY KEY,
                  TASK_ID VARCHAR(128) NOT NULL,
                  TENANT_ID VARCHAR(128) NOT NULL,
                  TARGET_URL VARCHAR(1024) NOT NULL,
                  TASK_STATUS VARCHAR(32) NOT NULL,
                  PAYLOAD_JSON MEDIUMTEXT NOT NULL,
                  STATUS VARCHAR(16) NOT NULL,
                  ATTEMPTS INT NOT NULL DEFAULT 0,
                  NEXT_ATTEMPT_AT BIGINT NOT NULL,
                  LAST_ERROR VARCHAR(512),
                  CREATED_AT BIGINT NOT NULL,
                  UPDATED_AT BIGINT NOT NULL,
                  INDEX IDX_ASYNC_TASK_WEBHOOK_DUE (STATUS, NEXT_ATTEMPT_AT),
                  INDEX IDX_ASYNC_TASK_WEBHOOK_TASK (TASK_ID)
                )""");
        log.info("ASYNC_TASK_WEBHOOK_OUTBOX table ready");
    }

    public void enqueue(AsyncTask task, String targetUrl, long now) {
        jdbc.update("""
                INSERT INTO ASYNC_TASK_WEBHOOK_OUTBOX
                (OUTBOX_ID, TASK_ID, TENANT_ID, TARGET_URL, TASK_STATUS, PAYLOAD_JSON, STATUS, ATTEMPTS, NEXT_ATTEMPT_AT, LAST_ERROR, CREATED_AT, UPDATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, NULL, ?, ?)
                ON DUPLICATE KEY UPDATE TARGET_URL=VALUES(TARGET_URL), TASK_STATUS=VALUES(TASK_STATUS),
                  PAYLOAD_JSON=VALUES(PAYLOAD_JSON), STATUS='PENDING', ATTEMPTS=0,
                  NEXT_ATTEMPT_AT=VALUES(NEXT_ATTEMPT_AT), LAST_ERROR=NULL, UPDATED_AT=VALUES(UPDATED_AT)""",
                task.taskId(),
                task.taskId(),
                task.tenantId(),
                targetUrl,
                task.status().name(),
                payload(task),
                now,
                now,
                now);
    }

    public List<Row> claimDue(long now, int limit) {
        return jdbc.query("""
                SELECT OUTBOX_ID, TASK_ID, TENANT_ID, TARGET_URL, TASK_STATUS, PAYLOAD_JSON, ATTEMPTS
                FROM ASYNC_TASK_WEBHOOK_OUTBOX
                WHERE STATUS='PENDING' AND NEXT_ATTEMPT_AT <= ?
                ORDER BY NEXT_ATTEMPT_AT ASC LIMIT ?""",
                this::mapRow, now, limit);
    }

    public void markDelivered(String outboxId, long now) {
        jdbc.update("UPDATE ASYNC_TASK_WEBHOOK_OUTBOX SET STATUS='DELIVERED', UPDATED_AT=? WHERE OUTBOX_ID=?",
                now, outboxId);
    }

    public void markRetry(String outboxId, int attempts, long nextAttemptAt, String error, long now) {
        jdbc.update("""
                UPDATE ASYNC_TASK_WEBHOOK_OUTBOX
                SET ATTEMPTS=?, NEXT_ATTEMPT_AT=?, LAST_ERROR=?, UPDATED_AT=?
                WHERE OUTBOX_ID=?""",
                attempts, nextAttemptAt, trunc(error), now, outboxId);
    }

    public void markDead(String outboxId, int attempts, String error, long now) {
        jdbc.update("""
                UPDATE ASYNC_TASK_WEBHOOK_OUTBOX
                SET STATUS='DEAD', ATTEMPTS=?, LAST_ERROR=?, UPDATED_AT=?
                WHERE OUTBOX_ID=?""",
                attempts, trunc(error), now, outboxId);
    }

    public List<InspectionRow> listDead(String tenantId, int limit) {
        return jdbc.query("""
                SELECT OUTBOX_ID, TASK_ID, TENANT_ID, TARGET_URL, TASK_STATUS, STATUS, ATTEMPTS,
                       LAST_ERROR, CREATED_AT, UPDATED_AT
                FROM ASYNC_TASK_WEBHOOK_OUTBOX
                WHERE TENANT_ID=? AND STATUS='DEAD'
                ORDER BY UPDATED_AT DESC LIMIT ?""",
                this::mapInspectionRow, tenantId, Math.max(1, limit));
    }

    static Decision schedule(int attemptsAfter, int maxAttempts, long now, long baseBackoffMs) {
        if (attemptsAfter >= maxAttempts) {
            return new Decision(true, 0L);
        }
        long delay = (long) (baseBackoffMs * Math.pow(3, Math.max(0, attemptsAfter - 1)));
        return new Decision(false, now + delay);
    }

    private Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                rs.getString("OUTBOX_ID"),
                rs.getString("TASK_ID"),
                rs.getString("TENANT_ID"),
                rs.getString("TARGET_URL"),
                rs.getString("TASK_STATUS"),
                rs.getString("PAYLOAD_JSON"),
                rs.getInt("ATTEMPTS"));
    }

    private InspectionRow mapInspectionRow(ResultSet rs, int rowNum) throws SQLException {
        return new InspectionRow(
                rs.getString("OUTBOX_ID"),
                rs.getString("TASK_ID"),
                rs.getString("TENANT_ID"),
                rs.getString("TARGET_URL"),
                rs.getString("TASK_STATUS"),
                rs.getString("STATUS"),
                rs.getInt("ATTEMPTS"),
                rs.getString("LAST_ERROR"),
                Instant.ofEpochMilli(rs.getLong("CREATED_AT")),
                Instant.ofEpochMilli(rs.getLong("UPDATED_AT")));
    }

    private String payload(AsyncTask task) {
        try {
            return mapper.writeValueAsString(task);
        } catch (Exception ex) {
            return "{\"taskId\":\"" + task.taskId() + "\",\"status\":\"" + task.status().name() + "\"}";
        }
    }

    private static String trunc(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    public record Row(String outboxId,
                      String taskId,
                      String tenantId,
                      String targetUrl,
                      String taskStatus,
                      String payloadJson,
                      int attempts) {
    }

    public record InspectionRow(String outboxId,
                                String taskId,
                                String tenantId,
                                String targetUrl,
                                String taskStatus,
                                String status,
                                int attempts,
                                String lastError,
                                Instant createdAt,
                                Instant updatedAt) {
    }

    record Decision(boolean dead, long nextAttemptAt) {
    }
}
