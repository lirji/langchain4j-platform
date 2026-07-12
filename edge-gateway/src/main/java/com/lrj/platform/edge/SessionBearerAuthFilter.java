package com.lrj.platform.edge;

import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 边缘全局过滤器：校验登录后颁发的会话访问 JWT（{@code Authorization: Bearer}）→ 换发短时内部 JWT
 * 注入 {@code X-Internal-Token} 转发给下游。这是"登录体系"接入现有两层网关的接缝：凭证来源换成
 * 自建账号密码，下游对内部 JWT 的处理完全不变。
 *
 * <p>与 {@link ApiKeyToInternalTokenFilter} 并存（双模）：
 * <ul>
 *   <li>带有效 Bearer → 本 filter 换发内部 JWT，下游 api-key filter 见已认证即透传；</li>
 *   <li>无 / 无效 Bearer → 透传给 api-key filter，由它按 {@code X-Api-Key} 决定放行或 401。</li>
 * </ul>
 * 排在 api-key filter（-100）之前。
 */
@Component
public class SessionBearerAuthFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";

    private final InternalSecurityProperties props;
    /** 平台默认内部 JWT bean（内部密钥、短时）——用于 mint 下游内部令牌。 */
    private final InternalToken internalTokens;
    /** 会话令牌验签实例（会话密钥）——仅用于 verify 前端 Bearer。 */
    private final InternalToken sessionTokens;

    public SessionBearerAuthFilter(InternalSecurityProperties props, InternalToken internalTokens) {
        this.props = props;
        this.internalTokens = internalTokens;
        InternalSecurityProperties.Session s = props.getSession();
        this.sessionTokens = new InternalToken(s.getJwtSecret(), s.getAccessTtl());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (EdgeOpenPaths.isOpen(path)) {
            return chain.filter(exchange);
        }

        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith(BEARER_PREFIX)) {
            return chain.filter(exchange); // 交给 api-key filter
        }

        String token = auth.substring(BEARER_PREFIX.length()).trim();
        TenantContext.Tenant tenant = sessionTokens.verify(token);
        if (tenant == null) {
            // 无效/过期会话令牌：不在此直接 401，透传给 api-key filter 统一处理（无 api-key 则 401）。
            return chain.filter(exchange);
        }

        String jwt = internalTokens.mint(tenant);
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(props.getInternalHeader(), jwt)
                .headers(h -> h.remove(HttpHeaders.AUTHORIZATION)) // 会话令牌不进内网
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        return -110; // 早于 ApiKeyToInternalTokenFilter(-100)
    }
}
