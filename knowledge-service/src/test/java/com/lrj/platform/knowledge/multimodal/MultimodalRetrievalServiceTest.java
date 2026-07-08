package com.lrj.platform.knowledge.multimodal;

import com.lrj.platform.knowledge.store.InMemoryEmbeddingStoreRouter;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多模态检索的租户隔离单测：用 in-memory image router + 定长向量 stub 模型（脱网），
 * 验证 text→image 命中、以及「租户各自独立 image collection」的强隔离。
 */
class MultimodalRetrievalServiceTest {

    /** 无论输入都返回同一向量（余弦=1），使 ingest 的图片必被同租户 query 命中。 */
    private static final class ConstantModel implements MultimodalEmbeddingModel {
        @Override public float[] embedText(String text) { return new float[]{1, 0, 0, 0}; }
        @Override public float[] embedImage(byte[] image, String mimeType) { return new float[]{1, 0, 0, 0}; }
        @Override public int dimension() { return 4; }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void ingestThenSearch_findsImageForSameTenant() {
        MultimodalRetrievalService svc = new MultimodalRetrievalService(
                new ConstantModel(), new InMemoryEmbeddingStoreRouter(), 5, 0.0);

        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));
        String id = svc.ingestImage(new byte[]{1, 2, 3}, "image/png", "chart.png");
        assertThat(id).isNotBlank();

        List<MultimodalRetrievalService.ImageMatch> hits = svc.searchByText("退款趋势", 0, -1);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).fileName()).isEqualTo("chart.png");
    }

    @Test
    void search_isTenantIsolated() {
        MultimodalRetrievalService svc = new MultimodalRetrievalService(
                new ConstantModel(), new InMemoryEmbeddingStoreRouter(), 5, 0.0);

        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("ingest")));
        svc.ingestImage(new byte[]{1, 2, 3}, "image/png", "acme.png");
        TenantContext.clear();

        // 另一租户看不到 acme 的图片（每租户独立 image collection）。
        TenantContext.set(new TenantContext.Tenant("globex", "bob", Set.of("ingest")));
        List<MultimodalRetrievalService.ImageMatch> hits = svc.searchByText("anything", 0, -1);
        assertThat(hits).isEmpty();
    }
}
