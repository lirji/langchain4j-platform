package com.lrj.platform.knowledge.search;

/**
 * 融合策略（阶段2，es-hybrid-rerank）：
 * <ul>
 *   <li>{@code WEIGHTED_MAX} —— 同 chunk 跨源取加权分 max（复刻现状；ES 关闭默认）；
 *   <li>{@code RRF} —— 倒数排名融合，只看名次、免疫 BM25/余弦量纲差（ES 开启默认）。
 * </ul>
 */
public enum FusionStrategy {
    WEIGHTED_MAX,
    RRF;

    public static FusionStrategy parse(String value, FusionStrategy fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.trim().toLowerCase()) {
            case "rrf" -> RRF;
            case "weighted_max", "weighted-max", "max" -> WEIGHTED_MAX;
            default -> fallback;
        };
    }

    /**
     * 有效默认（es-hybrid-rerank #5 修复）：显式配置优先；未配置时——只有 ES **真正参与查询**（enabled 且 query-enabled）
     * 才翻成 RRF，否则保持 WEIGHTED_MAX。这样"只开写不查"的灰度阶段不会改变现有 vector+keyword 排序。
     */
    public static FusionStrategy effectiveDefault(String configured, boolean esEnabled, boolean esQueryEnabled) {
        boolean esQueryActive = esEnabled && esQueryEnabled;
        return parse(configured, esQueryActive ? RRF : WEIGHTED_MAX);
    }
}
