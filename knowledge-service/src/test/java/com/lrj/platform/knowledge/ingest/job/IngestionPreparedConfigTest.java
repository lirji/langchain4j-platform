package com.lrj.platform.knowledge.ingest.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.knowledge.DocumentSplitterFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IngestionPreparedConfigTest {
    @Test
    void nonWorkerRolesDoNotNeedPreparationDependencies() {
        for (String role : new String[]{"query", "ingest-api"}) {
            new ApplicationContextRunner().withUserConfiguration(IngestionPreparedConfig.class)
                    .withPropertyValues("app.rag.runtime.role=" + role).run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(IngestionPreparedStore.class);
                    });
        }
    }

    @Test
    void workerBindsBudgetsAndUsesS3ForDurableResults() {
        S3Client client = mock(S3Client.class);
        var factory = mock(DocumentSplitterFactory.class);
        when(factory.processingConfiguration()).thenReturn(Map.of("strategy", "recursive"));
        new ApplicationContextRunner().withUserConfiguration(IngestionPreparedConfig.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(DocumentSourceProperties.class, DocumentSourceProperties::new)
                .withBean(IngestionResourceProperties.class, IngestionResourceProperties::new)
                .withBean(DocumentSplitterFactory.class, () -> factory)
                .withBean(S3Client.class, () -> client)
                .withPropertyValues("app.rag.runtime.role=ingest-worker", "app.rag.source.store=s3",
                        "app.rag.ingestion.prepared.max-bytes=4096", "app.rag.ingestion.prepared.processing-version=v2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(IngestionPreparedStore.class);
                    assertThat(context.getBean(IngestionPreparedProperties.class).getMaxBytes()).isEqualTo(4096);
                    assertThat(context.getBean(IngestionPreparedProperties.class).getProcessingVersion()).isEqualTo("v2");
                    verifyNoInteractions(client);
                });
    }
}
