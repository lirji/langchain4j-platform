package com.lrj.platform.knowledge.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认实现：不失效。{@code app.rag.cache-invalidation.enabled} 关闭或未配置时装配——对 ingest 零影响。
 */
@Component
@ConditionalOnProperty(name = "app.rag.cache-invalidation.enabled", havingValue = "false", matchIfMissing = true)
public class NoopSemanticCacheInvalidator implements SemanticCacheInvalidator {

    @Override
    public void invalidateCurrentTenant() {
        // no-op
    }
}
