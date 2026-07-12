package com.lrj.platform.edge;

import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyToInternalTokenFilterTest {

    private final InternalSecurityProperties props = buildProps();
    private final InternalToken tokens = new InternalToken(props.getJwtSecret(), Duration.ofMinutes(5));
    private final ApiKeyToInternalTokenFilter filter = new ApiKeyToInternalTokenFilter(props, tokens);

    private static InternalSecurityProperties buildProps() {
        InternalSecurityProperties p = new InternalSecurityProperties();
        InternalSecurityProperties.KeyBinding binding = new InternalSecurityProperties.KeyBinding();
        binding.setTenant("acme");
        binding.setUser("alice");
        binding.setScopes(List.of("chat"));
        p.setApiKeys(Map.of("dev-key-acme", binding));
        return p;
    }

    @Test
    void alreadyAuthenticatedByBearerPassesThroughWithoutApiKey() {
        // 模拟 SessionBearerAuthFilter 已注入内部 JWT：即使没有 X-Api-Key 也不应 401
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/chat").header(props.getInternalHeader(), "some.internal.jwt"));

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        filter.filter(exchange, passThrough(chainCalled)).block();

        assertThat(chainCalled).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull(); // 未 401
    }

    @Test
    void validApiKeyMintsInternalToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/chat").header(props.getApiKeyHeader(), "dev-key-acme"));

        java.util.concurrent.atomic.AtomicReference<ServerWebExchange> captured = new java.util.concurrent.atomic.AtomicReference<>();
        GatewayFilterChain chain = ex -> { captured.set(ex); return Mono.empty(); };
        filter.filter(exchange, chain).block();

        String internal = captured.get().getRequest().getHeaders().getFirst(props.getInternalHeader());
        assertThat(internal).isNotNull();
        assertThat(tokens.verify(internal).tenantId()).isEqualTo("acme");
        // 外部 api-key 被剥离，不泄进内网
        assertThat(captured.get().getRequest().getHeaders().getFirst(props.getApiKeyHeader())).isNull();
    }

    @Test
    void missingCredentialsReturns401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/chat"));

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        filter.filter(exchange, passThrough(chainCalled)).block();

        assertThat(chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void openPathPassesThrough() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/auth/login"));
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        filter.filter(exchange, passThrough(chainCalled)).block();
        assertThat(chainCalled).isTrue();
    }

    private static GatewayFilterChain passThrough(AtomicBoolean flag) {
        return ex -> {
            flag.set(true);
            return Mono.empty();
        };
    }
}
