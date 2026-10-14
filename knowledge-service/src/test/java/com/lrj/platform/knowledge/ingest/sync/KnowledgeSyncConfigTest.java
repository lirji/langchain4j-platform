package com.lrj.platform.knowledge.ingest.sync;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class KnowledgeSyncConfigTest {
    @Test void disabledByDefaultAndAbsentOnQueryAndWorkerRoles() {
        var runner = new ApplicationContextRunner().withUserConfiguration(KnowledgeSyncConfig.class, KnowledgeSyncController.class);
        runner.run(context -> assertThat(context).doesNotHaveBean(KnowledgeSyncService.class));
        for (String role : java.util.List.of("query", "ingest-worker"))
            runner.withPropertyValues("app.rag.http-sync.enabled=true", "app.rag.runtime.role=" + role)
                    .run(context -> { assertThat(context).hasNotFailed(); assertThat(context).doesNotHaveBean(KnowledgeSyncService.class); });
    }
    @Test void enabledConfigurationBindsSourcesAndWorksWithAuthorizationDisabled() {
        new ApplicationContextRunner().withUserConfiguration(KnowledgeSyncConfig.class, KnowledgeSyncController.class)
                .withBean("ingestionJobDataSource", javax.sql.DataSource.class, SyncTestSupport::database)
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
                .withBean(com.lrj.platform.knowledge.ingest.job.IngestionSubmissionService.class,
                        () -> org.mockito.Mockito.mock(com.lrj.platform.knowledge.ingest.job.IngestionSubmissionService.class))
                .withBean(com.lrj.platform.knowledge.lifecycle.DocumentRegistry.class,
                        com.lrj.platform.knowledge.lifecycle.InMemoryDocumentRegistry::new)
                .withPropertyValues("app.rag.http-sync.enabled=true", "app.rag.ingestion.store=jdbc", "app.rag.source.store=s3",
                        "app.rag.registry.store=redis", "app.rag.http-sync.sources[0].id=faq",
                        "app.rag.http-sync.sources[0].tenant-id=tenant", "app.rag.http-sync.sources[0].user-id=sync",
                        "app.rag.http-sync.sources[0].endpoint=https://example.invalid/feed",
                        "app.rag.http-sync.sources[0].operator-user-ids[0]=operator")
                .run(context -> {
                    assertThat(context).hasNotFailed(); assertThat(context).hasSingleBean(KnowledgeSyncService.class);
                    assertThat(context.getBean(KnowledgeSyncProperties.class).sources()).hasSize(1);
                    assertThat(context.getBean(KnowledgeSyncProperties.class).sources().getFirst().operatorUserIds()).containsExactly("operator");
                });
    }
    @Test void enabledWithoutPersistentDatabaseFailsStartup() {
        new ApplicationContextRunner().withUserConfiguration(KnowledgeSyncConfig.class)
                .withPropertyValues("app.rag.http-sync.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
