package com.lrj.platform.asynctask;

import com.lrj.platform.security.TenantContext;
import org.slf4j.MDC;
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
public class AsyncTaskWebConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.async-task.webhook")
    AsyncTaskWebhookProperties asyncTaskWebhookProperties() {
        return new AsyncTaskWebhookProperties();
    }

    @Bean
    RestTemplate asyncTaskWebhookRestTemplate(RestTemplateBuilder builder, AsyncTaskWebhookProperties properties) {
        return builder
                .setConnectTimeout(properties.getConnectTimeout())
                .setReadTimeout(properties.getReadTimeout())
                .build();
    }

    @Bean(name = "asyncTaskExecutor")
    Executor asyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-task-");
        executor.setTaskDecorator(task -> {
            TenantContext.Tenant tenant = TenantContext.captureRaw();
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            return () -> {
                TenantContext.Tenant previousTenant = TenantContext.captureRaw();
                Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                try {
                    if (tenant != null) TenantContext.set(tenant); else TenantContext.clear();
                    if (mdc != null) MDC.setContextMap(mdc); else MDC.clear();
                    task.run();
                } finally {
                    if (previousTenant != null) TenantContext.set(previousTenant); else TenantContext.clear();
                    if (previousMdc != null) MDC.setContextMap(previousMdc); else MDC.clear();
                }
            };
        });
        executor.initialize();
        return executor;
    }
}
