package com.lrj.platform.knowledge.ingest.job;

import com.lrj.platform.observability.OutboundTraceForwarder;
import com.lrj.platform.security.OutboundTenantForwarder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class IngestionTaskLifecycleConfig {

    @Bean
    @ConditionalOnProperty(
            name = "app.rag.ingestion.async-task.enabled",
            havingValue = "false",
            matchIfMissing = true)
    IngestionTaskLifecycle noopIngestionTaskLifecycle() {
        return new NoopIngestionTaskLifecycle();
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.rag.ingestion.async-task.enabled",
            havingValue = "true")
    RestTemplate ingestionAsyncTaskRestTemplate(
            RestTemplateBuilder builder,
            OutboundTenantForwarder tenantForwarder,
            OutboundTraceForwarder traceForwarder,
            @Value("${app.rag.ingestion.async-task.base-url:"
                    + "http://async-task-service:8086}") String baseUrl,
            @Value("${app.rag.ingestion.async-task.connect-timeout:1s}")
            Duration connectTimeout,
            @Value("${app.rag.ingestion.async-task.read-timeout:2s}")
            Duration readTimeout
    ) {
        return builder
                .rootUri(baseUrl)
                .additionalInterceptors(tenantForwarder, traceForwarder)
                .setConnectTimeout(connectTimeout)
                .setReadTimeout(readTimeout)
                .build();
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.rag.ingestion.async-task.enabled",
            havingValue = "true")
    IngestionTaskLifecycle httpIngestionTaskLifecycle(
            RestTemplate ingestionAsyncTaskRestTemplate
    ) {
        return new HttpIngestionTaskLifecycle(ingestionAsyncTaskRestTemplate);
    }
}
