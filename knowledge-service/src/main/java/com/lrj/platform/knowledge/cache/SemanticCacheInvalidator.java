package com.lrj.platform.knowledge.cache;

/**
 * 知识库变更后失效 conversation 侧 L1 语义缓存的客户端（松耦合、尽力而为）。
 *
 * <p>默认 {@link NoopSemanticCacheInvalidator}（不失效）；开启 {@code app.rag.cache-invalidation.enabled=true}
 * 时走 {@link HttpSemanticCacheInvalidator}，调 conversation 的 {@code DELETE /chat/cache}。
 * 租户由出站内部 JWT 传播，conversation 侧据此只清对应租户的桶。<b>失败只记日志、绝不阻断文档 ingest。</b>
 */
public interface SemanticCacheInvalidator {

    /** 失效当前租户（{@code TenantContext}）的语义缓存桶。尽力而为。 */
    void invalidateCurrentTenant();
}
