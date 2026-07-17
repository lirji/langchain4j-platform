package com.lrj.platform.asynctask;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskCreateRequest;
import com.lrj.platform.protocol.asynctask.AsyncTaskLeaseRequest;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatusUpdateRequest;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AsyncTaskControllerTest：验证 {@link AsyncTaskController} 的核心行为——创建返回 202 且按租户绑定、
 * 支持调用方自带 taskId、拒绝空 kind、状态迁移到终态、worker 租约抢占与到期重认领、活跃租约阻断他人
 * 更新、终态任务不重复发终态事件、跨租户不可见（404），以及死信 webhook outbox 的租户隔离与上限约束。
 */
class AsyncTaskControllerTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createReturnsAcceptedTaskScopedToTenant() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AsyncTaskController controller = controller();

        var response = controller.create(new AsyncTaskCreateRequest(
                "agent.run",
                Map.of("goal", "summarize"),
                "http://callback.local/tasks"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isInstanceOf(AsyncTask.class);
        AsyncTask task = (AsyncTask) response.getBody();
        assertThat(task.taskId()).isNotBlank();
        assertThat(task.tenantId()).isEqualTo("acme");
        assertThat(task.userId()).isEqualTo("alice");
        assertThat(task.kind()).isEqualTo("agent.run");
        assertThat(task.status()).isEqualTo(AsyncTaskStatus.PENDING);
        assertThat(task.input()).containsEntry("goal", "summarize");
        assertThat(task.webhookUrl()).isEqualTo("http://callback.local/tasks");
    }

    @Test
    void createCanUseCallerSuppliedTaskId() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AsyncTaskController controller = controller();

        var response = controller.create(new AsyncTaskCreateRequest(
                "agent-task-1",
                "agent.run",
                Map.of("goal", "summarize"),
                null));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        AsyncTask task = (AsyncTask) response.getBody();
        assertThat(task.taskId()).isEqualTo("agent-task-1");
    }

    @Test
    void createRejectsBlankKind() {
        AsyncTaskController controller = controller();

        var response = controller.create(new AsyncTaskCreateRequest(" ", Map.of(), null));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void updateStatusMovesTaskToTerminalState() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AsyncTaskController controller = controller();
        AsyncTask created = (AsyncTask) controller.create(new AsyncTaskCreateRequest("agent.run", Map.of(), null)).getBody();

        var response = controller.updateStatus(created.taskId(),
                new AsyncTaskStatusUpdateRequest(AsyncTaskStatus.SUCCEEDED, Map.of("answer", "done"), null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        AsyncTask task = (AsyncTask) response.getBody();
        assertThat(task.status()).isEqualTo(AsyncTaskStatus.SUCCEEDED);
        assertThat(task.result()).isEqualTo(Map.of("answer", "done"));
        assertThat(task.finishedAt()).isNotNull();
    }

    @Test
    void workerCanLeasePendingTask() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AsyncTaskController controller = controller();
        AsyncTask created = (AsyncTask) controller.create(new AsyncTaskCreateRequest("agent.run", Map.of(), null)).getBody();

        var response = controller.lease(created.taskId(), new AsyncTaskLeaseRequest("worker-1", 30L));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        AsyncTask task = (AsyncTask) response.getBody();
        assertThat(task.status()).isEqualTo(AsyncTaskStatus.RUNNING);
        assertThat(task.leaseOwnerId()).isEqualTo("worker-1");
        assertThat(task.leaseExpiresAt()).isNotNull();
    }

    @Test
    void activeLeaseBlocksOtherWorkersAndProtectsStatusUpdates() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AsyncTaskController controller = controller();
        AsyncTask created = (AsyncTask) controller.create(new AsyncTaskCreateRequest("agent.run", Map.of(), null)).getBody();
        controller.lease(created.taskId(), new AsyncTaskLeaseRequest("worker-1", 30L));

        var leaseResponse = controller.lease(created.taskId(), new AsyncTaskLeaseRequest("worker-2", 30L));
        var updateResponse = controller.updateStatus(created.taskId(),
                new AsyncTaskStatusUpdateRequest(AsyncTaskStatus.SUCCEEDED, Map.of("answer", "done"), null, "worker-2"));
        var ownerUpdateResponse = controller.updateStatus(created.taskId(),
                new AsyncTaskStatusUpdateRequest(AsyncTaskStatus.SUCCEEDED, Map.of("answer", "done"), null, "worker-1"));

        assertThat(leaseResponse.getStatusCode().value()).isEqualTo(409);
        assertThat(updateResponse.getStatusCode().value()).isEqualTo(409);
        assertThat(ownerUpdateResponse.getStatusCode().value()).isEqualTo(200);
        AsyncTask task = (AsyncTask) ownerUpdateResponse.getBody();
        assertThat(task.status()).isEqualTo(AsyncTaskStatus.SUCCEEDED);
        assertThat(task.leaseOwnerId()).isNull();
    }

    @Test
    void expiredLeaseCanBeClaimedByAnotherWorker() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AsyncTaskStore store = new AsyncTaskStore(Duration.ofHours(1));
        AsyncTaskController controller = new AsyncTaskController(
                store,
                new AsyncTaskSseService(store),
                mock(AuditLogger.class),
                mock(ApplicationEventPublisher.class));
        AsyncTask created = (AsyncTask) controller.create(new AsyncTaskCreateRequest("agent.run", Map.of(), null)).getBody();
        controller.lease(created.taskId(), new AsyncTaskLeaseRequest("worker-1", 1L));
        store.update(created.taskId(), task -> AsyncTaskStore.withLease(
                task,
                "worker-1",
                java.time.Instant.now().minusSeconds(1)));

        var response = controller.lease(created.taskId(), new AsyncTaskLeaseRequest("worker-2", 30L));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        AsyncTask task = (AsyncTask) response.getBody();
        assertThat(task.leaseOwnerId()).isEqualTo("worker-2");
    }

    @Test
    void updatingTerminalTaskDoesNotPublishDuplicateTerminalEvent() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AsyncTaskStore store = new AsyncTaskStore(Duration.ofHours(1));
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        AsyncTaskController controller = new AsyncTaskController(
                store,
                new AsyncTaskSseService(store),
                mock(AuditLogger.class),
                events);
        AsyncTask created = (AsyncTask) controller.create(new AsyncTaskCreateRequest("agent.run", Map.of(), null)).getBody();
        controller.updateStatus(created.taskId(),
                new AsyncTaskStatusUpdateRequest(AsyncTaskStatus.SUCCEEDED, Map.of("answer", "done"), null));

        var response = controller.updateStatus(created.taskId(),
                new AsyncTaskStatusUpdateRequest(AsyncTaskStatus.FAILED, null, "late failure"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        AsyncTask task = (AsyncTask) response.getBody();
        assertThat(task.status()).isEqualTo(AsyncTaskStatus.SUCCEEDED);
        verify(events, times(2)).publishEvent(org.mockito.ArgumentMatchers.any(AsyncTaskEvent.class));
    }

    @Test
    void tenantCannotSeeOtherTenantTask() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AsyncTaskController controller = controller();
        AsyncTask created = (AsyncTask) controller.create(new AsyncTaskCreateRequest("agent.run", Map.of(), null)).getBody();

        TenantContext.set(new TenantContext.Tenant("globex", "bob", Set.of("agent")));

        assertThat(controller.get(created.taskId()).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.updateStatus(created.taskId(),
                new AsyncTaskStatusUpdateRequest(AsyncTaskStatus.RUNNING, null, null)).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void cancelMarksPendingTaskCancelled() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AsyncTaskController controller = controller();
        AsyncTask created = (AsyncTask) controller.create(new AsyncTaskCreateRequest("agent.run", Map.of(), null)).getBody();

        var response = controller.cancel(created.taskId());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        AsyncTask cancelled = controller.get(created.taskId()).getBody();
        assertThat(cancelled.status()).isEqualTo(AsyncTaskStatus.CANCELLED);
        assertThat(cancelled.error()).isEqualTo("cancelled by user");
    }

    @Test
    void deadWebhookOutboxIsTenantScopedAndBounded() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AsyncTaskWebhookOutbox outbox = mock(AsyncTaskWebhookOutbox.class);
        Instant now = Instant.now();
        when(outbox.listDead("acme", 200)).thenReturn(List.of(new AsyncTaskWebhookOutbox.InspectionRow(
                "task-1",
                "task-1",
                "acme",
                "http://callback.local/tasks",
                "FAILED",
                "DEAD",
                3,
                "SERVER_ERROR",
                now,
                now)));
        AsyncTaskStore store = new AsyncTaskStore(Duration.ofHours(1));
        AsyncTaskController controller = new AsyncTaskController(
                store,
                new AsyncTaskSseService(store),
                mock(AuditLogger.class),
                mock(ApplicationEventPublisher.class),
                outbox);

        List<AsyncTaskWebhookOutbox.InspectionRow> rows = controller.deadWebhookOutbox(999);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().tenantId()).isEqualTo("acme");
        verify(outbox).listDead("acme", 200);
    }

    private static AsyncTaskController controller() {
        AsyncTaskStore store = new AsyncTaskStore(Duration.ofHours(1));
        return new AsyncTaskController(
                store,
                new AsyncTaskSseService(store),
                mock(AuditLogger.class),
                mock(ApplicationEventPublisher.class));
    }
}
