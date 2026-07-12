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

class AuthControllerTest {

    private AuthService service;
    private SessionTokenIssuer issuer;
    private AuthProperties props;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        PasswordHasher hasher = new PasswordHasher();
        props = new AuthProperties();
        InMemoryUserAccountStore userStore = new InMemoryUserAccountStore(hasher, props);
        InMemoryRefreshSessionStore sessionStore = new InMemoryRefreshSessionStore();
        issuer = new SessionTokenIssuer(new InternalSecurityProperties());
        service = new AuthService(userStore, sessionStore, hasher, issuer, new LoginThrottle(props));
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

    private static String firstCookie(ResponseEntity<?> resp) {
        List<String> cookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull().isNotEmpty();
        return cookies.get(0);
    }
}
