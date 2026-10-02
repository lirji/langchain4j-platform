package com.lrj.platform.conversation.shadow;

import com.lrj.platform.observability.OutboundTraceForwarder;
import com.lrj.platform.security.OutboundTenantForwarder;
import com.lrj.platform.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;

/** 默认关闭的 conversation candidate shadow 接线。 */
@Configuration
public class ConversationShadowConfig {

    @Bean
    @ConditionalOnProperty(name = "app.conversation.shadow.streaming-enabled", havingValue = "false", matchIfMissing = true)
    ConversationStreamShadowObserver disabledStreamShadow() {
        return ConversationStreamShadowObserver.disabled();
    }

    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnProperty(prefix = "app.conversation.shadow", name = {"enabled", "streaming-enabled"}, havingValue = "true")
    java.util.concurrent.ExecutorService conversationStreamShadowExecutor() {
        return new java.util.concurrent.ThreadPoolExecutor(1, 2, 30, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(16), runnable -> {
                    Thread thread = new Thread(runnable, "conversation-stream-shadow");
                    thread.setDaemon(true);
                    return thread;
                }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnProperty(prefix = "app.conversation.shadow", name = {"enabled", "streaming-enabled"}, havingValue = "true")
    java.util.concurrent.ScheduledExecutorService conversationStreamShadowTimer() {
        var timer = new java.util.concurrent.ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "conversation-stream-shadow-deadline");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.conversation.shadow", name = {"enabled", "streaming-enabled"}, havingValue = "true")
    ConversationStreamShadowObserver conversationStreamShadowObserver(
            @Qualifier("conversationStreamShadowExecutor") java.util.concurrent.ExecutorService executor,
            @Qualifier("conversationStreamShadowTimer") java.util.concurrent.ScheduledExecutorService timer,
            com.fasterxml.jackson.databind.ObjectMapper mapper, com.lrj.platform.security.InternalToken tokens,
            com.lrj.platform.security.InternalSecurityProperties security,
            ObjectProvider<MeterRegistry> registry,
            @Value("${app.conversation.shadow.base-url}") String baseUrl,
            @Value("${app.conversation.shadow.read-timeout:5s}") Duration deadline) {
        // RS256纯验签节点不能签发候选凭据; 显式启用时启动即失败, 避免永远失败却看似开启.
        tokens.mint(new TenantContext.Tenant("shadow-preflight", "shadow-preflight", java.util.Set.of("chat")));
        var client = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        return new HttpConversationStreamShadowObserver(client,
                java.net.URI.create(baseUrl.replaceAll("/$", "") + "/internal/conversation/stream"), mapper,
                tokens, security.getInternalHeader(), executor, timer, deadline,
                new ConversationShadowMetrics(registry.getIfAvailable()));
    }

    @Bean
    @ConditionalOnProperty(name = "app.conversation.shadow.enabled", havingValue = "false",
            matchIfMissing = true)
    ConversationShadowObserver noOpConversationShadowObserver() {
        return (request, primaryReply) -> {
            // disabled
        };
    }

    @Bean(name = "conversationShadowExecutor")
    @ConditionalOnProperty(name = "app.conversation.shadow.enabled", havingValue = "true")
    Executor conversationShadowExecutor(
            @Value("${app.conversation.shadow.executor.core-size:1}") int coreSize,
            @Value("${app.conversation.shadow.executor.max-size:2}") int maxSize,
            @Value("${app.conversation.shadow.executor.queue-capacity:128}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("conversation-shadow-");
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

    @Bean
    @Qualifier("conversationShadowRestTemplate")
    @ConditionalOnProperty(name = "app.conversation.shadow.enabled", havingValue = "true")
    RestTemplate conversationShadowRestTemplate(
            RestTemplateBuilder builder,
            OutboundTenantForwarder tenantForwarder,
            OutboundTraceForwarder traceForwarder,
            @Value("${app.conversation.shadow.base-url}") String baseUrl,
            @Value("${app.conversation.shadow.connect-timeout:500ms}") Duration connectTimeout,
            @Value("${app.conversation.shadow.read-timeout:5s}") Duration readTimeout) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "app.conversation.shadow.base-url is required when conversation shadow is enabled");
        }
        return builder
                .rootUri(baseUrl)
                .additionalInterceptors(tenantForwarder, traceForwarder)
                .setConnectTimeout(connectTimeout)
                .setReadTimeout(readTimeout)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.conversation.shadow.enabled", havingValue = "true")
    ConversationShadowObserver httpConversationShadowObserver(
            @Qualifier("conversationShadowRestTemplate") RestTemplate restTemplate,
            @Qualifier("conversationShadowExecutor") Executor executor,
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new HttpConversationShadowObserver(
                restTemplate,
                executor,
                new ConversationShadowMetrics(meterRegistry.getIfAvailable()));
    }
}
