package com.lrj.platform.eval;

/**
 * Embedding 相似度断言的可插拔边界。
 *
 * <p>沿用平台「接口 + 默认关闭实现 + 由属性开启的变体」惯例：默认
 * {@link DisabledEvalEmbeddingComparator} 关闭，只有在 {@code app.eval.embedding.enabled=true}
 * 时才装配经网关 OpenAI 兼容 embedding 的 {@code GatewayEvalEmbeddingComparator}。
 * 单测通过 mock 本接口覆盖通过/不通过/未配置跳过三条路径，不需要真实 embedding provider。
 */
public interface EvalEmbeddingComparator {

    /** 是否已配置可用；未配置时 {@link EvalRunner} 跳过 embedding 断言。 */
    boolean enabled();

    /**
     * 计算期望文本与实际响应的向量余弦相似度。
     *
     * @param expected 用例里的 {@code embeddingExpected}
     * @param actual   目标服务的实际响应体
     * @return -1.0 到 1.0 之间的余弦相似度
     */
    double similarity(String expected, String actual);
}
