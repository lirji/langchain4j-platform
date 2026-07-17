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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JDBC 模式（{@code app.async-task.store=jdbc}）下的 webhook 事务性 outbox，直连裸 {@link JdbcTemplate} 管理
 * {@code ASYNC_TASK_WEBHOOK_OUTBOX} 表（表结构以 {@code CREATE TABLE IF NOT EXISTS}/{@code ALTER TABLE} 字面量在
 * {@code init()} 内演进）。提供入队、基于 claim TTL 的抢占式派发（{@link #claimDue}，支持过期重认领）、
 * 投递成功/重试/死信标记、指数退避调度（{@link #schedule}）与死信巡检。由 {@link AsyncTaskWebhookOutboxEnqueuer}
 * 入队、{@link AsyncTaskWebhookOutboxDispatcher} 轮询派发。
 */
@Component
@ConditionalOnProperty(name = "app.async-task.store", havingValue = "jdbc")
public class AsyncTaskWebhookOutbox {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskWebhookOutbox.class);

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    static final String STATUS_DELIVERED = "DELIVERED";
    static final String STATUS_DEAD = "DEAD";
    private static final long DEFAULT_CLAIM_TTL_MS = 120_000L;

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
                  CLAIMED_BY VARCHAR(128),
                  CLAIMED_UNTIL BIGINT,
                  CREATED_AT BIGINT NOT NULL,
                  UPDATED_AT BIGINT NOT NULL,
                  INDEX IDX_ASYNC_TASK_WEBHOOK_DUE (STATUS, NEXT_ATTEMPT_AT),
                  INDEX IDX_ASYNC_TASK_WEBHOOK_CLAIM (STATUS, CLAIMED_UNTIL),
                  INDEX IDX_ASYNC_TASK_WEBHOOK_TASK (TASK_ID)
                )""");
        addColumnIfMissing("CLAIMED_BY VARCHAR(128)");
        addColumnIfMissing("CLAIMED_UNTIL BIGINT");
        addIndexIfMissing("IDX_ASYNC_TASK_WEBHOOK_CLAIM", "STATUS, CLAIMED_UNTIL");
        log.info("ASYNC_TASK_WEBHOOK_OUTBOX table ready");
    }

    private void addColumnIfMissing(String definition) {
        try {
            jdbc.execute("ALTER TABLE ASYNC_TASK_WEBHOOK_OUTBOX ADD COLUMN " + definition);
        } catch (Exception ignored) {
            // Duplicate-column errors are harmless across old MySQL/H2 versions.
        }
    }

    private void addIndexIfMissing(String name, String columns) {
        try {
            jdbc.execute("CREATE INDEX " + name + " ON ASYNC_TASK_WEBHOOK_OUTBOX (" + columns + ")");
        } catch (Exception ignored) {
            // Existing-index errors are harmless here.
        }
    }

    public void enqueue(AsyncTask task, String targetUrl, long now) {
        jdbc.update("""
                INSERT INTO ASYNC_TASK_WEBHOOK_OUTBOX
                (OUTBOX_ID, TASK_ID, TENANT_ID, TARGET_URL, TASK_STATUS, PAYLOAD_JSON, STATUS, ATTEMPTS, NEXT_ATTEMPT_AT, LAST_ERROR, CREATED_AT, UPDATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, NULL, ?, ?)
                ON DUPLICATE KEY UPDATE TARGET_URL=VALUES(TARGET_URL), TASK_STATUS=VALUES(TASK_STATUS),
                  PAYLOAD_JSON=VALUES(PAYLOAD_JSON), STATUS='PENDING', ATTEMPTS=0,
                  NEXT_ATTEMPT_AT=VALUES(NEXT_ATTEMPT_AT), LAST_ERROR=NULL, CLAIMED_BY=NULL,
                  CLAIMED_UNTIL=NULL, UPDATED_AT=VALUES(UPDATED_AT)""",
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
        return claimDue(now, limit, UUID.randomUUID().toString(), DEFAULT_CLAIM_TTL_MS);
    }

    List<Row> claimDue(long now, int limit, String ownerId, long claimTtlMs) {
        int boundedLimit = Math.max(1, limit);
        long claimedUntil = now + Math.max(1_000L, claimTtlMs);
        List<String> candidateIds = jdbc.queryForList("""
                SELECT OUTBOX_ID
                FROM ASYNC_TASK_WEBHOOK_OUTBOX
                WHERE (STATUS='PENDING' AND NEXT_ATTEMPT_AT <= ?)
                   OR (STATUS='IN_PROGRESS' AND CLAIMED_UNTIL <= ?)
                ORDER BY NEXT_ATTEMPT_AT ASC LIMIT ?""",
                String.class, now, now, boundedLimit);
        if (candidateIds.isEmpty()) {
            return List.of();
        }

        String placeholders = placeholders(candidateIds.size());
        List<Object> updateArgs = new ArrayList<>();
        updateArgs.add(STATUS_IN_PROGRESS);
        updateArgs.add(ownerId);
        updateArgs.add(claimedUntil);
        updateArgs.add(now);
        updateArgs.addAll(candidateIds);
        updateArgs.add(now);
        updateArgs.add(now);
        jdbc.update("""
                UPDATE ASYNC_TASK_WEBHOOK_OUTBOX
                SET STATUS=?, CLAIMED_BY=?, CLAIMED_UNTIL=?, UPDATED_AT=?
                WHERE OUTBOX_ID IN (%s)
                  AND ((STATUS='PENDING' AND NEXT_ATTEMPT_AT <= ?)
                    OR (STATUS='IN_PROGRESS' AND CLAIMED_UNTIL <= ?))""".formatted(placeholders),
                updateArgs.toArray());

        List<Object> selectArgs = new ArrayList<>();
        selectArgs.add(ownerId);
        selectArgs.addAll(candidateIds);
        return jdbc.query("""
                SELECT OUTBOX_ID, TASK_ID, TENANT_ID, TARGET_URL, TASK_STATUS, PAYLOAD_JSON, ATTEMPTS
                FROM ASYNC_TASK_WEBHOOK_OUTBOX
                WHERE STATUS='IN_PROGRESS' AND CLAIMED_BY=? AND OUTBOX_ID IN (%s)
                ORDER BY NEXT_ATTEMPT_AT ASC LIMIT ?""".formatted(placeholders),
                this::mapRow,
                selectArgsWithLimit(selectArgs, boundedLimit).toArray());
    }

    public void markDelivered(String outboxId, long now) {
        jdbc.update("""
                UPDATE ASYNC_TASK_WEBHOOK_OUTBOX
                SET STATUS='DELIVERED', CLAIMED_BY=NULL, CLAIMED_UNTIL=NULL, UPDATED_AT=?
                WHERE OUTBOX_ID=?""",
                now, outboxId);
    }

    public int purgeDeliveredBefore(long cutoffUpdatedAt) {
        int deleted = jdbc.update("""
                DELETE FROM ASYNC_TASK_WEBHOOK_OUTBOX
                WHERE STATUS='DELIVERED' AND UPDATED_AT < ?""", cutoffUpdatedAt);
        if (deleted > 0) {
            log.info("purged delivered async task webhook outbox rows count={} cutoff={}", deleted, cutoffUpdatedAt);
        }
        return deleted;
    }

    public void markRetry(String outboxId, int attempts, long nextAttemptAt, String error, long now) {
        jdbc.update("""
                UPDATE ASYNC_TASK_WEBHOOK_OUTBOX
                SET STATUS='PENDING', ATTEMPTS=?, NEXT_ATTEMPT_AT=?, LAST_ERROR=?,
                    CLAIMED_BY=NULL, CLAIMED_UNTIL=NULL, UPDATED_AT=?
                WHERE OUTBOX_ID=?""",
                attempts, nextAttemptAt, trunc(error), now, outboxId);
    }

    public void markDead(String outboxId, int attempts, String error, long now) {
        jdbc.update("""
                UPDATE ASYNC_TASK_WEBHOOK_OUTBOX
                SET STATUS='DEAD', ATTEMPTS=?, LAST_ERROR=?,
                    CLAIMED_BY=NULL, CLAIMED_UNTIL=NULL, UPDATED_AT=?
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

    private static String placeholders(int count) {
        return Collections.nCopies(count, "?").stream().collect(Collectors.joining(","));
    }

    private static List<Object> selectArgsWithLimit(List<Object> args, int limit) {
        List<Object> withLimit = new ArrayList<>(args);
        withLimit.add(limit);
        return withLimit;
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
