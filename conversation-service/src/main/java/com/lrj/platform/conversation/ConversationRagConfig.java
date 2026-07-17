package com.lrj.platform.conversation;

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
 * 对话服务访问 knowledge-service 的 RAG 客户端配置：仅当 {@code app.conversation.rag.enabled=true} 时激活，
 * 构建指向知识库（默认 {@code :8084}）的 {@code knowledgeRestTemplate}，并装上 {@link OutboundTenantForwarder} +
 * {@link OutboundTraceForwarder} 拦截器以跨服务透传租户身份与 traceId，同时按配置设置连接/读取超时。
 * 供 {@link HttpKnowledgeClient} 注入使用。
 */
@Configuration
@ConditionalOnProperty(name = "app.conversation.rag.enabled", havingValue = "true")
public class ConversationRagConfig {

    @Bean
    RestTemplate knowledgeRestTemplate(RestTemplateBuilder builder,
                                       OutboundTenantForwarder tenantForwarder,
                                       OutboundTraceForwarder traceForwarder,
                                       @Value("${app.conversation.rag.knowledge-base-url:http://localhost:8084}") String baseUrl,
                                       @Value("${app.conversation.rag.connect-timeout:1s}") Duration connectTimeout,
                                       @Value("${app.conversation.rag.read-timeout:3s}") Duration readTimeout) {
        return builder
                .rootUri(baseUrl)
                .additionalInterceptors(tenantForwarder, traceForwarder)
                .setConnectTimeout(connectTimeout)
                .setReadTimeout(readTimeout)
                .build();
    }
}
