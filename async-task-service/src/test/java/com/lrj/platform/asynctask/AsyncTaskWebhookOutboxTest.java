package com.lrj.platform.asynctask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AsyncTaskWebhookOutboxTest：基于内存 H2（MySQL 兼容模式）验证 {@link AsyncTaskWebhookOutbox} 的
 * 指数退避调度与到达上限死信判定、仅清理过期的 delivered 行、claimDue 抢占并阻止重复认领、过期 claim
 * 可被其他 worker 重认领，以及 markRetry 释放 claim 供后续到期再派发。
 */
class AsyncTaskWebhookOutboxTest {

    @Test
    void scheduleUsesExponentialBackoff() {
        AsyncTaskWebhookOutbox.Decision first = AsyncTaskWebhookOutbox.schedule(1, 5, 1000L, 250L);
        AsyncTaskWebhookOutbox.Decision third = AsyncTaskWebhookOutbox.schedule(3, 5, 1000L, 250L);

        assertThat(first.dead()).isFalse();
        assertThat(first.nextAttemptAt()).isEqualTo(1250L);
        assertThat(third.dead()).isFalse();
        assertThat(third.nextAttemptAt()).isEqualTo(3250L);
    }

    @Test
    void scheduleMarksDeadAtMaxAttempts() {
        assertThat(AsyncTaskWebhookOutbox.schedule(3, 3, 1000L, 250L).dead()).isTrue();
    }

    @Test
    void purgeDeliveredBeforeRemovesOnlyExpiredDeliveredRows() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:async_outbox_retention;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        AsyncTaskWebhookOutbox outbox = new AsyncTaskWebhookOutbox(dataSource, new ObjectMapper());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long now = 10_000L;

        outbox.enqueue(task("old-delivered"), "http://callback.local/old", now);
        outbox.enqueue(task("new-delivered"), "http://callback.local/new", now);
        outbox.enqueue(task("pending"), "http://callback.local/pending", now);
        outbox.enqueue(task("dead"), "http://callback.local/dead", now);
        outbox.markDelivered("old-delivered", 1_000L);
        outbox.markDelivered("new-delivered", 9_000L);
        outbox.markDead("dead", 3, "failed", 1_000L);

        int deleted = outbox.purgeDeliveredBefore(5_000L);

        assertThat(deleted).isEqualTo(1);
        assertThat(ids(jdbc)).containsExactlyInAnyOrder("new-delivered", "pending", "dead");
    }

    @Test
    void claimDueMarksRowsInProgressAndPreventsDuplicateClaims() {
        AsyncTaskWebhookOutbox outbox = outbox("async_outbox_claim");
        long now = 10_000L;
        outbox.enqueue(task("task-1"), "http://callback.local/1", now);
        outbox.enqueue(task("task-2"), "http://callback.local/2", now);

        var firstClaim = outbox.claimDue(now, 10, "worker-1", 30_000L);
        var duplicateClaim = outbox.claimDue(now, 10, "worker-2", 30_000L);

        assertThat(firstClaim).extracting(AsyncTaskWebhookOutbox.Row::outboxId)
                .containsExactlyInAnyOrder("task-1", "task-2");
        assertThat(duplicateClaim).isEmpty();
    }

    @Test
    void expiredClaimCanBeReclaimedByAnotherWorker() {
        AsyncTaskWebhookOutbox outbox = outbox("async_outbox_reclaim");
        long now = 10_000L;
        outbox.enqueue(task("task-1"), "http://callback.local/1", now);
        outbox.claimDue(now, 10, "worker-1", 1_000L);

        var tooEarly = outbox.claimDue(now + 500L, 10, "worker-2", 1_000L);
        var reclaimed = outbox.claimDue(now + 1_500L, 10, "worker-2", 1_000L);

        assertThat(tooEarly).isEmpty();
        assertThat(reclaimed).extracting(AsyncTaskWebhookOutbox.Row::outboxId).containsExactly("task-1");
    }

    @Test
    void markRetryReleasesClaimForFutureDispatch() {
        AsyncTaskWebhookOutbox outbox = outbox("async_outbox_retry_claim");
        long now = 10_000L;
        outbox.enqueue(task("task-1"), "http://callback.local/1", now);
        AsyncTaskWebhookOutbox.Row row = outbox.claimDue(now, 10, "worker-1", 30_000L).getFirst();

        outbox.markRetry(row.outboxId(), 1, now + 500L, "SERVER_ERROR", now + 100L);
        var beforeDue = outbox.claimDue(now + 400L, 10, "worker-2", 30_000L);
        var afterDue = outbox.claimDue(now + 500L, 10, "worker-2", 30_000L);

        assertThat(beforeDue).isEmpty();
        assertThat(afterDue).extracting(AsyncTaskWebhookOutbox.Row::outboxId).containsExactly("task-1");
    }

    private static AsyncTask task(String taskId) {
        Instant now = Instant.ofEpochMilli(1_000L);
        return new AsyncTask(
                taskId,
                "acme",
                "alice",
                "agent.run",
                AsyncTaskStatus.SUCCEEDED,
                Map.of("goal", "test"),
                Map.of("answer", "done"),
                null,
                "http://callback.local/tasks",
                now,
                now,
                now);
    }

    private static AsyncTaskWebhookOutbox outbox(String dbName) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        return new AsyncTaskWebhookOutbox(dataSource, new ObjectMapper());
    }

    private static java.util.List<String> ids(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT OUTBOX_ID FROM ASYNC_TASK_WEBHOOK_OUTBOX ORDER BY OUTBOX_ID", String.class);
    }
}
