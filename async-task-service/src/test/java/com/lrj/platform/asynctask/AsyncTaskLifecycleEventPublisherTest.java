package com.lrj.platform.asynctask;

import com.lrj.platform.eventbus.EventPublisher;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import com.lrj.platform.protocol.event.AsyncTaskLifecycleMessage;
import com.lrj.platform.protocol.event.EventTopics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 纯 POJO：生命周期发布器把终态任务映射为 {@link AsyncTaskLifecycleMessage}，按 topic/key=tenantId 发布，
 * 无事务管理器时直接顺序执行（Noop 路径）。
 */
class AsyncTaskLifecycleEventPublisherTest {

    private static AsyncTask task(AsyncTaskStatus status) {
        Instant now = Instant.now();
        return new AsyncTask("task-7", "acme", "u1", "workflow.terminal", status,
                Map.of("k", "v"), Map.of("reply", "done"), null, "https://cb.local/x", now, now, now);
    }

    @Test
    void publishesLifecycleKeyedByTenant() {
        EventPublisher eventPublisher = mock(EventPublisher.class);
        AsyncTaskLifecycleEventPublisher publisher =
                new AsyncTaskLifecycleEventPublisher(eventPublisher, (org.springframework.transaction.PlatformTransactionManager) null);

        publisher.publish(task(AsyncTaskStatus.SUCCEEDED));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publish(eq(EventTopics.ASYNCTASK_LIFECYCLE), eq("acme"), payload.capture());
        AsyncTaskLifecycleMessage msg = (AsyncTaskLifecycleMessage) payload.getValue();
        assertThat(msg.eventId()).isEqualTo("asynctask:task-7:SUCCEEDED");
        assertThat(msg.taskId()).isEqualTo("task-7");
        assertThat(msg.tenantId()).isEqualTo("acme");
        assertThat(msg.kind()).isEqualTo("workflow.terminal");
        assertThat(msg.status()).isEqualTo("SUCCEEDED");
        assertThat(msg.result()).isEqualTo(Map.of("reply", "done"));
        assertThat(msg.webhookUrl()).isEqualTo("https://cb.local/x");
    }

    @Test
    void eventIdEncodesStatusSoDistinctTerminalsDoNotCollide() {
        AsyncTaskLifecycleMessage failed =
                AsyncTaskLifecycleEventPublisher.message(task(AsyncTaskStatus.FAILED));
        assertThat(failed.eventId()).isEqualTo("asynctask:task-7:FAILED");
    }

    @Test
    void notifierPublishesOnlyForTerminalAndEnabled() {
        EventPublisher eventPublisher = mock(EventPublisher.class);
        AsyncTaskLifecycleEventPublisher publisher =
                new AsyncTaskLifecycleEventPublisher(eventPublisher, (org.springframework.transaction.PlatformTransactionManager) null);
        AsyncTaskWebhookProperties props = new AsyncTaskWebhookProperties();
        props.setTransport("kafka");
        AsyncTaskKafkaNotifier notifier = new AsyncTaskKafkaNotifier(props, publisher);

        notifier.onTaskEvent(new AsyncTaskEvent(task(AsyncTaskStatus.RUNNING)));
        verify(eventPublisher, org.mockito.Mockito.never()).publish(any(), any(), any());

        notifier.onTaskEvent(new AsyncTaskEvent(task(AsyncTaskStatus.SUCCEEDED)));
        verify(eventPublisher).publish(eq(EventTopics.ASYNCTASK_LIFECYCLE), eq("acme"), any());
    }

    @Test
    void notifierSkipsWhenDisabled() {
        EventPublisher eventPublisher = mock(EventPublisher.class);
        AsyncTaskLifecycleEventPublisher publisher =
                new AsyncTaskLifecycleEventPublisher(eventPublisher, (org.springframework.transaction.PlatformTransactionManager) null);
        AsyncTaskWebhookProperties props = new AsyncTaskWebhookProperties();
        props.setEnabled(false);
        new AsyncTaskKafkaNotifier(props, publisher).onTaskEvent(new AsyncTaskEvent(task(AsyncTaskStatus.SUCCEEDED)));
        verify(eventPublisher, org.mockito.Mockito.never()).publish(any(), any(), any());
    }
}
