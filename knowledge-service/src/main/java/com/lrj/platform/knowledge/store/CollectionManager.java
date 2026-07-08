package com.lrj.platform.knowledge.store;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.OptionalInt;

/**
 * 封装某个向量库后端「按名字的 collection/表」的探测、建立与绑定，
 * 便于 {@link ManagedEmbeddingStoreRouter} 对任意后端做统一的 collection-per-tenant 路由（可注入 fake 单测）。
 *
 * <p>各后端（Qdrant/PgVector/Milvus/Chroma/Doris）只需实现本接口，路由/维度守卫逻辑全部复用 router。
 */
public interface CollectionManager {

    /** 若 collection/表 已存在返回其向量维度，否则返回 empty（无外部探测能力时返回 empty，退回惰性建 + 进程内缓存）。 */
    OptionalInt existingDimension(String collection);

    /** 幂等建 collection/表。仅在 {@link #existingDimension} 为 empty 时被 router 调用，且已带上当前 embedding 维度。 */
    void ensureCollection(String collection, int dimension);

    /** 绑定到（已由 {@link #ensureCollection} 建好的）collection/表，返回可读写的 {@link EmbeddingStore}。 */
    EmbeddingStore<TextSegment> buildStore(String collection);
}
