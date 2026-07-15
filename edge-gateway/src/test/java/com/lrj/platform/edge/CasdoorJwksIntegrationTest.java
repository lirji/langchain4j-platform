package com.lrj.platform.edge;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Casdoor JWKS 验签集成测试：本地 Casdoor(:8000) password grant 拿真 token，经
 * {@link CasdoorDecoderConfig} 产出的 {@link ReactiveJwtDecoder} 验签，断言 owner/sub claim。
 * 证明 ③ 阶段① 的 decoder 配置对<strong>真实 token</strong> 工作（不止 mock），闭环 P0-1。
 *
 * <p>凭据从 env 读（不入库密钥）：{@code CASDOOR_CLIENT_ID}/{@code CASDOOR_CLIENT_SECRET}；
 * Casdoor 不可达或未提供凭据时自动跳过。
 */
class CasdoorJwksIntegrationTest {

    private static final String CASDOOR = envOr("CASDOOR_URL", "http://localhost:8000");
    private static final String CLIENT_ID = envOr("CASDOOR_CLIENT_ID", "");
    private static final String CLIENT_SECRET = envOr("CASDOOR_CLIENT_SECRET", "");

    @BeforeEach
    void requireCasdoorAndCreds() {
        Assumptions.assumeTrue(!CLIENT_ID.isBlank() && !CLIENT_SECRET.isBlank(),
                "未提供 CASDOOR_CLIENT_ID/SECRET，跳过");
        Assumptions.assumeTrue(reachable(), "Casdoor(" + CASDOOR + ") 未启动，跳过");
    }

    @Test
    void decoder_verifiesRealCasdoorToken() throws Exception {
        String token = fetchToken();
        Assumptions.assumeTrue(token != null && !token.isBlank(), "拿不到 token，跳过");

        CasdoorSecurityProperties props = new CasdoorSecurityProperties();
        props.setEnabled(true);
        props.setIssuer(CASDOOR);
        props.setJwkSetUri(CASDOOR + "/.well-known/jwks");
        props.setAudiences(List.of(CLIENT_ID));
        ReactiveJwtDecoder decoder = new CasdoorDecoderConfig().casdoorJwtDecoder(props);

        Jwt jwt = decoder.decode(token).block();
        assertThat(jwt).as("真实 Casdoor token 验签通过").isNotNull();
        assertThat(jwt.getClaimAsString("owner")).as("tenant←owner").isEqualTo("built-in");
        assertThat(jwt.getClaimAsString("sub")).as("userId←sub 稳定非空").isNotBlank();
    }

    @Test
    void filter_endToEnd_realToken_mintsInternalJwtWithScopes() throws Exception {
        String token = fetchToken();
        Assumptions.assumeTrue(token != null && !token.isBlank(), "拿不到 token，跳过");

        CasdoorSecurityProperties props = new CasdoorSecurityProperties();
        props.setEnabled(true);
        props.setIssuer(CASDOOR);
        props.setJwkSetUri(CASDOOR + "/.well-known/jwks");
        props.setAudiences(List.of(CLIENT_ID));
        props.setScopeClaim("permissions");
        props.setScopeNameField("name");
        props.setScopeAllowlist(List.of("chat", "ingest", "approve", "agent", "channel",
                "eval", "vision", "voice", "analytics", "role-admin", "public-ingest"));
        ReactiveJwtDecoder decoder = new CasdoorDecoderConfig().casdoorJwtDecoder(props);
        InternalSecurityProperties internalProps = new InternalSecurityProperties();
        InternalToken internalTokens =
                new InternalToken("edge-e2e-internal-secret-at-least-32-bytes", Duration.ofMinutes(5));
        CasdoorTokenExchangeFilter filter =
                new CasdoorTokenExchangeFilter(props, internalProps, internalTokens, decoder);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/rag/query").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();

        ServerWebExchange out = captured.get();
        assertThat(out).as("真实 Casdoor token 应换发内部 JWT 放行").isNotNull();
        String internalJwt = out.getRequest().getHeaders().getFirst(internalProps.getInternalHeader());
        assertThat(internalJwt).as("注入内部 JWT").isNotNull();
        TenantContext.Tenant t = internalTokens.verify(internalJwt);
        assertThat(t.tenantId()).as("tenant←owner").isEqualTo("built-in");
        assertThat(t.userId()).as("userId←sub").isNotBlank();
        assertThat(t.scopes()).as("scope 从 Casdoor permissions 提取（前提：casdoor-seed.sh + admin 已分配角色）：非空、都在 allowlist、含基础 chat")
                .isNotEmpty()
                .isSubsetOf(props.getScopeAllowlist())
                .contains("chat");
        assertThat(out.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .as("Casdoor token 不进内网").isNull();
    }

    private static boolean reachable() {
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(CASDOOR + "/.well-known/openid-configuration"))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static String fetchToken() throws Exception {
        String form = "grant_type=password&username=admin&password=123"
                + "&client_id=" + CLIENT_ID + "&client_secret=" + CLIENT_SECRET + "&scope=openid";
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(CASDOOR + "/api/login/oauth/access_token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        String body = r.body();
        int i = body.indexOf("\"access_token\"");
        if (i < 0) {
            return null;
        }
        int start = body.indexOf('"', body.indexOf(':', i) + 1) + 1;
        int end = body.indexOf('"', start);
        return start > 0 && end > start ? body.substring(start, end) : null;
    }

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return v == null ? def : v;
    }
}
