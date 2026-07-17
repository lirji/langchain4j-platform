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

/**
 * async-task-service 的 Web/调度装配：绑定 webhook 配置（{@link AsyncTaskWebhookProperties}）、构建带超时的
 * webhook {@link RestTemplate}，并提供 {@code asyncTaskExecutor} 线程池——其 TaskDecorator 会跨线程传播
 * {@link TenantContext} 与 MDC，保证异步 webhook 投递仍带正确的租户与 traceId 上下文。{@code @EnableScheduling}
 * 打开各 {@code @Scheduled} 清理/派发任务。
 */
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
