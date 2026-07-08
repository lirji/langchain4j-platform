package com.lrj.platform.knowledge.rerank;

import com.lrj.platform.knowledge.KnowledgeQueryService.Hit;

import java.util.List;

/**
 * 重排序 SPI（移植单体 {@code ReRankingContentAggregator}）。在混合召回（向量+关键词+图谱）融合后、
 * 截断到 topK 前，对候选按与 query 的相关性重新打分排序，提升最终 topK 的精度。
 *
 * <p>默认 {@link NoopReranker}（不改顺序、直接取前 topK）；开启后 {@link #retrieveMultiplier()} 让上游
 * 多召回一些候选（rerank 才有腾挪空间）。接口 + {@code @ConditionalOnProperty}，默认零依赖。
 */
public interface Reranker {

    /** 召回候选放大倍数：rerank 需要比最终 topK 更大的候选池才有意义。Noop 返回 1。 */
    default int retrieveMultiplier() {
        return 1;
    }

    /**
     * 对已按初始分排序的候选重排，返回前 {@code topK}。入参 {@code candidates} 已由调用方按分数降序。
     */
    List<Hit> rerank(String query, List<Hit> candidates, int topK);
}
