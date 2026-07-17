package com.lrj.platform.conversation.cache;

import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SemanticCacheTest：验证 {@link SemanticCache#getOrCompute} 的命中短路 supplier、未命中回填、相似度阈值命中/未命中、
 * 租户隔离、关闭时旁路不落缓存，以及按租户/按问题失效。
 */
class SemanticCacheTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** 确定性 stub embedder：按问题文本映射到固定向量，用来精确控制相似度。 */
    private static SemanticCacheEmbedder embedder(Map<String, float[]> vectors) {
        return text -> vectors.getOrDefault(text, new float[]{0f, 0f});
    }

    private static void tenant(String tenantId) {
        TenantContext.set(new TenantContext.Tenant(tenantId, "user", Set.of("chat")));
    }

    @Test
    void hitReturnsCachedReplyAndShortCircuitsSupplier() {
        SemanticCacheEmbedder embedder = embedder(Map.of("q", new float[]{1f, 0f}));
        SemanticCache cache = new SemanticCache(embedder, new InMemorySemanticCacheStore(1000), true, 0.9);
        tenant("acme");
        AtomicInteger calls = new AtomicInteger();

        String first = cache.getOrCompute("q", () -> "reply-" + calls.incrementAndGet());
        String second = cache.getOrCompute("q", () -> "reply-" + calls.incrementAndGet());

        assertThat(first).isEqualTo("reply-1");
        assertThat(second).isEqualTo("reply-1");
        assertThat(calls.get()).isEqualTo(1); // 第二次命中缓存，supplier 未再执行
    }

    @Test
    void missBackfillsCacheForSubsequentHit() {
        SemanticCacheEmbedder embedder = embedder(Map.of("q", new float[]{1f, 0f}));
        InMemorySemanticCacheStore store = new InMemorySemanticCacheStore(1000);
        SemanticCache cache = new SemanticCache(embedder, store, true, 0.9);
        tenant("acme");

        cache.getOrCompute("q", () -> "computed");

        assertThat(store.findNearest("acme", new float[]{1f, 0f}))
                .hasValueSatisfying(hit -> assertThat(hit.reply()).isEqualTo("computed"));
    }

    @Test
    void similarQuestionAboveThresholdHits() {
        // 两条向量夹角很小，余弦 ~0.9997 > 0.95
        SemanticCacheEmbedder embedder = embedder(Map.of(
                "退款怎么审批", new float[]{1f, 0f},
                "退款如何审批", new float[]{0.98f, 0.02f}));
        SemanticCache cache = new SemanticCache(embedder, new InMemorySemanticCacheStore(1000), true, 0.95);
        tenant("acme");
        AtomicInteger calls = new AtomicInteger();

        cache.getOrCompute("退款怎么审批", () -> "policy-" + calls.incrementAndGet());
        String second = cache.getOrCompute("退款如何审批", () -> "policy-" + calls.incrementAndGet());

        assertThat(second).isEqualTo("policy-1");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void belowThresholdMissesAndComputesAgain() {
        SemanticCacheEmbedder embedder = embedder(Map.of(
                "a", new float[]{1f, 0f},
                "b", new float[]{0f, 1f})); // 正交，余弦 0 < 0.95
        SemanticCache cache = new SemanticCache(embedder, new InMemorySemanticCacheStore(1000), true, 0.95);
        tenant("acme");
        AtomicInteger calls = new AtomicInteger();

        cache.getOrCompute("a", () -> "reply-" + calls.incrementAndGet());
        String second = cache.getOrCompute("b", () -> "reply-" + calls.incrementAndGet());

        assertThat(second).isEqualTo("reply-2");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void cacheIsIsolatedPerTenant() {
        SemanticCacheEmbedder embedder = embedder(Map.of("q", new float[]{1f, 0f}));
        SemanticCache cache = new SemanticCache(embedder, new InMemorySemanticCacheStore(1000), true, 0.9);
        AtomicInteger calls = new AtomicInteger();

        tenant("acme");
        String acme = cache.getOrCompute("q", () -> "acme-reply-" + calls.incrementAndGet());

        tenant("globex");
        String globex = cache.getOrCompute("q", () -> "globex-reply-" + calls.incrementAndGet());

        assertThat(acme).isEqualTo("acme-reply-1");
        assertThat(globex).isEqualTo("globex-reply-2"); // 另一租户桶里没有，重新计算
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void disabledCacheAlwaysBypassesAndDoesNotStore() {
        InMemorySemanticCacheStore store = new InMemorySemanticCacheStore(1000);
        SemanticCache cache = new SemanticCache(embedder(Map.of("q", new float[]{1f, 0f})), store, false, 0.9);
        tenant("acme");
        AtomicInteger calls = new AtomicInteger();

        String first = cache.getOrCompute("q", () -> "reply-" + calls.incrementAndGet());
        String second = cache.getOrCompute("q", () -> "reply-" + calls.incrementAndGet());

        assertThat(first).isEqualTo("reply-1");
        assertThat(second).isEqualTo("reply-2"); // 关闭时每次都执行 supplier
        assertThat(calls.get()).isEqualTo(2);
        assertThat(store.findNearest("acme", new float[]{1f, 0f})).isEmpty(); // 关闭时不回填
    }

    @Test
    void invalidateTenantClearsBucket() {
        SemanticCacheEmbedder embedder = embedder(Map.of("q", new float[]{1f, 0f}));
        SemanticCache cache = new SemanticCache(embedder, new InMemorySemanticCacheStore(1000), true, 0.9);
        tenant("acme");
        AtomicInteger calls = new AtomicInteger();
        cache.getOrCompute("q", () -> "reply-" + calls.incrementAndGet());

        int removed = cache.invalidateTenant("acme");
        String afterInvalidate = cache.getOrCompute("q", () -> "reply-" + calls.incrementAndGet());

        assertThat(removed).isEqualTo(1);
        assertThat(afterInvalidate).isEqualTo("reply-2"); // 失效后重新计算
    }

    @Test
    void invalidateQuestionRemovesSingleEntry() {
        SemanticCacheEmbedder embedder = embedder(Map.of("q", new float[]{1f, 0f}));
        SemanticCache cache = new SemanticCache(embedder, new InMemorySemanticCacheStore(1000), true, 0.9);
        tenant("acme");
        cache.getOrCompute("q", () -> "cached");

        assertThat(cache.invalidate("acme", "q")).isTrue();
        assertThat(cache.invalidate("acme", "q")).isFalse(); // 已删除
    }
}
