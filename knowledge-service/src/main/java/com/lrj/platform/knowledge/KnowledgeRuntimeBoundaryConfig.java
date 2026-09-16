package com.lrj.platform.knowledge;

import com.lrj.platform.knowledge.ingest.job.DocumentSourceProperties;
import com.lrj.platform.knowledge.ingest.job.IngestionJobProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * 独立角色必须依赖共享权威存储。combined 保留现有本地开发与兼容 façade 行为。
 */
@Configuration
@EnableConfigurationProperties({
        KnowledgeRuntimeProperties.class,
        DocumentSourceProperties.class,
        IngestionJobProperties.class
})
public class KnowledgeRuntimeBoundaryConfig {

    @Bean
    InitializingBean knowledgeRuntimeBoundaryValidator(
            KnowledgeRuntimeProperties runtime,
            DocumentSourceProperties source,
            IngestionJobProperties ingestion,
            Environment environment
    ) {
        return () -> {
            if (runtime.isProduction() && runtime.isLegacyWriteEnabled()) {
                throw new IllegalStateException("production requires legacy-write-enabled=false");
            }
            if (runtime.getRole() == KnowledgeRuntimeProperties.Role.COMBINED && !runtime.isProduction()) {
                return;
            }
            if (runtime.getRole() == KnowledgeRuntimeProperties.Role.QUERY) {
                validateQueryDataPlane(environment);
                return;
            }
            if (!"jdbc".equalsIgnoreCase(ingestion.getStore())) {
                throw new IllegalStateException(
                        "ingest roles require app.rag.ingestion.store=jdbc");
            }
            if (!"s3".equalsIgnoreCase(source.getStore())) {
                throw new IllegalStateException(
                        "ingest roles require app.rag.source.store=s3");
            }
            if (runtime.isProduction() && runtime.getRole() != KnowledgeRuntimeProperties.Role.INGEST_API) {
                validateQueryDataPlane(environment);
                if (environment.getProperty("app.rag.es.enabled", Boolean.class, false)
                        && (!environment.getProperty("app.rag.es.index-enabled", Boolean.class, false)
                        || !environment.getProperty("app.rag.es.fail-fast", Boolean.class, false))) {
                    throw new IllegalStateException("production ingestion requires ES index-enabled=true and fail-fast=true");
                }
            }
            String registryStore = environment.getProperty(
                    "app.rag.registry.store", "in-memory");
            if (!"redis".equalsIgnoreCase(registryStore)) {
                throw new IllegalStateException(
                        "ingest roles require a shared persistent document registry");
            }
        };
    }

    private static void validateQueryDataPlane(Environment environment) {
        String vectorStore = environment.getProperty(
                "app.rag.vector-store.provider", "in-memory");
        String registryStore = environment.getProperty(
                "app.rag.registry.store", "in-memory");
        boolean hybrid = environment.getProperty(
                "app.rag.hybrid.enabled", Boolean.class, true);
        boolean esEnabled = environment.getProperty(
                "app.rag.es.enabled", Boolean.class, false);
        boolean esQueryEnabled = environment.getProperty(
                "app.rag.es.query-enabled", Boolean.class, false);
        boolean graphEnabled = environment.getProperty(
                "app.rag.graph.enabled", Boolean.class, false);
        if ("in-memory".equalsIgnoreCase(vectorStore)
                || !"redis".equalsIgnoreCase(registryStore)) {
            throw new IllegalStateException(
                    "query role requires persistent vector and registry stores");
        }
        if (hybrid && (!esEnabled || !esQueryEnabled)) {
            throw new IllegalStateException(
                    "query role with hybrid retrieval requires persistent Elasticsearch query");
        }
        // 图检索曾在 query 角色整体禁用：图命中不带版本 provenance，既无法按 Registry 当前版本判新鲜度，
        // 也无法做文档级判权。现在 sourceId 里的 <docId>/v<version>/ 会还原进命中，因此改为要求显式开启
        // require-provenance —— 该开关下无 provenance 的历史三元组被丢弃，剩下的和向量/ES 命中走同两道过滤。
        boolean graphRequireProvenance = environment.getProperty(
                "app.rag.graph.require-provenance", Boolean.class, false);
        if (graphEnabled && !graphRequireProvenance) {
            throw new IllegalStateException(
                    "query role with graph retrieval requires app.rag.graph.require-provenance=true");
        }
    }

    @Bean
    FilterRegistrationBean<KnowledgeRoleRequestFilter> knowledgeRoleRequestFilter(
            KnowledgeRuntimeProperties runtime
    ) {
        FilterRegistrationBean<KnowledgeRoleRequestFilter> registration =
                new FilterRegistrationBean<>(
                        new KnowledgeRoleRequestFilter(runtime.getRole(), runtime.isLegacyWriteEnabled()));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        return registration;
    }
}
