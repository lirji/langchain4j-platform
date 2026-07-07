package com.lrj.platform.workflow;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.eventbus.EventPublisher;
import com.lrj.platform.protocol.event.EventTopics;
import com.lrj.platform.protocol.event.WorkflowTerminalMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
 * 纯逻辑单测：终态事件 Kafka relay 的投递/重试/DLQ 决策与消息重建（mock 掉 outbox / publisher / replyStore）。
 */
class WorkflowTerminalEventRelayTest {

    private WorkflowProperties props() {
        WorkflowProperties p = new WorkflowProperties();
        p.getTerminalNotification().setMode("kafka");
        return p;
    }

    private WorkflowTerminalEventOutbox.Row row(int attempts) {
        return new WorkflowTerminalEventOutbox.Row("inst-1", "acme", "u1", "granted", "http://cb/hook", attempts);
    }

    @Test
    void message_rebuildsTerminalMessageFromRowAndReply() {
        WorkflowTerminalMessage m = WorkflowTerminalEventRelay.message(row(0), "已受理你的退款");
        assertThat(m.eventId()).isEqualTo("workflow:inst-1");
        assertThat(m.tenantId()).isEqualTo("acme");
        assertThat(m.instanceId()).isEqualTo("inst-1");
        assertThat(m.chatId()).isEqualTo("u1");
        assertThat(m.outcome()).isEqualTo("granted");
        assertThat(m.reply()).isEqualTo("已受理你的退款");
        assertThat(m.status()).isEqualTo(WorkflowService.STATUS_COMPLETED);
    }

    @Test
    void dispatch_emptyDue_doesNothing() {
        WorkflowTerminalEventOutbox outbox = mock(WorkflowTerminalEventOutbox.class);
        EventPublisher publisher = mock(EventPublisher.class);
        when(outbox.claimDue(anyLong(), anyInt())).thenReturn(List.of());

        relay(outbox, mock(WorkflowReplyStore.class), publisher).dispatch();

        verify(publisher, never()).publish(anyString(), anyString(), any());
    }

    @Test
    void dispatch_publishesToWorkflowTerminalTopicKeyedByTenantAndMarksDelivered() {
        WorkflowTerminalEventOutbox outbox = mock(WorkflowTerminalEventOutbox.class);
        EventPublisher publisher = mock(EventPublisher.class);
        WorkflowReplyStore replyStore = mock(WorkflowReplyStore.class);
        when(outbox.claimDue(anyLong(), anyInt())).thenReturn(List.of(row(0)));
        when(replyStore.find("inst-1")).thenReturn("已受理");

        relay(outbox, replyStore, publisher).dispatch();

        verify(publisher).publish(eq(EventTopics.WORKFLOW_TERMINAL), eq("acme"), any(WorkflowTerminalMessage.class));
        verify(outbox).markDelivered(eq("inst-1"), anyLong());
    }

    @Test
    void dispatch_publishFailure_marksRetryBeforeMaxAttempts() {
        WorkflowTerminalEventOutbox outbox = mock(WorkflowTerminalEventOutbox.class);
        EventPublisher publisher = mock(EventPublisher.class);
        WorkflowReplyStore replyStore = mock(WorkflowReplyStore.class);
        when(outbox.claimDue(anyLong(), anyInt())).thenReturn(List.of(row(0)));
        doThrow(new RuntimeException("broker down")).when(publisher).publish(anyString(), anyString(), any());

        relay(outbox, replyStore, publisher).dispatch();

        verify(outbox).markRetry(eq("inst-1"), eq(1), anyLong(), anyString(), anyLong());
        verify(outbox, never()).markDelivered(anyString(), anyLong());
    }

    @Test
    void dispatch_publishFailure_marksDeadAtMaxAttempts() {
        WorkflowTerminalEventOutbox outbox = mock(WorkflowTerminalEventOutbox.class);
        EventPublisher publisher = mock(EventPublisher.class);
        WorkflowReplyStore replyStore = mock(WorkflowReplyStore.class);
        // 默认 maxAttempts=6：已尝试 5 次的行本次再失败 → attemptsAfter=6 → DEAD
        when(outbox.claimDue(anyLong(), anyInt())).thenReturn(List.of(row(5)));
        doThrow(new RuntimeException("broker down")).when(publisher).publish(anyString(), anyString(), any());

        relay(outbox, replyStore, publisher).dispatch();

        verify(outbox).markDead(eq("inst-1"), eq(6), anyString(), anyLong());
        verify(outbox, never()).markRetry(anyString(), anyInt(), anyLong(), anyString(), anyLong());
    }

    private WorkflowTerminalEventRelay relay(WorkflowTerminalEventOutbox outbox,
                                             WorkflowReplyStore replyStore,
                                             EventPublisher publisher) {
        return new WorkflowTerminalEventRelay(outbox, replyStore, publisher, props(), mock(AuditLogger.class));
    }
}
