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
 *
 * <p>免鉴权路径（{@link EdgeOpenPaths}）没有租户身份，但**不再整体跳过限流**：账号入口与渠道回调改按
 * 客户端 IP 限桶（family 见 {@link EdgeOpenPaths#rateLimitFamilyOf}），只有健康检查/发现类端点不限。
 */
@Component
public class EdgeRateLimitFilter implements GlobalFilter, Ordered {

    /** 取不到对端地址时的兜底 key：宁可让这类请求共享一个桶，也不能整类放行。 */
    static final String UNKNOWN_CLIENT = "unknown";

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
        if (!props.isEnabled()) {
            return chain.filter(exchange);
        }
        String openFamily = EdgeOpenPaths.rateLimitFamilyOf(path);
        if (openFamily != null) {
            // 免鉴权业务入口：无租户身份 → 用客户端 IP 当限流主体，桶与租户桶天然分开（key 里的主体不同）
            return consume(exchange, chain, clientBucketKey(exchange), openFamily);
        }
        if (EdgeOpenPaths.isOpen(path)) {
            return chain.filter(exchange); // 探针/发现类端点：限流会误伤存活检查
        }

        String jwt = exchange.getRequest().getHeaders().getFirst(securityProps.getInternalHeader());
        TenantContext.Tenant tenant = tokens.verify(jwt);
        if (tenant == null) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        return consume(exchange, chain, tenant.tenantId(), familyOf(path));
    }

    /** 消费一次 (subject, family) 桶：放行则继续链，超限回 429 + Retry-After。 */
    private Mono<Void> consume(ServerWebExchange exchange, GatewayFilterChain chain,
                               String subject, String family) {
        RateLimiterRegistry.Decision decision = registry.tryConsume(subject, family);
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
                + "\",\"tenant\":\"" + subject
                + "\",\"retryAfterSeconds\":" + decision.retryAfterSeconds() + "}")
                .getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    /**
     * 免鉴权请求的限流主体：{@code ip:<地址>}。
     *
     * <p>默认只用 TCP 对端地址，**不信任任何请求头**——{@code X-Forwarded-For} 可由客户端伪造，
     * 信任它等于把限流变成自愿参与。边缘前面确有会覆写该头的可信代理/LB 时，用
     * {@code app.rate-limit.client-ip-header} 显式指定要读的头（取第一跳），否则整站请求会共享同一个桶。
     */
    String clientBucketKey(ServerWebExchange exchange) {
        String header = props.getClientIpHeader();
        if (header != null && !header.isBlank()) {
            String forwarded = exchange.getRequest().getHeaders().getFirst(header);
            if (forwarded != null && !forwarded.isBlank()) {
                // XFF 形如 "client, proxy1, proxy2"，第一跳是原始客户端
                return "ip:" + forwarded.split(",")[0].trim();
            }
        }
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "ip:" + UNKNOWN_CLIENT;
        }
        return "ip:" + remote.getAddress().getHostAddress();
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
