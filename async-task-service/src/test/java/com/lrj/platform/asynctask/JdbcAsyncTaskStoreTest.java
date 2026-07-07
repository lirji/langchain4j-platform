package com.lrj.platform.asynctask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAsyncTaskStoreTest {

    @Test
    void transitionToTerminal_atomicallyWritesLifecycleOutboxRow() {
        DataSource ds = h2("async_task_lifecycle_atomic");
        AsyncTaskLifecycleOutbox outbox = new AsyncTaskLifecycleOutbox(ds); // 与 store 同一数据源 → 同事务
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()); // 消息含 Instant
        JdbcAsyncTaskStore store = new JdbcAsyncTaskStore(ds, mapper, Duration.ofHours(1),
                new DataSourceTransactionManager(ds), provider(outbox));
        store.put(task("task-x", AsyncTaskStatus.RUNNING, null, null));
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 非终态更新：不写 outbox
        store.update("task-x", t -> AsyncTaskStore.withStatus(t, AsyncTaskStatus.RUNNING, null, null));
        assertThat(count(jdbc)).isZero();

        // 转终态：同事务内原子写一条 outbox
        store.update("task-x", t -> AsyncTaskStore.withStatus(t, AsyncTaskStatus.SUCCEEDED, Map.of("a", 1), null));
        assertThat(count(jdbc)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT EVENT_ID FROM ASYNC_TASK_LIFECYCLE_OUTBOX", String.class))
                .isEqualTo("asynctask:task-x:SUCCEEDED");

        // 已终态的 no-op 重写：不重复入队（EVENT_ID 幂等 + 终态转变判定）
        store.update("task-x", t -> t);
        assertThat(count(jdbc)).isEqualTo(1);
    }

    private static int count(JdbcTemplate jdbc) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM ASYNC_TASK_LIFECYCLE_OUTBOX", Integer.class);
        return n == null ? 0 : n;
    }

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
        DataSource dataSource = h2(dbName);
        return new JdbcAsyncTaskStore(dataSource, new ObjectMapper(), Duration.ofHours(1),
                new DataSourceTransactionManager(dataSource), provider(null));
    }

    private static DataSource h2(String dbName) {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + dbName + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    /** 极简 ObjectProvider：只需 getIfAvailable()（JdbcAsyncTaskStore 唯一用到），其余委托它。 */
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
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
