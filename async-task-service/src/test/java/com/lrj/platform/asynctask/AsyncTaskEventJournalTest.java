package com.lrj.platform.asynctask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:async_task_events;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
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
