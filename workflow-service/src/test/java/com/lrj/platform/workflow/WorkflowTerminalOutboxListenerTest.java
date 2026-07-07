package com.lrj.platform.workflow;

import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 纯逻辑单测：end 事件监听器按 mode 决定是否写终态事件 outbox（不连 DB / Flowable，只验路由 + 变量映射）。
 */
class WorkflowTerminalOutboxListenerTest {

    private WorkflowProperties props(String mode) {
        WorkflowProperties p = new WorkflowProperties();
        p.getTerminalNotification().setMode(mode);
        return p;
    }

    private DelegateExecution execution() {
        DelegateExecution e = mock(DelegateExecution.class);
        when(e.getProcessInstanceId()).thenReturn("inst-1");
        when(e.getVariable("tenantId")).thenReturn("acme");
        when(e.getVariable("chatId")).thenReturn("u1");
        when(e.getVariable("terminalOutcome")).thenReturn("granted");
        when(e.getVariable("webhookUrl")).thenReturn("http://cb.local/hook");
        return e;
    }

    @Test
    void kafkaMode_enqueuesEventOutboxWithVariables() {
        WorkflowTerminalEventOutbox outbox = mock(WorkflowTerminalEventOutbox.class);
        new WorkflowTerminalOutboxListener(outbox, props("kafka")).notify(execution());

        verify(outbox).enqueue(eq("inst-1"), eq("acme"), eq("u1"), eq("granted"),
                eq("http://cb.local/hook"), anyLong());
    }

    @Test
    void localMode_isNoOp() {
        WorkflowTerminalEventOutbox outbox = mock(WorkflowTerminalEventOutbox.class);
        new WorkflowTerminalOutboxListener(outbox, props("local")).notify(mock(DelegateExecution.class));
        verifyNoInteractions(outbox);
    }

    @Test
    void asyncTaskMode_isNoOp() {
        WorkflowTerminalEventOutbox outbox = mock(WorkflowTerminalEventOutbox.class);
        new WorkflowTerminalOutboxListener(outbox, props("async-task")).notify(mock(DelegateExecution.class));
        verify(outbox, never()).enqueue(any(), any(), any(), any(), any(), anyLong());
    }
}
