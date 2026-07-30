package com.lrj.platform.edge;

import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;

class AgentCanaryRoutingFilterTest {

    private final InternalSecurityProperties security = new InternalSecurityProperties();
    private final InternalToken tokens = new InternalToken(security.getJwtSecret(), Duration.ofMinutes(5));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void disabledByDefaultKeepsStaticRouteAndReportsAgentScopeBaseline() {
        AgentCanaryProperties properties = new AgentCanaryProperties();
        properties.setTenants(List.of("acme"));
        AgentCanaryRoutingFilter filter = filter(properties);
        MockServerWebExchange exchange = exchange(HttpMethod.POST, "/agent/run", token("acme"));
        EdgeAuthenticatedTenant.set(exchange, tenant("acme"));

        ServerWebExchange captured = run(filter, exchange);

        assertThat((URI) captured.getAttribute(GATEWAY_REQUEST_URL_ATTR))
                .isEqualTo(URI.create("http://legacy-agent:8085/agent/run"));
        assertThat(captured.getResponse().getHeaders().getFirst(AgentCanaryRoutingFilter.BACKEND_HEADER))
                .isEqualTo(AgentCanaryRoutingFilter.CANDIDATE_BACKEND);
        assertCounter(AgentCanaryRoutingFilter.CANDIDATE_BACKEND, "disabled", 1.0);
    }

    @Test
    void allowlistedVerifiedTenantRoutesToCandidateV2AndPreservesContext() {
        AgentCanaryProperties properties = enabledFor("acme");
        AgentCanaryRoutingFilter filter = filter(properties);
        String jwt = token("acme");
        MockServerWebExchange exchange = exchange(
                HttpMethod.POST,
                "/agent/run?mode=read%20only",
                jwt,
                "globex");
        EdgeAuthenticatedTenant.set(exchange, tenant("acme"));

        ServerWebExchange captured = run(filter, exchange);

        assertThat((URI) captured.getAttribute(GATEWAY_REQUEST_URL_ATTR))
                .isEqualTo(URI.create("http://agentscope:8085/agent/v2/run?mode=read%20only"));
        assertThat(captured.getRequest().getHeaders().getFirst(security.getInternalHeader())).isEqualTo(jwt);
        assertThat(captured.getRequest().getHeaders().getFirst("X-Trace-Id")).isEqualTo("trace-canary");
        assertThat(captured.getResponse().getHeaders().getFirst(AgentCanaryRoutingFilter.BACKEND_HEADER))
                .isEqualTo(AgentCanaryRoutingFilter.CANDIDATE_BACKEND);
        assertCounter(AgentCanaryRoutingFilter.CANDIDATE_BACKEND, "allowlisted", 1.0);
    }

    @Test
    void tenantHeaderCannotOverrideVerifiedIdentity() {
        AgentCanaryProperties properties = enabledFor("acme");
        AgentCanaryRoutingFilter filter = filter(properties);
        MockServerWebExchange exchange = exchange(
                HttpMethod.POST,
                "/agent/run",
                token("globex"),
                "acme");
        EdgeAuthenticatedTenant.set(exchange, tenant("globex"));

        ServerWebExchange captured = run(filter, exchange);

        assertThat((URI) captured.getAttribute(GATEWAY_REQUEST_URL_ATTR))
                .isEqualTo(URI.create("http://legacy-agent:8085/agent/run"));
        assertCounter(AgentCanaryRoutingFilter.CANDIDATE_BACKEND, "tenant_not_allowed", 1.0);
    }

    @Test
    void tenantAllowlistUsesExactIsolationKey() {
        AgentCanaryProperties properties = enabledFor("ACME");
        AgentCanaryRoutingFilter filter = filter(properties);

        ServerWebExchange captured = run(
                filter,
                authenticatedExchange(HttpMethod.POST, "/agent/run", "acme"));

        assertThat((URI) captured.getAttribute(GATEWAY_REQUEST_URL_ATTR))
                .isEqualTo(URI.create("http://legacy-agent:8085/agent/run"));
        assertCounter(AgentCanaryRoutingFilter.CANDIDATE_BACKEND, "tenant_not_allowed", 1.0);
    }

    @Test
    void missingOrForgedInternalIdentityCannotSelectCandidate() {
        AgentCanaryProperties properties = enabledFor("acme");
        AgentCanaryRoutingFilter filter = filter(properties);

        ServerWebExchange missing = run(filter, exchange(HttpMethod.POST, "/agent/run", null));
        ServerWebExchange forged = run(filter, exchange(HttpMethod.POST, "/agent/run", "forged"));

        assertThat((URI) missing.getAttribute(GATEWAY_REQUEST_URL_ATTR))
                .isEqualTo(URI.create("http://legacy-agent:8085/agent/run"));
        assertThat((URI) forged.getAttribute(GATEWAY_REQUEST_URL_ATTR))
                .isEqualTo(URI.create("http://legacy-agent:8085/agent/run"));
        assertCounter(AgentCanaryRoutingFilter.CANDIDATE_BACKEND, "invalid_identity", 2.0);
    }

    @Test
    void otherMethodsAndAgentPathsRemainOutsideCanarySlice() {
        AgentCanaryProperties properties = enabledFor("acme");
        AgentCanaryRoutingFilter filter = filter(properties);

        ServerWebExchange get = run(filter, authenticatedExchange(HttpMethod.GET, "/agent/run", "acme"));
        ServerWebExchange dag = run(filter, authenticatedExchange(HttpMethod.POST, "/agent/dag/run", "acme"));

        assertThat((URI) get.getAttribute(GATEWAY_REQUEST_URL_ATTR))
                .isEqualTo(URI.create("http://legacy-agent:8085/agent/run"));
        assertThat((URI) dag.getAttribute(GATEWAY_REQUEST_URL_ATTR))
                .isEqualTo(URI.create("http://legacy-agent:8085/agent/dag/run"));
        assertThat(get.getResponse().getHeaders()).doesNotContainKey(AgentCanaryRoutingFilter.BACKEND_HEADER);
        assertThat(dag.getResponse().getHeaders()).doesNotContainKey(AgentCanaryRoutingFilter.BACKEND_HEADER);
        assertThat(meters.find(AgentCanaryRoutingFilter.METRIC_NAME).counters())
                .allMatch(counter -> counter.count() == 0.0);
    }

    @Test
    void emptyAllowlistFailsClosedToLegacy() {
        AgentCanaryProperties properties = new AgentCanaryProperties();
        properties.setEnabled(true);
        properties.setUri(URI.create("http://agentscope:8085"));
        AgentCanaryRoutingFilter filter = filter(properties);

        ServerWebExchange captured = run(
                filter,
                authenticatedExchange(HttpMethod.POST, "/agent/run", "acme"));

        assertThat((URI) captured.getAttribute(GATEWAY_REQUEST_URL_ATTR))
                .isEqualTo(URI.create("http://legacy-agent:8085/agent/run"));
        assertCounter(AgentCanaryRoutingFilter.CANDIDATE_BACKEND, "empty_allowlist", 1.0);
    }

    @Test
    void explicitJavaRollbackUsesLegacyBackendEvidence() {
        AgentCanaryProperties properties = new AgentCanaryProperties();
        properties.setBaselineBackend(AgentCanaryRoutingFilter.LEGACY_BACKEND);
        properties.validate();
        AgentCanaryRoutingFilter filter = filter(properties);

        ServerWebExchange captured = run(
                filter,
                authenticatedExchange(HttpMethod.POST, "/agent/run", "acme"));

        assertThat(captured.getResponse().getHeaders().getFirst(AgentCanaryRoutingFilter.BACKEND_HEADER))
                .isEqualTo(AgentCanaryRoutingFilter.LEGACY_BACKEND);
        assertCounter(AgentCanaryRoutingFilter.LEGACY_BACKEND, "disabled", 1.0);
    }

    @Test
    void candidateUriAndBaselineLabelRejectUnsafeOrAmbiguousValues() {
        for (String uri : List.of(
                "ftp://agentscope:8085",
                "http://user:pass@agentscope:8085",
                "http://agentscope:8085/prefix",
                "http://agentscope:8085?x=1",
                "http://agentscope:8085#fragment")) {
            AgentCanaryProperties properties = new AgentCanaryProperties();
            properties.setUri(URI.create(uri));
            assertThatThrownBy(properties::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("edge.agent-canary.uri");
        }
        AgentCanaryProperties properties = new AgentCanaryProperties();
        properties.setBaselineBackend("tenant-" + System.nanoTime());
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("edge.agent-canary.baseline-backend");
    }

    private AgentCanaryProperties enabledFor(String tenant) {
        AgentCanaryProperties properties = new AgentCanaryProperties();
        properties.setEnabled(true);
        properties.setUri(URI.create("http://agentscope:8085"));
        properties.setTenants(List.of(" ", " " + tenant + " ", tenant));
        properties.validate();
        return properties;
    }

    private AgentCanaryRoutingFilter filter(AgentCanaryProperties properties) {
        return new AgentCanaryRoutingFilter(properties, meters);
    }

    private String token(String tenant) {
        return tokens.mint(tenant(tenant));
    }

    private TenantContext.Tenant tenant(String tenant) {
        return new TenantContext.Tenant(tenant, "alice", Set.of("agent"));
    }

    private MockServerWebExchange authenticatedExchange(HttpMethod method, String path, String tenant) {
        MockServerWebExchange exchange = exchange(method, path, token(tenant));
        EdgeAuthenticatedTenant.set(exchange, tenant(tenant));
        return exchange;
    }

    private MockServerWebExchange exchange(HttpMethod method, String pathAndQuery, String jwt) {
        return exchange(method, pathAndQuery, jwt, null);
    }

    private MockServerWebExchange exchange(
            HttpMethod method,
            String pathAndQuery,
            String jwt,
            String clientTenant) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest
                .method(method, URI.create(pathAndQuery))
                .header("X-Trace-Id", "trace-canary");
        if (jwt != null) {
            request.header(security.getInternalHeader(), jwt);
        }
        if (clientTenant != null) {
            request.header("X-Tenant-Id", clientTenant);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        String rawPath = URI.create(pathAndQuery).getRawPath();
        String rawQuery = URI.create(pathAndQuery).getRawQuery();
        String legacyUrl = "http://legacy-agent:8085" + rawPath
                + (rawQuery == null ? "" : "?" + rawQuery);
        exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, URI.create(legacyUrl));
        return exchange;
    }

    private ServerWebExchange run(AgentCanaryRoutingFilter filter, MockServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return captured.get();
    }

    private void assertCounter(String target, String reason, double expected) {
        Counter counter = meters.get(AgentCanaryRoutingFilter.METRIC_NAME)
                .tag("target", target)
                .tag("reason", reason)
                .counter();
        assertThat(counter.count()).isEqualTo(expected);
        assertThat(counter.getId().getTags())
                .noneMatch(tag -> Set.of("tenant", "tenantId", "user", "prompt", "token")
                        .contains(tag.getKey()));
    }
}
