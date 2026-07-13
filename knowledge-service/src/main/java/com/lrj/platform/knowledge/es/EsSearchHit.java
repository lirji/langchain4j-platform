package com.lrj.platform.knowledge.es;

/**
 * ES 全文检索返回的一条命中（阶段3，es-hybrid-rerank）。{@code score} 为原始 BM25 分（无上界），
 * 归一/加权由 {@code EsKeywordRetrievalSource} 处理。
 */
public record EsSearchHit(
        String docId,
        String displayName,
        String category,
        String index,
        String version,
        String text,
        double score) {
}
