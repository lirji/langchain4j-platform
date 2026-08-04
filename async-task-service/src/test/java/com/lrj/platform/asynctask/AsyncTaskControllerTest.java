package com.lrj.platform.asynctask;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskCreateRequest;
import com.lrj.platform.protocol.asynctask.AsyncTaskLeaseRequest;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatusUpdateRequest;
import com.lrj.platform.security.TenantContext;
import com.lrj.platform.security.AsyncTaskWorkerToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.argThat;
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
        AsyncTask leased = (AsyncTask) controller.lease(
                created.taskId(), new AsyncTaskLeaseRequest("worker-1", 30L), worker("worker-1"))
                .getBody();

        var response = controller.updateStatus(created.taskId(),
                new AsyncTaskStatusUpdateRequest(
                        AsyncTaskStatus.SUCCEEDED, Map.of("answer", "done"), null, "worker-1",
                        leased.leaseEpoch()),
                worker("worker-1"));

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

        var response = controller.lease(
                created.taskId(), new AsyncTaskLeaseRequest("worker-1", 30L), worker("worker-1"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        AsyncTask task = (AsyncTask) response.getBody();
        assertThat(task.status()).isEqualTo(AsyncTaskStatus.RUNNING);
        assertThat(task.leaseOwnerId()).isEqualTo("worker-1");
        assertThat(task.leaseExpiresAt()).isNotNull();
        assertThat(task.leaseEpoch()).isEqualTo(1L);
    }

    @Test
    void statusUpdateRequiresCurrentUnexpiredWorkerLease() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AsyncTaskStore store = new AsyncTaskStore(Duration.ofHours(1));
        AsyncTaskController controller = new AsyncTaskController(
                store,
                new AsyncTaskSseService(store),
                mock(AuditLogger.class),
                mock(ApplicationEventPublisher.class));
        AsyncTask withoutLease = (AsyncTask) controller.create(
                new AsyncTaskCreateRequest("agent.run", Map.of(), null)).getBody();

        var missingLease = controller.updateStatus(withoutLease.taskId(),
                new AsyncTaskStatusUpdateRequest(
                        AsyncTaskStatus.SUCCEEDED, Map.of("answer", "done"), null, "worker-1", 1L),
                worker("worker-1"));

        AsyncTask expiredLease = (AsyncTask) controller.create(
                new AsyncTaskCreateRequest("agent.run", Map.of(), null)).getBody();
        store.update(expiredLease.taskId(), task -> AsyncTaskStore.withLease(
                task, "worker-1", Instant.now().minusSeconds(1)));
        long expiredEpoch = store.get(expiredLease.taskId()).orElseThrow().leaseEpoch();
        var expired = controller.updateStatus(expiredLease.taskId(),
                new AsyncTaskStatusUpdateRequest(
                        AsyncTaskStatus.SUCCEEDED, Map.of("answer", "late"), null, "worker-1",
                        expiredEpoch),
                worker("worker-1"));

        assertThat(missingLease.getStatusCode().value()).isEqualTo(409);
        assertThat(expired.getStatusCode().value()).isEqualTo(409);
        assertThat(store.get(withoutLease.taskId()).orElseThrow().status())
                .isEqualTo(AsyncTaskStatus.PENDING);
        assertThat(store.get(expiredLease.taskId()).orElseThrow().status())
                .isEqualTo(AsyncTaskStatus.RUNNING);
    }

    @Test
    void activeLeaseBlocksOtherWorkersAndProtectsStatusUpdates() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AsyncTaskController controller = controller();
        AsyncTask created = (AsyncTask) controller.create(new AsyncTaskCreateRequest("agent.run", Map.of(), null)).getBody();
        AsyncTask leased = (AsyncTask) controller.lease(
                created.taskId(), new AsyncTaskLeaseRequest("worker-1", 30L), worker("worker-1"))
                .getBody();

        var leaseResponse = controller.lease(
                created.taskId(), new AsyncTaskLeaseRequest("worker-2", 30L), worker("worker-2"));
        var updateResponse = controller.updateStatus(created.taskId(),
                new AsyncTaskStatusUpdateRequest(
                        AsyncTaskStatus.SUCCEEDED, Map.of("answer", "done"), null, "worker-2",
                        leased.leaseEpoch()),
                worker("worker-2"));
        var ownerUpdateResponse = controller.updateStatus(created.taskId(),
                new AsyncTaskStatusUpdateRequest(
                        AsyncTaskStatus.SUCCEEDED, Map.of("answer", "done"), null, "worker-1",
                        leased.leaseEpoch()),
                worker("worker-1"));

        assertThat(leaseResponse.getStatusCode().value()).isEqualTo(409);
        assertThat(updateResponse.getStatusCode().value()).isEqualTo(409);
        assertThat(ownerUpdateResponse.getStatusCode().value()).isEqualTo(200);
        AsyncTask task = (AsyncTask) ownerUpdateResponse.getBody();
        assertThat(task.status()).isEqualTo(AsyncTaskStatus.SUCCEEDED);
        assertThat(task.leaseOwnerId()).isNull();
    }

    @Test
    void leaseEpochFencesStaleReplicaAfterExpiredLeaseTakeover() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AsyncTaskStore store = new AsyncTaskStore(Duration.ofHours(1));
        InMemoryAsyncTaskEventJournal journal = new InMemoryAsyncTaskEventJournal();
        AsyncTaskController controller = new AsyncTaskController(
                store,
                new AsyncTaskSseService(store, journal),
                mock(AuditLogger.class),
                mock(ApplicationEventPublisher.class),
                null,
                journal);
        AsyncTask created = (AsyncTask) controller.create(
                new AsyncTaskCreateRequest("agent.run", Map.of(), null)).getBody();

        String replicaA = "agentscope-orchestrator.replica-a";
        String replicaB = "agentscope-orchestrator.replica-b";
        AsyncTask firstLease = (AsyncTask) controller.lease(
                created.taskId(), new AsyncTaskLeaseRequest(replicaA, 30L), worker(replicaA))
                .getBody();
        assertThat(firstLease.leaseEpoch()).isEqualTo(1L);
        assertThat(controller.lease(
                created.taskId(), new AsyncTaskLeaseRequest(replicaB, 30L), worker(replicaB))
                .getStatusCode().value()).isEqualTo(409);

        store.update(created.taskId(), task -> AsyncTaskStore.withLease(
                task, replicaA, Instant.now().minusSeconds(1), task.leaseEpoch()));
        AsyncTask secondLease = (AsyncTask) controller.lease(
                created.taskId(), new AsyncTaskLeaseRequest(replicaB, 30L), worker(replicaB))
                .getBody();

        assertThat(secondLease.leaseEpoch()).isEqualTo(2L);
        assertThat(controller.appendEvent(created.taskId(), new AsyncTaskEventAppendRequest(
                "replica-a:late",
                "dag-worker-start",
                Map.of("late", true),
                replicaA,
                firstLease.leaseEpoch()), worker(replicaA)).getStatusCode().value()).isEqualTo(409);
        assertThat(journal.eventsAfter(created.taskId(), 0))
                .noneMatch(event -> "replica-a:late".equals(event.eventKey()));
        assertThat(controller.updateStatus(created.taskId(), new AsyncTaskStatusUpdateRequest(
                AsyncTaskStatus.SUCCEEDED,
                Map.of("winner", "a"),
                null,
                replicaA,
                firstLease.leaseEpoch()), worker(replicaA)).getStatusCode().value()).isEqualTo(409);
        assertThat(controller.updateStatus(created.taskId(), new AsyncTaskStatusUpdateRequest(
                AsyncTaskStatus.SUCCEEDED,
                Map.of("winner", "b"),
                null,
                replicaB,
                secondLease.leaseEpoch()), worker(replicaB)).getStatusCode().value()).isEqualTo(200);
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
        controller.lease(
                created.taskId(), new AsyncTaskLeaseRequest("worker-1", 1L), worker("worker-1"));
        store.update(created.taskId(), task -> AsyncTaskStore.withLease(
                task,
                "worker-1",
                java.time.Instant.now().minusSeconds(1)));

        var response = controller.lease(
                created.taskId(), new AsyncTaskLeaseRequest("worker-2", 30L), worker("worker-2"));

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
        AsyncTask leased = (AsyncTask) controller.lease(
                created.taskId(), new AsyncTaskLeaseRequest("worker-1", 30L), worker("worker-1"))
                .getBody();
        controller.updateStatus(created.taskId(),
                new AsyncTaskStatusUpdateRequest(
                        AsyncTaskStatus.SUCCEEDED, Map.of("answer", "done"), null, "worker-1",
                        leased.leaseEpoch()),
                worker("worker-1"));

        var response = controller.updateStatus(created.taskId(),
                new AsyncTaskStatusUpdateRequest(
                        AsyncTaskStatus.FAILED, null, "late failure", "worker-1",
                        leased.leaseEpoch()),
                worker("worker-1"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        AsyncTask task = (AsyncTask) response.getBody();
        assertThat(task.status()).isEqualTo(AsyncTaskStatus.SUCCEEDED);
        // create + lease + first terminal transition; the repeated terminal update adds no event.
        verify(events, times(3)).publishEvent(org.mockito.ArgumentMatchers.any(AsyncTaskEvent.class));
    }

    @Test
    void tenantCannotSeeOtherTenantTask() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AsyncTaskController controller = controller();
        AsyncTask created = (AsyncTask) controller.create(new AsyncTaskCreateRequest("agent.run", Map.of(), null)).getBody();

        TenantContext.set(new TenantContext.Tenant("globex", "bob", Set.of("agent")));

        assertThat(controller.get(created.taskId()).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.updateStatus(created.taskId(),
                new AsyncTaskStatusUpdateRequest(
                        AsyncTaskStatus.RUNNING, null, null, "worker-1", 1L),
                worker("worker-1")).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void sameTenantUserCannotListReadStreamOrCancelAnotherUsersTask() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AsyncTaskController controller = controller();
        AsyncTask created = (AsyncTask) controller.create(
                new AsyncTaskCreateRequest("agent.run", Map.of(), null)).getBody();

        TenantContext.set(new TenantContext.Tenant("acme", "mallory", Set.of("agent")));

        assertThat(controller.listMine()).isEmpty();
        assertThat(controller.get(created.taskId()).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.stream(created.taskId(), null, null).getStatusCode().value())
                .isEqualTo(404);
        assertThat(controller.cancel(created.taskId()).getStatusCode().value()).isEqualTo(404);

        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        assertThat(controller.get(created.taskId()).getStatusCode().value()).isEqualTo(200);
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

    @Test
    void progressAppendRequiresAgentKindTenantAndLiveWorkerLease() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AsyncTaskStore store = new AsyncTaskStore(Duration.ofHours(1));
        InMemoryAsyncTaskEventJournal journal = new InMemoryAsyncTaskEventJournal();
        ApplicationEventPublisher applicationEvents = mock(ApplicationEventPublisher.class);
        AsyncTaskController controller = new AsyncTaskController(
                store,
                new AsyncTaskSseService(store, journal),
                mock(AuditLogger.class),
                applicationEvents,
                null,
                journal);
        AsyncTask task = (AsyncTask) controller.create(
                new AsyncTaskCreateRequest("agent.dag", Map.of(), null)).getBody();
        AsyncTask leased = (AsyncTask) controller.lease(
                task.taskId(), new AsyncTaskLeaseRequest("worker-1", 30L), worker("worker-1"))
                .getBody();
        AsyncTaskEventAppendRequest event = new AsyncTaskEventAppendRequest(
                "worker-1:step-1",
                "dag-worker-start",
                Map.of("taskId", "t1"),
                "worker-1",
                leased.leaseEpoch());

        var first = controller.appendEvent(task.taskId(), event, worker("worker-1"));
        var duplicate = controller.appendEvent(task.taskId(), event, worker("worker-1"));
        var wrongWorker = controller.appendEvent(task.taskId(), new AsyncTaskEventAppendRequest(
                "worker-2:step-1", "dag-worker-start", Map.of(), "worker-2", leased.leaseEpoch()),
                worker("worker-2"));

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(duplicate.getBody()).isEqualTo(first.getBody());
        assertThat(wrongWorker.getStatusCode().value()).isEqualTo(409);
        assertThat(journal.eventsAfter(task.taskId(), 0)).extracting(AsyncTaskStreamEvent::event)
                .containsExactly("PENDING", "RUNNING", "dag-worker-start");
        verify(applicationEvents, times(1)).publishEvent(argThat((Object item) ->
                item instanceof AsyncTaskEvent asyncEvent
                        && asyncEvent.streamEvent() != null
                        && "worker-1:step-1".equals(asyncEvent.streamEvent().eventKey())));

        TenantContext.set(new TenantContext.Tenant("globex", "bob", Set.of("agent")));
        assertThat(controller.appendEvent(task.taskId(), event, worker("worker-1")).getStatusCode().value())
                .isEqualTo(404);
    }

    private static AsyncTaskController controller() {
        AsyncTaskStore store = new AsyncTaskStore(Duration.ofHours(1));
        return new AsyncTaskController(
                store,
                new AsyncTaskSseService(store),
                mock(AuditLogger.class),
                mock(ApplicationEventPublisher.class));
    }

    private static AsyncTaskWorkerToken.Principal worker(String workerId) {
        TenantContext.Tenant caller = TenantContext.current();
        return new AsyncTaskWorkerToken.Principal(
                workerId, caller.tenantId(), caller.userId(), workerId);
    }
}
