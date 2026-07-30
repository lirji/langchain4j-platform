package com.lrj.platform.knowledge;

import com.lrj.platform.knowledge.ingest.job.DocumentSourceProperties;
import com.lrj.platform.knowledge.ingest.job.IngestionJobProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeRuntimeBoundaryConfigTest {

    private final KnowledgeRuntimeBoundaryConfig config = new KnowledgeRuntimeBoundaryConfig();

    @Test
    void combinedRoleKeepsZeroDependencyDevelopmentMode() {
        assertThatCode(() -> validator(KnowledgeRuntimeProperties.Role.COMBINED,
                "memory", "memory", new MockEnvironment()).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void queryRoleRequiresPersistentQueryStoresButNotIngestionCredentials() {
        MockEnvironment persistent = new MockEnvironment()
                .withProperty("app.rag.vector-store.provider", "qdrant")
                .withProperty("app.rag.registry.store", "redis")
                .withProperty("app.rag.hybrid.enabled", "true")
                .withProperty("app.rag.es.enabled", "true")
                .withProperty("app.rag.es.query-enabled", "true")
                .withProperty("app.rag.graph.enabled", "false");
        assertThatCode(() -> validator(KnowledgeRuntimeProperties.Role.QUERY,
                "memory", "memory", persistent).afterPropertiesSet()).doesNotThrowAnyException();
        persistent.withProperty("app.rag.graph.enabled", "true");
        assertThatThrownBy(() -> validator(KnowledgeRuntimeProperties.Role.QUERY,
                "memory", "memory", persistent).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("document version provenance");
        assertThatThrownBy(() -> validator(KnowledgeRuntimeProperties.Role.QUERY,
                "memory", "memory", new MockEnvironment()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("persistent vector and registry");
    }

    @Test
    void ingestRolesRequireJdbcAndS3() {
        MockEnvironment persistentRegistry = new MockEnvironment()
                .withProperty("app.rag.registry.store", "redis");
        assertThatThrownBy(() -> validator(KnowledgeRuntimeProperties.Role.INGEST_WORKER,
                "memory", "jdbc", persistentRegistry).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source.store=s3");
        assertThatCode(() -> validator(KnowledgeRuntimeProperties.Role.INGEST_API,
                "s3", "jdbc", persistentRegistry).afterPropertiesSet())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator(KnowledgeRuntimeProperties.Role.INGEST_API,
                "s3", "jdbc", new MockEnvironment()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("persistent document registry");
    }

    private InitializingBean validator(
            KnowledgeRuntimeProperties.Role role,
            String sourceStore,
            String ingestionStore,
            MockEnvironment environment
    ) {
        KnowledgeRuntimeProperties runtime = new KnowledgeRuntimeProperties();
        runtime.setRole(role);
        DocumentSourceProperties source = new DocumentSourceProperties();
        source.setStore(sourceStore);
        IngestionJobProperties ingestion = new IngestionJobProperties();
        ingestion.setStore(ingestionStore);
        return config.knowledgeRuntimeBoundaryValidator(runtime, source, ingestion, environment);
    }
}
