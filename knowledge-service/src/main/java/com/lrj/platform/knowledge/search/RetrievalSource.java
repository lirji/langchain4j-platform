package com.lrj.platform.knowledge.search;

import java.util.List;

/**
 * 检索源 SPI（阶段1，es-hybrid-rerank）。向量、内存关键词、ES 全文、图谱各实现一份。
 * {@link com.lrj.platform.knowledge.KnowledgeQueryService} 作为编排器，收集所有 {@link #enabled()} 源的命中，
 * 交给 {@link HybridFusionService} 融合。
 *
 * <p>顺序敏感：{@code weighted_max} 融合下命中按源列表顺序合并（向量→关键词→ES→图谱），
 * 以复刻现有 LinkedHashMap 合并语义；RRF 融合与顺序无关。
 */
public interface RetrievalSource {

    /** 源名，用于日志与去重前缀，如 vector/keyword/es/graph。 */
    String name();

    /** 是否启用；关闭时编排器跳过，不调用 {@link #retrieve}。 */
    boolean enabled();

    /** 召回候选（已按各自权重打分、按源内相关性降序）。实现必须按 {@code request.tenantId()} 隔离。 */
    List<RetrievalHit> retrieve(RetrievalRequest request);
}
