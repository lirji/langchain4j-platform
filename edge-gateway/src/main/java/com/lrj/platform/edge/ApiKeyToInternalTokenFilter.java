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

    private final InternalSecurityProperties props;
    private final InternalToken tokens;

    public ApiKeyToInternalTokenFilter(InternalSecurityProperties props, InternalToken tokens) {
        this.props = props;
        this.tokens = tokens;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (isOpen(path)) {
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
        String jwt = tokens.mint(new TenantContext.Tenant(binding.getTenant(), binding.getUser(), scopes));

        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(props.getInternalHeader(), jwt)
                .headers(h -> h.remove(props.getApiKeyHeader())) // 不把外部 api key 泄到内网
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private boolean isOpen(String path) {
        return path.startsWith("/actuator")
                || path.startsWith("/.well-known")
                // 飞书事件回调不带平台 api-key，靠飞书签名验真（见 channel-service FeishuInboundController）
                || path.equals("/channel/feishu/events")
                // 钉钉机器人消息回调不带平台 api-key，靠钉钉 timestamp/sign 验真（见 channel-service DingtalkInboundController）
                || path.equals("/channel/dingtalk/events")
                || path.equals("/health");
    }

    @Override
    public int getOrder() {
        return -100; // 早于路由转发
    }
}
