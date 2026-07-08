package com.lrj.platform.knowledge.store;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 通用「每租户一个 collection/表（名 {@code <base>_<tenantId>}）」强隔离路由器。
 *
 * <p>惰性建 collection（幂等）+ 维度守卫：若目标 collection 已按别的维度存在则 fail-fast。
 * 具体后端交互委托给 {@link CollectionManager}，因此 Qdrant / PgVector / Milvus / Chroma / Doris
 * 共用这一套路由逻辑（各自只提供一个 CollectionManager，可注入 fake 做单测）。
 */
public class ManagedEmbeddingStoreRouter implements EmbeddingStoreRouter {

    private final CollectionManager manager;
    private final String baseCollection;
    private final ConcurrentMap<String, EmbeddingStore<TextSegment>> cache = new ConcurrentHashMap<>();

    public ManagedEmbeddingStoreRouter(CollectionManager manager, String baseCollection) {
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

    /** collection/表 命名：base + 归一化后的租户 id（仅保留 [a-zA-Z0-9_-]，其余替换为下划线）。 */
    static String collectionName(String base, String tenantId) {
        String safe = tenantId == null || tenantId.isBlank()
                ? "default"
                : tenantId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return base + "_" + safe;
    }
}
