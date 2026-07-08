package com.lrj.platform.knowledge.multimodal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.knowledge.store.CollectionManager;
import com.lrj.platform.knowledge.store.EmbeddingStoreRouter;
import com.lrj.platform.knowledge.store.InMemoryEmbeddingStoreRouter;
import com.lrj.platform.knowledge.store.ManagedEmbeddingStoreRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 原生多模态 embedding 装配。<strong>整个 config 条件化在
 * {@code app.rag.multimodal-embedding.enabled=true}</strong>——关闭（默认）时相关 Bean 全不存在，
 * 零开销、零依赖网络，主 RAG 链完全不受影响。
 *
 * <p>刻意<strong>不注册 {@code EmbeddingModel} Bean</strong>：主 RAG 的文本 {@code EmbeddingModel}
 * 已由 {@code KnowledgeEmbeddingConfig} 装配，两者维度/语义空间不同，混在同一类型里会污染 RAG 自动装配。
 * 这里只暴露自定义 {@link MultimodalEmbeddingModel} 接口。
 *
 * <p>图片向量存入<strong>独立的 image collection</strong>（base={@code knowledge_images}）：复用当前
 * provider 的 {@link CollectionManager}（若存在），否则退回进程内 in-memory router；与文本集合物理分开，
 * 维度独立。
 */
@Configuration
@ConditionalOnProperty(name = "app.rag.multimodal-embedding.enabled", havingValue = "true")
public class MultimodalEmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(MultimodalEmbeddingConfig.class);

    @Bean
    @ConfigurationProperties(prefix = "app.rag.multimodal-embedding")
    public MultimodalEmbeddingProperties multimodalEmbeddingProperties() {
        return new MultimodalEmbeddingProperties();
    }

    @Bean
    public MultimodalEmbeddingModel multimodalEmbeddingModel(MultimodalEmbeddingProperties props,
                                                             ObjectMapper mapper) {
        log.info("Native multimodal embedding enabled: model={} dim={}",
                props.getModelName(), props.getDimension());
        return new DefaultMultimodalEmbeddingModel(props, mapper);
    }

    /**
     * 图片向量专用路由器（与文本 {@code embeddingStoreRouter} 分离；非 {@code @Primary}）。base 默认
     * {@code knowledge_images}，每租户 {@code knowledge_images_<tenant>}。复用当前 provider 的
     * CollectionManager（qdrant/pgvector/milvus/chroma/doris）——因 collection 名与文本不同，共用同一
     * manager 实例也不冲突；provider=in-memory 时退回独立的内存 router。
     */
    @Bean
    public EmbeddingStoreRouter imageEmbeddingStoreRouter(
            ObjectProvider<CollectionManager> collectionManagerProvider,
            @Value("${app.rag.multimodal-embedding.base-collection:knowledge_images}") String imageBase) {
        CollectionManager manager = collectionManagerProvider.getIfAvailable();
        if (manager != null) {
            log.info("Multimodal image store: collection-per-tenant (base={})", imageBase);
            return new ManagedEmbeddingStoreRouter(manager, imageBase);
        }
        log.info("Multimodal image store: in-memory (one store per tenant)");
        return new InMemoryEmbeddingStoreRouter();
    }

    @Bean
    public MultimodalRetrievalService multimodalRetrievalService(MultimodalEmbeddingModel model,
                                                                 EmbeddingStoreRouter imageEmbeddingStoreRouter,
                                                                 MultimodalEmbeddingProperties props) {
        return new MultimodalRetrievalService(model, imageEmbeddingStoreRouter, props.getTopK(), props.getMinScore());
    }
}
