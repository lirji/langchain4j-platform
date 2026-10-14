package com.lrj.platform.asynctask;

import com.lrj.platform.asynctask.mapper.AsyncTaskWebhookOutboxMapper;
import com.lrj.platform.asynctask.mapper.AsyncTaskWebhookOutboxRow;
import com.lrj.platform.persistence.PlusMapperFactory;
import com.lrj.platform.persistence.Rows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JDBC 模式（{@code app.async-task.store=jdbc}）下的 webhook 事务性 outbox，直连裸 {@code MyBatis-Plus Mapper} 管理
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

    private final AsyncTaskWebhookOutboxMapper db;
    private final ObjectMapper mapper;

    public AsyncTaskWebhookOutbox(DataSource asyncTaskDataSource, ObjectMapper mapper) {
        this.db = PlusMapperFactory.create(asyncTaskDataSource, AsyncTaskWebhookOutboxMapper.class);
        this.mapper = mapper;
        init();
    }

    private void init() {
        db.readInit1();
        log.info("ASYNC_TASK_WEBHOOK_OUTBOX schema verified");
    }

    public void enqueue(AsyncTask task, String targetUrl, long now) {
        db.writeEnqueue1(task.taskId(), task.taskId(), task.tenantId(), targetUrl, task.status().name(), payload(task), now, now, now);
    }

    public List<Row> claimDue(long now, int limit) {
        return claimDue(now, limit, UUID.randomUUID().toString(), DEFAULT_CLAIM_TTL_MS);
    }

    List<Row> claimDue(long now, int limit, String ownerId, long claimTtlMs) {
        int boundedLimit = Math.max(1, limit);
        long claimedUntil = now + Math.max(1_000L, claimTtlMs);
        List<String> candidateIds = db.readClaimDue1(now, now, boundedLimit);
        if (candidateIds.isEmpty()) {
            return List.of();
        }

        db.claim(candidateIds, ownerId, claimedUntil, now);
        return Rows.map(db.claimed(candidateIds, ownerId, boundedLimit), this::mapRow);
    }

    public boolean markDelivered(String outboxId, String claimOwner, long now) {
        return db.writeMarkDelivered1(now, outboxId, claimOwner, now) == 1;
    }

    public int purgeDeliveredBefore(long cutoffUpdatedAt) {
        int deleted = db.writePurgeDeliveredBefore1(cutoffUpdatedAt);
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
        return db.writeMarkRetry1(attempts, nextAttemptAt, trunc(error), now, outboxId, claimOwner, now) == 1;
    }

    public boolean markDead(
            String outboxId,
            String claimOwner,
            int attempts,
            String error,
            long now) {
        return db.writeMarkDead1(attempts, trunc(error), now, outboxId, claimOwner, now) == 1;
    }

    public List<InspectionRow> listDead(String tenantId, int limit) {
        return Rows.map(db.readListDead1(tenantId, Math.max(1, limit)), this::mapInspectionRow);
    }

    static Decision schedule(int attemptsAfter, int maxAttempts, long now, long baseBackoffMs) {
        if (attemptsAfter >= maxAttempts) {
            return new Decision(true, 0L);
        }
        long delay = (long) (baseBackoffMs * Math.pow(3, Math.max(0, attemptsAfter - 1)));
        return new Decision(false, now + delay);
    }

    private Row mapRow(AsyncTaskWebhookOutboxRow rs, int rowNum) {
        return new Row(
                rs.outboxId,
                rs.taskId,
                rs.tenantId,
                rs.targetUrl,
                rs.taskStatus,
                rs.payloadJson,
                Rows.intValue(rs.attempts),
                rs.claimedBy);
    }

    private InspectionRow mapInspectionRow(AsyncTaskWebhookOutboxRow rs, int rowNum) {
        return new InspectionRow(
                rs.outboxId,
                rs.taskId,
                rs.tenantId,
                rs.targetUrl,
                rs.taskStatus,
                rs.status,
                Rows.intValue(rs.attempts),
                rs.lastError,
                Instant.ofEpochMilli(Rows.longValue(rs.createdAt)),
                Instant.ofEpochMilli(Rows.longValue(rs.updatedAt)));
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
