package com.lrj.platform.asynctask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncTaskEventJournalTest {

    @Test
    void inMemoryJournalIsTaskScopedOrderedAndIdempotent() {
        InMemoryAsyncTaskEventJournal journal = new InMemoryAsyncTaskEventJournal();
        Instant now = Instant.now();

        AsyncTaskStreamEvent first = journal.append(
                "task-1", "event-1", "RUNNING", Map.of("status", "RUNNING"), null, now);
        AsyncTaskStreamEvent duplicate = journal.append(
                "task-1", "event-1", "ignored", Map.of(), null, now.plusSeconds(1));
        AsyncTaskStreamEvent second = journal.append(
                "task-1", "event-2", "dag-planned", Map.of("goal", "test"), "worker-1",
                now.plusSeconds(2));

        assertThat(duplicate).isEqualTo(first);
        assertThat(second.sequence()).isEqualTo(2);
        assertThat(journal.eventsAfter("task-1", 1)).containsExactly(second);
        assertThat(journal.latest("task-1")).contains(second);
    }

    @Test
    void jdbcJournalPersistsReplayAcrossInstances() {
        DataSource dataSource = AsyncTaskTestDatabase.migrated("async_task_events");
        ObjectMapper mapper = new ObjectMapper();
        JdbcAsyncTaskStore store = new JdbcAsyncTaskStore(
                dataSource,
                mapper,
                Duration.ofHours(1),
                new DataSourceTransactionManager(dataSource),
                JdbcAsyncTaskStoreTest.provider(null));
        store.put(task("task-1"));
        DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
        JdbcAsyncTaskEventJournal first = new JdbcAsyncTaskEventJournal(dataSource, mapper, tx);
        AsyncTaskStreamEvent appended = first.append(
                "task-1", "event-1", "PENDING", Map.of("status", "PENDING"), null, Instant.now());

        JdbcAsyncTaskEventJournal restarted = new JdbcAsyncTaskEventJournal(dataSource, mapper, tx);

        assertThat(restarted.eventsAfter("task-1", 0)).containsExactly(appended);
        assertThat(restarted.append(
                "task-1", "event-1", "ignored", Map.of(), null, Instant.now()))
                .isEqualTo(appended);
    }

    @Test
    void cleanupHidesEventsButPreservesIdempotencyAndSequence() {
        var source = AsyncTaskTestDatabase.migrated("event_tombstones");
        var mapper = new ObjectMapper();
        var tx = new DataSourceTransactionManager(source);
        var tasks = new JdbcAsyncTaskStore(source, mapper, Duration.ofHours(1), tx, JdbcAsyncTaskStoreTest.provider(null));
        tasks.put(task("retained"));
        var journal = new JdbcAsyncTaskEventJournal(source, mapper, tx);
        var now = Instant.now();
        var first = journal.append("retained", "old", "RUNNING", Map.of(), null, now);
        assertThat(journal.cleanupBefore(now.plusSeconds(1))).isEqualTo(1);
        assertThat(journal.cleanupBefore(now.plusSeconds(1))).isZero();
        assertThat(journal.eventsAfter("retained", 0)).isEmpty();
        assertThat(journal.latest("retained")).isEmpty();
        assertThat(journal.append("retained", "old", "RUNNING", Map.of(), null, now)).isEqualTo(first);
        assertThat(journal.append("retained", "new", "RUNNING", Map.of(), null, now.plusSeconds(2)).sequence()).isEqualTo(2);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT DELETED FROM ASYNC_TASK_EVENT WHERE TASK_ID='retained' AND SEQUENCE=1", Integer.class)).isEqualTo(1);
    }

    private static AsyncTask task(String taskId) {
        Instant now = Instant.now();
        return new AsyncTask(
                taskId,
                "acme",
                "alice",
                "agent.run",
                AsyncTaskStatus.PENDING,
                Map.of("goal", "test"),
                null,
                null,
                null,
                now,
                now,
                null);
    }
}
