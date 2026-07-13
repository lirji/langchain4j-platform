package com.lrj.platform.knowledge.es;

import com.lrj.platform.knowledge.search.EsKeywordRetrievalSource;
import com.lrj.platform.knowledge.search.RetrievalSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ES RAG 装配（阶段3，es-hybrid-rerank）。默认（ES 关闭）只提供 {@link NoopSegmentIndexer}，不加载任何 ES 客户端；
 * {@code app.rag.es.enabled=true} 时提供真实 gateway / indexer / 检索源。检索源作为 {@link RetrievalSource} bean，
 * 被 {@code KnowledgeQueryService} 通过 ObjectProvider 收进额外源列表。
 */
@Configuration
@EnableConfigurationProperties(EsRagProperties.class)
public class EsRagConfig {

    /** 默认：ES 关闭时的空索引器，入库零副作用。 */
    @Bean
    @ConditionalOnProperty(name = "app.rag.es.enabled", havingValue = "false", matchIfMissing = true)
    public SegmentIndexer noopSegmentIndexer() {
        return new NoopSegmentIndexer();
    }

    /** ES 网关（低层 RestClient）；Spring 关闭时自动调 close()（实现 AutoCloseable）。 */
    @Bean
    @ConditionalOnProperty(name = "app.rag.es.enabled", havingValue = "true")
    public EsGateway esGateway(EsRagProperties props) {
        return new ElasticsearchEsGateway(props);
    }

    /** ES 索引器；建索引惰性化（首次写时），不在启动期连 ES，避免 ES 不可用拖垮 Spring 启动。 */
    @Bean
    @ConditionalOnProperty(name = "app.rag.es.enabled", havingValue = "true")
    public SegmentIndexer elasticsearchSegmentIndexer(EsGateway gateway, EsRagProperties props) {
        return new ElasticsearchSegmentIndexer(gateway, props);
    }

    /** ES 全文检索源（BM25），并入混合检索。 */
    @Bean
    @ConditionalOnProperty(name = "app.rag.es.enabled", havingValue = "true")
    public RetrievalSource esKeywordRetrievalSource(EsGateway gateway,
                                                    EsRagProperties props,
                                                    @Value("${app.rag.ranking.es-weight:1.0}") double esWeight) {
        return new EsKeywordRetrievalSource(gateway, props, esWeight);
    }
}
