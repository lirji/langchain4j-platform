package com.lrj.platform.conversation;

import com.lrj.platform.conversation.cache.SemanticCache;
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

    public ConversationController(Assistant assistant,
                                  RagPromptAugmenter ragPromptAugmenter,
                                  SemanticCache semanticCache) {
        this.assistant = assistant;
        this.ragPromptAugmenter = ragPromptAugmenter;
        this.semanticCache = semanticCache;
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestParam(value = "chatId", defaultValue = "default") String chatId,
                                    @RequestBody Map<String, String> body) {
        TenantContext.Tenant tenant = TenantContext.current();
        String message = body.getOrDefault("message", "");
        // L1 语义缓存在 RAG+LLM 之前：命中直接返回缓存回复，未命中走原流程并回填。默认关闭时等价于直接调用。
        String reply = semanticCache.getOrCompute(message,
                () -> assistant.chat(ragPromptAugmenter.augment(message)));
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
