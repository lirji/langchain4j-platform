package com.lrj.platform.conversation;

import com.lrj.platform.protocol.knowledge.KnowledgeHit;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RagPromptAugmenter {

    private final KnowledgeClient knowledgeClient;
    private final boolean enabled;
    private final int topK;
    private final Double minScore;
    private final String category;
    private final int maxContextChars;

    public RagPromptAugmenter(KnowledgeClient knowledgeClient,
                              @Value("${app.conversation.rag.enabled:false}") boolean enabled,
                              @Value("${app.conversation.rag.top-k:5}") int topK,
                              @Value("${app.conversation.rag.min-score:0.0}") Double minScore,
                              @Value("${app.conversation.rag.category:}") String category,
                              @Value("${app.conversation.rag.max-context-chars:4000}") int maxContextChars) {
        this.knowledgeClient = knowledgeClient;
        this.enabled = enabled;
        this.topK = Math.max(1, topK);
        this.minScore = minScore;
        this.category = category == null || category.isBlank() ? null : category;
        this.maxContextChars = Math.max(256, maxContextChars);
    }

    /**
     * 老式「上下文 + 用户问题」合并串（保留兼容：把知识来源与问题拼进单条消息）。
     * 新的多轮记忆流程改用 {@link #contextFor(String)}，把来源与用户消息分开。
     */
    public String augment(String message) {
        String block = sourcesBlock(message);
        if (block == null) {
            return message;
        }
        return block + "\n[User question]\n" + message;
    }

    /**
     * 只返回「知识来源」上下文块（不含用户问题），供经 {@code @V("context")} 注入系统提示。
     * 未开启 / 无命中 / 空问题时返回空串——此时对话与无 RAG 完全一致。
     */
    public String contextFor(String message) {
        return contextWithHits(message).context();
    }

    /**
     * 与 {@link #contextFor(String)} 相同的检索，但同时带回真正进入上下文块的结构化命中，
     * 供 grounding 事后校验（F3.5）用答案 vs 来源核对。未开启 / 无命中时 {@code context=""、hits=[]}。
     */
    public RagContext contextWithHits(String message) {
        return contextWithHits(message, null);
    }

    /**
     * 同 {@link #contextWithHits(String)}，但支持 <strong>per-request 类目</strong>：{@code categoryOverride}
     * 非空时限定检索到该 {@code metadata.category} 的文档（对齐单体 {@code /chat/category} 的 ThreadLocal 语义），
     * 否则回退配置默认 {@code app.conversation.rag.category}。knowledge-service 已按 {@code KnowledgeQueryRequest.category}
     * 过滤，故此处只需把有效类目透传下去。
     */
    public RagContext contextWithHits(String message, String categoryOverride) {
        if (!enabled || message == null || message.isBlank()) {
            return RagContext.EMPTY;
        }
        String effectiveCategory = categoryOverride == null || categoryOverride.isBlank() ? category : categoryOverride;
        KnowledgeQueryReply reply = knowledgeClient.query(new KnowledgeQueryRequest(message, topK, minScore, effectiveCategory));
        List<KnowledgeHit> hits = reply.hits().stream()
                .filter(hit -> hit.text() != null && !hit.text().isBlank())
                .toList();
        if (hits.isEmpty()) {
            return RagContext.EMPTY;
        }
        StringBuilder context = new StringBuilder();
        context.append("[Knowledge sources]\n");
        List<KnowledgeHit> included = new java.util.ArrayList<>();
        int used = 0;
        for (KnowledgeHit hit : hits) {
            String sourceId = sourceId(hit);
            String block = "<source id=\"" + sourceId + "\" type=\"" + hit.source() + "\">\n"
                    + hit.text().trim()
                    + "\n</source>\n";
            if (used + block.length() > maxContextChars) {
                break;
            }
            context.append(block);
            included.add(hit);
            used += block.length();
        }
        if (used == 0) {
            return RagContext.EMPTY;
        }
        return new RagContext(context.toString(), List.copyOf(included));
    }

    /**
     * 检索并拼出 {@code [Knowledge sources]} 来源块；未开启 / 空问题 / 无有效命中时返回 {@code null}。
     */
    private String sourcesBlock(String message) {
        RagContext rag = contextWithHits(message);
        return rag.context().isEmpty() ? null : rag.context();
    }

    /**
     * RAG 检索结果：注入系统提示的 {@code context} 文本块 + 真正进入该块的结构化命中 {@code hits}
     * （已按 {@code max-context-chars} 截断）。
     */
    public record RagContext(String context, List<KnowledgeHit> hits) {
        public static final RagContext EMPTY = new RagContext("", List.of());
    }

    /** 来源 id：{@code displayName#index}（与注入上下文块里 {@code <source id=...>} 一致，供 grounding 引用核对复用）。 */
    public static String sourceId(KnowledgeHit hit) {
        String displayName = hit.displayName() == null || hit.displayName().isBlank() ? "doc" : hit.displayName();
        String index = hit.index() == null || hit.index().isBlank() ? "0" : hit.index();
        return displayName + "#" + index;
    }
}
