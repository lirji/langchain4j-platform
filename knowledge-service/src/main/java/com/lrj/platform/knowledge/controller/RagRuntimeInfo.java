package com.lrj.platform.knowledge.controller;

import com.lrj.platform.protocol.knowledge.KnowledgeRuntimeView.RagRuntime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 汇总当前 knowledge-service 解析后的 RAG 运行时配置，派生成对前端友好的 {@link RagRuntime} 视图，
 * 供 {@code GET /rag/config} 如实展示"现在实际用的是哪种 embedding / 向量库 / 有没有开混排"。
 *
 * <p>沿用本仓库主导范式：构造器 {@code @Value} 注入（与 {@code KnowledgeQueryService} 一致），
 * 字段默认值对齐 {@code application.yml} 里 {@code app.rag.*} 的零依赖基线，便于纯 POJO 单测。
 * 只读、非敏感——不含任何 host / apiKey / 密码。
 */
@Component
public class RagRuntimeInfo {

    private final String embeddingProvider;
    private final String openAiModel;
    private final String ollamaModel;
    private final String vectorStoreProvider;
    private final boolean esHybridEnabled;
    private final String fusionStrategy;
    private final boolean graphEnabled;
    private final boolean keywordHybridEnabled;
    private final boolean multimodalEnabled;

    public RagRuntimeInfo(
            @Value("${app.rag.embedding.provider:hash}") String embeddingProvider,
            @Value("${app.rag.embedding.model-name:embedding-default}") String openAiModel,
            @Value("${app.rag.embedding.ollama.model-name:nomic-embed-text}") String ollamaModel,
            @Value("${app.rag.vector-store.provider:in-memory}") String vectorStoreProvider,
            @Value("${app.rag.es.enabled:true}") boolean esHybridEnabled,
            @Value("${app.rag.fusion.strategy:}") String fusionStrategy,
            @Value("${app.rag.graph.enabled:true}") boolean graphEnabled,
            @Value("${app.rag.hybrid.enabled:true}") boolean keywordHybridEnabled,
            @Value("${app.rag.multimodal-embedding.enabled:false}") boolean multimodalEnabled) {
        this.embeddingProvider = embeddingProvider;
        this.openAiModel = openAiModel;
        this.ollamaModel = ollamaModel;
        this.vectorStoreProvider = vectorStoreProvider;
        this.esHybridEnabled = esHybridEnabled;
        this.fusionStrategy = fusionStrategy;
        this.graphEnabled = graphEnabled;
        this.keywordHybridEnabled = keywordHybridEnabled;
        this.multimodalEnabled = multimodalEnabled;
    }

    /** 派生前端视图：semantic、有效模型名、有效融合策略均在此收敛，前端不再自行推断。 */
    public RagRuntime view() {
        boolean semantic = !isHash();
        return new RagRuntime(
                embeddingProvider,
                effectiveEmbeddingModel(),
                semantic,
                vectorStoreProvider,
                esHybridEnabled,
                effectiveFusionStrategy(),
                graphEnabled,
                keywordHybridEnabled,
                multimodalEnabled);
    }

    /** hash provider 无真实模型，返回 {@code hash} 占位；其余取各自 provider 的模型名。 */
    private String effectiveEmbeddingModel() {
        if (isHash()) {
            return "hash";
        }
        return "ollama".equalsIgnoreCase(embeddingProvider) ? ollamaModel : openAiModel;
    }

    /** strategy 留空时按 ES 开关取有效默认（ES 开→rrf；ES 关→weighted_max），与 application.yml 注释一致。 */
    private String effectiveFusionStrategy() {
        if (fusionStrategy != null && !fusionStrategy.isBlank()) {
            return fusionStrategy;
        }
        return esHybridEnabled ? "rrf" : "weighted_max";
    }

    private boolean isHash() {
        return embeddingProvider == null || "hash".equalsIgnoreCase(embeddingProvider);
    }
}
