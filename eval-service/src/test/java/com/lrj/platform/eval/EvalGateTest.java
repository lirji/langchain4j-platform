package com.lrj.platform.eval;

import com.lrj.platform.protocol.eval.EvalGateResult;
import com.lrj.platform.protocol.eval.EvalGateTolerances;
import com.lrj.platform.protocol.eval.EvalTargetSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EvalGate} 纯函数门禁判定：passRate 跌 / averageScore 跌 / agreement 低 / 容差边界。
 * 全部用内存聚合驱动，不起单体/网关。
 */
class EvalGateTest {

    private static final EvalGateTolerances TOL = new EvalGateTolerances(0.05D, 0.05D, 0.6D, 1);

    private static EvalTargetSummary summary(String name, double passRate, double averageScore) {
        return new EvalTargetSummary(name, "http://" + name, 4, 1, passRate, averageScore, List.of());
    }

    @Test
    void passesWhenCandidateMatchesOracle() {
        EvalGateResult result = EvalGate.evaluate(
                summary("candidate", 1.0D, 0.9D), summary("oracle", 1.0D, 0.9D), 0.95D, TOL);

        assertThat(result.passed()).isTrue();
        assertThat(result.regressions()).isEmpty();
        assertThat(result.agreement()).isEqualTo(0.95D);
    }

    @Test
    void passesWhenCandidateWithinTolerance() {
        // candidate 比 oracle 低 0.05（正好等于容差）→ 边界通过。
        EvalGateResult result = EvalGate.evaluate(
                summary("candidate", 0.95D, 0.85D), summary("oracle", 1.0D, 0.9D), 0.7D, TOL);

        assertThat(result.passed()).isTrue();
        assertThat(result.regressions()).isEmpty();
    }

    @Test
    void failsWhenPassRateDropsBeyondTolerance() {
        EvalGateResult result = EvalGate.evaluate(
                summary("candidate", 0.80D, 0.9D), summary("oracle", 1.0D, 0.9D), 0.9D, TOL);

        assertThat(result.passed()).isFalse();
        assertThat(result.regressions()).anyMatch(r -> r.contains("passRate regression"));
    }

    @Test
    void failsWhenAverageScoreDropsBeyondTolerance() {
        EvalGateResult result = EvalGate.evaluate(
                summary("candidate", 1.0D, 0.70D), summary("oracle", 1.0D, 0.9D), 0.9D, TOL);

        assertThat(result.passed()).isFalse();
        assertThat(result.regressions()).anyMatch(r -> r.contains("averageScore regression"));
    }

    @Test
    void failsWhenAgreementBelowThreshold() {
        EvalGateResult result = EvalGate.evaluate(
                summary("candidate", 1.0D, 0.9D), summary("oracle", 1.0D, 0.9D), 0.5D, TOL);

        assertThat(result.passed()).isFalse();
        assertThat(result.regressions()).anyMatch(r -> r.contains("agreement below threshold"));
    }

    @Test
    void agreementBoundaryExactlyAtThresholdPasses() {
        EvalGateResult result = EvalGate.evaluate(
                summary("candidate", 1.0D, 0.9D), summary("oracle", 1.0D, 0.9D), 0.6D, TOL);

        assertThat(result.passed()).isTrue();
    }

    @Test
    void reportsAllThreeRegressionsAtOnce() {
        EvalGateResult result = EvalGate.evaluate(
                summary("candidate", 0.2D, 0.1D), summary("oracle", 1.0D, 0.9D), 0.1D, TOL);

        assertThat(result.passed()).isFalse();
        assertThat(result.regressions()).hasSize(3);
    }

    @Test
    void floatingPointNoiseDoesNotTripEpsilonGuard() {
        // candidate 仅比容差线低 1e-9（远小于 EPS）→ 不算回归。
        EvalGateResult result = EvalGate.evaluate(
                summary("candidate", 0.95D - 1e-9D, 0.9D), summary("oracle", 1.0D, 0.9D), 0.9D, TOL);

        assertThat(result.passed()).isTrue();
    }
}
