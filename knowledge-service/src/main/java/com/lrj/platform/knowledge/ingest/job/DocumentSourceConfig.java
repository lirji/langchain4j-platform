package com.lrj.platform.knowledge.ingest.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
@EnableConfigurationProperties(DocumentSourceProperties.class)
public class DocumentSourceConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.rag.source", name = "store",
            havingValue = "memory", matchIfMissing = true)
    DocumentSourceStore inMemoryDocumentSourceStore() {
        return new InMemoryDocumentSourceStore();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.rag.source", name = "store", havingValue = "s3")
    S3Client documentSourceS3Client(DocumentSourceProperties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(properties.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isPathStyle())
                        .build());
        if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        boolean hasAccess = properties.getAccessKey() != null
                && !properties.getAccessKey().isBlank();
        boolean hasSecret = properties.getSecretKey() != null
                && !properties.getSecretKey().isBlank();
        if (hasAccess != hasSecret) {
            throw new IllegalArgumentException(
                    "both source S3 access key and secret key are required");
        }
        builder.credentialsProvider(hasAccess
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        properties.getAccessKey(), properties.getSecretKey()))
                : DefaultCredentialsProvider.builder().build());
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.rag.source", name = "store", havingValue = "s3")
    DocumentSourceStore s3DocumentSourceStore(
            S3Client documentSourceS3Client,
            DocumentSourceProperties properties
    ) {
        return new S3DocumentSourceStore(
                documentSourceS3Client, properties.getBucket(), properties.getPrefix());
    }
}
