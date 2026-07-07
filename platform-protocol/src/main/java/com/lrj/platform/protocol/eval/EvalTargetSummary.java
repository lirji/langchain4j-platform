package com.lrj.platform.protocol.eval;

import java.util.List;

/**
 * 单个双跑目标（oracle 冻结单体 / candidate edge-gateway）在一个 suite 上的聚合结果。
 *
 * <p>{@code passRate} 按「(case, run) 独立 trial」计（passedTrials / (totalCases * runs)），
 * 与单体 {@code EvalResult.Summary.overallPassRate} 语义一致；{@code averageScore} 是逐 case
 * 语义打分（对参考答案的确定性余弦相似度）的均值——与二值 {@code passRate} 互补，反映质量梯度。
 * {@code results} 每个 case 一条代表性结果（多 runs 时取首个 attempt 的响应片段）。
 *
 * <p>作为「冻结 oracle 快照」时，本记录整体被预存进 {@link EvalOracleSnapshot} 的 JSON，
 * PR 模式下只跑 candidate 与之比对。
 */
public record EvalTargetSummary(String name,
                                String baseUrl,
                                int totalCases,
                                int runs,
                                double passRate,
                                double averageScore,
                                List<EvalCaseResult> results) {

    public EvalTargetSummary {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
