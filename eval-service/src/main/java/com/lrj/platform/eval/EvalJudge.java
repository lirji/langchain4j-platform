package com.lrj.platform.eval;

/**
 * LLM-judge 断言的可插拔边界。
 *
 * <p>沿用平台「接口 + 默认关闭实现 + 由属性开启的变体」惯例：默认 {@link DisabledEvalJudge}
 * 关闭（{@link #enabled()} 返回 {@code false}），只有在 {@code app.eval.judge.enabled=true}
 * 时才装配走 platform-gateway-client 确定性 {@code ChatModel} 的 {@code LlmEvalJudge}。
 * 单测通过 mock 本接口覆盖通过/不通过/未配置跳过三条路径，不需要真实网关。
 */
public interface EvalJudge {

    /** 是否已配置可用；未配置时 {@link EvalRunner} 跳过 judge 断言。 */
    boolean enabled();

    /**
     * 让判官对响应打分。
     *
     * @param criteria       期望的评判标准 / 参考答案（用例里的 {@code judgeExpected}）
     * @param actualResponse 目标服务的实际响应体
     * @return 0.0（完全不满足）到 1.0（完全满足）之间的分数
     */
    double score(String criteria, String actualResponse);
}
