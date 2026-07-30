package com.lrj.platform.asynctask;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AsyncTaskOrphanReaperTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void failsOnlyAllowlistedKindsAndRestoresSchedulerContext() {
        AsyncTaskStore store = new AsyncTaskStore(Duration.ofHours(1));
        store.put(task("agent-1", "agent.run"));
        store.put(task("legacy-agent-1", "agent.task"));
        store.put(task("workflow-1", "workflow.instance"));
        InMemoryAsyncTaskEventJournal journal = new InMemoryAsyncTaskEventJournal();
        AsyncTaskOrphanProperties properties = new AsyncTaskOrphanProperties();
        properties.setPendingTimeout(Duration.ofSeconds(1));
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        AuditLogger audit = mock(AuditLogger.class);
        AsyncTaskOrphanReaper reaper = new AsyncTaskOrphanReaper(
                store, journal, properties, events, audit);
        TenantContext.Tenant scheduler = new TenantContext.Tenant(
                "scheduler", "system", Set.of("system"));
        TenantContext.set(scheduler);

        reaper.reap();

        assertThat(store.get("agent-1").orElseThrow().status()).isEqualTo(AsyncTaskStatus.FAILED);
        assertThat(store.get("agent-1").orElseThrow().error())
                .isEqualTo(AsyncTaskOrphanReaper.ORPHAN_ERROR);
        assertThat(store.get("legacy-agent-1").orElseThrow().status())
                .isEqualTo(AsyncTaskStatus.FAILED);
        assertThat(store.get("workflow-1").orElseThrow().status())
                .isEqualTo(AsyncTaskStatus.PENDING);
        assertThat(TenantContext.current()).isEqualTo(scheduler);
        verify(events, times(2)).publishEvent(org.mockito.ArgumentMatchers.any(AsyncTaskEvent.class));
    }

    @Test
    void rejectsNonAgentAllowlistConfiguration() {
        AsyncTaskOrphanProperties properties = new AsyncTaskOrphanProperties();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> properties.setKinds(Set.of("agent.run", "workflow.instance")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AsyncTask task(String taskId, String kind) {
        Instant old = Instant.now().minusSeconds(60);
        return new AsyncTask(
                taskId,
                "acme",
                "alice",
                kind,
                AsyncTaskStatus.PENDING,
                Map.of(),
                null,
                null,
                null,
                old,
                old,
                null);
    }
}
