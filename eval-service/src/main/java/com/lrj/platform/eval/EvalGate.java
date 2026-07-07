package com.lrj.platform.eval;

import com.lrj.platform.protocol.eval.EvalGateResult;
import com.lrj.platform.protocol.eval.EvalGateTolerances;
import com.lrj.platform.protocol.eval.EvalTargetSummary;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 candidate（edge-gateway）相对 oracle（冻结单体）的双跑聚合对照，判断有没有行为回归。
 * <strong>纯函数、无 IO</strong>，所以能被 JUnit 确定性地测（见 {@code EvalGateTest}）。
 *
 * <p>语义移植自单体 {@code BaselineGate}，但比对基准从「静态基线」换成「同 suite 的 oracle 实测/快照」：
 * <ol>
 *   <li>candidate.passRate 低于 {@code oracle.passRate - passRateTolerance} → 回归</li>
 *   <li>candidate.averageScore 低于 {@code oracle.averageScore - averageScoreTolerance} → 回归</li>
 *   <li>跨目标语义一致性 agreement 低于 {@code minAgreement} → 回归</li>
 * </ol>
 * 全部带 {@link #EPS} 浮点容差，避免 0.7499999 &lt; 0.75 这种假回归。{@code passed = regressions.isEmpty()}。
 */
public final class EvalGate {

    /** 浮点容差：观测值只要不比门槛低于这个量就算过。 */
    public static final double EPS = 1e-6;

    private EvalGate() {
    }

    /**
     * @param candidate  candidate 目标聚合
     * @param oracle     oracle 目标聚合（live 实测或冻结快照）
     * @param agreement  candidate 与 oracle 的跨目标语义一致性（0..1），由 {@link EvalDualRunner} 算出
     * @param tolerances 容差配置
     */
    public static EvalGateResult evaluate(EvalTargetSummary candidate,
                                          EvalTargetSummary oracle,
                                          double agreement,
                                          EvalGateTolerances tolerances) {
        List<String> regressions = new ArrayList<>();

        double passRateFloor = oracle.passRate() - tolerances.passRateTolerance();
        if (candidate.passRate() + EPS < passRateFloor) {
            regressions.add(String.format(
                    "passRate regression: candidate %.4f < oracle %.4f - tolerance %.4f = %.4f",
                    candidate.passRate(), oracle.passRate(), tolerances.passRateTolerance(), passRateFloor));
        }

        double scoreFloor = oracle.averageScore() - tolerances.averageScoreTolerance();
        if (candidate.averageScore() + EPS < scoreFloor) {
            regressions.add(String.format(
                    "averageScore regression: candidate %.4f < oracle %.4f - tolerance %.4f = %.4f",
                    candidate.averageScore(), oracle.averageScore(), tolerances.averageScoreTolerance(), scoreFloor));
        }

        if (agreement + EPS < tolerances.minAgreement()) {
            regressions.add(String.format(
                    "agreement below threshold: %.4f < %.4f",
                    agreement, tolerances.minAgreement()));
        }

        return new EvalGateResult(regressions.isEmpty(), regressions, agreement, candidate, oracle, tolerances);
    }
}
