package com.lrj.platform.eval;

/**
 * 默认关闭的 judge 实现。未开启 {@code app.eval.judge.enabled} 时装配，保证零依赖 dev/test，
 * 用例即使带 {@code judgeExpected} 也会被 {@link EvalRunner} 跳过（不影响现有确定性断言）。
 */
public class DisabledEvalJudge implements EvalJudge {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public double score(String criteria, String actualResponse) {
        return 0.0D;
    }
}
