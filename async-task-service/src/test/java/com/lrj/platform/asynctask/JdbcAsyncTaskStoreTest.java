package com.lrj.platform.asynctask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAsyncTaskStoreTest {

    @Test
    void leaseBlocksAnotherWorkerWhileLeaseIsActive() {
        JdbcAsyncTaskStore store = store("async_task_lease_active");
        store.put(task("task-1", AsyncTaskStatus.PENDING, null, null));

        AsyncTask first = store.lease("task-1", "worker-1", Instant.now().plusSeconds(30)).orElseThrow();
        AsyncTask second = store.lease("task-1", "worker-2", Instant.now().plusSeconds(30)).orElseThrow();

        assertThat(first.leaseOwnerId()).isEqualTo("worker-1");
        assertThat(second.leaseOwnerId()).isEqualTo("worker-1");
    }

    @Test
    void leaseCanReclaimExpiredLease() {
        JdbcAsyncTaskStore store = store("async_task_lease_expired");
        store.put(task("task-1", AsyncTaskStatus.RUNNING, "worker-1", Instant.now().minusSeconds(1)));

        AsyncTask reclaimed = store.lease("task-1", "worker-2", Instant.now().plusSeconds(30)).orElseThrow();

        assertThat(reclaimed.status()).isEqualTo(AsyncTaskStatus.RUNNING);
        assertThat(reclaimed.leaseOwnerId()).isEqualTo("worker-2");
        assertThat(reclaimed.leaseExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void leaseDoesNotModifyTerminalTask() {
        JdbcAsyncTaskStore store = store("async_task_lease_terminal");
        store.put(task("task-1", AsyncTaskStatus.CANCELLED, null, null));

        AsyncTask task = store.lease("task-1", "worker-1", Instant.now().plusSeconds(30)).orElseThrow();

        assertThat(task.status()).isEqualTo(AsyncTaskStatus.CANCELLED);
        assertThat(task.leaseOwnerId()).isNull();
    }

    private static JdbcAsyncTaskStore store(String dbName) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        return new JdbcAsyncTaskStore(dataSource, new ObjectMapper(), Duration.ofHours(1));
    }

    private static AsyncTask task(String taskId,
                                  AsyncTaskStatus status,
                                  String leaseOwnerId,
                                  Instant leaseExpiresAt) {
        Instant now = Instant.now();
        return new AsyncTask(
                taskId,
                "acme",
                "alice",
                "agent.run",
                status,
                Map.of("goal", "test"),
                null,
                status == AsyncTaskStatus.CANCELLED ? "cancelled by user" : null,
                null,
                now,
                now,
                status.isTerminal() ? now : null,
                leaseOwnerId,
                leaseExpiresAt);
    }
}
