package com.lrj.platform.asynctask;

import com.lrj.platform.asynctask.mapper.AsyncTaskLifecycleOutboxMapper;
import com.lrj.platform.persistence.PlusMapperFactory;
import com.lrj.platform.persistence.Rows;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

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

    private final AsyncTaskLifecycleOutboxMapper db;

    public AsyncTaskLifecycleOutbox(DataSource asyncTaskDataSource) {
        this.db = PlusMapperFactory.create(asyncTaskDataSource, AsyncTaskLifecycleOutboxMapper.class);
        init();
    }

    void init() {
        db.readContract1();
        log.info("ASYNC_TASK_LIFECYCLE_OUTBOX schema verified");
    }

    /** 入队（在 JdbcAsyncTaskStore.update 的事务内调用 → 与终态更新原子提交）。EVENT_ID 冲突即幂等忽略。 */
    public void enqueue(String eventId, String tenantId, String payloadJson, long now) {
        db.writeEnqueue1(eventId, tenantId, payloadJson, now, now, now);
    }

    public List<Row> claimDue(long now, int limit) {
        return claimDue(now, limit, UUID.randomUUID().toString(), 120_000L);
    }

    List<Row> claimDue(long now, int limit, String ownerId, long claimTtlMs) {
        int boundedLimit = Math.max(1, limit);
        long claimedUntil = now + Math.max(1_000L, claimTtlMs);
        List<String> candidateIds = db.readClaimDue1(now, now, boundedLimit);
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        db.claim(candidateIds, ownerId, claimedUntil, now);
        return Rows.map(db.claimed(candidateIds, ownerId, boundedLimit), (rs, n) -> new Row(rs.eventId, rs.tenantId, rs.payloadJson, Rows.intValue(rs.attempts), rs.claimedBy));
    }

    public boolean markDelivered(String eventId, String claimOwner, long now) {
        return db.writeMarkDelivered1(now, eventId, claimOwner, now) == 1;
    }

    public boolean markRetry(
            String eventId,
            String claimOwner,
            int attempts,
            long nextAttemptAt,
            String lastError,
            long now) {
        return db.writeMarkRetry1(attempts, nextAttemptAt, trunc(lastError), now, eventId, claimOwner, now) == 1;
    }

    public boolean markDead(
            String eventId,
            String claimOwner,
            int attempts,
            String lastError,
            long now) {
        return db.writeMarkDead1(attempts, trunc(lastError), now, eventId, claimOwner, now) == 1;
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

    public record Row(
            String eventId,
            String tenantId,
            String payloadJson,
            int attempts,
            String claimOwner) {}

    public record Decision(boolean dead, long nextAttemptAt) {}
}
