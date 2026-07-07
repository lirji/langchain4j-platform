package com.lrj.platform.agent.critique;

/**
 * Critique 三维打分的加权聚合——DAG replan 与 reflexion 自省环共用的唯一实现。
 *
 * <p>抽出前，{@code AgentDagService} 与单体 {@code ReflexiveService} 各有一份完全相同的加权算法。
 * 收敛到这里后两处同吃，避免行为漂移。
 */
public final class CritiqueAggregation {

    private CritiqueAggregation() {
    }

    /**
     * 按权重加权平均三个维度分。
     *
     * <p>权重总和 &le; 0（配置异常）时退化为等权平均，避免除零 / NaN。
     *
     * @param weights      三维权重（不需归一化）
     * @param correctness  正确性分 0.0-1.0
     * @param completeness 完整性分 0.0-1.0
     * @param clarity      清晰度分 0.0-1.0
     * @return 加权聚合分 0.0-1.0
     */
    public static double aggregate(CritiqueWeights weights,
                                   double correctness,
                                   double completeness,
                                   double clarity) {
        double wc = weights.getCorrectness();
        double wp = weights.getCompleteness();
        double wl = weights.getClarity();
        double sum = wc + wp + wl;
        if (sum <= 0) {
            return (correctness + completeness + clarity) / 3.0;
        }
        return (wc * correctness + wp * completeness + wl * clarity) / sum;
    }
}
