package com.lrj.platform.channel.feishu;

import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuMessageBridgeTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private FeishuProperties props() {
        FeishuProperties p = new FeishuProperties();
        p.setTenantId("acme");
        return p;
    }

    private static FeishuInboundMessage msg(String id, String text) {
        return new FeishuInboundMessage(id, "ou_1", "oc_2", text);
    }

    @Test
    void handle_callsChatThenReply() {
        HttpConversationClient conversation = mock(HttpConversationClient.class);
        HttpFeishuReplyClient reply = mock(HttpFeishuReplyClient.class);
        when(conversation.chat(eq("你好"), eq("ou_1"))).thenReturn("你好，我能帮你什么？");
        FeishuMessageBridge bridge = new FeishuMessageBridge(conversation, reply, Runnable::run, props());

        bridge.handle(msg("om_1", "你好"));

        verify(conversation).chat(eq("你好"), eq("ou_1"));
        verify(reply).replyText(eq("ou_1"), eq("你好，我能帮你什么？"));
    }

    @Test
    void handle_deduplicatesSameMessageId() {
        HttpConversationClient conversation = mock(HttpConversationClient.class);
        HttpFeishuReplyClient reply = mock(HttpFeishuReplyClient.class);
        when(conversation.chat(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("r");
        FeishuMessageBridge bridge = new FeishuMessageBridge(conversation, reply, Runnable::run, props());

        bridge.handle(msg("om_dup", "hi"));
        bridge.handle(msg("om_dup", "hi"));

        verify(conversation, times(1)).chat(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void handle_blankText_noOp() {
        HttpConversationClient conversation = mock(HttpConversationClient.class);
        HttpFeishuReplyClient reply = mock(HttpFeishuReplyClient.class);
        FeishuMessageBridge bridge = new FeishuMessageBridge(conversation, reply, Runnable::run, props());

        bridge.handle(msg("om_2", "   "));

        verify(conversation, times(0)).chat(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void handle_emptyReply_skipsReplyCall() {
        HttpConversationClient conversation = mock(HttpConversationClient.class);
        HttpFeishuReplyClient reply = mock(HttpFeishuReplyClient.class);
        when(conversation.chat(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("");
        FeishuMessageBridge bridge = new FeishuMessageBridge(conversation, reply, Runnable::run, props());

        bridge.handle(msg("om_3", "hi"));

        verify(reply, times(0)).replyText(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
