package com.lrj.platform.agent.reflexion;

import com.lrj.platform.agent.critique.CritiqueWeights;

/**
 * {@code app.agent.reflexion.*} 绑定（可变 JavaBean 风格，沿用平台 {@code AgentDagProperties}）。
 *
 * <p>默认权重与单体对齐：正确性 / 完整性各 0.4、清晰度 0.2（优先不出错、其次全面、最后表达）；
 * 聚合算法复用共享 {@code CritiqueAggregation}。
 */
public class ReflexionProperties {

    /** 聚合分达此阈值即收敛停止。 */
    private double threshold = 0.75;

    /** 最大 improve 轮数（总尝试数 = 1 初答 + maxAttempts 次 improve）。 */
    private int maxAttempts = 2;

    /** 三维加权聚合权重（与 DAG 共吃 {@code CritiqueWeights} / {@code CritiqueAggregation}）。 */
    private CritiqueWeights weights = new CritiqueWeights(0.4, 0.4, 0.2);

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public CritiqueWeights getWeights() {
        return weights;
    }

    public void setWeights(CritiqueWeights weights) {
        this.weights = weights;
    }
}
