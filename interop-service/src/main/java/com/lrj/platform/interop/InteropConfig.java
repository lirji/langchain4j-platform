package com.lrj.platform.interop;

import com.lrj.platform.observability.OutboundTraceForwarder;
import com.lrj.platform.security.OutboundTenantForwarder;
import com.lrj.platform.security.TenantContext;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.Executor;

@Configuration
@EnableConfigurationProperties(InteropProperties.class)
public class InteropConfig {

    @Bean
    RestTemplate interopAgentRestTemplate(RestTemplateBuilder builder,
                                          OutboundTenantForwarder tenantForwarder,
                                          OutboundTraceForwarder traceForwarder,
                                          InteropProperties properties) {
        return builder
                .rootUri(properties.getAgentBaseUrl())
                .additionalInterceptors(tenantForwarder, traceForwarder)
                .setConnectTimeout(properties.getConnectTimeout())
                .setReadTimeout(properties.getReadTimeout())
                .build();
    }

    /** conversation /chat/stream 代理用 RestTemplate：挂租户/trace 转发器，读超时放宽给 SSE 流。 */
    @Bean
    RestTemplate interopConversationRestTemplate(RestTemplateBuilder builder,
                                                 OutboundTenantForwarder tenantForwarder,
                                                 OutboundTraceForwarder traceForwarder,
                                                 InteropProperties properties) {
        return builder
                .rootUri(properties.getConversationBaseUrl())
                .additionalInterceptors(tenantForwarder, traceForwarder)
                .setConnectTimeout(properties.getConnectTimeout())
                .setReadTimeout(properties.getStreamReadTimeout())
                .build();
    }

    /**
     * A2A 流式 / push 中继的后台执行器：SSE 消费与 push 投递是阻塞 I/O，须离开请求线程。
     * TaskDecorator 把提交线程的租户 + MDC 透传到工作线程，保证下游调用带上内部 JWT。
     */
    @Bean(name = "interopStreamExecutor")
    Executor interopStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("interop-stream-");
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

    /** live capability discovery client —— 仅在 {@code app.interop.discovery-enabled=true} 时装配。 */
    @Bean
    @ConditionalOnProperty(prefix = "app.interop", name = "discovery-enabled", havingValue = "true")
    AgentCapabilityClient agentCapabilityClient(RestTemplate interopAgentRestTemplate) {
        return new HttpAgentCapabilityClient(interopAgentRestTemplate);
    }

    /**
     * MCP 工具目录：注入 discovery client（若 discovery 开启则存在，否则回退静态默认）。
     * 拿不到 client 时 registry 走纯静态行为，保持 dev/test 零依赖。
     */
    @Bean
    InteropToolRegistry interopToolRegistry(ObjectProvider<AgentCapabilityClient> discoveryClient,
                                            InteropProperties properties) {
        return new InteropToolRegistry(discoveryClient.getIfAvailable(), properties.getCapabilityTtl());
    }
}
