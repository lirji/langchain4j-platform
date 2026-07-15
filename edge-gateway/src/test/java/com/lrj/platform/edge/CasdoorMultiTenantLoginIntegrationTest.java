package com.lrj.platform.edge;

import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 方案C 多租户登录 E2E（真实 Casdoor）：用 Casdoor <strong>Shared Application</strong> 分别为 acme/beta 两个租户
 * password grant 拿真 token（org 专属 client_id={@code <base>-org-<org>}），经真实 {@link CasdoorDecoderConfig}
 * 验签 + {@link CasdoorTokenExchangeFilter} 换发内部 JWT，断言：
 * <ul>
 *   <li>两个租户的 shared app token 都被真实 decoder 接受（aud 家族 + owner 绑定）；</li>
 *   <li>换发的内部 JWT 的 tenant 正确（acme→acme、beta→beta），证明多租户登录端到端可用。</li>
 * </ul>
 * Casdoor 不可达或 shared app/用户未就绪时自动跳过。凭据/密码从 env 读，默认 dev 值。
 */
class CasdoorMultiTenantLoginIntegrationTest {

    private static final String CASDOOR = envOr("CASDOOR_URL", "http://localhost:8000");
    private static final String BASE_CID = envOr("CASDOOR_SHARED_CLIENT_ID", "ragshared0client00000001");
    private static final String SECRET = envOr("CASDOOR_SHARED_CLIENT_SECRET", "ragshared0secret000000000000000001");

    @BeforeEach
    void requireCasdoor() {
        Assumptions.assumeTrue(reachable(), "Casdoor(" + CASDOOR + ") 未启动，跳过");
    }

    private CasdoorSecurityProperties props() {
        CasdoorSecurityProperties p = new CasdoorSecurityProperties();
        p.setEnabled(true);
        p.setIssuer(CASDOOR);
        p.setJwkSetUri(CASDOOR + "/.well-known/jwks");
        p.setAudiences(List.of(BASE_CID)); // base；decoder 应接受 <base>-org-<org> 家族并绑 owner
        return p;
    }

    @Test
    void decoder_acceptsAcmeSharedAppToken() {
        String token = token("acme", "alice", envOr("ACME_PW", "Alice@12345"));
        Assumptions.assumeTrue(token != null, "拿不到 acme shared-app token，跳过");
        Jwt jwt = new CasdoorDecoderConfig().casdoorJwtDecoder(props()).decode(token).block();
        assertThat(jwt).as("真实 acme token 经真实 decoder 验签+aud家族+owner绑定通过").isNotNull();
        assertThat(jwt.getClaimAsString("owner")).isEqualTo("acme");
        assertThat(jwt.getAudience()).contains(BASE_CID + "-org-acme");
    }

    @Test
    void decoder_acceptsBetaSharedAppToken() {
        String token = token("beta", "carol", envOr("BETA_PW", "Carol@12345"));
        Assumptions.assumeTrue(token != null, "拿不到 beta shared-app token，跳过");
        Jwt jwt = new CasdoorDecoderConfig().casdoorJwtDecoder(props()).decode(token).block();
        assertThat(jwt).as("真实 beta token 通过（同一 shared app、不同 org）").isNotNull();
        assertThat(jwt.getClaimAsString("owner")).isEqualTo("beta");
    }

    @Test
    void filter_acme_mintsInternalJwtWithTenantAcme() {
        String token = token("acme", "alice", envOr("ACME_PW", "Alice@12345"));
        Assumptions.assumeTrue(token != null, "跳过");
        TenantContext.Tenant t = exchangeAndVerify(token);
        assertThat(t).isNotNull();
        assertThat(t.tenantId()).as("tenant←owner=acme").isEqualTo("acme");
        assertThat(t.userId()).isNotBlank();
    }

    @Test
    void filter_beta_mintsInternalJwtWithTenantBeta() {
        String token = token("beta", "carol", envOr("BETA_PW", "Carol@12345"));
        Assumptions.assumeTrue(token != null, "跳过");
        TenantContext.Tenant t = exchangeAndVerify(token);
        assertThat(t.tenantId()).as("tenant←owner=beta（多租户互不串）").isEqualTo("beta");
    }

    /** 经真实 filter 换发内部 JWT 并验签重建 Tenant。 */
    private TenantContext.Tenant exchangeAndVerify(String token) {
        InternalSecurityProperties internalProps = new InternalSecurityProperties();
        InternalToken internalTokens = new InternalToken("edge-e2e-internal-secret-at-least-32-bytes", Duration.ofMinutes(5));
        CasdoorTokenExchangeFilter filter = new CasdoorTokenExchangeFilter(
                props(), internalProps, internalTokens, new CasdoorDecoderConfig().casdoorJwtDecoder(props()));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/rag/query").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            captured.set(ex);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        ServerWebExchange out = captured.get();
        assertThat(out).as("token 被接受、放行").isNotNull();
        String internalJwt = out.getRequest().getHeaders().getFirst(internalProps.getInternalHeader());
        assertThat(internalJwt).as("注入内部 JWT").isNotNull();
        return internalTokens.verify(internalJwt);
    }

    /** 用 shared app 的 org 专属 client_id={@code <base>-org-<org>} password grant 取 token。 */
    private static String token(String org, String user, String pw) {
        try {
            String cid = BASE_CID + "-org-" + org;
            String form = "grant_type=password"
                    + "&username=" + enc(user) + "&password=" + enc(pw)
                    + "&client_id=" + enc(cid) + "&client_secret=" + enc(SECRET)
                    + "&scope=" + enc("openid profile");
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
        } catch (Exception e) {
            return null;
        }
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

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return v == null ? def : v;
    }
}
