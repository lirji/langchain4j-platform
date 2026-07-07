package com.lrj.platform.asynctask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

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
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ASYNC_TASK_LIFECYCLE_OUTBOX (
                  EVENT_ID VARCHAR(160) NOT NULL PRIMARY KEY,
                  TENANT_ID VARCHAR(64),
                  PAYLOAD_JSON TEXT NOT NULL,
                  STATUS VARCHAR(16) NOT NULL,
                  ATTEMPTS INT NOT NULL DEFAULT 0,
                  NEXT_ATTEMPT_AT BIGINT NOT NULL,
                  LAST_ERROR VARCHAR(512),
                  CREATED_AT BIGINT NOT NULL,
                  UPDATED_AT BIGINT NOT NULL,
                  INDEX IDX_ASYNC_LIFECYCLE_DUE (STATUS, NEXT_ATTEMPT_AT)
                )""");
        log.info("ASYNC_TASK_LIFECYCLE_OUTBOX 表就绪（生命周期事件事务性 outbox，A1）");
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
        return jdbc.query("""
                SELECT EVENT_ID, TENANT_ID, PAYLOAD_JSON, ATTEMPTS FROM ASYNC_TASK_LIFECYCLE_OUTBOX
                WHERE STATUS='PENDING' AND NEXT_ATTEMPT_AT <= ?
                ORDER BY NEXT_ATTEMPT_AT ASC LIMIT ?""",
                (rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
                now, limit);
    }

    public void markDelivered(String eventId, long now) {
        jdbc.update("UPDATE ASYNC_TASK_LIFECYCLE_OUTBOX SET STATUS='DELIVERED', UPDATED_AT=? WHERE EVENT_ID=?", now, eventId);
    }

    public void markRetry(String eventId, int attempts, long nextAttemptAt, String lastError, long now) {
        jdbc.update("UPDATE ASYNC_TASK_LIFECYCLE_OUTBOX SET ATTEMPTS=?, NEXT_ATTEMPT_AT=?, LAST_ERROR=?, UPDATED_AT=? WHERE EVENT_ID=?",
                attempts, nextAttemptAt, trunc(lastError), now, eventId);
    }

    public void markDead(String eventId, int attempts, String lastError, long now) {
        jdbc.update("UPDATE ASYNC_TASK_LIFECYCLE_OUTBOX SET STATUS='DEAD', ATTEMPTS=?, LAST_ERROR=?, UPDATED_AT=? WHERE EVENT_ID=?",
                attempts, trunc(lastError), now, eventId);
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

    public record Row(String eventId, String tenantId, String payloadJson, int attempts) {}

    public record Decision(boolean dead, long nextAttemptAt) {}
}
