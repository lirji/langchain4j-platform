package com.lrj.platform.conversation.routing;

import com.lrj.platform.observability.OutboundTraceForwarder;
import com.lrj.platform.security.OutboundTenantForwarder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/** conversation-service 的订单意图路由 HTTP 客户端配置。 */
@Configuration
@ConditionalOnProperty(
        name = "app.conversation.router.order.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ConversationOrderConfig {

    @Bean
    RestTemplate orderRestTemplate(RestTemplateBuilder builder,
                                   OutboundTenantForwarder tenantForwarder,
                                   OutboundTraceForwarder traceForwarder,
                                   @Value("${app.conversation.router.order.base-url:http://localhost:8093}") String baseUrl,
                                   @Value("${app.conversation.router.order.connect-timeout:1s}") Duration connectTimeout,
                                   @Value("${app.conversation.router.order.read-timeout:5s}") Duration readTimeout) {
        return builder
                .rootUri(baseUrl)
                .additionalInterceptors(tenantForwarder, traceForwarder)
                .setConnectTimeout(connectTimeout)
                .setReadTimeout(readTimeout)
                .build();
    }
}
