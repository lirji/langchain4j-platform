package com.lrj.platform.conversation;

import com.lrj.platform.conversation.cache.SemanticCache;
import com.lrj.platform.conversation.grounding.GroundingChecker;
import com.lrj.platform.conversation.grounding.GroundingResult;
import com.lrj.platform.conversation.guardrail.ConversationGuardrail;
import com.lrj.platform.conversation.history.HistoryAwareQueryCompressor;
import com.lrj.platform.conversation.prompt.ResolvedAssistantStyle;
import com.lrj.platform.security.TenantContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * /chat 入口。租户身份由 {@code InternalTokenAuthFilter}（platform-security）从内部 JWT 重建，
 * 这里直接读 {@link TenantContext} 即拿到跨网络跳过来的租户 —— 验证租户传播闭环。
 */
@RestController
public class ConversationController {

    private final Assistant assistant;
    private final RagPromptAugmenter ragPromptAugmenter;
    private final SemanticCache semanticCache;
    private final ConversationGuardrail guardrail;
    private final HistoryAwareQueryCompressor historyCompressor;
    private final GroundingChecker groundingChecker;
    private final ResolvedAssistantStyle style;

    public ConversationController(Assistant assistant,
                                  RagPromptAugmenter ragPromptAugmenter,
                                  SemanticCache semanticCache,
                                  ConversationGuardrail guardrail,
                                  HistoryAwareQueryCompressor historyCompressor,
                                  GroundingChecker groundingChecker,
                                  ResolvedAssistantStyle style) {
        this.assistant = assistant;
        this.ragPromptAugmenter = ragPromptAugmenter;
        this.semanticCache = semanticCache;
        this.guardrail = guardrail;
        this.historyCompressor = historyCompressor;
        this.groundingChecker = groundingChecker;
        this.style = style;
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestParam(value = "chatId", defaultValue = "default") String chatId,
                                    @RequestBody Map<String, String> body) {
        TenantContext.Tenant tenant = TenantContext.current();
        String message = body.getOrDefault("message", "");
        // 前置注入护栏：block 档命中直接拒答，不进 RAG/LLM/记忆；sanitize 档剥离控制 token 后继续。
        ConversationGuardrail.InputDecision decision = guardrail.inspectInput(message);
        if (decision.blocked()) {
            return Map.of(
                    "reply", decision.blockReply(),
                    "blocked", true,
                    "reason", decision.reason(),
                    "chatId", chatId,
                    "tenantId", tenant.tenantId(),
                    "userId", tenant.userId());
        }
        String effective = decision.message();
        // per-request 类目（可空）：body 带 category 时把 RAG 检索限定到该 metadata.category 的文档
        // （对齐单体 /chat/category）；不带则回退配置默认 app.conversation.rag.category。
        String category = body.get("category");
        // 记忆按 <tenantId>::<chatId> 隔离：同租户不同会话互不串，跨租户天然隔离（key 前缀不同）。
        String memoryKey = tenant.tenantId() + "::" + chatId;
        // History-aware：用会话历史把追问压缩为自包含检索 query（默认关时直通返回 effective）。
        // 仅用于检索；assistant.chat 仍传 effective，用户原始消息才进多轮记忆。
        String retrievalQuery = historyCompressor.compress(memoryKey, effective);
        // L1 语义缓存在 RAG+LLM 之前：命中直接返回缓存回复（不落记忆），未命中走原流程并回填。默认关闭时等价于直接调用。
        // 检索（含结构化来源）与 grounding、LLM 都在 lambda 内 —— 缓存命中时全部短路。
        // RAG 来源经 context 注入系统提示，用户原始消息才进记忆；grounding warn 模式追加可信度提示后缀；
        // 输出 PII 脱敏在回填缓存之前，保证缓存里存的也是（含 grounding 后缀的）脱敏答案。
        String reply = semanticCache.getOrCompute(effective, () -> {
            RagPromptAugmenter.RagContext rag = ragPromptAugmenter.contextWithHits(retrievalQuery, category);
            String answer = assistant.chat(memoryKey, style.getLanguage(), style.getTone(),
                    style.getCitationPolicy(), style.getExtra(), effective, rag.context());
            GroundingResult grounded = groundingChecker.verify(answer, rag.hits());
            return guardrail.redactOutput(grounded.answer());
        });
        return Map.of(
                "reply", reply,
                "chatId", chatId,
                "tenantId", tenant.tenantId(),
                "userId", tenant.userId());
    }

    /**
     * 失效当前租户的 L1 语义缓存——知识库更新后调用，避免 {@code /chat} 返回缓存里的旧答案。
     * 租户取自内部 JWT（{@link TenantContext}），只能清自己的桶，无法影响别的租户。
     *
     * <p>不带 {@code question}：清空整租户桶（知识库整体更新用）。
     * 带 {@code question}：只失效该原始问题（定向失效）。语义缓存关闭时为 no-op（清 0 条）。
     */
    @DeleteMapping("/chat/cache")
    public Map<String, Object> invalidateCache(
            @RequestParam(value = "question", required = false) String question) {
        String tenantId = TenantContext.current().tenantId();
        if (question != null && !question.isBlank()) {
            boolean removed = semanticCache.invalidate(tenantId, question);
            return Map.of("tenantId", tenantId, "scope", "question", "removed", removed);
        }
        int cleared = semanticCache.invalidateTenant(tenantId);
        return Map.of("tenantId", tenantId, "scope", "tenant", "cleared", cleared);
    }
}
