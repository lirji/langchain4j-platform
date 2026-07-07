package com.lrj.platform.conversation.cache;

import com.lrj.platform.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * L1 语义缓存（应用侧 / 问题级 / 租户桶 / pre-RAG）编排器。
 *
 * <p>放在 {@code /chat} 的 RAG+LLM 之前：用 embedding 把用户原始问题向量化，在当前租户桶里做相似度检索，
 * 命中（余弦 ≥ 阈值）则直接返回缓存回复、短路后续 embedding/检索/组装/LLM；未命中则跑正常流程并回填缓存。
 *
 * <p>默认关闭（{@code app.conversation.semantic-cache.enabled=false} / {@code CONVERSATION_SEMANTIC_CACHE_ENABLED}）。
 * 关闭时 {@link #getOrCompute} 直接执行原流程，对现有行为零影响。租户隔离由 {@link SemanticCacheStore} 按
 * {@link TenantContext} 的 tenantId 分桶保证。
 */
@Component
public class SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);

    private final SemanticCacheEmbedder embedder;
    private final SemanticCacheStore store;
    private final boolean enabled;
    private final double threshold;

    public SemanticCache(SemanticCacheEmbedder embedder,
                         SemanticCacheStore store,
                         @Value("${app.conversation.semantic-cache.enabled:false}") boolean enabled,
                         @Value("${app.conversation.semantic-cache.threshold:0.95}") double threshold) {
        this.embedder = embedder;
        this.store = store;
        this.enabled = enabled;
        this.threshold = threshold;
        if (enabled) {
            log.info("Semantic cache L1: enabled (threshold={})", threshold);
        }
    }

    /**
     * 语义缓存主入口：命中返回缓存回复并短路 {@code supplier}；未命中执行 {@code supplier} 并回填缓存。
     * 关闭或问题为空时直接执行 {@code supplier}，行为与无缓存完全一致。
     *
     * @param question 用户原始问题（pre-RAG，未经上下文增强）
     * @param supplier 未命中时的正常回复计算（RAG 增强 + LLM）
     */
    public String getOrCompute(String question, Supplier<String> supplier) {
        if (!enabled || question == null || question.isBlank()) {
            return supplier.get();
        }
        String tenantId = TenantContext.current().tenantId();
        float[] vector = embedder.embed(question);

        Optional<SemanticCacheHit> hit = store.findNearest(tenantId, vector)
                .filter(h -> h.score() >= threshold);
        if (hit.isPresent()) {
            log.debug("semantic cache hit tenant={} score={}", tenantId, hit.get().score());
            return hit.get().reply();
        }

        String reply = supplier.get();
        if (reply != null && !reply.isBlank()) {
            store.put(tenantId, new SemanticCacheEntry(question, vector, reply));
        }
        return reply;
    }

    /** 清空某租户整个缓存桶（如该租户知识库整体更新），返回清除条目数。 */
    public int invalidateTenant(String tenantId) {
        return store.invalidateTenant(tenantId);
    }

    /** 定向失效某租户下某个原始问题；命中并删除返回 true。 */
    public boolean invalidate(String tenantId, String question) {
        return store.invalidateQuestion(tenantId, question);
    }

    public boolean isEnabled() {
        return enabled;
    }
}
