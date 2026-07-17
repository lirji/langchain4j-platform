package com.lrj.platform.gateway.tenant;

/**
 * 当前请求的可信租户身份来源（SPI）。
 *
 * <p>gateway-client 对 {@code platform-security} 只是 optional 编译依赖（避免把安全 filter 传递给
 * eval-service 这类无鉴权服务），因此不直接引用 {@code TenantContext}，而是经本接口解耦：
 * security 在 classpath 时由 {@link TenantContextIdentityAutoConfiguration} 自动提供
 * {@code TenantContext.current().tenantId()} 适配；缺失时退化为常量 {@code anonymous}。
 * 未来换 Vault / 短时签名身份只需换实现，不动 wrapper。
 */
@FunctionalInterface
public interface TenantIdentityProvider {

    /** 当前线程的租户 id；无身份时约定返回 {@code "anonymous"}（与 TenantContext 兜底语义一致），不返回 null。 */
    String currentTenantId();
}
