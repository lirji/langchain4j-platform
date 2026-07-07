package com.lrj.platform.agent.voting;

/**
 * {@code app.agent.voting.*} 绑定（可变 JavaBean 风格，沿用平台 {@code AgentDagProperties}）。
 *
 * <p><strong>注意 {@code majority} 语义边界</strong>：多数表决对<strong>自由文本</strong>几乎必然不达标
 * （每次措辞不同 → 归一化后仍各自成派 → agreement≈1/n），仅适用于<strong>离散/分类题</strong>
 * （是否合规、情感极性、单选标签等，答案取值域小）。自由文本题应改用 {@code synthesis} 策略。
 * 沿用单体约束。
 */
public class VotingProperties {

    public enum Strategy { MAJORITY, SYNTHESIS }

    /** 并行投票次数。 */
    private int n = 3;
    /** 聚合策略：majority（确定性多数表决，仅离散/分类题）| synthesis（聚合器 LLM 收口，自由文本题）。 */
    private Strategy strategy = Strategy.MAJORITY;
    /** majority 策略的置信阈值：胜出票占比 ≥ 此值才 confident。 */
    private double minAgreement = 0.5;

    public int getN() {
        return n;
    }

    public void setN(int n) {
        this.n = n;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    public void setStrategy(Strategy strategy) {
        this.strategy = strategy;
    }

    public double getMinAgreement() {
        return minAgreement;
    }

    public void setMinAgreement(double minAgreement) {
        this.minAgreement = minAgreement;
    }
}
