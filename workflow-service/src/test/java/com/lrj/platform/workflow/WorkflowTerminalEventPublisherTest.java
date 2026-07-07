package com.lrj.platform.workflow;

import com.lrj.platform.eventbus.EventPublisher;
import com.lrj.platform.protocol.event.EventTopics;
import com.lrj.platform.protocol.event.WorkflowTerminalMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 纯 POJO：kafka 终态发布器把终态映射为 {@link WorkflowTerminalMessage} 并按 topic/key 发布，
 * 有 webhookUrl 时同步写 outbox 权威行，无事务管理器时直接顺序执行（Noop 路径）。
 */
class WorkflowTerminalEventPublisherTest {

    @Test
    void publishesTerminalMessageKeyedByTenantAndWritesOutbox() {
        EventPublisher eventPublisher = mock(EventPublisher.class);
        WorkflowOutbox outbox = mock(WorkflowOutbox.class);
        WorkflowTerminalEventPublisher publisher =
                new WorkflowTerminalEventPublisher(eventPublisher, outbox, (org.springframework.transaction.PlatformTransactionManager) null);

        publisher.publishTerminal("inst-9", "acme", "feishu:u1", "granted", "refund approved", "https://cb.local/x");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publish(org.mockito.ArgumentMatchers.eq(EventTopics.WORKFLOW_TERMINAL),
                org.mockito.ArgumentMatchers.eq("acme"), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(WorkflowTerminalMessage.class);
        WorkflowTerminalMessage msg = (WorkflowTerminalMessage) payload.getValue();
        assertThat(msg.eventId()).isEqualTo("workflow:inst-9");
        assertThat(msg.tenantId()).isEqualTo("acme");
        assertThat(msg.instanceId()).isEqualTo("inst-9");
        assertThat(msg.chatId()).isEqualTo("feishu:u1");
        assertThat(msg.outcome()).isEqualTo("granted");
        assertThat(msg.reply()).isEqualTo("refund approved");
        assertThat(msg.status()).isEqualTo(WorkflowService.STATUS_COMPLETED);

        verify(outbox).enqueue(org.mockito.ArgumentMatchers.eq("inst-9"),
                org.mockito.ArgumentMatchers.eq("acme"),
                org.mockito.ArgumentMatchers.eq("https://cb.local/x"),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void skipsOutboxWhenNoWebhookUrlButStillPublishes() {
        EventPublisher eventPublisher = mock(EventPublisher.class);
        WorkflowOutbox outbox = mock(WorkflowOutbox.class);
        WorkflowTerminalEventPublisher publisher =
                new WorkflowTerminalEventPublisher(eventPublisher, outbox, (org.springframework.transaction.PlatformTransactionManager) null);

        publisher.publishTerminal("inst-1", "acme", "chat", "auto", "done", null);

        verify(eventPublisher).publish(org.mockito.ArgumentMatchers.eq(EventTopics.WORKFLOW_TERMINAL),
                org.mockito.ArgumentMatchers.eq("acme"), org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(outbox);
    }

    @Test
    void staticMessageMapperUsesStableEventId() {
        WorkflowTerminalMessage msg =
                WorkflowTerminalEventPublisher.message("inst-2", "t", "c", "rejected", "no");
        assertThat(msg.eventId()).isEqualTo("workflow:inst-2");
        assertThat(msg.schemaVersion()).isEqualTo(WorkflowTerminalMessage.CURRENT_SCHEMA_VERSION);
    }

    @Test
    void blankWebhookUrlSkipsOutbox() {
        EventPublisher eventPublisher = mock(EventPublisher.class);
        WorkflowOutbox outbox = mock(WorkflowOutbox.class);
        new WorkflowTerminalEventPublisher(eventPublisher, outbox, (org.springframework.transaction.PlatformTransactionManager) null)
                .publishTerminal("i", "t", "c", "auto", "r", "   ");
        verify(outbox, never()).enqueue(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }
}
