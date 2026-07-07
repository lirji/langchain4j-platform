package com.lrj.platform.asynctask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lrj.platform.eventbus.EventPublisher;
import com.lrj.platform.protocol.event.AsyncTaskLifecycleMessage;
import com.lrj.platform.protocol.event.EventTopics;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 纯逻辑单测：生命周期事件 relay 反序列化快照并发布 Kafka，成功标 DELIVERED、失败重试/DLQ。
 */
class AsyncTaskLifecycleRelayTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private AsyncTaskLifecycleOutbox.Row row(int attempts) throws Exception {
        AsyncTaskLifecycleMessage m = new AsyncTaskLifecycleMessage(
                "asynctask:t1:SUCCEEDED", AsyncTaskLifecycleMessage.CURRENT_SCHEMA_VERSION,
                "acme", "t1", "agent.run", "SUCCEEDED", java.util.Map.of("answer", "ok"), null,
                "http://cb/hook", Instant.parse("2026-07-07T10:00:00Z"), "trace");
        return new AsyncTaskLifecycleOutbox.Row("asynctask:t1:SUCCEEDED", "acme", mapper.writeValueAsString(m), attempts);
    }

    private AsyncTaskWebhookProperties props() {
        AsyncTaskWebhookProperties p = new AsyncTaskWebhookProperties();
        p.setTransport("kafka");
        return p;
    }

    @Test
    void publishesToLifecycleTopicKeyedByTenantAndMarksDelivered() throws Exception {
        AsyncTaskLifecycleOutbox outbox = mock(AsyncTaskLifecycleOutbox.class);
        EventPublisher publisher = mock(EventPublisher.class);
        when(outbox.claimDue(anyLong(), anyInt())).thenReturn(List.of(row(0)));

        new AsyncTaskLifecycleRelay(outbox, publisher, mapper, props()).dispatch();

        verify(publisher).publish(eq(EventTopics.ASYNCTASK_LIFECYCLE), eq("acme"), any(AsyncTaskLifecycleMessage.class));
        verify(outbox).markDelivered(eq("asynctask:t1:SUCCEEDED"), anyLong());
    }

    @Test
    void emptyDue_doesNothing() {
        AsyncTaskLifecycleOutbox outbox = mock(AsyncTaskLifecycleOutbox.class);
        EventPublisher publisher = mock(EventPublisher.class);
        when(outbox.claimDue(anyLong(), anyInt())).thenReturn(List.of());

        new AsyncTaskLifecycleRelay(outbox, publisher, mapper, props()).dispatch();

        verify(publisher, never()).publish(anyString(), anyString(), any());
    }

    @Test
    void publishFailure_marksRetryBeforeMaxAttempts() throws Exception {
        AsyncTaskLifecycleOutbox outbox = mock(AsyncTaskLifecycleOutbox.class);
        EventPublisher publisher = mock(EventPublisher.class);
        when(outbox.claimDue(anyLong(), anyInt())).thenReturn(List.of(row(0)));
        doThrow(new RuntimeException("broker down")).when(publisher).publish(anyString(), anyString(), any());

        new AsyncTaskLifecycleRelay(outbox, publisher, mapper, props()).dispatch();

        verify(outbox).markRetry(eq("asynctask:t1:SUCCEEDED"), eq(1), anyLong(), anyString(), anyLong());
        verify(outbox, never()).markDelivered(anyString(), anyLong());
    }

    @Test
    void publishFailure_marksDeadAtMaxAttempts() throws Exception {
        AsyncTaskLifecycleOutbox outbox = mock(AsyncTaskLifecycleOutbox.class);
        EventPublisher publisher = mock(EventPublisher.class);
        // 默认 maxAttempts=3：已尝试 2 次再失败 → attemptsAfter=3 → DEAD
        when(outbox.claimDue(anyLong(), anyInt())).thenReturn(List.of(row(2)));
        doThrow(new RuntimeException("broker down")).when(publisher).publish(anyString(), anyString(), any());

        new AsyncTaskLifecycleRelay(outbox, publisher, mapper, props()).dispatch();

        verify(outbox).markDead(eq("asynctask:t1:SUCCEEDED"), eq(3), anyString(), anyLong());
    }
}
