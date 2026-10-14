package com.lrj.platform.asynctask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.asynctask.*;
import com.lrj.platform.security.AsyncTaskWorkerToken;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.lrj.platform.migrations.SchemaMigrationRunner;
import com.lrj.platform.migrations.SchemaName;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** 同一套断言用于 H2 和隔离 MySQL，检查提交窗口与 fencing。 */
class DagCheckpointTest {
    private DriverManagerDataSource dataSource;
    private DagCheckpointStore checkpoints;
    private JdbcAsyncTaskStore tasks;
    private ReadOnlyTaskDispatch dispatch;
    private JdbcTemplate jdbc;
    private AsyncTask task;
    private AsyncTaskWorkerToken.Principal worker;

    @BeforeEach void setup() {
        String url = System.getenv("DISPATCH_TEST_DB_URL");
        if (url != null && !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/lc4j_dispatch_it_[a-z0-9_]+(?:\\?.*)?"))
            throw new IllegalArgumentException("isolated database required");
        var ds = url == null ? AsyncTaskTestDatabase.migrated("checkpoint_" + UUID.randomUUID())
            : new DriverManagerDataSource(url, System.getenv("DISPATCH_TEST_DB_USER"), System.getenv("DISPATCH_TEST_DB_PASSWORD"));
        SchemaMigrationRunner.migrate(ds, SchemaName.ASYNC_TASK);
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
        jdbc.update("DELETE FROM ASYNC_TASK");
        var tx = new DataSourceTransactionManager(ds);
        tasks = new JdbcAsyncTaskStore(ds, new ObjectMapper(), Duration.ofHours(24), tx,
            new StaticListableBeanFactory().getBeanProvider(AsyncTaskLifecycleOutbox.class));
        dispatch = new ReadOnlyTaskDispatch(ds, tx, tasks,
            new InternalToken("test-dispatch-internal-secret-32-characters", Duration.ofMinutes(5)), 4, 1, 60);
        checkpoints = new DagCheckpointStore(ds, tx, new ObjectMapper(), new JdbcAsyncTaskEventJournal(ds, new ObjectMapper(), tx));
        var now = Instant.now();
        dispatch.create(new AsyncTask("test", "acme", "alice", "agent.readonly.dag.v2", AsyncTaskStatus.PENDING,
            Map.of("goal", "readonly"), null, null, null, now, now, null),
            new TenantContext.Tenant("acme", "alice", Set.of("agent")), "trace");
        assertThat(dispatch.claim("agentscope-platform.old")).isNull();
        task = dispatch.claim("agentscope-platform.one", 1).task();
        worker = principal("acme", "alice", task.leaseOwnerId());
    }
    private AsyncTaskWorkerToken.Principal principal(String tenant, String user, String owner) {
        return new AsyncTaskWorkerToken.Principal("agentscope-platform", tenant, user, owner);
    }
    private Map<String,Object> state(int calls) {
        return Map.of("schemaVersion", 1, "binding", "a".repeat(64), "deadline", 9999999999L,
            "modelCalls", calls, "reservedTokens", calls * 100, "operations", Map.of());
    }
    private DagCheckpoint access(AsyncTaskWorkerToken.Principal who, long epoch, Long revision, Map<String,Object> state) {
        return checkpoints.access("test", who, new DagCheckpointRequest(epoch, revision, state));
    }
    @Test void writesAndEventAreAtomicAndRevisionIsFenced() {
        assertThat(access(worker, task.leaseEpoch(), null, null).revision()).isZero();
        assertThat(access(worker, task.leaseEpoch(), 0L, state(1)).revision()).isEqualTo(1);
        assertThat(access(worker, task.leaseEpoch(), null, null).state()).containsEntry("modelCalls", 1);
        assertThatThrownBy(() -> access(worker, task.leaseEpoch(), 0L, state(2))).hasMessageContaining("409");
        assertThatThrownBy(() -> access(worker, task.leaseEpoch(), 1L, state(0))).hasMessageContaining("409");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ASYNC_TASK_EVENT WHERE TASK_ID='test'", Integer.class)).isEqualTo(1);
    }
    @Test void takeoverKeepsResultButRejectsOldEpochAndCancel() {
        access(worker, task.leaseEpoch(), 0L, state(1));
        jdbc.update("UPDATE ASYNC_TASK SET LEASE_EXPIRES_AT=1 WHERE TASK_ID='test'");
        assertThatThrownBy(() -> access(worker, task.leaseEpoch(), null, null)).hasMessageContaining("409");
        var next = dispatch.claim("agentscope-platform.two", 1).task();
        var nextWorker = principal("acme", "alice", next.leaseOwnerId());
        assertThat(access(nextWorker, next.leaseEpoch(), null, null).revision()).isEqualTo(1);
        assertThatThrownBy(() -> access(worker, task.leaseEpoch(), 1L, state(2))).hasMessageContaining("409");
        jdbc.update("UPDATE ASYNC_TASK SET STATUS='CANCELLED' WHERE TASK_ID='test'");
        assertThatThrownBy(() -> access(nextWorker, next.leaseEpoch(), 1L, state(2))).hasMessageContaining("409");
    }
    @Test void tenantAndActorCannotReadOrWriteCheckpoint() {
        assertThatThrownBy(() -> access(principal("globex", "alice", task.leaseOwnerId()), task.leaseEpoch(), null, null)).hasMessageContaining("404");
        assertThatThrownBy(() -> access(principal("acme", "bob", task.leaseOwnerId()), task.leaseEpoch(), 0L, state(1))).hasMessageContaining("404");
        assertThatThrownBy(() -> access(principal("acme", "alice", "agentscope-platform.other"), task.leaseEpoch(), null, null)).hasMessageContaining("409");
    }
    @Test void eventFailureRollsBackSnapshot() {
        var journal = org.mockito.Mockito.mock(AsyncTaskEventJournal.class);
        org.mockito.Mockito.when(journal.append(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("event write failed"));
        var broken = new DagCheckpointStore(dataSource, new DataSourceTransactionManager(dataSource), new ObjectMapper(), journal);
        assertThatThrownBy(() -> broken.access("test", worker, new DagCheckpointRequest(task.leaseEpoch(), 0L, state(1))))
            .hasMessageContaining("event write failed");
        assertThat(access(worker, task.leaseEpoch(), null, null).revision()).isZero();
    }
    @Test void concurrentCompareAndSetHasExactlyOneWinner() throws Exception {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i=0; i<2; i++) futures.add(pool.submit(() -> {
                try { access(worker, task.leaseEpoch(), 0L, state(1)); return true; }
                catch (org.springframework.web.server.ResponseStatusException e) {
                    assertThat(e.getStatusCode().value()).isEqualTo(409); return false;
                }
            }));
            int winners=0;
            for (var f : futures) if (f.get()) winners++;
            assertThat(winners).isEqualTo(1);
        }
    }
    @Test void tombstoneMakesCheckpointInaccessibleAndPreservesAuditData() {
        access(worker, task.leaseEpoch(), 0L, state(1));
        jdbc.update("UPDATE ASYNC_TASK SET STATUS='SUCCEEDED', FINISHED_AT=1 WHERE TASK_ID='test'");
        tasks.cleanup();
        assertThatThrownBy(() -> access(worker, task.leaseEpoch(), null, null)).hasMessageContaining("404");
        assertThat(jdbc.queryForObject("SELECT DELETED FROM ASYNC_TASK_DAG_CHECKPOINT WHERE TASK_ID='test'", Integer.class)).isEqualTo(1);
    }
    @Test void invalidVersionAndOversizedSnapshotAreRejected() {
        var wrong = new java.util.HashMap<String,Object>(state(0));
        wrong.put("schemaVersion", 2);
        assertThatThrownBy(() -> access(worker, task.leaseEpoch(), 0L, wrong)).hasMessageContaining("400");
        wrong.put("schemaVersion", 1);
        wrong.put("operations", Map.of("node", "x".repeat(2_097_152)));
        assertThatThrownBy(() -> access(worker, task.leaseEpoch(), 0L, wrong)).hasMessageContaining("413");
        assertThat(access(worker, task.leaseEpoch(), null, null).revision()).isZero();
    }
}
