package com.lrj.platform.asynctask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * AsyncTaskDrainInventoryTest：Java Agent 退役门禁「async-task-service 中已无只能被 Java worker 领取的
 * 存量任务」的机器检查。断言内存与 JDBC 两种 store 的盘点结果一致——只统计未完结任务、严格按租户隔离、
 * 按 kind + 状态 + 租约持有者分组，且未持有租约时 leaseOwnerId 为 null（表示任何合法 worker 都能领取）。
 */
class AsyncTaskDrainInventoryTest {

    private static final Set<String> AGENT_KINDS = AsyncTaskOrphanProperties.SUPPORTED_KINDS;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void inMemoryStoreCountsOnlyOpenTasksOfTheCallingTenant() {
        AsyncTaskStore store = new AsyncTaskStore(Duration.ofHours(1));
        store.put(task("t1", "acme", "agent.run", AsyncTaskStatus.RUNNING, "agent-service"));
        store.put(task("t2", "acme", "agent.run", AsyncTaskStatus.RUNNING, "agent-service"));
        store.put(task("t3", "acme", "agent.run", AsyncTaskStatus.PENDING, null));
        store.put(task("t4", "acme", "agent.dag", AsyncTaskStatus.RUNNING, "agentscope-orchestrator"));
        // 已完结、其它租户、非 Agent kind 都不该出现在存量里
        store.put(task("t5", "acme", "agent.run", AsyncTaskStatus.SUCCEEDED, "agent-service"));
        store.put(task("t6", "globex", "agent.run", AsyncTaskStatus.RUNNING, "agent-service"));
        store.put(task("t7", "acme", "billing.export", AsyncTaskStatus.RUNNING, "billing-worker"));

        assertThat(store.drainInventory("acme", AGENT_KINDS)).containsExactly(
                new AsyncTaskStore.DrainInventoryRow("agent.dag", AsyncTaskStatus.RUNNING,
                        "agentscope-orchestrator", 1),
                new AsyncTaskStore.DrainInventoryRow("agent.run", AsyncTaskStatus.PENDING, null, 1),
                new AsyncTaskStore.DrainInventoryRow("agent.run", AsyncTaskStatus.RUNNING,
                        "agent-service", 2));
    }

    @Test
    void inMemoryStoreReportsNothingOnceEveryAgentTaskIsDrained() {
        AsyncTaskStore store = new AsyncTaskStore(Duration.ofHours(1));
        store.put(task("t1", "acme", "agent.run", AsyncTaskStatus.SUCCEEDED, "agent-service"));
        store.put(task("t2", "acme", "agent.dag", AsyncTaskStatus.CANCELLED, null));

        assertThat(store.drainInventory("acme", AGENT_KINDS)).isEmpty();
    }

    @Test
    void jdbcStoreAggregatesTheSameInventoryFromSql() {
        DataSource dataSource = AsyncTaskTestDatabase.migrated("async_task_drain_inventory");
        JdbcAsyncTaskStore store = new JdbcAsyncTaskStore(dataSource, new ObjectMapper(),
                Duration.ofHours(1), new DataSourceTransactionManager(dataSource),
                JdbcAsyncTaskStoreTest.provider(null));
        store.put(task("t1", "acme", "agent.run", AsyncTaskStatus.RUNNING, "agent-service"));
        store.put(task("t2", "acme", "agent.run", AsyncTaskStatus.RUNNING, "agent-service"));
        store.put(task("t3", "acme", "agent.run", AsyncTaskStatus.PENDING, null));
        store.put(task("t4", "acme", "agent.dag", AsyncTaskStatus.RUNNING, "agentscope-orchestrator"));
        store.put(task("t5", "acme", "agent.run", AsyncTaskStatus.SUCCEEDED, "agent-service"));
        store.put(task("t6", "globex", "agent.run", AsyncTaskStatus.RUNNING, "agent-service"));

        assertThat(store.drainInventory("acme", AGENT_KINDS)).containsExactly(
                new AsyncTaskStore.DrainInventoryRow("agent.dag", AsyncTaskStatus.RUNNING,
                        "agentscope-orchestrator", 1),
                new AsyncTaskStore.DrainInventoryRow("agent.run", AsyncTaskStatus.PENDING, null, 1),
                new AsyncTaskStore.DrainInventoryRow("agent.run", AsyncTaskStatus.RUNNING,
                        "agent-service", 2));
    }

    @Test
    void endpointDefaultsToAgentKindsAndScopesToCallerTenant() {
        AsyncTaskStore store = new AsyncTaskStore(Duration.ofHours(1));
        store.put(task("t1", "acme", "agent.task", AsyncTaskStatus.RUNNING, "agent-service"));
        store.put(task("t2", "globex", "agent.task", AsyncTaskStatus.RUNNING, "agent-service"));
        store.put(task("t3", "acme", "billing.export", AsyncTaskStatus.RUNNING, "billing-worker"));
        AsyncTaskController controller = controller(store);
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of()));

        assertThat(controller.drainInventory(null)).containsExactly(
                new AsyncTaskStore.DrainInventoryRow("agent.task", AsyncTaskStatus.RUNNING,
                        "agent-service", 1));
    }

    @Test
    void endpointHonoursAnExplicitKindFilter() {
        AsyncTaskStore store = new AsyncTaskStore(Duration.ofHours(1));
        store.put(task("t1", "acme", "agent.task", AsyncTaskStatus.RUNNING, "agent-service"));
        store.put(task("t2", "acme", "agent.dag", AsyncTaskStatus.RUNNING, "agent-service"));
        AsyncTaskController controller = controller(store);
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of()));

        assertThat(controller.drainInventory(List.of("agent.dag"))).containsExactly(
                new AsyncTaskStore.DrainInventoryRow("agent.dag", AsyncTaskStatus.RUNNING,
                        "agent-service", 1));
    }

    private static AsyncTaskController controller(AsyncTaskStore store) {
        return new AsyncTaskController(store, new AsyncTaskSseService(store),
                mock(AuditLogger.class), event -> { });
    }

    private static AsyncTask task(String taskId,
                                  String tenantId,
                                  String kind,
                                  AsyncTaskStatus status,
                                  String leaseOwnerId) {
        Instant now = Instant.now();
        return new AsyncTask(taskId, tenantId, "alice", kind, status,
                Map.of("goal", "test"), null, null, null,
                now, now, status.isTerminal() ? now : null,
                leaseOwnerId, status.isTerminal() ? null : now.plusSeconds(60));
    }
}
