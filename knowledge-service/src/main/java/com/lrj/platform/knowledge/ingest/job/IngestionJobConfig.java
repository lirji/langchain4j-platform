package com.lrj.platform.knowledge.ingest.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.knowledge.DocumentSplitterFactory;
import com.lrj.platform.knowledge.DocumentMirror;
import com.lrj.platform.knowledge.authz.KnowledgeAuthz;
import com.lrj.platform.knowledge.authz.NoopKnowledgeAuthz;
import com.lrj.platform.knowledge.es.NoopSegmentIndexer;
import com.lrj.platform.knowledge.es.SegmentIndexer;
import com.lrj.platform.knowledge.graph.GraphIngestor;
import com.lrj.platform.knowledge.ingest.ContextualEnricher;
import com.lrj.platform.knowledge.ingest.NoopContextualEnricher;
import com.lrj.platform.knowledge.lifecycle.DocumentTextExtractor;
import com.lrj.platform.knowledge.lifecycle.DocumentRegistry;
import com.lrj.platform.knowledge.observability.ChunkMetrics;
import com.lrj.platform.knowledge.store.EmbeddingStoreRouter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Set;

@Configuration
@EnableScheduling
@ConditionalOnExpression(
        "'${app.rag.runtime.role:combined}' != 'query'")
@EnableConfigurationProperties(IngestionJobProperties.class)
public class IngestionJobConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.rag.ingestion", name = "store",
            havingValue = "memory", matchIfMissing = true)
    IngestionJobStore inMemoryIngestionJobStore() {
        return new InMemoryIngestionJobStore();
    }

    @Bean(name = "ingestionJobDataSource", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.rag.ingestion", name = "store", havingValue = "jdbc")
    DataSource ingestionJobDataSource(IngestionJobProperties properties) {
        IngestionJobProperties.Datasource source = properties.getDatasource();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(source.getUrl());
        config.setUsername(source.getUsername());
        config.setPassword(source.getPassword());
        config.setDriverClassName(source.getDriverClassName());
        config.setMaximumPoolSize(source.getMaximumPoolSize());
        config.setPoolName("knowledge-ingestion-job");
        return new HikariDataSource(config);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.rag.ingestion", name = "store", havingValue = "jdbc")
    IngestionJobStore jdbcIngestionJobStore(
            @Qualifier("ingestionJobDataSource") DataSource dataSource,
            ObjectMapper objectMapper
    ) {
        return new JdbcIngestionJobStore(dataSource, objectMapper);
    }

    @Bean
    IngestionReconciler ingestionReconciler(
            IngestionJobStore store,
            IngestionJobProperties properties
    ) {
        return new IngestionReconciler(
                store,
                Clock.systemUTC(),
                properties.getProcessingTimeout(),
                properties.getReconcileBatchSize());
    }

    @Bean
    IngestionSubmissionService ingestionSubmissionService(
            DocumentSourceStore sources,
            IngestionJobStore jobs,
            DocumentRegistry registry,
            ObjectProvider<KnowledgeAuthz> authorization
    ) {
        return new IngestionSubmissionService(
                sources,
                jobs,
                registry,
                authorization.getIfAvailable(NoopKnowledgeAuthz::new),
                Clock.systemUTC(),
                Set.of(
                        IngestionSink.VECTOR,
                        IngestionSink.ELASTICSEARCH,
                        IngestionSink.GRAPH,
                        IngestionSink.REGISTRY,
                        IngestionSink.AUTHORIZATION));
    }

    @Bean
    @ConditionalOnExpression(
            "'${app.rag.runtime.role:combined}' == 'combined'"
                    + " || '${app.rag.runtime.role:combined}' == 'ingest-worker'")
    IngestionDocumentPreparer ingestionDocumentPreparer(
            DocumentSourceStore sources,
            DocumentTextExtractor textExtractor,
            DocumentSplitterFactory splitterFactory,
            ObjectProvider<ContextualEnricher> contextualEnricher,
            EmbeddingModel embeddingModel,
            ObjectProvider<ChunkMetrics> chunkMetrics
    ) {
        return new DefaultIngestionDocumentPreparer(
                sources,
                textExtractor,
                splitterFactory,
                contextualEnricher.getIfAvailable(NoopContextualEnricher::new),
                embeddingModel,
                chunkMetrics.getIfAvailable(),
                Clock.systemUTC());
    }

    @Bean
    @ConditionalOnExpression(
            "'${app.rag.runtime.role:combined}' == 'combined'"
                    + " || '${app.rag.runtime.role:combined}' == 'ingest-worker'")
    IngestionSinkProcessor ingestionSinkProcessor(
            EmbeddingStoreRouter storeRouter,
            EmbeddingModel embeddingModel,
            ObjectProvider<SegmentIndexer> segmentIndexer,
            ObjectProvider<GraphIngestor> graphIngestor,
            ObjectProvider<KnowledgeAuthz> authorization,
            DocumentRegistry registry
    ) {
        return new DefaultIngestionSinkProcessor(
                storeRouter,
                embeddingModel,
                segmentIndexer.getIfAvailable(NoopSegmentIndexer::new),
                graphIngestor.getIfAvailable(),
                authorization.getIfAvailable(NoopKnowledgeAuthz::new),
                registry);
    }

    @Bean
    @ConditionalOnExpression(
            "'${app.rag.runtime.role:combined}' == 'combined'"
                    + " || '${app.rag.runtime.role:combined}' == 'ingest-worker'")
    IngestionJobWorker ingestionJobWorker(
            IngestionJobStore store,
            IngestionDocumentPreparer preparer,
            IngestionSinkProcessor processor,
            IngestionTaskLifecycle lifecycle
    ) {
        return new IngestionJobWorker(
                store, preparer, processor, lifecycle, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnExpression(
            "'${app.rag.runtime.role:combined}' == 'combined'"
                    + " || '${app.rag.runtime.role:combined}' == 'ingest-worker'")
    IngestionWorkerLoop ingestionWorkerLoop(
            IngestionJobStore store,
            IngestionJobWorker worker,
            IngestionReconciler reconciler,
            IngestionJobProperties properties
    ) {
        return new IngestionWorkerLoop(
                store, worker, reconciler, properties.getWorkerBatchSize());
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "app.rag.ingestion.version-gc", name = "enabled", havingValue = "true")
    @ConditionalOnExpression(
            "'${app.rag.runtime.role:combined}' == 'combined'"
                    + " || '${app.rag.runtime.role:combined}' == 'ingest-worker'")
    KnowledgeVersionGarbageCollector knowledgeVersionGarbageCollector(
            DocumentRegistry registry,
            EmbeddingStoreRouter storeRouter,
            EmbeddingModel embeddingModel,
            DocumentMirror mirror,
            ObjectProvider<SegmentIndexer> segmentIndexer,
            ObjectProvider<GraphIngestor> graphIngestor,
            IngestionJobProperties properties
    ) {
        return new KnowledgeVersionGarbageCollector(
                registry, storeRouter, embeddingModel, mirror,
                segmentIndexer.getIfAvailable(NoopSegmentIndexer::new),
                graphIngestor.getIfAvailable(), Clock.systemUTC(), properties.getVersionGc());
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "app.rag.ingestion.version-gc", name = "enabled", havingValue = "true")
    @ConditionalOnExpression(
            "'${app.rag.runtime.role:combined}' == 'combined'"
                    + " || '${app.rag.runtime.role:combined}' == 'ingest-worker'")
    KnowledgeVersionGcLoop knowledgeVersionGcLoop(KnowledgeVersionGarbageCollector collector) {
        return new KnowledgeVersionGcLoop(collector);
    }
}
