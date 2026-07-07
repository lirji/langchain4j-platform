package com.lrj.platform.protocol.eval;

import java.util.List;

/**
 * 纯函数门禁判定结果：candidate 相对 oracle 是否回归。
 *
 * <p>{@code passed = regressions.isEmpty()}；{@code regressions} 是人类可读的回归明细
 * （passRate 跌破容差 / averageScore 跌破容差 / agreement 低于阈值）。端点在
 * {@code passed=false} 时返回 HTTP 422 供 CI 门禁 fail。
 */
public record EvalGateResult(boolean passed,
                             List<String> regressions,
                             double agreement,
                             EvalTargetSummary candidate,
                             EvalTargetSummary oracle,
                             EvalGateTolerances tolerances) {

    public EvalGateResult {
        regressions = regressions == null ? List.of() : List.copyOf(regressions);
    }
}
