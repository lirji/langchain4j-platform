package com.lrj.platform.knowledge.store;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 默认（零外部依赖）路由器：每租户一个独立的 {@link InMemoryEmbeddingStore}，物理隔离。
 *
 * <p>租户 A 的段与租户 B 的段落在不同的 store 实例里，跨租户物理不可见（不再依赖 metadata filter）。
 * 同一租户切换 embedding 维度时 fail-fast。
 */
public class InMemoryEmbeddingStoreRouter implements EmbeddingStoreRouter {

    private static final String DEFAULT_NAMESPACE = "__default__";

    private final ConcurrentMap<String, Entry> stores = new ConcurrentHashMap<>();

    @Override
    public EmbeddingStore<TextSegment> forTenant(String tenantId, int dimension) {
        String key = tenantId == null || tenantId.isBlank() ? DEFAULT_NAMESPACE : tenantId;
        Entry entry = stores.computeIfAbsent(key, k -> new Entry(new InMemoryEmbeddingStore<>(), dimension));
        if (dimension > 0 && entry.dimension > 0 && entry.dimension != dimension) {
            throw new DimensionMismatchException(key, entry.dimension, dimension);
        }
        return entry.store;
    }

    /** 当前已建立的租户 namespace 数量（便于观测/测试）。 */
    public int tenantCount() {
        return stores.size();
    }

    private record Entry(EmbeddingStore<TextSegment> store, int dimension) {}
}
