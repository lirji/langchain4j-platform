package com.lrj.platform.conversation.cache;

/**
 * 把用户原始问题向量化，供 L1 语义缓存在租户桶内做相似度检索。
 *
 * <p>沿用「接口 + {@code @ConditionalOnProperty} 多实现」惯例：
 * 默认 {@link HashSemanticCacheEmbedder}（确定性 hash，零依赖 dev/test），
 * 可选 {@link GatewaySemanticCacheEmbedder}（经 LiteLLM/OpenAI-compatible embedding）。
 */
public interface SemanticCacheEmbedder {

    /** 返回归一化后的向量；文本为空时返回零向量。 */
    float[] embed(String text);
}
