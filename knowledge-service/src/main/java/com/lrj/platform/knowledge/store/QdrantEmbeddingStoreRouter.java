package com.lrj.platform.knowledge.store;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 每租户一个 Qdrant collection（名 {@code <base>_<tenantId>}）的强隔离路由器。
 *
 * <p>惰性建 collection（幂等）+ 维度守卫：若目标 collection 已按别的维度存在则 fail-fast。
 * 具体 Qdrant 交互委托给 {@link QdrantCollectionManager}（可注入 fake 做单测）。
 */
public class QdrantEmbeddingStoreRouter implements EmbeddingStoreRouter {

    private final QdrantCollectionManager manager;
    private final String baseCollection;
    private final ConcurrentMap<String, EmbeddingStore<TextSegment>> cache = new ConcurrentHashMap<>();

    public QdrantEmbeddingStoreRouter(QdrantCollectionManager manager, String baseCollection) {
        this.manager = manager;
        this.baseCollection = baseCollection == null || baseCollection.isBlank() ? "knowledge_segments" : baseCollection;
    }

    @Override
    public EmbeddingStore<TextSegment> forTenant(String tenantId, int dimension) {
        String collection = collectionName(baseCollection, tenantId);
        return cache.computeIfAbsent(collection, c -> {
            OptionalInt existing = manager.existingDimension(c);
            if (existing.isPresent()) {
                if (dimension > 0 && existing.getAsInt() != dimension) {
                    throw new DimensionMismatchException(c, existing.getAsInt(), dimension);
                }
            } else {
                manager.ensureCollection(c, dimension);
            }
            return manager.buildStore(c);
        });
    }

    /** Qdrant collection 命名：base + 归一化后的租户 id（仅保留 [a-zA-Z0-9_-]）。 */
    static String collectionName(String base, String tenantId) {
        String safe = tenantId == null || tenantId.isBlank()
                ? "default"
                : tenantId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return base + "_" + safe;
    }
}
