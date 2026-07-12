package com.lrj.platform.edge;

import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SessionBearerAuthFilterTest {

    private final InternalSecurityProperties props = new InternalSecurityProperties();
    private final InternalToken internalTokens =
            new InternalToken(props.getJwtSecret(), Duration.ofMinutes(5));
    private final SessionBearerAuthFilter filter = new SessionBearerAuthFilter(props, internalTokens);

    /** 用会话密钥签发一个前端会话访问 JWT。 */
    private String sessionBearer(TenantContext.Tenant tenant) {
        InternalToken sign = new InternalToken(props.getSession().getJwtSecret(), props.getSession().getAccessTtl());
        return sign.mint(tenant);
    }

    @Test
    void validBearerMintsInternalTokenAndStripsAuthorization() {
        String bearer = sessionBearer(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/chat").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer));

        ServerWebExchange result = runThrough(exchange);

        String internal = result.getRequest().getHeaders().getFirst(props.getInternalHeader());
        assertThat(internal).isNotNull();
        assertThat(result.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isNull();
        // 换发的内部 JWT 能被内部密钥验签，且身份一致
        TenantContext.Tenant t = internalTokens.verify(internal);
        assertThat(t).isNotNull();
        assertThat(t.tenantId()).isEqualTo("acme");
        assertThat(t.userId()).isEqualTo("alice");
    }

    @Test
    void invalidBearerPassesThroughUnchanged() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/chat").header(HttpHeaders.AUTHORIZATION, "Bearer garbage"));

        ServerWebExchange result = runThrough(exchange);

        assertThat(result.getRequest().getHeaders().getFirst(props.getInternalHeader())).isNull();
        // 未换发，交给 api-key filter 处理（Authorization 原样透传）
        assertThat(result.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer garbage");
    }

    @Test
    void noAuthorizationPassesThrough() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/chat"));
        ServerWebExchange result = runThrough(exchange);
        assertThat(result.getRequest().getHeaders().getFirst(props.getInternalHeader())).isNull();
    }

    @Test
    void openLoginPathPassesThroughWithoutBearer() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/auth/login"));
        ServerWebExchange result = runThrough(exchange);
        assertThat(result).isNotNull();
        assertThat(result.getRequest().getHeaders().getFirst(props.getInternalHeader())).isNull();
    }

    private ServerWebExchange runThrough(MockServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return captured.get();
    }
}
