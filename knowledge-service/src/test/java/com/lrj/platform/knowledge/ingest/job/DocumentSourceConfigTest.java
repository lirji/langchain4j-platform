package com.lrj.platform.knowledge.ingest.job;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentSourceConfigTest {

    @Test
    void buildsS3CompatibleClientWithoutApacheHttpRuntimeLinkage() {
        DocumentSourceProperties properties = new DocumentSourceProperties();
        properties.setEndpoint("http://127.0.0.1:9000");
        properties.setRegion("us-east-1");
        properties.setAccessKey("test-access");
        properties.setSecretKey("test-secret");
        properties.setPathStyle(true);

        try (S3Client client = new DocumentSourceConfig().documentSourceS3Client(properties)) {
            assertThat(client.serviceName()).isEqualTo("s3");
        }
    }
}
