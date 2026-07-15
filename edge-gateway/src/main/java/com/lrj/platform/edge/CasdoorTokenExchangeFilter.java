package com.lrj.platform.edge;

import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Collection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ③ 边缘全局过滤器：验 Casdoor access token（{@code Authorization: Bearer}，Casdoor JWKS/RS256）→
 * 用 {@code owner→tenantId}、{@code sub→userId}、scope 换发内部 JWT 注入 {@code X-Internal-Token}。
 *
 * <p>验不过 / 无 Bearer → <strong>透传</strong>给 legacy filter（灰度期 session/api-key 仍可用）。
 * 是 Casdoor 签的但缺 tenant/sub → 401（fail closed，不降级匿名）。
 * order <strong>-120</strong>，早于 {@code SessionBearerAuthFilter}(-110) / {@code ApiKeyToInternalTokenFilter}(-100)。
 */
@Component
@ConditionalOnProperty(prefix = "edge.casdoor", name = "enabled", havingValue = "true")
public class CasdoorTokenExchangeFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CasdoorTokenExchangeFilter.class);

    private static final String BEARER = "Bearer ";

    private final CasdoorSecurityProperties casdoor;
    private final InternalSecurityProperties internalProps;
    private final InternalToken internalTokens;
    private final ReactiveJwtDecoder decoder;

    public CasdoorTokenExchangeFilter(CasdoorSecurityProperties casdoor,
                                      InternalSecurityProperties internalProps,
                                      InternalToken internalTokens,
                                      ReactiveJwtDecoder casdoorJwtDecoder) {
        this.casdoor = casdoor;
        this.internalProps = internalProps;
        this.internalTokens = internalTokens;
        this.decoder = casdoorJwtDecoder;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 剥离客户端伪造的入站内部头：Casdoor 是最早(-120)的认证 filter，内部头只应由本链各 filter 换发注入，
        // 绝不能信任外部传入——否则可伪造有效内部 JWT 绕过 Casdoor/session/api-key 全部认证。
        ServerWebExchange ex = stripInboundInternalHeader(exchange);
        String path = ex.getRequest().getPath().value();
        if (EdgeOpenPaths.isOpen(path)) {
            return chain.filter(ex);
        }
        String auth = ex.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth == null || !auth.startsWith(BEARER)) {
            return onMissingOrInvalid(ex, chain); // 无 Bearer：DUAL 透传 legacy / ONLY 401
        }
        String token = auth.substring(BEARER.length()).trim();
        // decode 的【任何】失败（验签失败 / JWKS 拉取 / 网络）→ DUAL 透传 legacy / ONLY 401。用 Optional 隔离，使 onError
        // 只作用于 decode 本身；decode 成功后的 mint/claim/下游错误正常传播（不被吞、不误当"验签失败"重入 chain）。
        return decoder.decode(token)
                .map(java.util.Optional::of)
                .onErrorReturn(java.util.Optional.empty())
                .flatMap(opt -> opt.isPresent()
                        ? exchangeAndForward(ex, chain, opt.get())
                        : onMissingOrInvalid(ex, chain));
    }

    /**
     * 无 Bearer / 验签失败时的处置：
     * <ul>
     *   <li>DUAL：透传给 legacy(session/api-key) filter（灰度）。</li>
     *   <li>ONLY：401，不落 legacy——非 open path 必须持有效 Casdoor token，杜绝身份混用。</li>
     * </ul>
     */
    private Mono<Void> onMissingOrInvalid(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (casdoor.getMode() == CasdoorSecurityProperties.Mode.ONLY) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    /** 移除入站内部头（客户端伪造防护）；无则原样返回。 */
    private ServerWebExchange stripInboundInternalHeader(ServerWebExchange exchange) {
        String header = internalProps.getInternalHeader();
        if (exchange.getRequest().getHeaders().getFirst(header) == null) {
            return exchange;
        }
        ServerHttpRequest req = exchange.getRequest().mutate()
                .headers(h -> h.remove(header))
                .build();
        return exchange.mutate().request(req).build();
    }

    private Mono<Void> exchangeAndForward(ServerWebExchange exchange, GatewayFilterChain chain, Jwt jwt) {
        String tenant = jwt.getClaimAsString(casdoor.getTenantClaim());
        String uid = jwt.getClaimAsString(casdoor.getSubjectClaim());
        if (tenant == null || tenant.isBlank() || uid == null || uid.isBlank()) {
            // 是 Casdoor 签的但缺 tenant/sub：fail closed（不透传成匿名）。
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        Set<String> scopes = extractScopes(jwt);
        String department = extractDepartment(jwt, tenant, uid);
        String jwtOut = internalTokens.mint(new TenantContext.Tenant(tenant, uid, scopes, department));
        // 注：本 filter 在 decoder.decode(...) 的【异步】回调里改头，此时 exchange.getRequest().mutate() 的 Builder 头是
        // 只读的（ReadOnlyHttpHeaders）——.header()/.headers(set) 都抛 UnsupportedOperationException（仅真实 Netty 请求才有，
        // mock 单测发现不了；同步的 SessionBearer/ApiKey filter 无此问题）。故绕开 Builder：用可写副本 + Decorator 覆写 getHeaders。
        HttpHeaders forwardHeaders = new HttpHeaders();
        forwardHeaders.addAll(exchange.getRequest().getHeaders());
        forwardHeaders.set(internalProps.getInternalHeader(), jwtOut);
        forwardHeaders.remove(HttpHeaders.AUTHORIZATION); // Casdoor token 不进内网
        // dual 模式客户端同时带 X-Api-Key：Casdoor 已认证并注入内部头，下游 ApiKeyToInternalTokenFilter 见内部头即放行、
        // 不会再处理它，故这里必须一并剥离——否则外部 api-key 明文透传进内网（违反"外部 api-key 不泄内网"约束，#3）。
        forwardHeaders.remove(internalProps.getApiKeyHeader());
        ServerHttpRequest mutated = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public HttpHeaders getHeaders() {
                return forwardHeaders;
            }
        };
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /**
     * 从 Casdoor token 的 groups claim 解析用户唯一部门（一人一部门）。groups 元素形如 {@code <org>/<group>}；
     * 只接受与 token owner 同 org 的组（防跨租户串权），取裸 {@code <group>} 作部门 id。
     * 恰好一个候选→返回；0=缺失、>1=歧义→返回 null（不猜；下游知识写路径按 mode 处理，不因部门异常拒登录）。
     * <p>注：真实 token groups 的精确形状为 V-04 待验证——非字符串列表/异常形状时安全返回 null。
     */
    private String extractDepartment(Jwt jwt, String owner, String uid) {
        Object raw = jwt.getClaim(casdoor.getGroupsClaim());
        if (!(raw instanceof Collection<?> col) || col.isEmpty()) {
            return null;
        }
        String prefix = owner + "/";
        LinkedHashSet<String> depts = new LinkedHashSet<>();
        for (Object o : col) {
            if (!(o instanceof String g) || g.isBlank()) {
                continue;
            }
            if (g.startsWith(prefix)) {
                depts.add(g.substring(prefix.length()));   // <org>/<group> -> <group>
            } else if (!g.contains("/")) {
                depts.add(g);                               // 已是裸名
            }
            // 跨 org 的 group 忽略（防串租户）
        }
        if (depts.size() == 1) {
            return depts.iterator().next();
        }
        if (depts.size() > 1) {
            log.warn("casdoor token owner={} sub={} 含多个部门候选 {}，标 ambiguous，不写 department", owner, uid, depts);
        }
        return null;
    }

    /**
     * 从 {@code scopeClaim} 取 Casdoor 已展开的能力，∩ 固定 {@code scopeAllowlist}（未知 scope 丢弃）。
     * allowlist 为空视为不放行任何 scope（fail closed）。edge 不做 role→scope 展开。
     */
    private Set<String> extractScopes(Jwt jwt) {
        Set<String> out = new LinkedHashSet<>();
        List<String> allow = casdoor.getScopeAllowlist();
        if (allow == null || allow.isEmpty()) {
            return out;
        }
        for (String s : extractCandidates(jwt.getClaim(casdoor.getScopeClaim()))) {
            if (allow.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * claim 可能是：对象数组（Casdoor {@code permissions}，取 {@code scopeNameField} 字段）/ String 数组 /
     * 空格分隔 String（OAuth scope）。edge 只提取名字，不做 role→scope 展开（那在 Casdoor 完成）。
     */
    private List<String> extractCandidates(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> r = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof String s) {
                    r.add(s);
                } else if (o instanceof Map<?, ?> m) {
                    Object v = m.get(casdoor.getScopeNameField());
                    if (v != null) {
                        r.add(String.valueOf(v));
                    }
                } else if (o != null) {
                    r.add(String.valueOf(o));
                }
            }
            return r;
        }
        if (raw instanceof String s && !s.isBlank()) {
            return List.of(s.trim().split("\\s+"));
        }
        return List.of();
    }

    @Override
    public int getOrder() {
        return -120; // 早于 SessionBearerAuthFilter(-110) / ApiKeyToInternalTokenFilter(-100)
    }
}
