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
        // 图检索不再整体禁用，但必须显式要求版本 provenance：否则无归属的历史三元组会绕过
        // 「按 Registry 当前版本过滤」与「文档级判权」两道过滤。
        persistent.withProperty("app.rag.graph.enabled", "true");
        assertThatThrownBy(() -> validator(KnowledgeRuntimeProperties.Role.QUERY,
                "memory", "memory", persistent).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.rag.graph.require-provenance=true");
        persistent.withProperty("app.rag.graph.require-provenance", "true");
        assertThatCode(() -> validator(KnowledgeRuntimeProperties.Role.QUERY,
                "memory", "memory", persistent).afterPropertiesSet()).doesNotThrowAnyException();
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

    @Test
    void productionCombinedRequiresDurableStoresAndStrictEsWrites() throws Exception {
        var runtime = new KnowledgeRuntimeProperties();
        runtime.setProduction(true);
        var source = new DocumentSourceProperties();
        var ingestion = new IngestionJobProperties();
        var env = new MockEnvironment().withProperty("app.rag.vector-store.provider", "qdrant")
                .withProperty("app.rag.registry.store", "redis")
                .withProperty("app.rag.es.enabled", "true")
                .withProperty("app.rag.es.query-enabled", "true")
                .withProperty("app.rag.es.index-enabled", "true");
        var check = config.knowledgeRuntimeBoundaryValidator(runtime, source, ingestion, env);
        assertThatThrownBy(check::afterPropertiesSet).hasMessageContaining("legacy-write-enabled=false");
        runtime.setLegacyWriteEnabled(false);
        assertThatThrownBy(check::afterPropertiesSet).hasMessageContaining("store=jdbc");
        ingestion.setStore("jdbc");
        assertThatThrownBy(check::afterPropertiesSet).hasMessageContaining("store=s3");
        source.setStore("s3");
        assertThatThrownBy(check::afterPropertiesSet).hasMessageContaining("fail-fast=true");
        env.withProperty("app.rag.es.fail-fast", "true");
        assertThatCode(check::afterPropertiesSet).doesNotThrowAnyException();
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
