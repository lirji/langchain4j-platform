package com.lrj.platform.conversation.cache;

import java.util.Optional;

/**
 * L1 语义缓存的租户桶存储 + 相似度检索。
 *
 * <p>沿用「接口 + {@code @ConditionalOnProperty} 多实现」惯例：默认 {@link InMemorySemanticCacheStore}，
 * 可选 {@link RedisSemanticCacheStore}。所有读写都以 {@code tenantId} 分桶，天然租户隔离。
 *
 * <p>相似度阈值判定不在 store 内，store 只负责返回租户桶内最近邻；是否算命中由 {@link SemanticCache} 决定。
 * 失效接口分两种粒度：{@link #invalidateTenant} 清空整租户桶（如知识库整体更新），
 * {@link #invalidateQuestion} 精确删除某条问题（如定向失效）。
 */
public interface SemanticCacheStore {

    /** 在租户桶内找与 {@code queryVector} 余弦相似度最高的一条；桶为空返回 empty。 */
    Optional<SemanticCacheHit> findNearest(String tenantId, float[] queryVector);

    /** 回填/更新一条缓存记录（同一原始问题会覆盖）。 */
    void put(String tenantId, SemanticCacheEntry entry);

    /** 清空某租户整个缓存桶，返回清除的条目数。 */
    int invalidateTenant(String tenantId);

    /** 定向失效某租户下某个原始问题；命中并删除返回 true。 */
    boolean invalidateQuestion(String tenantId, String question);
}
