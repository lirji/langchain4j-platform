package com.lrj.platform.knowledge.store;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.OptionalInt;

/**
 * 封装 Qdrant collection 的探测/建立/绑定，便于 {@link QdrantEmbeddingStoreRouter} 单测（可注入 fake）。
 */
public interface QdrantCollectionManager {

    /** 若 collection 已存在返回其向量维度，否则返回 empty。 */
    OptionalInt existingDimension(String collection);

    /** 幂等建 collection（含 payload 索引）。仅在 {@link #existingDimension} 为 empty 时调用。 */
    void ensureCollection(String collection, int dimension);

    /** 绑定到已存在的 collection，返回可读写的 {@link EmbeddingStore}。 */
    EmbeddingStore<TextSegment> buildStore(String collection);
}
