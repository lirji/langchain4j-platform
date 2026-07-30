package com.lrj.platform.edge;

import com.lrj.platform.security.TenantContext;
import org.springframework.web.server.ServerWebExchange;

/**
 * edge 认证链与后续过滤器之间的可信请求内身份接缝。
 *
 * <p>属性只由成功完成凭证校验的 filter 写入，不来自 HTTP header，因此客户端不能伪造。
 */
final class EdgeAuthenticatedTenant {

    private static final String ATTRIBUTE = EdgeAuthenticatedTenant.class.getName() + ".tenant";

    private EdgeAuthenticatedTenant() {}

    static void set(ServerWebExchange exchange, TenantContext.Tenant tenant) {
        exchange.getAttributes().put(ATTRIBUTE, tenant);
    }

    static TenantContext.Tenant get(ServerWebExchange exchange) {
        return exchange.getAttribute(ATTRIBUTE);
    }
}
