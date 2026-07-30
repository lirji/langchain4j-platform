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
            if (runtime.getRole() == KnowledgeRuntimeProperties.Role.COMBINED) {
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
            String registryStore = environment.getProperty(
                    "app.rag.registry.store", "in-memory");
            if ("in-memory".equalsIgnoreCase(registryStore)) {
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
                || "in-memory".equalsIgnoreCase(registryStore)) {
            throw new IllegalStateException(
                    "query role requires persistent vector and registry stores");
        }
        if (hybrid && (!esEnabled || !esQueryEnabled)) {
            throw new IllegalStateException(
                    "query role with hybrid retrieval requires persistent Elasticsearch query");
        }
        if (graphEnabled) {
            throw new IllegalStateException(
                    "query role must disable graph retrieval until graph hits carry document version provenance");
        }
    }

    @Bean
    FilterRegistrationBean<KnowledgeRoleRequestFilter> knowledgeRoleRequestFilter(
            KnowledgeRuntimeProperties runtime
    ) {
        FilterRegistrationBean<KnowledgeRoleRequestFilter> registration =
                new FilterRegistrationBean<>(
                        new KnowledgeRoleRequestFilter(runtime.getRole()));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        return registration;
    }
}
