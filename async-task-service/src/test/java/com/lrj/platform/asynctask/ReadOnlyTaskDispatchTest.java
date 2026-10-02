package com.lrj.platform.asynctask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.migrations.SchemaMigrationRunner;
import com.lrj.platform.migrations.SchemaName;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

/** 验证真实事务、跨副本配额和崩溃接管; MySQL只接受脚本生成的隔离库. */
class ReadOnlyTaskDispatchTest {
    private DriverManagerDataSource ds;
    private JdbcAsyncTaskStore store;
    private JdbcTemplate jdbc;
    private ReadOnlyTaskDispatch dispatch;
    private InternalToken tokens;

    @BeforeEach
    void setup() {
        String url = System.getenv("DISPATCH_TEST_DB_URL");
        if (url != null && !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/lc4j_dispatch_it_[a-z0-9_]+(?:\\?.*)?"))
            throw new IllegalArgumentException("dispatch test database must be isolated");
        ds = url == null ? new DriverManagerDataSource("jdbc:h2:mem:dispatch_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")
                : new DriverManagerDataSource(url, System.getenv("DISPATCH_TEST_DB_USER"), System.getenv("DISPATCH_TEST_DB_PASSWORD"));
        SchemaMigrationRunner.migrate(ds, SchemaName.ASYNC_TASK);
        jdbc = new JdbcTemplate(ds);
        jdbc.update("DELETE FROM ASYNC_TASK");
        jdbc.update("UPDATE ASYNC_TASK_DISPATCH_LOCK SET LAST_TENANT='' WHERE LOCK_ID=1");
        store = new JdbcAsyncTaskStore(ds, new ObjectMapper(), Duration.ofHours(24),
                new DataSourceTransactionManager(ds), new StaticListableBeanFactory().getBeanProvider(AsyncTaskLifecycleOutbox.class));
        tokens = new InternalToken("test-dispatch-internal-secret-32-characters", Duration.ofMinutes(5));
        dispatch = replica(4, 1);
    }

    private ReadOnlyTaskDispatch replica(int global, int tenant) {
        return new ReadOnlyTaskDispatch(ds, new DataSourceTransactionManager(ds), store, tokens, global, tenant, 60);
    }

    private void create(String id, String tenant) {
        Instant now = Instant.now();
        dispatch.create(new AsyncTask(id, tenant, "alice", "agent.readonly.run.v1", AsyncTaskStatus.PENDING,
                        Map.of("goal", "readonly"), null, null, null, now, now, null),
                new TenantContext.Tenant(tenant, "alice", Set.of("agent", "workflow.admin"), "dept-a"), "trace-1");
    }

    @Test
    void contextCommitIsAtomicAndOnlyNarrowCredentialsAreIssued() {
        assertThatThrownBy(() -> new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(s -> {
            create("rollback", "acme");
            throw new IllegalStateException("crash before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.get("rollback")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ASYNC_TASK_DISPATCH", Long.class)).isZero();
        create("t1", "acme");
        var result = dispatch.claim("agentscope-platform.one");
        assertThat(result.task().taskId()).isEqualTo("t1");
        assertThat(result.traceId()).isEqualTo("trace-1");
        var identity = tokens.verify(result.internalToken());
        assertThat(identity).isEqualTo(new TenantContext.Tenant("acme", "alice", Set.of("agent"), "dept-a"));
        assertThat(jdbc.queryForMap("SELECT * FROM ASYNC_TASK_DISPATCH WHERE TASK_ID='t1'").values())
                .doesNotContain(result.internalToken());
    }

    @Test
    void expiredLeaseCanBeRecoveredAndOldEpochCannotWrite() {
        create("t1", "acme");
        var first = dispatch.claim("agentscope-platform.one").task();
        jdbc.update("UPDATE ASYNC_TASK SET LEASE_EXPIRES_AT=? WHERE TASK_ID='t1'", Instant.now().minusSeconds(1).toEpochMilli());
        var recovered = replica(4, 1).claim("agentscope-platform.two").task();
        assertThat(recovered.leaseEpoch()).isEqualTo(first.leaseEpoch() + 1);
        assertThat(store.transition("t1", "acme", first.leaseOwnerId(), first.leaseEpoch(), AsyncTaskStatus.SUCCEEDED, "stale", null).changed()).isFalse();
        assertThat(store.withActiveLease("t1", "acme", first.leaseOwnerId(), first.leaseEpoch(), () -> "stale event").executed()).isFalse();
        assertThat(store.transition("t1", "acme", recovered.leaseOwnerId(), recovered.leaseEpoch(), AsyncTaskStatus.SUCCEEDED, "fresh", null).changed()).isTrue();
    }

    @Test
    void hotTenantDoesNotTakeOtherTenantsCapacity() {
        for (int i=0; i<5; i++) create("hot"+i, "a-hot");
        create("normal", "z-normal");
        assertThat(dispatch.claim("agentscope-platform.one").task().tenantId()).isEqualTo("a-hot");
        assertThat(dispatch.claim("agentscope-platform.two").task().taskId()).isEqualTo("normal");
        assertThat(dispatch.claim("agentscope-platform.three")).isNull();
    }

    @Test
    void concurrentReplicasNeverExceedGlobalAndTenantLimits() throws Exception {
        for (int i=0; i<20; i++) create("t"+i, "tenant"+(i%5));
        var other = replica(4, 1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i=0; i<20; i++) {
                final int n=i;
                futures.add(pool.submit(() -> (n%2==0 ? dispatch : other).claim("agentscope-platform.worker"+n) != null));
            }
            int claimed=0;
            for (var future : futures) if (future.get()) claimed++;
            assertThat(claimed).isEqualTo(4);
        }
        assertThat(jdbc.queryForList("SELECT TENANT_ID, COUNT(*) AS N FROM ASYNC_TASK WHERE LEASE_EXPIRES_AT IS NOT NULL GROUP BY TENANT_ID"))
                .allSatisfy(row -> assertThat(((Number)row.get("N")).intValue()).isOne());
    }

    @Test
    void repeatedCrashesAreBoundedAndLegacyReaperDoesNotFailRecoverableTasks() {
        create("t1", "acme");
        for (int i=0; i<3; i++) {
            assertThat(dispatch.claim("agentscope-platform.worker"+i)).isNotNull();
            jdbc.update("UPDATE ASYNC_TASK SET LEASE_EXPIRES_AT=?, STATUS='RUNNING' WHERE TASK_ID='t1'", 0L);
        }
        assertThat(store.failOrphans(ReadOnlyTaskDispatch.KINDS, Instant.now(), Instant.now(), 10, "orphan")).isEmpty();
        assertThat(dispatch.claim("agentscope-platform.last")).isNull();
        assertThat(store.get("t1").orElseThrow().error()).isEqualTo("ASYNC_TASK_RECOVERY_EXHAUSTED");
    }
}
