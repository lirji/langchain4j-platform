package com.lrj.platform.protocol.eval;

/**
 * 双跑门禁的容差配置。candidate 允许比 oracle 低出这些容差而不判回归，用于吸收模型非确定性抖动。
 *
 * @param passRateTolerance     candidate.passRate 允许低于 oracle.passRate 的最大幅度
 * @param averageScoreTolerance candidate.averageScore 允许低于 oracle.averageScore 的最大幅度
 * @param minAgreement          跨目标语义一致性（agreement）的最低阈值，低于即回归
 * @param runs                  每个 case 重复打的次数（>=1），用于平抑 temp>0 抖动
 */
public record EvalGateTolerances(double passRateTolerance,
                                 double averageScoreTolerance,
                                 double minAgreement,
                                 int runs) {

    public EvalGateTolerances {
        runs = Math.max(1, runs);
    }
}
