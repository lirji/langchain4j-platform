package com.lrj.platform.conversation.cache;

/**
 * 语义缓存相似度检索的最近邻结果：命中的原始问题、对应缓存回复，以及与查询向量的相似度分。
 *
 * <p>是否算「命中」由 {@link SemanticCache} 用阈值判定，store 只负责返回租户桶内的最近邻。
 */
public record SemanticCacheHit(String question, String reply, double score) {
}
