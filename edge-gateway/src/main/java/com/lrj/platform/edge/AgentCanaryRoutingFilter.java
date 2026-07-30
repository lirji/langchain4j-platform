package com.lrj.platform.edge;

import com.lrj.platform.security.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;

/**
 * 在 edge 完成身份换发和限流之后，按已验签的租户身份把一个只读 Agent 入口灰度到候选版本。
 *
 * <p>Gateway 的静态 route 先选中 {@code AGENT_URI}。全量切换后该基线默认是 AgentScope；
 * Java 只作为显式回滚目标。filter 仅在所有灰度条件满足时改写到候选 URI。
 */
@Component
public class AgentCanaryRoutingFilter implements GlobalFilter, Ordered {

    static final String LEGACY_PATH = "/agent/run";
    static final String CANDIDATE_PATH = "/agent/v2/run";
    static final String BACKEND_HEADER = "X-Agent-Backend";
    static final String CANDIDATE_BACKEND = "agentscope";
    static final String LEGACY_BACKEND = "legacy-java";
    static final String METRIC_NAME = "edge.agent.canary.routing";

    private final AgentCanaryProperties properties;
    private final Map<String, Counter> baselineCounters;
    private final Counter candidateCounter;

    public AgentCanaryRoutingFilter(
            AgentCanaryProperties properties,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.candidateCounter = counter(meterRegistry, CANDIDATE_BACKEND, "allowlisted");
        String baselineBackend = properties.getBaselineBackend();
        this.baselineCounters = Map.of(
                "disabled", counter(meterRegistry, baselineBackend, "disabled"),
                "empty_allowlist", counter(meterRegistry, baselineBackend, "empty_allowlist"),
                "invalid_identity", counter(meterRegistry, baselineBackend, "invalid_identity"),
                "tenant_not_allowed", counter(meterRegistry, baselineBackend, "tenant_not_allowed"),
                "missing_route_url", counter(meterRegistry, baselineBackend, "missing_route_url"));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!isEligibleRequest(exchange)) {
            return chain.filter(exchange);
        }
        if (!properties.isEnabled()) {
            return useBaseline(exchange, chain, "disabled");
        }

        Set<String> allowedTenants = properties.normalizedTenants();
        if (allowedTenants.isEmpty()) {
            return useBaseline(exchange, chain, "empty_allowlist");
        }

        TenantContext.Tenant tenant = EdgeAuthenticatedTenant.get(exchange);
        if (tenant == null || tenant.tenantId() == null || tenant.tenantId().isBlank()) {
            return useBaseline(exchange, chain, "invalid_identity");
        }
        if (!allowedTenants.contains(tenant.tenantId().trim())) {
            return useBaseline(exchange, chain, "tenant_not_allowed");
        }

        URI current = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
        if (current == null) {
            return useBaseline(exchange, chain, "missing_route_url");
        }
        URI candidate = UriComponentsBuilder.fromUri(properties.getUri())
                .replacePath(CANDIDATE_PATH)
                .replaceQuery(current.getRawQuery())
                .fragment(null)
                .build(true)
                .toUri();
        exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, candidate);
        exchange.getResponse().getHeaders().set(BACKEND_HEADER, CANDIDATE_BACKEND);
        candidateCounter.increment();
        return chain.filter(exchange);
    }

    private boolean isEligibleRequest(ServerWebExchange exchange) {
        return HttpMethod.POST.equals(exchange.getRequest().getMethod())
                && LEGACY_PATH.equals(exchange.getRequest().getPath().value());
    }

    private Mono<Void> useBaseline(ServerWebExchange exchange, GatewayFilterChain chain, String reason) {
        exchange.getResponse().getHeaders().set(BACKEND_HEADER, properties.getBaselineBackend());
        baselineCounters.get(reason).increment();
        return chain.filter(exchange);
    }

    private static Counter counter(MeterRegistry registry, String target, String reason) {
        return Counter.builder(METRIC_NAME)
                .description("Agent edge routing decisions for the transparent AgentScope canary")
                .tag("target", target)
                .tag("reason", reason)
                .register(registry);
    }

    @Override
    public int getOrder() {
        // RouteToRequestUrlFilter 已把静态基线 route 解析成 URL；NettyRoutingFilter 尚未发出请求。
        return RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER + 1;
    }
}
