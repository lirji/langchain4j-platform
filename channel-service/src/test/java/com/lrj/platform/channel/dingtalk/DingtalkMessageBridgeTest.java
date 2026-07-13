package com.lrj.platform.channel.dingtalk;

import com.lrj.platform.protocol.knowledge.KnowledgeHit;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DingtalkMessageBridgeTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private DingtalkProperties props() {
        DingtalkProperties p = new DingtalkProperties();
        p.setTenantId("acme");
        p.getFallback().setMinHits(1);
        p.getFallback().setMinScore(0.5);
        p.getFallback().setHumanAgentIds(List.of("agent_1"));
        return p;
    }

    private static DingtalkInboundMessage msg(String id, String text) {
        return new DingtalkInboundMessage(id, "cid_2", "staff_1", "客服A", text);
    }

    private static KnowledgeHit hit(double score, String text) {
        return new KnowledgeHit("id", score, "doc", "guide.md", "客服", "0", text, "hybrid", "tenant");
    }

    private static KnowledgeQueryReply reply(KnowledgeHit... hits) {
        return new KnowledgeQueryReply("q", "acme", List.of(hits));
    }

    @Test
    void hit_callsChatThenReply() {
        DingtalkConversationClient conversation = mock(DingtalkConversationClient.class);
        DingtalkKnowledgeClient knowledge = mock(DingtalkKnowledgeClient.class);
        HttpDingtalkReplyClient reply = mock(HttpDingtalkReplyClient.class);
        when(knowledge.query(any())).thenReturn(reply(hit(0.9, "退款需主管审批")));
        when(conversation.chat(eq("退款怎么审批？"), eq("staff_1"))).thenReturn("需主管审批，3 个工作日到账");
        DingtalkMessageBridge bridge = new DingtalkMessageBridge(
                conversation, knowledge, reply, Runnable::run, props());

        bridge.handle(msg("m_1", "退款怎么审批？"));

        verify(conversation).chat(eq("退款怎么审批？"), eq("staff_1"));
        verify(reply).replyText(eq("cid_2"), eq("需主管审批，3 个工作日到账"));
        verify(reply, never()).replyAtUsers(any(), any(), any());
    }

    @Test
    void noHit_handoffToHuman_withoutCallingChat() {
        DingtalkConversationClient conversation = mock(DingtalkConversationClient.class);
        DingtalkKnowledgeClient knowledge = mock(DingtalkKnowledgeClient.class);
        HttpDingtalkReplyClient reply = mock(HttpDingtalkReplyClient.class);
        when(knowledge.query(any())).thenReturn(reply()); // 空命中
        DingtalkMessageBridge bridge = new DingtalkMessageBridge(
                conversation, knowledge, reply, Runnable::run, props());

        bridge.handle(msg("m_2", "今天午饭吃什么？"));

        verify(reply).replyAtUsers(eq("cid_2"),
                eq("知识库暂未收录该问题，已为您转接人工客服，请稍候。"),
                eq(List.of("agent_1")));
        verify(conversation, never()).chat(any(), any());
        verify(reply, never()).replyText(any(), any());
    }

    @Test
    void weakHitBelowMinScore_handoff() {
        DingtalkConversationClient conversation = mock(DingtalkConversationClient.class);
        DingtalkKnowledgeClient knowledge = mock(DingtalkKnowledgeClient.class);
        HttpDingtalkReplyClient reply = mock(HttpDingtalkReplyClient.class);
        when(knowledge.query(any())).thenReturn(reply(hit(0.3, "勉强相关"))); // 分数 < minScore 0.5
        DingtalkMessageBridge bridge = new DingtalkMessageBridge(
                conversation, knowledge, reply, Runnable::run, props());

        bridge.handle(msg("m_3", "边缘问题"));

        verify(reply).replyAtUsers(any(), any(), any());
        verify(conversation, never()).chat(any(), any());
    }

    @Test
    void deduplicatesSameMsgId() {
        DingtalkConversationClient conversation = mock(DingtalkConversationClient.class);
        DingtalkKnowledgeClient knowledge = mock(DingtalkKnowledgeClient.class);
        HttpDingtalkReplyClient reply = mock(HttpDingtalkReplyClient.class);
        when(knowledge.query(any())).thenReturn(reply(hit(0.9, "命中")));
        when(conversation.chat(any(), any())).thenReturn("r");
        DingtalkMessageBridge bridge = new DingtalkMessageBridge(
                conversation, knowledge, reply, Runnable::run, props());

        bridge.handle(msg("m_dup", "hi"));
        bridge.handle(msg("m_dup", "hi"));

        verify(knowledge, times(1)).query(any());
    }

    @Test
    void blankText_noOp() {
        DingtalkConversationClient conversation = mock(DingtalkConversationClient.class);
        DingtalkKnowledgeClient knowledge = mock(DingtalkKnowledgeClient.class);
        HttpDingtalkReplyClient reply = mock(HttpDingtalkReplyClient.class);
        DingtalkMessageBridge bridge = new DingtalkMessageBridge(
                conversation, knowledge, reply, Runnable::run, props());

        bridge.handle(msg("m_4", "   "));

        verify(knowledge, never()).query(any());
        verify(conversation, never()).chat(any(), any());
    }

    @Test
    void hitButEmptyLlmReply_skipsReplyCall() {
        DingtalkConversationClient conversation = mock(DingtalkConversationClient.class);
        DingtalkKnowledgeClient knowledge = mock(DingtalkKnowledgeClient.class);
        HttpDingtalkReplyClient reply = mock(HttpDingtalkReplyClient.class);
        when(knowledge.query(any())).thenReturn(reply(hit(0.9, "命中")));
        when(conversation.chat(any(), any())).thenReturn("");
        DingtalkMessageBridge bridge = new DingtalkMessageBridge(
                conversation, knowledge, reply, Runnable::run, props());

        bridge.handle(msg("m_5", "hi"));

        verify(reply, never()).replyText(any(), any());
    }
}
