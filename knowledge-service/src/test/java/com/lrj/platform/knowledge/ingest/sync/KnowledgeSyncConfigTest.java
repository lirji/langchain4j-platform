package com.lrj.platform.knowledge.ingest.sync;

import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KnowledgeSyncConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeSyncConfig.class, KnowledgeSyncController.class);
    private final String[] source = {"app.rag.http-sync.sources[0].id=faq",
            "app.rag.http-sync.sources[0].tenant-id=tenant", "app.rag.http-sync.sources[0].user-id=sync",
            "app.rag.http-sync.sources[0].endpoint=https://example.invalid/feed",
            "app.rag.http-sync.sources[0].operator-user-ids[0]=operator"};

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void enabledByDefaultWithoutSourcesProvidesAuthorizedEmptyListAndNoCollectionComponents() {
        for (String role : java.util.List.of("combined", "ingest-api")) {
            runner.withPropertyValues("app.rag.runtime.role=" + role).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(KnowledgeSyncController.class);
                assertThat(context.getBean(KnowledgeSyncProperties.class).enabled()).isTrue();
                assertThat(context).doesNotHaveBean(KnowledgeSyncStore.class)
                        .doesNotHaveBean(KnowledgeSyncService.class).doesNotHaveBean(KnowledgeSyncScheduler.class);
                var mvc = MockMvcBuilders.standaloneSetup(context.getBean(KnowledgeSyncController.class)).build();
                TenantContext.clear();
                mvc.perform(get("/rag/ingestions/sources")).andExpect(status().isForbidden());
                TenantContext.set(new TenantContext.Tenant("tenant", "sync", Set.of("ingest")));
                mvc.perform(get("/rag/ingestions/sources")).andExpect(status().isOk()).andExpect(content().json("[]"));
                for (String suffix : java.util.List.of("faq", "faq/document?externalId=item", "faq/runs", "faq/runs/run"))
                    mvc.perform(get("/rag/ingestions/sources/" + suffix)).andExpect(status().isNotFound());
                for (String action : java.util.List.of("poll", "retry", "pause", "resume"))
                    mvc.perform(post("/rag/ingestions/sources/faq/" + action).contentType("application/json")
                            .content("{\"expectedRevision\":0}")).andExpect(status().isNotFound());
            });
        }
    }

    @Test void explicitlyDisabledAndQueryWorkerRolesNeverRegisterCollectionOrController() {
        runner.withPropertyValues(source).withPropertyValues("app.rag.http-sync.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KnowledgeSyncController.class)
                        .doesNotHaveBean(KnowledgeSyncService.class).doesNotHaveBean(KnowledgeSyncScheduler.class));
        for (String role : java.util.List.of("query", "ingest-worker"))
            runner.withPropertyValues(source).withPropertyValues("app.rag.runtime.role=" + role)
                    .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KnowledgeSyncController.class)
                            .doesNotHaveBean(KnowledgeSyncService.class).doesNotHaveBean(KnowledgeSyncScheduler.class));
    }

    @Test void configuredSourcesStartByDefaultAndWorkWithAuthorizationDisabled() {
        persistentRunner().withPropertyValues(source).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(KnowledgeSyncService.class)
                    .hasSingleBean(KnowledgeSyncScheduler.class);
            var properties = context.getBean(KnowledgeSyncProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.sources()).hasSize(1);
            assertThat(properties.sources().getFirst().operatorUserIds()).containsExactly("operator");
            TenantContext.set(new TenantContext.Tenant("tenant", "operator", Set.of("ingest")));
            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(KnowledgeSyncController.class)).build();
            mvc.perform(get("/rag/ingestions/sources")).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].sourceId").value("faq"));
        });
    }

    @Test void configuredSourceWithoutPersistentDatabaseStillFailsStartup() {
        runner.withPropertyValues(source).run(context -> assertThat(context).hasFailed());
    }

    @Test void configuredSourceRejectsEachNonPersistentStore() {
        for (String store : java.util.List.of("ingestion", "source", "registry"))
            persistentRunner().withPropertyValues(source).withPropertyValues("app.rag." + store + ".store=memory")
                    .run(context -> assertThat(context).hasFailed()
                            .getFailure().hasRootCauseMessage("HTTP sync requires jdbc jobs, s3 sources and redis registry"));
    }

    @Test void incompleteSourceCannotBeMistakenForEmptyConfiguration() {
        runner.withPropertyValues("app.rag.http-sync.sources[0].tenant-id=tenant")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void packagedApplicationConfigEnablesByDefaultAndSupportsEnvironmentSwitch() throws Exception {
        var yaml = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("application", new org.springframework.core.io.ClassPathResource("application.yml"));
        var configured = runner.withInitializer(context -> yaml.forEach(propertySource ->
                context.getEnvironment().getPropertySources().addLast(propertySource)));
        configured.run(context -> assertThat(context).hasNotFailed().hasSingleBean(KnowledgeSyncController.class));
        configured.withPropertyValues("KNOWLEDGE_HTTP_SYNC_ENABLED=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KnowledgeSyncController.class));
    }

    private ApplicationContextRunner persistentRunner() {
        return runner.withBean("ingestionJobDataSource", javax.sql.DataSource.class, SyncTestSupport::database)
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
                .withBean(com.lrj.platform.knowledge.ingest.job.IngestionSubmissionService.class,
                        () -> org.mockito.Mockito.mock(com.lrj.platform.knowledge.ingest.job.IngestionSubmissionService.class))
                .withBean(com.lrj.platform.knowledge.lifecycle.DocumentRegistry.class,
                        com.lrj.platform.knowledge.lifecycle.InMemoryDocumentRegistry::new)
                .withPropertyValues("app.rag.ingestion.store=jdbc", "app.rag.source.store=s3", "app.rag.registry.store=redis");
    }
}
