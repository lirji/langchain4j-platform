package com.lrj.platform.channel.dingtalk;

import com.lrj.platform.protocol.knowledge.KnowledgeHit;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;
import com.lrj.platform.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 钉钉入站消息桥：客服在群里 @机器人 提问 → 查知识库 → 机器人在群里回复。
 *
 * <p>控制器需在 3s 内 ack 钉钉，而查库 + {@code /chat} 走 LLM 可能更久，故本桥<b>异步</b>处理。
 * 处理链：按 {@code msgId} 去重（钉钉会重投）→ 设 {@link TenantContext}（钉钉应用配置的租户）→
 * <b>兜底闸门</b>：先查 knowledge {@code /rag/query}，命中不足则发转人工话术 + @人工客服、<b>不调 LLM</b>；
 * 命中充分才调 conversation {@code /chat}（内部透明 RAG + LLM，命中相同知识块）→ 经 {@link HttpDingtalkReplyClient} 回复。
 *
 * <p><b>骨架说明</b>：去重目前是进程内 best-effort；生产多副本需换 Redis/JDBC 去重。回复需钉钉应用凭据，
 * 凭据未配时只收不回，便于先验证入站链路。
 */
public class DingtalkMessageBridge {

    private static final Logger log = LoggerFactory.getLogger(DingtalkMessageBridge.class);

    private final DingtalkConversationClient conversation;
    private final DingtalkKnowledgeClient knowledge;
    private final HttpDingtalkReplyClient reply;
    private final Executor executor;
    private final DingtalkProperties props;
    private final ConcurrentHashMap<String, Boolean> seen = new ConcurrentHashMap<>();

    public DingtalkMessageBridge(DingtalkConversationClient conversation,
                                 DingtalkKnowledgeClient knowledge,
                                 HttpDingtalkReplyClient reply,
                                 Executor dingtalkBridgeExecutor,
                                 DingtalkProperties props) {
        this.conversation = conversation;
        this.knowledge = knowledge;
        this.reply = reply;
        this.executor = dingtalkBridgeExecutor;
        this.props = props;
    }

    /** 异步处理一条入站消息（控制器已 ack）。 */
    public void handle(DingtalkInboundMessage msg) {
        if (msg == null || msg.text() == null || msg.text().isBlank()) {
            return;
        }
        if (msg.msgId() != null && seen.putIfAbsent(msg.msgId(), Boolean.TRUE) != null) {
            log.debug("dingtalk message deduplicated msgId={}", msg.msgId());
            return;
        }
        executor.execute(() -> process(msg));
    }

    /** 同步处理（抽出便于单测：不经 executor 直接跑）。 */
    void process(DingtalkInboundMessage msg) {
        TenantContext.Tenant prev = TenantContext.captureRaw();
        try {
            TenantContext.set(new TenantContext.Tenant(
                    props.getTenantId(), "dingtalk:" + nz(msg.senderStaffId()), Set.of("chat")));

            // 兜底闸门：命中不足 → 转人工，不调 LLM
            if (strongHits(msg.text()) < props.getFallback().getMinHits()) {
                log.debug("dingtalk knowledge gate: insufficient hits → handoff, conversationId={}", msg.conversationId());
                reply.replyAtUsers(msg.conversationId(), props.getFallback().getMessage(),
                        props.getFallback().getHumanAgentIds());
                return;
            }

            // 命中充分 → 正常作答（/chat 内部透明 RAG + LLM）
            String answer = conversation.chat(msg.text(), nz(msg.senderStaffId()));
            if (answer != null && !answer.isBlank()) {
                reply.replyText(msg.conversationId(), answer);
            }
        } catch (Exception e) {
            log.warn("dingtalk bridge process failed msgId={}: {}", msg.msgId(), e.toString());
        } finally {
            if (prev != null) TenantContext.set(prev); else TenantContext.clear();
        }
    }

    /** 查知识库并统计强命中数（text 非空且 score ≥ minScore）。 */
    private long strongHits(String question) {
        double minScore = props.getFallback().getMinScore();
        KnowledgeQueryReply kb = knowledge.query(new KnowledgeQueryRequest(
                question, props.getRagTopK(), minScore, props.ragCategoryOrNull()));
        return kb.hits().stream()
                .filter(h -> h.text() != null && !h.text().isBlank())
                .filter(h -> score(h) >= minScore)
                .count();
    }

    private static double score(KnowledgeHit hit) {
        return hit.score() == null ? 0.0 : hit.score();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
