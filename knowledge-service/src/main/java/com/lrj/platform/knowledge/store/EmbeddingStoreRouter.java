package com.lrj.platform.knowledge.store;

import com.lrj.platform.security.TenantContext;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;

/**
 * 按租户把向量读写路由到对应的逻辑 collection / namespace，实现 collection-per-tenant 强隔离。
 *
 * <p>每次拿 store 都要带上当前 embedding 维度，路由器据此做惰性建 collection + 维度守卫：
 * 若目标 collection 已按别的维度建立则 fail-fast（{@link DimensionMismatchException}）。
 */
public interface EmbeddingStoreRouter {

    /**
     * 取指定租户的向量 store（惰性建 collection、幂等）。
     *
     * @param tenantId  租户 id（null/blank 归一到默认 namespace）
     * @param dimension 当前 EmbeddingModel 的向量维度，用于建 collection 与维度守卫
     */
    EmbeddingStore<TextSegment> forTenant(String tenantId, int dimension);

    /** 取当前 {@link TenantContext} 租户的向量 store。 */
    default EmbeddingStore<TextSegment> forCurrentTenant(int dimension) {
        return forTenant(TenantContext.current().tenantId(), dimension);
    }
}
