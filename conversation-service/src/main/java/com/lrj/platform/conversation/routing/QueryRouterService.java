package com.lrj.platform.conversation.routing;

import com.lrj.platform.conversation.Assistant;
import com.lrj.platform.conversation.RagPromptAugmenter;
import com.lrj.platform.conversation.prompt.ResolvedAssistantStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM-as-Router（迁移单体 {@code QueryRouterService}）：classify → dispatch → answer 三段。
 * <ul>
 *   <li>{@code RAG} → 走 {@link RagPromptAugmenter} 注入知识来源的完整对话；</li>
 *   <li>{@code TOOL}/{@code CHAT} → 裸对话（空 context，不检索）；</li>
 *   <li>分类器异常 → 降级 {@code RAG}（保守：宁可多检索一次）。</li>
 * </ul>
 */
public class QueryRouterService {

    private static final Logger log = LoggerFactory.getLogger(QueryRouterService.class);

    private final QueryClassifier classifier;
    private final Assistant assistant;
    private final RagPromptAugmenter ragPromptAugmenter;
    private final ResolvedAssistantStyle style;

    public QueryRouterService(QueryClassifier classifier, Assistant assistant,
                              RagPromptAugmenter ragPromptAugmenter, ResolvedAssistantStyle style) {
        this.classifier = classifier;
        this.assistant = assistant;
        this.ragPromptAugmenter = ragPromptAugmenter;
        this.style = style;
    }

    public RoutedReply route(String memoryKey, String message) {
        long t0 = System.nanoTime();
        RouteDecision decision;
        try {
            decision = classifier.classify(message);
        } catch (RuntimeException e) {
            log.warn("classifier error, falling back to RAG: {}", e.toString());
            decision = new RouteDecision(RouteKind.RAG, "classifier error fallback");
        }
        long classifyMs = (System.nanoTime() - t0) / 1_000_000L;

        long t1 = System.nanoTime();
        String context = decision.kind() == RouteKind.RAG ? ragPromptAugmenter.contextFor(message) : "";
        String reply = assistant.chat(memoryKey, style.getLanguage(), style.getTone(),
                style.getCitationPolicy(), style.getExtra(), message, context);
        long answerMs = (System.nanoTime() - t1) / 1_000_000L;

        return new RoutedReply(decision, reply, classifyMs, answerMs);
    }
}
