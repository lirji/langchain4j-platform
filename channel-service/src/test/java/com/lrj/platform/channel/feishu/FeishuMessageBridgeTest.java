package com.lrj.platform.channel.feishu;

import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    private FeishuProperties routingProps() {
        FeishuProperties p = props();
        p.getIntentRouting().setEnabled(true);
        return p;
    }

    private static FeishuInboundMessage msg(String id, String text) {
        return new FeishuInboundMessage(id, "ou_1", "oc_2", text);
    }

    @Test
    void handle_callsChatThenReply() {
        HttpConversationClient conversation = mock(HttpConversationClient.class);
        HttpFeishuReplyClient reply = mock(HttpFeishuReplyClient.class);
        HttpWorkflowClient workflow = mock(HttpWorkflowClient.class);
        when(conversation.chat(eq("你好"), eq("ou_1"))).thenReturn("你好，我能帮你什么？");
        FeishuMessageBridge bridge = new FeishuMessageBridge(conversation, reply, workflow, Runnable::run, props());

        bridge.handle(msg("om_1", "你好"));

        verify(conversation).chat(eq("你好"), eq("ou_1"));
        verify(reply).replyText(eq("ou_1"), eq("你好，我能帮你什么？"));
    }

    @Test
    void handle_deduplicatesSameMessageId() {
        HttpConversationClient conversation = mock(HttpConversationClient.class);
        HttpFeishuReplyClient reply = mock(HttpFeishuReplyClient.class);
        HttpWorkflowClient workflow = mock(HttpWorkflowClient.class);
        when(conversation.chat(any(), any())).thenReturn("r");
        FeishuMessageBridge bridge = new FeishuMessageBridge(conversation, reply, workflow, Runnable::run, props());

        bridge.handle(msg("om_dup", "hi"));
        bridge.handle(msg("om_dup", "hi"));

        verify(conversation, times(1)).chat(any(), any());
    }

    @Test
    void handle_blankText_noOp() {
        HttpConversationClient conversation = mock(HttpConversationClient.class);
        HttpFeishuReplyClient reply = mock(HttpFeishuReplyClient.class);
        HttpWorkflowClient workflow = mock(HttpWorkflowClient.class);
        FeishuMessageBridge bridge = new FeishuMessageBridge(conversation, reply, workflow, Runnable::run, props());

        bridge.handle(msg("om_2", "   "));

        verify(conversation, times(0)).chat(any(), any());
    }

    @Test
    void handle_emptyReply_skipsReplyCall() {
        HttpConversationClient conversation = mock(HttpConversationClient.class);
        HttpFeishuReplyClient reply = mock(HttpFeishuReplyClient.class);
        HttpWorkflowClient workflow = mock(HttpWorkflowClient.class);
        when(conversation.chat(any(), any())).thenReturn("");
        FeishuMessageBridge bridge = new FeishuMessageBridge(conversation, reply, workflow, Runnable::run, props());

        bridge.handle(msg("om_3", "hi"));

        verify(reply, times(0)).replyText(any(), any());
    }

    @Test
    void intentRoutingOff_refundKeywordStillChats() {
        HttpConversationClient conversation = mock(HttpConversationClient.class);
        HttpFeishuReplyClient reply = mock(HttpFeishuReplyClient.class);
        HttpWorkflowClient workflow = mock(HttpWorkflowClient.class);
        when(conversation.chat(any(), any())).thenReturn("好的");
        // 默认关：即便命中退款关键词也走对话，不碰 workflow（零回归）
        FeishuMessageBridge bridge = new FeishuMessageBridge(conversation, reply, workflow, Runnable::run, props());

        bridge.handle(msg("om_r1", "我要退款"));

        verify(conversation).chat(eq("我要退款"), eq("ou_1"));
        verifyNoInteractions(workflow);
    }

    @Test
    void intentRoutingOn_refundKeyword_waitingApproval_repliesTransferredToHuman() {
        HttpConversationClient conversation = mock(HttpConversationClient.class);
        HttpFeishuReplyClient reply = mock(HttpFeishuReplyClient.class);
        HttpWorkflowClient workflow = mock(HttpWorkflowClient.class);
        when(workflow.startRefund(eq("我要退款"), eq("feishu:ou_1"), eq("om_r2")))
                .thenReturn(new HttpWorkflowClient.StartResult("inst-12345678abc", HttpWorkflowClient.STATUS_WAITING, null));
        FeishuMessageBridge bridge = new FeishuMessageBridge(conversation, reply, workflow, Runnable::run, routingProps());

        bridge.handle(msg("om_r2", "我要退款"));

        verify(workflow).startRefund(eq("我要退款"), eq("feishu:ou_1"), eq("om_r2"));
        verify(reply).replyText(eq("ou_1"), contains("已转人工审核（工单 inst-123"));
        // 起了工单就不再走对话
        verifyNoInteractions(conversation);
    }

    @Test
    void intentRoutingOn_refundKeyword_autoCompleted_repliesWithFlowReply() {
        HttpConversationClient conversation = mock(HttpConversationClient.class);
        HttpFeishuReplyClient reply = mock(HttpFeishuReplyClient.class);
        HttpWorkflowClient workflow = mock(HttpWorkflowClient.class);
        when(workflow.startRefund(any(), any(), any()))
                .thenReturn(new HttpWorkflowClient.StartResult("inst-9", HttpWorkflowClient.STATUS_COMPLETED, "已为您自动办理退款。"));
        FeishuMessageBridge bridge = new FeishuMessageBridge(conversation, reply, workflow, Runnable::run, routingProps());

        bridge.handle(msg("om_r3", "申请退货"));

        verify(reply).replyText(eq("ou_1"), eq("已为您自动办理退款。"));
        verifyNoInteractions(conversation);
    }

    @Test
    void intentRoutingOn_nonKeyword_chats() {
        HttpConversationClient conversation = mock(HttpConversationClient.class);
        HttpFeishuReplyClient reply = mock(HttpFeishuReplyClient.class);
        HttpWorkflowClient workflow = mock(HttpWorkflowClient.class);
        when(conversation.chat(any(), any())).thenReturn("你好");
        FeishuMessageBridge bridge = new FeishuMessageBridge(conversation, reply, workflow, Runnable::run, routingProps());

        bridge.handle(msg("om_c1", "今天天气怎么样"));

        verify(conversation).chat(eq("今天天气怎么样"), eq("ou_1"));
        verifyNoInteractions(workflow);
    }

    @Test
    void intentRoutingOn_workflowFails_fallsBackToChat() {
        HttpConversationClient conversation = mock(HttpConversationClient.class);
        HttpFeishuReplyClient reply = mock(HttpFeishuReplyClient.class);
        HttpWorkflowClient workflow = mock(HttpWorkflowClient.class);
        when(workflow.startRefund(any(), any(), any())).thenThrow(new RuntimeException("workflow down"));
        when(conversation.chat(any(), any())).thenReturn("我先帮您登记一下");
        FeishuMessageBridge bridge = new FeishuMessageBridge(conversation, reply, workflow, Runnable::run, routingProps());

        bridge.handle(msg("om_r4", "投诉"));

        // 起单失败降级为普通对话，不因工作流不可用而丢消息
        verify(conversation).chat(eq("投诉"), eq("ou_1"));
        verify(reply).replyText(eq("ou_1"), eq("我先帮您登记一下"));
    }
}
