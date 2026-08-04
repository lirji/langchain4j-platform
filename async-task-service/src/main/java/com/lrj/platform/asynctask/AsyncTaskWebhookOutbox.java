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
 * {@code ASYNC_TASK_WEBHOOK_OUTBOX} 表；schema 由独立 migration 管理。提供入队、基于 claim TTL 的抢占式派发（{@link #claimDue}，支持过期重认领）、
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
        jdbc.queryForList("""
                SELECT OUTBOX_ID, TASK_ID, TENANT_ID, TARGET_URL, TASK_STATUS, PAYLOAD_JSON,
                       STATUS, ATTEMPTS, NEXT_ATTEMPT_AT, LAST_ERROR, CLAIMED_BY, CLAIMED_UNTIL,
                       CREATED_AT, UPDATED_AT
                FROM ASYNC_TASK_WEBHOOK_OUTBOX WHERE 1=0""");
        log.info("ASYNC_TASK_WEBHOOK_OUTBOX schema verified");
    }

    public void enqueue(AsyncTask task, String targetUrl, long now) {
        jdbc.update("""
                INSERT INTO ASYNC_TASK_WEBHOOK_OUTBOX
                (OUTBOX_ID, TASK_ID, TENANT_ID, TARGET_URL, TASK_STATUS, PAYLOAD_JSON, STATUS, ATTEMPTS, NEXT_ATTEMPT_AT, LAST_ERROR, CREATED_AT, UPDATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, NULL, ?, ?)
                ON DUPLICATE KEY UPDATE OUTBOX_ID=OUTBOX_ID""",
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
                SELECT OUTBOX_ID, TASK_ID, TENANT_ID, TARGET_URL, TASK_STATUS, PAYLOAD_JSON,
                       ATTEMPTS, CLAIMED_BY
                FROM ASYNC_TASK_WEBHOOK_OUTBOX
                WHERE STATUS='IN_PROGRESS' AND CLAIMED_BY=? AND OUTBOX_ID IN (%s)
                ORDER BY NEXT_ATTEMPT_AT ASC LIMIT ?""".formatted(placeholders),
                this::mapRow,
                selectArgsWithLimit(selectArgs, boundedLimit).toArray());
    }

    public boolean markDelivered(String outboxId, String claimOwner, long now) {
        return jdbc.update("""
                UPDATE ASYNC_TASK_WEBHOOK_OUTBOX
                SET STATUS='DELIVERED', CLAIMED_BY=NULL, CLAIMED_UNTIL=NULL, UPDATED_AT=?
                WHERE OUTBOX_ID=? AND STATUS='IN_PROGRESS'
                  AND CLAIMED_BY=? AND CLAIMED_UNTIL > ?""",
                now, outboxId, claimOwner, now) == 1;
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

    public boolean markRetry(
            String outboxId,
            String claimOwner,
            int attempts,
            long nextAttemptAt,
            String error,
            long now) {
        return jdbc.update("""
                UPDATE ASYNC_TASK_WEBHOOK_OUTBOX
                SET STATUS='PENDING', ATTEMPTS=?, NEXT_ATTEMPT_AT=?, LAST_ERROR=?,
                    CLAIMED_BY=NULL, CLAIMED_UNTIL=NULL, UPDATED_AT=?
                WHERE OUTBOX_ID=? AND STATUS='IN_PROGRESS'
                  AND CLAIMED_BY=? AND CLAIMED_UNTIL > ?""",
                attempts, nextAttemptAt, trunc(error), now, outboxId, claimOwner, now) == 1;
    }

    public boolean markDead(
            String outboxId,
            String claimOwner,
            int attempts,
            String error,
            long now) {
        return jdbc.update("""
                UPDATE ASYNC_TASK_WEBHOOK_OUTBOX
                SET STATUS='DEAD', ATTEMPTS=?, LAST_ERROR=?,
                    CLAIMED_BY=NULL, CLAIMED_UNTIL=NULL, UPDATED_AT=?
                WHERE OUTBOX_ID=? AND STATUS='IN_PROGRESS'
                  AND CLAIMED_BY=? AND CLAIMED_UNTIL > ?""",
                attempts, trunc(error), now, outboxId, claimOwner, now) == 1;
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
                rs.getInt("ATTEMPTS"),
                rs.getString("CLAIMED_BY"));
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
            return mapper.writeValueAsString(AsyncTaskWebhookPayloadFactory.payload(task));
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
                      int attempts,
                      String claimOwner) {
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
