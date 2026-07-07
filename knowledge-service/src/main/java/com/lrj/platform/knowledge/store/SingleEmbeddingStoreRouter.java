package com.lrj.platform.knowledge.store;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;

/**
 * 兼容/共享模式路由器：所有租户共用一个 {@link EmbeddingStore}，租户隔离退回到 metadata filter。
 *
 * <p>用于两处：{@code app.rag.vector-store.isolation=shared} 的显式退回；以及现有直接 new 出
 * 单例 store 的单元测试（保持构造函数向后兼容）。仍带维度守卫。
 */
public class SingleEmbeddingStoreRouter implements EmbeddingStoreRouter {

    private final EmbeddingStore<TextSegment> store;
    private final int recordedDimension;

    public SingleEmbeddingStoreRouter(EmbeddingStore<TextSegment> store, int recordedDimension) {
        this.store = store;
        this.recordedDimension = recordedDimension;
    }

    @Override
    public EmbeddingStore<TextSegment> forTenant(String tenantId, int dimension) {
        if (recordedDimension > 0 && dimension > 0 && recordedDimension != dimension) {
            throw new DimensionMismatchException("shared", recordedDimension, dimension);
        }
        return store;
    }
}
