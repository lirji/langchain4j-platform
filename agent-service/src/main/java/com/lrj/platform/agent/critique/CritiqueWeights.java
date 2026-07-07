package com.lrj.platform.agent.critique;

/**
 * 三个正交评分维度（correctness / completeness / clarity）的加权聚合权重。
 *
 * <p>可变 JavaBean 风格，便于 {@code @ConfigurationProperties} 嵌套绑定。DAG 的 replan 阈值判定
 * （{@code app.agent.dag.replan.weights.*}）与 reflexion 自省环（{@code app.agent.reflexion.weights.*}）
 * 共吃同一份权重语义 + {@link CritiqueAggregation} 聚合算法，消除两处重复。
 *
 * <p>默认沿用 DAG 的历史取值（正确性 0.5 / 完整性 0.35 / 清晰度 0.15）。reflexion 在自己的
 * properties 里另设默认（正确/完整各 0.4、清晰 0.2），与单体对齐。权重<strong>不需要归一化</strong>，
 * {@link CritiqueAggregation} 内部按总和作分母处理。
 */
public class CritiqueWeights {

    private double correctness = 0.5;
    private double completeness = 0.35;
    private double clarity = 0.15;

    public CritiqueWeights() {
    }

    public CritiqueWeights(double correctness, double completeness, double clarity) {
        this.correctness = correctness;
        this.completeness = completeness;
        this.clarity = clarity;
    }

    public double getCorrectness() {
        return correctness;
    }

    public void setCorrectness(double correctness) {
        this.correctness = correctness;
    }

    public double getCompleteness() {
        return completeness;
    }

    public void setCompleteness(double completeness) {
        this.completeness = completeness;
    }

    public double getClarity() {
        return clarity;
    }

    public void setClarity(double clarity) {
        this.clarity = clarity;
    }
}
