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

    private static java.util.List<String> ids(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT OUTBOX_ID FROM ASYNC_TASK_WEBHOOK_OUTBOX ORDER BY OUTBOX_ID", String.class);
    }
}
