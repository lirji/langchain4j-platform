package com.lrj.platform.interop;

import com.lrj.platform.observability.OutboundTraceForwarder;
import com.lrj.platform.security.OutboundTenantForwarder;
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
}
