package com.lrj.platform.edge;

import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 边缘全局过滤器：校验入站 {@code X-Api-Key} → 换发短时内部 JWT 注入 {@code X-Internal-Token} 转发给下游。
 *
 * <p>这是"边缘用 API key 换发内部令牌、下游只信 JWT"的签发端；下游 {@code InternalTokenAuthFilter} 是校验端。
 * 放行 actuator / 发现类路径（无需鉴权）。
 */
@Component
public class ApiKeyToInternalTokenFilter implements GlobalFilter, Ordered {

    private static final TenantContext.Tenant EDGE_SERVICE_IDENTITY =
            new TenantContext.Tenant("_platform", "edge-gateway", Set.of("service"));

    private final InternalSecurityProperties props;
    private final InternalToken tokens;

    public ApiKeyToInternalTokenFilter(InternalSecurityProperties props, InternalToken tokens) {
        this.props = props;
        this.tokens = tokens;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (EdgeOpenPaths.isOpen(path)) {
            // 登录、第三方回调、发现等入口对客户端开放，但下游端口本身仍强制内部 JWT。
            // 用短时 edge 服务身份转发，既不把开放路径变成 anonymous，也不赋予租户业务 scope。
            String jwt = tokens.mint(EDGE_SERVICE_IDENTITY);
            ServerHttpRequest request = exchange.getRequest().mutate()
                    .header(props.getInternalHeader(), jwt)
                    .headers(headers -> headers.remove(props.getApiKeyHeader()))
                    .build();
            ServerWebExchange authenticated = exchange.mutate().request(request).build();
            EdgeAuthenticatedTenant.set(authenticated, EDGE_SERVICE_IDENTITY);
            return chain.filter(authenticated);
        }

        // 已被更早的认证 filter（服务令牌 / Casdoor / 会话）换发内部 JWT：直接放行，不再要求 X-Api-Key。
        // 只认 exchange 属性而不认 header —— header 可由客户端伪造，属性只由完成验签的 filter 写入。
        if (EdgeAuthenticatedTenant.get(exchange) != null) {
            return chain.filter(exchange);
        }

        String apiKey = exchange.getRequest().getHeaders().getFirst(props.getApiKeyHeader());
        InternalSecurityProperties.KeyBinding binding =
                (apiKey == null) ? null : props.getApiKeys().get(apiKey);
        if (binding == null) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        Set<String> scopes = binding.getScopes() == null
                ? Set.of() : new LinkedHashSet<>(binding.getScopes());
        TenantContext.Tenant tenant = new TenantContext.Tenant(binding.getTenant(), binding.getUser(), scopes);
        String jwt = tokens.mint(tenant);

        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(props.getInternalHeader(), jwt)
                .headers(h -> h.remove(props.getApiKeyHeader())) // 不把外部 api key 泄到内网
                .build();
        ServerWebExchange authenticated = exchange.mutate().request(mutated).build();
        EdgeAuthenticatedTenant.set(authenticated, tenant);
        return chain.filter(authenticated);
    }

    @Override
    public int getOrder() {
        return -100; // 早于路由转发
    }
}
