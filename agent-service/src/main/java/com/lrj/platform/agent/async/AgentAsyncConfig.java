package com.lrj.platform.agent.async;

import com.lrj.platform.security.TenantContext;
import com.lrj.platform.observability.OutboundTraceForwarder;
import com.lrj.platform.security.OutboundTenantForwarder;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.Executor;

@Configuration
@EnableScheduling
public class AgentAsyncConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.agent.async.webhook")
    AgentWebhookProperties agentWebhookProperties() {
        return new AgentWebhookProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.agent.async.external")
    ExternalAsyncTaskProperties externalAsyncTaskProperties() {
        return new ExternalAsyncTaskProperties();
    }

    @Bean
    RestTemplate agentWebhookRestTemplate(RestTemplateBuilder builder, AgentWebhookProperties properties) {
        return builder
                .setConnectTimeout(properties.getConnectTimeout())
                .setReadTimeout(properties.getReadTimeout())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.agent.async.external.enabled", havingValue = "true")
    RestTemplate asyncTaskRestTemplate(RestTemplateBuilder builder,
                                       OutboundTenantForwarder tenantForwarder,
                                       OutboundTraceForwarder traceForwarder,
                                       @Value("${app.agent.async.external.base-url:http://localhost:8086}") String baseUrl,
                                       @Value("${app.agent.http.connect-timeout:1s}") java.time.Duration connectTimeout,
                                       @Value("${app.agent.http.read-timeout:5s}") java.time.Duration readTimeout) {
        return builder
                .rootUri(baseUrl)
                .additionalInterceptors(tenantForwarder, traceForwarder)
                .setConnectTimeout(connectTimeout)
                .setReadTimeout(readTimeout)
                .build();
    }

    @Bean(name = "agentTaskExecutor")
    Executor agentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("agent-task-");
        executor.setTaskDecorator(task -> {
            TenantContext.Tenant tenant = TenantContext.captureRaw();
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            return () -> {
                TenantContext.Tenant previousTenant = TenantContext.captureRaw();
                Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                try {
                    if (tenant != null) {
                        TenantContext.set(tenant);
                    } else {
                        TenantContext.clear();
                    }
                    if (mdc != null) {
                        MDC.setContextMap(mdc);
                    } else {
                        MDC.clear();
                    }
                    task.run();
                } finally {
                    if (previousTenant != null) {
                        TenantContext.set(previousTenant);
                    } else {
                        TenantContext.clear();
                    }
                    if (previousMdc != null) {
                        MDC.setContextMap(previousMdc);
                    } else {
                        MDC.clear();
                    }
                }
            };
        });
        executor.initialize();
        return executor;
    }
}
