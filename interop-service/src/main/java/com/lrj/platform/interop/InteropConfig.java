package com.lrj.platform.interop;

import com.lrj.platform.observability.OutboundTraceForwarder;
import com.lrj.platform.security.OutboundTenantForwarder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

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
