package com.lrj.platform.protocol.eval;

import java.util.List;

/**
 * 检索质量评测汇总（宏平均，每个 case 权重相同，不经 LLM）。
 *
 * @param avgRecall    宏平均 Recall@k —— 最该盯的召回指标（相关文档被捞回比例）
 * @param avgPrecision 宏平均 Precision@k（召回里有多少相关，反映噪声）
 * @param meanMrr      Mean Reciprocal Rank（首个相关命中排名倒数，反映排序质量）
 * @param hitRate      至少命中一个相关文档的 case 比例（最宽松的可用底线）
 */
public record RetrievalSummary(
        int cases,
        double avgRecall,
        double avgPrecision,
        double meanMrr,
        double hitRate,
        long totalDurationMs,
        List<RetrievalCaseResult> results) {
}
