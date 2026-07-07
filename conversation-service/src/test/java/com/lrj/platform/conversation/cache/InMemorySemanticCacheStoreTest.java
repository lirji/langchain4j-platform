package com.lrj.platform.conversation.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySemanticCacheStoreTest {

    @Test
    void findNearestReturnsHighestCosineWithinTenantBucket() {
        InMemorySemanticCacheStore store = new InMemorySemanticCacheStore(1000);
        store.put("acme", new SemanticCacheEntry("a", new float[]{1f, 0f}, "reply-a"));
        store.put("acme", new SemanticCacheEntry("b", new float[]{0f, 1f}, "reply-b"));

        assertThat(store.findNearest("acme", new float[]{0.9f, 0.1f}))
                .hasValueSatisfying(hit -> {
                    assertThat(hit.reply()).isEqualTo("reply-a");
                    assertThat(hit.score()).isGreaterThan(0.9);
                });
    }

    @Test
    void bucketsAreIsolatedByTenant() {
        InMemorySemanticCacheStore store = new InMemorySemanticCacheStore(1000);
        store.put("acme", new SemanticCacheEntry("q", new float[]{1f, 0f}, "acme-reply"));

        assertThat(store.findNearest("globex", new float[]{1f, 0f})).isEmpty();
        assertThat(store.findNearest("acme", new float[]{1f, 0f}))
                .hasValueSatisfying(hit -> assertThat(hit.reply()).isEqualTo("acme-reply"));
    }

    @Test
    void sameQuestionOverwritesInsteadOfDuplicating() {
        InMemorySemanticCacheStore store = new InMemorySemanticCacheStore(1000);
        store.put("acme", new SemanticCacheEntry("q", new float[]{1f, 0f}, "old"));
        store.put("acme", new SemanticCacheEntry("q", new float[]{1f, 0f}, "new"));

        assertThat(store.findNearest("acme", new float[]{1f, 0f}))
                .hasValueSatisfying(hit -> assertThat(hit.reply()).isEqualTo("new"));
    }

    @Test
    void bucketEvictsEldestBeyondMaxEntries() {
        InMemorySemanticCacheStore store = new InMemorySemanticCacheStore(2);
        store.put("acme", new SemanticCacheEntry("q1", new float[]{1f, 0f}, "r1"));
        store.put("acme", new SemanticCacheEntry("q2", new float[]{0f, 1f}, "r2"));
        store.put("acme", new SemanticCacheEntry("q3", new float[]{-1f, 0f}, "r3"));

        // q1（最旧）被淘汰：用它的向量查询不再精确命中它
        assertThat(store.invalidateQuestion("acme", "q1")).isFalse();
        assertThat(store.invalidateQuestion("acme", "q2")).isTrue();
        assertThat(store.invalidateQuestion("acme", "q3")).isTrue();
    }

    @Test
    void invalidateTenantReturnsRemovedCount() {
        InMemorySemanticCacheStore store = new InMemorySemanticCacheStore(1000);
        store.put("acme", new SemanticCacheEntry("q1", new float[]{1f, 0f}, "r1"));
        store.put("acme", new SemanticCacheEntry("q2", new float[]{0f, 1f}, "r2"));

        assertThat(store.invalidateTenant("acme")).isEqualTo(2);
        assertThat(store.findNearest("acme", new float[]{1f, 0f})).isEmpty();
    }
}
