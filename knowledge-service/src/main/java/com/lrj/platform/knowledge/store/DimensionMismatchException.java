package com.lrj.platform.knowledge.store;

/**
 * 维度守卫失败时抛出：某个逻辑 collection 已按维度 X 建立，但当前 EmbeddingModel 产出维度 Y。
 *
 * <p>切换 embedding provider/模型即切换向量维度 —— 在同一个 collection 里混入不同维度的向量会
 * 静默产生坏数据（Qdrant 直接插入失败，in-memory 则相似度计算错乱）。这里 fail-fast 兜住。
 */
public class DimensionMismatchException extends RuntimeException {

    private final String collection;
    private final int existingDimension;
    private final int incomingDimension;

    public DimensionMismatchException(String collection, int existingDimension, int incomingDimension) {
        super("Embedding dimension mismatch for collection '" + collection + "': existing dimension="
                + existingDimension + " but current embedding model produces dimension=" + incomingDimension
                + ". Switching embedding provider/model changes the vector dimension and requires recreating"
                + " the collection (drop + re-ingest) before serving traffic.");
        this.collection = collection;
        this.existingDimension = existingDimension;
        this.incomingDimension = incomingDimension;
    }

    public String collection() {
        return collection;
    }

    public int existingDimension() {
        return existingDimension;
    }

    public int incomingDimension() {
        return incomingDimension;
    }
}
