package com.lrj.platform.knowledge.cache;

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
 * 语义缓存失效装配。仅 {@code app.rag.cache-invalidation.enabled=true} 时生效——默认关，对 ingest 零影响。
 * 关闭时由 {@link NoopSemanticCacheInvalidator} 兜底，二者按属性互斥，容器里恒有且仅有一个 {@link SemanticCacheInvalidator}。
 */
@Configuration
@ConditionalOnProperty(name = "app.rag.cache-invalidation.enabled", havingValue = "true")
public class CacheInvalidationConfig {

    /** 知识库 → conversation：带租户/trace 转发器（铸发内部 JWT），rootUri 指向 conversation。 */
    @Bean
    RestTemplate cacheInvalidationRestTemplate(
            RestTemplateBuilder builder,
            OutboundTenantForwarder tenantForwarder,
            OutboundTraceForwarder traceForwarder,
            @Value("${app.rag.cache-invalidation.conversation-base-url:http://conversation-service:8081}") String baseUrl,
            @Value("${app.rag.cache-invalidation.connect-timeout:1s}") Duration connectTimeout,
            @Value("${app.rag.cache-invalidation.read-timeout:2s}") Duration readTimeout) {
        return builder
                .rootUri(baseUrl)
                .additionalInterceptors(tenantForwarder, traceForwarder)
                .setConnectTimeout(connectTimeout)
                .setReadTimeout(readTimeout)
                .build();
    }

    @Bean
    HttpSemanticCacheInvalidator httpSemanticCacheInvalidator(RestTemplate cacheInvalidationRestTemplate) {
        return new HttpSemanticCacheInvalidator(cacheInvalidationRestTemplate);
    }
}
