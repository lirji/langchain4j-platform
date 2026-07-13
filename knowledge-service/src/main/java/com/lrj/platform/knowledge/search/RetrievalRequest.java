package com.lrj.platform.knowledge.search;

import java.util.List;

/**
 * 一次检索的统一入参（阶段1，es-hybrid-rerank）。各 {@link RetrievalSource} 从中取所需字段：
 * 向量源用全部 {@code variants}（查询扩展后的多变体）多路召回；关键词 / ES 源用原始 {@code query}。
 *
 * @param query          原始查询串（非空）
 * @param variants       查询 + 扩展变体（至少含原始 query）；关闭扩展时只有一个元素
 * @param tenantId       当前租户，所有源必须据此隔离
 * @param category       可选 category 过滤，null/blank 表示不过滤
 * @param limit          候选池上限（= topK × rerank 放大倍数）
 * @param minScore       向量最低分阈值（仅向量源使用；关键词/图谱/ES 不适用）
 * @param publicTenantId 公共/共享库保留租户 id；非 null 时各源在隔离查 {@code tenantId} 的基础上
 *                       并入该公共分区的命中（读取不破坏隔离）。null 表示不并公共库（默认行为）。
 */
public record RetrievalRequest(
        String query,
        List<String> variants,
        String tenantId,
        String category,
        int limit,
        double minScore,
        String publicTenantId) {

    /** 向后兼容：不并公共库的 6 参构造（既有调用点与测试保持不变）。 */
    public RetrievalRequest(String query, List<String> variants, String tenantId,
                            String category, int limit, double minScore) {
        this(query, variants, tenantId, category, limit, minScore, null);
    }
}
