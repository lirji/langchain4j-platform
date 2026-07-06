package com.lrj.platform.agent.async;

import com.lrj.platform.agent.AgentDecision;
import com.lrj.platform.agent.AgentProperties;
import com.lrj.platform.agent.DeepAgentService;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.agent.AgentRunReply;
import com.lrj.platform.security.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentAsyncTaskServiceTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void submitRunsAgentAndPublishesStateChanges() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        List<AgentTaskEvent> events = new ArrayList<>();
        AgentAsyncTaskService service = service(events);

        AgentAsyncTask submitted = service.submit("summarize refund policy");

        AgentAsyncTask task = service.get(submitted.taskId()).orElseThrow();
        assertThat(task.status()).isEqualTo(AgentTaskStatus.SUCCEEDED);
        assertThat(task.tenantId()).isEqualTo("acme");
        assertThat(task.userId()).isEqualTo("alice");
        AgentRunReply result = (AgentRunReply) task.result();
        assertThat(result.finalAnswer()).isEqualTo("done");
        assertThat(result.tenantId()).isEqualTo("acme");
        assertThat(events).extracting(event -> event.task().status())
                .containsExactly(AgentTaskStatus.PENDING, AgentTaskStatus.RUNNING, AgentTaskStatus.SUCCEEDED);
    }

    @Test
    void getIsTenantScoped() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        AgentAsyncTaskService service = service(new ArrayList<>());

        String taskId = service.submit("goal").taskId();
        TenantContext.set(new TenantContext.Tenant("globex", "bob", Set.of("agent")));

        assertThat(service.get(taskId)).isEmpty();
        assertThat(service.listMine()).isEmpty();
    }

    @Test
    void authoritativeExternalStoreOwnsTaskState() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        List<AgentTaskEvent> events = new ArrayList<>();
        ExternalAsyncTaskClient external = mock(ExternalAsyncTaskClient.class);
        when(external.create(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(external.get(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> events.stream()
                        .map(AgentTaskEvent::task)
                        .filter(task -> task.taskId().equals(invocation.getArgument(0)))
                        .reduce((first, second) -> second));
        AgentAsyncTaskService service = service(events, external);
        when(external.lease(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

        AgentAsyncTask submitted = service.submit("goal");

        assertThat(service.get(submitted.taskId())).isPresent();
        verify(external).create(org.mockito.ArgumentMatchers.argThat(task -> task.taskId().equals(submitted.taskId())));
        verify(external).lease(submitted.taskId());
        verify(external).update(org.mockito.ArgumentMatchers.argThat(task -> task.status() == AgentTaskStatus.RUNNING));
        verify(external).update(org.mockito.ArgumentMatchers.argThat(task -> task.status() == AgentTaskStatus.SUCCEEDED));
    }

    @Test
    void authoritativeExternalTaskIsNotExecutedWhenLeaseFails() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        List<AgentTaskEvent> events = new ArrayList<>();
        ExternalAsyncTaskClient external = mock(ExternalAsyncTaskClient.class);
        when(external.create(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(external.lease(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        AgentAsyncTaskService service = service(events, external);

        AgentAsyncTask submitted = service.submit("goal");

        verify(external).create(org.mockito.ArgumentMatchers.argThat(task -> task.taskId().equals(submitted.taskId())));
        verify(external).lease(submitted.taskId());
        verify(external, org.mockito.Mockito.never()).update(org.mockito.ArgumentMatchers.any());
        assertThat(events).extracting(event -> event.task().status()).containsExactly(AgentTaskStatus.PENDING);
    }

    @Test
    void cancelBeforeExecutorRunsSkipsWorkAndKeepsCancelledState() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        List<AgentTaskEvent> events = new ArrayList<>();
        Queue<Runnable> queued = new ArrayDeque<>();
        AgentAsyncTaskService service = service(events, null, queued::add);
        AtomicBoolean workCalled = new AtomicBoolean(false);

        AgentAsyncTask submitted = service.submitWithProgress(
                "TEST",
                Map.of("goal", "goal"),
                progress -> {
                    workCalled.set(true);
                    return "done";
                });

        assertThat(service.cancel(submitted.taskId())).isTrue();
        queued.remove().run();

        AgentAsyncTask task = service.get(submitted.taskId()).orElseThrow();
        assertThat(task.status()).isEqualTo(AgentTaskStatus.CANCELLED);
        assertThat(task.result()).isNull();
        assertThat(workCalled).isFalse();
    }

    private static AgentAsyncTaskService service(List<AgentTaskEvent> events) {
        return service(events, null);
    }

    private static AgentAsyncTaskService service(List<AgentTaskEvent> events, ExternalAsyncTaskClient external) {
        return service(events, external, Runnable::run);
    }

    private static AgentAsyncTaskService service(List<AgentTaskEvent> events,
                                                ExternalAsyncTaskClient external,
                                                Executor executor) {
        DeepAgentService agent = new DeepAgentService(
                (goal, actions, scratchpad, history) -> new AgentDecision("", "finish", "", "", "done"),
                List.of(),
                new AgentProperties());
        if (external == null) {
            return new AgentAsyncTaskService(
                    new AgentTaskStore(Duration.ofHours(1)),
                    executor,
                    agent,
                    mock(AuditLogger.class),
                    event -> events.add((AgentTaskEvent) event));
        }
        ExternalAsyncTaskProperties properties = new ExternalAsyncTaskProperties();
        properties.setEnabled(true);
        properties.setAuthoritative(true);
        ObjectProvider<ExternalAsyncTaskClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(external);
        return new AgentAsyncTaskService(
                new AgentTaskStore(Duration.ofHours(1)),
                executor,
                agent,
                mock(AuditLogger.class),
                event -> events.add((AgentTaskEvent) event),
                properties,
                provider);
    }
}
