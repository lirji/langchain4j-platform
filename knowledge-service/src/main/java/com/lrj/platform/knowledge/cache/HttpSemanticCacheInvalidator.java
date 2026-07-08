package com.lrj.platform.knowledge.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 经 conversation-service 的 {@code DELETE /chat/cache} 失效当前租户语义缓存。
 *
 * <p>RestTemplate 带 {@code OutboundTenantForwarder}（从 {@code TenantContext} 铸发内部 JWT，把当前租户传过去）
 * + {@code OutboundTraceForwarder}，故 conversation 侧只清该租户的桶。<b>尽力而为</b>：任何异常只 warn，不抛，
 * 不阻断文档 ingest（调用方 upload/delete 已完成，这只是收尾）。
 */
public class HttpSemanticCacheInvalidator implements SemanticCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(HttpSemanticCacheInvalidator.class);

    private final RestTemplate http;

    public HttpSemanticCacheInvalidator(RestTemplate cacheInvalidationRestTemplate) {
        this.http = cacheInvalidationRestTemplate;
    }

    @Override
    public void invalidateCurrentTenant() {
        try {
            http.delete("/chat/cache");
        } catch (Exception e) {
            log.warn("semantic cache invalidation failed (best-effort, ignored): {}", e.toString());
        }
    }
}
