package com.lrj.platform.auth;

import com.lrj.platform.auth.dto.LoginRequest;
import com.lrj.platform.auth.dto.LoginResponse;
import com.lrj.platform.auth.dto.UserView;
import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.TenantContext;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AuthControllerTest：验证 {@link AuthController} 的登录/刷新/登出/me/publicConfig 端点。重点覆盖
 * 登录签发访问令牌并写入 HttpOnly 刷新 cookie（原始刷新令牌不进响应体）、刷新时从 cookie 读取并轮转、
 * 登出清 cookie 并作废令牌、{@code me} 从 {@link com.lrj.platform.security.TenantContext} 回显身份（未鉴权 401），
 * 以及注册开关仅在 rbac 与 registration 同开时才暴露。
 */
class AuthControllerTest {

    private AuthService service;
    private SessionTokenIssuer issuer;
    private AuthProperties props;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        PasswordHasher hasher = new PasswordHasher();
        props = new AuthProperties();
        props.getRbac().setEnabled(true);
        InMemoryUserAccountStore userStore = new InMemoryUserAccountStore(hasher, props);
        InMemoryRefreshSessionStore sessionStore = new InMemoryRefreshSessionStore();
        issuer = new SessionTokenIssuer(new InternalSecurityProperties());
        EffectivePermissionResolver resolver = EffectivePermissionResolver.twoLayer(new InMemoryRoleStore());
        RegistrationRuleEngine rules = new RegistrationRuleEngine(props);
        service = new AuthService(userStore, sessionStore, hasher, issuer,
                new LoginThrottle(props), resolver, rules,
                new PasswordPolicy(props), new RegistrationThrottle(props),
                new InMemoryRbacMutationExecutor(), props);
        controller = new AuthController(service, issuer, props);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void loginReturnsTokenAndSetsHttpOnlyRefreshCookie() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setRemoteAddr("127.0.0.1");

        ResponseEntity<LoginResponse> resp =
                controller.login(new LoginRequest("alice", "demo12345"), http);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().accessToken()).isNotBlank();
        assertThat(resp.getBody().user().tenant()).isEqualTo("acme");

        String cookie = firstCookie(resp);
        assertThat(cookie).contains(props.getCookie().getName() + "=")
                .contains("HttpOnly")
                .contains("Path=/auth")
                .contains("SameSite=Lax");
        // 刷新令牌原串绝不进响应体
        assertThat(resp.getBody().toString()).doesNotContain(cookie.split(";")[0].split("=", 2)[1]);
    }

    @Test
    void refreshReadsCookieAndRotates() {
        // 直接从 service 拿一个有效刷新令牌，再验证 controller 能从 cookie 读取并轮转
        AuthResult first = service.login("alice", "demo12345", "k");
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setCookies(new Cookie(props.getCookie().getName(), first.rawRefreshToken()));

        ResponseEntity<LoginResponse> resp = controller.refresh(http);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().accessToken()).isNotBlank();
        assertThat(firstCookie(resp)).contains(props.getCookie().getName() + "=");
    }

    @Test
    void logoutClearsCookieAndReturns204() {
        AuthResult first = service.login("alice", "demo12345", "k");
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setCookies(new Cookie(props.getCookie().getName(), first.rawRefreshToken()));

        ResponseEntity<Void> resp = controller.logout(http);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        assertThat(firstCookie(resp)).contains("Max-Age=0");
        // 令牌确已作废
        assertThatThrownBy(() -> service.refresh(first.rawRefreshToken()))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void meReturnsCurrentTenantFromContext() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat", "ingest")));
        ResponseEntity<UserView> resp = controller.me();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().username()).isEqualTo("alice");
        assertThat(resp.getBody().tenant()).isEqualTo("acme");
        assertThat(resp.getBody().scopes()).contains("chat", "ingest");
    }

    @Test
    void meWithoutAuthReturns401() {
        // 未设置 TenantContext → ANONYMOUS
        assertThatThrownBy(() -> controller.me())
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(401));
    }

    @Test
    void publicConfig_registrationDisabledByDefault_exposesPasswordPolicy() {
        // 默认 registration 关闭（即便 rbac 已开）→ registrationEnabled=false，且只回非敏感项
        var cfg = controller.publicConfig();
        assertThat(cfg.registrationEnabled()).isFalse();
        assertThat(cfg.passwordMinLength()).isEqualTo(6);
        assertThat(cfg.passwordMaxLength()).isEqualTo(128);
    }

    @Test
    void publicConfig_enabledOnlyWhenRbacAndRegistrationBothOn() {
        props.getRegistration().setEnabled(true);   // rbac 已在 setUp 开启
        assertThat(controller.publicConfig().registrationEnabled()).isTrue();
        props.getRbac().setEnabled(false);
        assertThat(controller.publicConfig().registrationEnabled()).isFalse();
    }

    private static String firstCookie(ResponseEntity<?> resp) {
        List<String> cookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull().isNotEmpty();
        return cookies.get(0);
    }
}
