package com.lrj.platform.protocol.knowledge;

/**
 * 知识库运行时共享状态视图（跨服务 DTO），供 {@code GET /rag/config} 返回，前端据此决定是否展示
 * "共享知识库" tab / 共享图片入口，并如实展示当前 RAG 后端实际形态。只读、非敏感。
 *
 * @param contractVersion    合同版本，前端能力协商用（当前 = 2；v2 起附带 {@link #rag}）
 * @param publicEnabled       共享库读并入是否开启（对应 {@code app.rag.public.enabled}）
 * @param sharedImagesSupported 共享库是否支持图片入库（当前恒 false —— 共享图片暂不支持）
 * @param rag                当前 RAG 运行时形态（embedding / 向量库 / 混排开关）；v1 后端不返回时前端按未探测处理
 */
public record KnowledgeRuntimeView(int contractVersion,
                                   boolean publicEnabled,
                                   boolean sharedImagesSupported,
                                   RagRuntime rag) {

    /**
     * RAG 运行时后端形态（只读、非敏感），供前端如实展示"当前实际用的是哪种 embedding / 向量库 /
     * 有没有开混排"，取代前端写死的"默认 HashEmbedding"提示。字段取自 knowledge-service 解析后的运行配置。
     *
     * @param embeddingProvider   embedding provider：{@code hash} | {@code ollama} | {@code openai}
     * @param embeddingModel      当前生效的 embedding 模型名（provider=hash 时为 {@code hash} 占位，无真实模型）
     * @param semantic            是否为真实语义向量（{@code provider != hash} 即 true；hash 为确定性降级）
     * @param vectorStoreProvider 向量库：{@code in-memory} | {@code qdrant} | {@code pgvector} | ...
     * @param esHybridEnabled     ES 全文混排是否开启（{@code app.rag.es.enabled}）
     * @param fusionStrategy      多源融合策略（有效值：{@code rrf} | {@code weighted_max}；留空时按 ES 开关取默认）
     * @param graphEnabled        GraphRAG 是否开启（{@code app.rag.graph.enabled}）
     * @param keywordHybridEnabled 关键词混排是否开启（{@code app.rag.hybrid.enabled}）
     * @param multimodalEnabled   原生图片多模态 embedding 是否开启（{@code app.rag.multimodal-embedding.enabled}）
     */
    public record RagRuntime(String embeddingProvider,
                             String embeddingModel,
                             boolean semantic,
                             String vectorStoreProvider,
                             boolean esHybridEnabled,
                             String fusionStrategy,
                             boolean graphEnabled,
                             boolean keywordHybridEnabled,
                             boolean multimodalEnabled) {
    }
}
