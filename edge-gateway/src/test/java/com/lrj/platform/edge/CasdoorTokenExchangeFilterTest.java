package com.lrj.platform.edge;

import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CasdoorTokenExchangeFilter 单测（mock ReactiveJwtDecoder，无需真实 Casdoor）。
 * 覆盖：Casdoor token→换发内部 JWT（owner/sub/scope 映射 + allowlist 过滤 + 剥离 Authorization）、
 * 无 Bearer/验签失败/open path/已有内部 token 透传、缺 tenant/sub → 401。
 */
class CasdoorTokenExchangeFilterTest {

    private static final String INTERNAL_SECRET = "edge-test-internal-secret-at-least-32-bytes";

    private final InternalSecurityProperties internalProps = new InternalSecurityProperties();
    private final InternalToken tokens = new InternalToken(INTERNAL_SECRET, Duration.ofMinutes(5));

    private CasdoorSecurityProperties casdoorProps() {
        CasdoorSecurityProperties p = new CasdoorSecurityProperties();
        p.setEnabled(true);
        p.setTenantClaim("owner");
        p.setSubjectClaim("sub");
        p.setScopeClaim("scope");
        p.setScopeAllowlist(List.of("chat", "ingest", "approve", "agent", "channel",
                "eval", "vision", "voice", "analytics", "role-admin", "public-ingest"));
        return p;
    }

    private static final class CapturingChain implements GatewayFilterChain {
        final AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            captured.set(exchange);
            return Mono.empty();
        }
    }

    @Test
    void casdoorToken_mintsInternalJwt_andStripsAuthorization() {
        ReactiveJwtDecoder decoder = mock(ReactiveJwtDecoder.class);
        Jwt jwt = Jwt.withTokenValue("casdoor-token").header("alg", "RS256")
                .claim("owner", "built-in").claim("sub", "uuid-123")
                .claim("scope", List.of("chat", "ingest", "unknown-scope")) // unknown 应被 allowlist 过滤
                .build();
        when(decoder.decode("casdoor-token")).thenReturn(Mono.just(jwt));
        CasdoorTokenExchangeFilter filter =
                new CasdoorTokenExchangeFilter(casdoorProps(), internalProps, tokens, decoder);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/rag/query").header(HttpHeaders.AUTHORIZATION, "Bearer casdoor-token"));
        CapturingChain chain = new CapturingChain();
        filter.filter(exchange, chain).block();

        ServerWebExchange out = chain.captured.get();
        assertThat(out).as("应放行到下游").isNotNull();
        String internalJwt = out.getRequest().getHeaders().getFirst(internalProps.getInternalHeader());
        assertThat(internalJwt).as("注入 X-Internal-Token").isNotNull();
        TenantContext.Tenant t = tokens.verify(internalJwt);
        assertThat(t.tenantId()).isEqualTo("built-in");
        assertThat(t.userId()).isEqualTo("uuid-123");
        assertThat(t.scopes()).as("unknown-scope 被 allowlist 过滤").containsExactlyInAnyOrder("chat", "ingest");
        assertThat(out.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .as("Casdoor token 不进内网").isNull();
    }

    @Test
    void casdoorToken_dualMode_stripsInboundApiKey() {
        // dual 模式：客户端同带 Casdoor Bearer + X-Api-Key。Casdoor 验过后必须把外部 api-key 也剥掉，
        // 不泄进内网（下游 ApiKeyToInternalTokenFilter 见内部头即放行、不会再消费/剥离它）。
        ReactiveJwtDecoder decoder = mock(ReactiveJwtDecoder.class);
        Jwt jwt = Jwt.withTokenValue("casdoor-token").header("alg", "RS256")
                .claim("owner", "built-in").claim("sub", "uuid-123")
                .claim("scope", List.of("chat")).build();
        when(decoder.decode("casdoor-token")).thenReturn(Mono.just(jwt));
        CasdoorTokenExchangeFilter filter =
                new CasdoorTokenExchangeFilter(casdoorProps(), internalProps, tokens, decoder);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/rag/query")
                .header(HttpHeaders.AUTHORIZATION, "Bearer casdoor-token")
                .header(internalProps.getApiKeyHeader(), "sk-external-key"));
        CapturingChain chain = new CapturingChain();
        filter.filter(exchange, chain).block();

        ServerWebExchange out = chain.captured.get();
        assertThat(out).as("应放行到下游").isNotNull();
        assertThat(out.getRequest().getHeaders().getFirst(internalProps.getInternalHeader()))
                .as("注入内部 JWT").isNotNull();
        assertThat(out.getRequest().getHeaders().getFirst(internalProps.getApiKeyHeader()))
                .as("外部 X-Api-Key 不进内网").isNull();
        assertThat(out.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .as("Casdoor token 不进内网").isNull();
    }

    @Test
    void noBearer_passesThrough_withoutDecoding() {
        ReactiveJwtDecoder decoder = mock(ReactiveJwtDecoder.class);
        CasdoorTokenExchangeFilter filter =
                new CasdoorTokenExchangeFilter(casdoorProps(), internalProps, tokens, decoder);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/rag/query"));
        CapturingChain chain = new CapturingChain();
        filter.filter(exchange, chain).block();

        assertThat(chain.captured.get()).isNotNull();
        assertThat(chain.captured.get().getRequest().getHeaders().getFirst(internalProps.getInternalHeader())).isNull();
        verify(decoder, never()).decode(any());
    }

    @Test
    void invalidToken_passesThroughToLegacy() {
        ReactiveJwtDecoder decoder = mock(ReactiveJwtDecoder.class);
        when(decoder.decode("not-casdoor")).thenReturn(Mono.error(new BadJwtException("invalid")));
        CasdoorTokenExchangeFilter filter =
                new CasdoorTokenExchangeFilter(casdoorProps(), internalProps, tokens, decoder);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/rag/query").header(HttpHeaders.AUTHORIZATION, "Bearer not-casdoor"));
        CapturingChain chain = new CapturingChain();
        filter.filter(exchange, chain).block();

        assertThat(chain.captured.get()).as("验签失败透传给 legacy").isNotNull();
        assertThat(chain.captured.get().getRequest().getHeaders().getFirst(internalProps.getInternalHeader())).isNull();
    }

    @Test
    void casdoorToken_missingTenant_returns401() {
        ReactiveJwtDecoder decoder = mock(ReactiveJwtDecoder.class);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256")
                .claim("sub", "uuid-123").build(); // 无 owner
        when(decoder.decode("t")).thenReturn(Mono.just(jwt));
        CasdoorTokenExchangeFilter filter =
                new CasdoorTokenExchangeFilter(casdoorProps(), internalProps, tokens, decoder);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/rag/query").header(HttpHeaders.AUTHORIZATION, "Bearer t"));
        CapturingChain chain = new CapturingChain();
        filter.filter(exchange, chain).block();

        assertThat(chain.captured.get()).as("fail closed，不放行").isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void extractsScopeFromCasdoorPermissionsObjects() {
        ReactiveJwtDecoder decoder = mock(ReactiveJwtDecoder.class);
        CasdoorSecurityProperties props = casdoorProps();
        props.setScopeClaim("permissions");
        props.setScopeNameField("name");
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256")
                .claim("owner", "built-in").claim("sub", "uuid-9")
                .claim("permissions", List.of(
                        Map.of("name", "ingest", "actions", List.of("Read")),
                        Map.of("name", "chat"),
                        Map.of("name", "unknown-x"))) // unknown 被 allowlist 过滤
                .build();
        when(decoder.decode("t")).thenReturn(Mono.just(jwt));
        CasdoorTokenExchangeFilter filter =
                new CasdoorTokenExchangeFilter(props, internalProps, tokens, decoder);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/rag/query").header(HttpHeaders.AUTHORIZATION, "Bearer t"));
        CapturingChain chain = new CapturingChain();
        filter.filter(exchange, chain).block();

        String internalJwt = chain.captured.get().getRequest().getHeaders().getFirst(internalProps.getInternalHeader());
        TenantContext.Tenant t = tokens.verify(internalJwt);
        assertThat(t.scopes()).as("从 permissions[].name 提取 + allowlist 过滤")
                .containsExactlyInAnyOrder("ingest", "chat");
    }

    @Test
    void stripsForgedInboundInternalToken() {
        ReactiveJwtDecoder decoder = mock(ReactiveJwtDecoder.class);
        CasdoorTokenExchangeFilter filter =
                new CasdoorTokenExchangeFilter(casdoorProps(), internalProps, tokens, decoder);
        // 客户端伪造入站内部头 + 无 Bearer → 内部头应被剥离后透传（防伪造内部 JWT 绕过认证）。
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/rag/query")
                .header(internalProps.getInternalHeader(), "forged-internal-jwt"));
        CapturingChain chain = new CapturingChain();
        filter.filter(exchange, chain).block();

        assertThat(chain.captured.get().getRequest().getHeaders().getFirst(internalProps.getInternalHeader()))
                .as("客户端伪造的入站内部头被剥离").isNull();
    }

    private CasdoorSecurityProperties casdoorPropsOnly() {
        CasdoorSecurityProperties p = casdoorProps();
        p.setMode(CasdoorSecurityProperties.Mode.ONLY);
        return p;
    }

    @Test
    void onlyMode_noBearer_returns401_noLegacyFallthrough() {
        ReactiveJwtDecoder decoder = mock(ReactiveJwtDecoder.class);
        CasdoorTokenExchangeFilter filter =
                new CasdoorTokenExchangeFilter(casdoorPropsOnly(), internalProps, tokens, decoder);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/rag/query"));
        CapturingChain chain = new CapturingChain();
        filter.filter(exchange, chain).block();

        assertThat(chain.captured.get()).as("ONLY 无 Bearer → 不落 legacy").isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(decoder, never()).decode(any());
    }

    @Test
    void onlyMode_invalidToken_returns401() {
        ReactiveJwtDecoder decoder = mock(ReactiveJwtDecoder.class);
        when(decoder.decode("bad")).thenReturn(Mono.error(new BadJwtException("invalid")));
        CasdoorTokenExchangeFilter filter =
                new CasdoorTokenExchangeFilter(casdoorPropsOnly(), internalProps, tokens, decoder);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/rag/query").header(HttpHeaders.AUTHORIZATION, "Bearer bad"));
        CapturingChain chain = new CapturingChain();
        filter.filter(exchange, chain).block();

        assertThat(chain.captured.get()).as("ONLY 验签失败 → 不落 legacy").isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void onlyMode_validToken_stillMintsInternalJwt() {
        ReactiveJwtDecoder decoder = mock(ReactiveJwtDecoder.class);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256")
                .claim("owner", "acme").claim("sub", "u1").claim("scope", List.of("chat")).build();
        when(decoder.decode("t")).thenReturn(Mono.just(jwt));
        CasdoorTokenExchangeFilter filter =
                new CasdoorTokenExchangeFilter(casdoorPropsOnly(), internalProps, tokens, decoder);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/rag/query").header(HttpHeaders.AUTHORIZATION, "Bearer t"));
        CapturingChain chain = new CapturingChain();
        filter.filter(exchange, chain).block();

        String internalJwt = chain.captured.get().getRequest().getHeaders().getFirst(internalProps.getInternalHeader());
        assertThat(internalJwt).as("ONLY 有效 token 仍换发内部 JWT").isNotNull();
        assertThat(tokens.verify(internalJwt).tenantId()).isEqualTo("acme");
    }

    @Test
    void openPath_passesThrough() {
        ReactiveJwtDecoder decoder = mock(ReactiveJwtDecoder.class);
        CasdoorTokenExchangeFilter filter =
                new CasdoorTokenExchangeFilter(casdoorProps(), internalProps, tokens, decoder);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").header(HttpHeaders.AUTHORIZATION, "Bearer x"));
        CapturingChain chain = new CapturingChain();
        filter.filter(exchange, chain).block();

        assertThat(chain.captured.get()).isNotNull();
        verify(decoder, never()).decode(any());
    }
}
