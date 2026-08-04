package com.lrj.platform.asynctask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 异步任务生命周期事件的<b>事务性 outbox</b>（A1，收口 async-task 版两段式缺口）。
 * 建在 async-task 同一个 {@code asyncTaskDataSource} 上的 {@code ASYNC_TASK_LIFECYCLE_OUTBOX} 表。
 *
 * <p><b>为什么</b>：async-task 终态通知原先是 {@code store.update} 提交<b>之后</b>的 {@code @EventListener}
 * 发布（kafka 档下 HTTP outbox 让位、无 DB 兜底），与 workflow B1b 修复前同类的两段式缺口——状态已提交、
 * 发布未跑时崩溃即丢。改为：在 {@link JdbcAsyncTaskStore#update} 的<b>同一 JDBC 事务</b>内写一条 outbox 行
 * （同 {@code asyncTaskDataSource}，经 {@code DataSourceUtils} 并入同连接），使「终态提交 ⇔ 事件行已写」原子成立；
 * 投递由 {@link AsyncTaskLifecycleRelay} relay 到 Kafka（至少一次 + 消费侧 eventId 去重 = effective exactly-once）。
 *
 * <p>PAYLOAD_JSON 存已序列化的 {@code AsyncTaskLifecycleMessage} 快照（终态时自足），relay 反序列化后原样发布。
 */
public class AsyncTaskLifecycleOutbox {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskLifecycleOutbox.class);

    private final JdbcTemplate jdbc;

    public AsyncTaskLifecycleOutbox(DataSource asyncTaskDataSource) {
        this.jdbc = new JdbcTemplate(asyncTaskDataSource);
        init();
    }

    void init() {
        jdbc.queryForList("""
                SELECT EVENT_ID, TENANT_ID, PAYLOAD_JSON, STATUS, ATTEMPTS, NEXT_ATTEMPT_AT,
                       LAST_ERROR, CLAIMED_BY, CLAIMED_UNTIL, CREATED_AT, UPDATED_AT
                FROM ASYNC_TASK_LIFECYCLE_OUTBOX WHERE 1=0""");
        log.info("ASYNC_TASK_LIFECYCLE_OUTBOX schema verified");
    }

    /** 入队（在 JdbcAsyncTaskStore.update 的事务内调用 → 与终态更新原子提交）。EVENT_ID 冲突即幂等忽略。 */
    public void enqueue(String eventId, String tenantId, String payloadJson, long now) {
        jdbc.update("""
                INSERT INTO ASYNC_TASK_LIFECYCLE_OUTBOX
                  (EVENT_ID, TENANT_ID, PAYLOAD_JSON, STATUS, ATTEMPTS, NEXT_ATTEMPT_AT, LAST_ERROR, CREATED_AT, UPDATED_AT)
                VALUES (?, ?, ?, 'PENDING', 0, ?, NULL, ?, ?)
                ON DUPLICATE KEY UPDATE EVENT_ID=EVENT_ID""",
                eventId, tenantId, payloadJson, now, now, now);
    }

    public List<Row> claimDue(long now, int limit) {
        return claimDue(now, limit, UUID.randomUUID().toString(), 120_000L);
    }

    List<Row> claimDue(long now, int limit, String ownerId, long claimTtlMs) {
        int boundedLimit = Math.max(1, limit);
        long claimedUntil = now + Math.max(1_000L, claimTtlMs);
        List<String> candidateIds = jdbc.queryForList("""
                SELECT EVENT_ID FROM ASYNC_TASK_LIFECYCLE_OUTBOX
                WHERE (STATUS='PENDING' AND NEXT_ATTEMPT_AT <= ?)
                   OR (STATUS='IN_PROGRESS' AND CLAIMED_UNTIL <= ?)
                ORDER BY NEXT_ATTEMPT_AT ASC LIMIT ?""",
                String.class, now, now, boundedLimit);
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        String placeholders = placeholders(candidateIds.size());
        List<Object> updateArgs = new ArrayList<>(List.of(ownerId, claimedUntil, now));
        updateArgs.addAll(candidateIds);
        updateArgs.add(now);
        updateArgs.add(now);
        jdbc.update("""
                UPDATE ASYNC_TASK_LIFECYCLE_OUTBOX
                SET STATUS='IN_PROGRESS', CLAIMED_BY=?, CLAIMED_UNTIL=?, UPDATED_AT=?
                WHERE EVENT_ID IN (%s)
                  AND ((STATUS='PENDING' AND NEXT_ATTEMPT_AT <= ?)
                    OR (STATUS='IN_PROGRESS' AND CLAIMED_UNTIL <= ?))""".formatted(placeholders),
                updateArgs.toArray());
        List<Object> selectArgs = new ArrayList<>();
        selectArgs.add(ownerId);
        selectArgs.addAll(candidateIds);
        selectArgs.add(boundedLimit);
        return jdbc.query("""
                SELECT EVENT_ID, TENANT_ID, PAYLOAD_JSON, ATTEMPTS, CLAIMED_BY
                FROM ASYNC_TASK_LIFECYCLE_OUTBOX
                WHERE STATUS='IN_PROGRESS' AND CLAIMED_BY=? AND EVENT_ID IN (%s)
                ORDER BY NEXT_ATTEMPT_AT ASC LIMIT ?""".formatted(placeholders),
                (rs, n) -> new Row(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getInt(4),
                        rs.getString(5)),
                selectArgs.toArray());
    }

    public boolean markDelivered(String eventId, String claimOwner, long now) {
        return jdbc.update("""
                UPDATE ASYNC_TASK_LIFECYCLE_OUTBOX
                SET STATUS='DELIVERED', CLAIMED_BY=NULL, CLAIMED_UNTIL=NULL, UPDATED_AT=?
                WHERE EVENT_ID=? AND STATUS='IN_PROGRESS'
                  AND CLAIMED_BY=? AND CLAIMED_UNTIL > ?""",
                now, eventId, claimOwner, now) == 1;
    }

    public boolean markRetry(
            String eventId,
            String claimOwner,
            int attempts,
            long nextAttemptAt,
            String lastError,
            long now) {
        return jdbc.update("""
                UPDATE ASYNC_TASK_LIFECYCLE_OUTBOX
                SET STATUS='PENDING', ATTEMPTS=?, NEXT_ATTEMPT_AT=?, LAST_ERROR=?,
                    CLAIMED_BY=NULL, CLAIMED_UNTIL=NULL, UPDATED_AT=?
                WHERE EVENT_ID=? AND STATUS='IN_PROGRESS'
                  AND CLAIMED_BY=? AND CLAIMED_UNTIL > ?""",
                attempts, nextAttemptAt, trunc(lastError), now, eventId, claimOwner, now) == 1;
    }

    public boolean markDead(
            String eventId,
            String claimOwner,
            int attempts,
            String lastError,
            long now) {
        return jdbc.update("""
                UPDATE ASYNC_TASK_LIFECYCLE_OUTBOX
                SET STATUS='DEAD', ATTEMPTS=?, LAST_ERROR=?,
                    CLAIMED_BY=NULL, CLAIMED_UNTIL=NULL, UPDATED_AT=?
                WHERE EVENT_ID=? AND STATUS='IN_PROGRESS'
                  AND CLAIMED_BY=? AND CLAIMED_UNTIL > ?""",
                attempts, trunc(lastError), now, eventId, claimOwner, now) == 1;
    }

    /** 指数退避 + DLQ 阈值（纯函数，便于单测）：第 n 次失败后 next = now + base*2^(n-1)；达 maxAttempts 进 DEAD。 */
    public static Decision schedule(int attemptsAfter, int maxAttempts, long now, long baseBackoffMs) {
        if (attemptsAfter >= maxAttempts) {
            return new Decision(true, now);
        }
        long backoff = baseBackoffMs * (1L << Math.min(attemptsAfter - 1, 20));
        return new Decision(false, now + backoff);
    }

    private static String trunc(String s) {
        return s == null ? null : (s.length() <= 512 ? s : s.substring(0, 512));
    }

    private static String placeholders(int count) {
        return Collections.nCopies(count, "?").stream().collect(Collectors.joining(","));
    }

    public record Row(
            String eventId,
            String tenantId,
            String payloadJson,
            int attempts,
            String claimOwner) {}

    public record Decision(boolean dead, long nextAttemptAt) {}
}
