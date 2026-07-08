package com.lrj.platform.conversation.vision;

import com.lrj.platform.observability.OutboundTraceForwarder;
import com.lrj.platform.security.OutboundTenantForwarder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * conversation → vision-service 的 RestTemplate（对齐 {@code ConversationRagConfig}）。
 * 仅在 {@code app.conversation.vision.enabled=true} 时装配；带租户/trace 转发。
 */
@Configuration
@ConditionalOnProperty(name = "app.conversation.vision.enabled", havingValue = "true")
public class ConversationVisionConfig {

    @Bean
    RestTemplate visionRestTemplate(RestTemplateBuilder builder,
                                    OutboundTenantForwarder tenantForwarder,
                                    OutboundTraceForwarder traceForwarder,
                                    @Value("${app.conversation.vision.vision-base-url:http://localhost:8090}") String baseUrl,
                                    @Value("${app.conversation.vision.connect-timeout:1s}") Duration connectTimeout,
                                    @Value("${app.conversation.vision.read-timeout:60s}") Duration readTimeout) {
        return builder
                .rootUri(baseUrl)
                .additionalInterceptors(tenantForwarder, traceForwarder)
                .setConnectTimeout(connectTimeout)
                .setReadTimeout(readTimeout)
                .build();
    }
}
