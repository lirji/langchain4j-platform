package com.lrj.platform.conversation.cache;

/**
 * 语义缓存里的一条记录：用户原始问题、它的向量、以及命中时要回放的缓存回复。
 *
 * <p>不可变 record，可被 Jackson 序列化到 Redis（{@code float[]} / String 都是 Jackson 原生支持）。
 */
public record SemanticCacheEntry(String question, float[] vector, String reply) {
}
