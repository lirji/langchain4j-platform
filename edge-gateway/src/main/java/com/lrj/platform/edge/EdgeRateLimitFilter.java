package com.lrj.platform.edge;

import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import com.lrj.platform.security.ratelimit.RateLimitProperties;
import com.lrj.platform.security.ratelimit.RateLimiterRegistry;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 边缘限流：API key 已在上游 filter 换成内部 JWT，这里从 JWT 还原 tenantId，
 * 按 (tenant, endpoint family) 消费限流桶。
 */
@Component
public class EdgeRateLimitFilter implements GlobalFilter, Ordered {

    private final RateLimitProperties props;
    private final RateLimiterRegistry registry;
    private final InternalSecurityProperties securityProps;
    private final InternalToken tokens;

    public EdgeRateLimitFilter(RateLimitProperties props,
                               RateLimiterRegistry registry,
                               InternalSecurityProperties securityProps,
                               InternalToken tokens) {
        this.props = props;
        this.registry = registry;
        this.securityProps = securityProps;
        this.tokens = tokens;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!props.isEnabled() || EdgeOpenPaths.isOpen(path)) {
            return chain.filter(exchange);
        }

        String jwt = exchange.getRequest().getHeaders().getFirst(securityProps.getInternalHeader());
        TenantContext.Tenant tenant = tokens.verify(jwt);
        if (tenant == null) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String family = familyOf(path);
        RateLimiterRegistry.Decision decision = registry.tryConsume(tenant.tenantId(), family);
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set("X-RateLimit-Limit", String.valueOf(decision.limit()));
        headers.set("X-RateLimit-Remaining", String.valueOf(decision.remainingTokens()));

        if (decision.allowed()) {
            return chain.filter(exchange);
        }

        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        byte[] body = ("{\"error\":\"rate_limited\",\"family\":\"" + family
                + "\",\"tenant\":\"" + tenant.tenantId()
                + "\",\"retryAfterSeconds\":" + decision.retryAfterSeconds() + "}")
                .getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    static String familyOf(String path) {
        if (path == null) return "default";
        if (path.endsWith("/stream")) return "stream";
        if (path.startsWith("/rag/ingest")) return "ingest";
        if (path.startsWith("/eval/")) return "eval";
        if (path.startsWith("/a2a")) return "a2a";
        if (path.startsWith("/chat") || path.startsWith("/extract")) return "chat";
        return "default";
    }

    @Override
    public int getOrder() {
        return -90; // after ApiKeyToInternalTokenFilter
    }
}
