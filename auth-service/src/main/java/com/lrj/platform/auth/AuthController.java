package com.lrj.platform.auth;

import com.lrj.platform.auth.dto.LoginRequest;
import com.lrj.platform.auth.dto.LoginResponse;
import com.lrj.platform.auth.dto.UserView;
import com.lrj.platform.security.TenantContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

/**
 * 登录端点。{@code /auth/login|refresh|logout} 在网关是 open 路径（无需 Bearer）；{@code /auth/me}
 * 走"会话 Bearer → 网关换发内部 JWT → TenantContext"链路。刷新令牌只经 httpOnly cookie 收发。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final SessionTokenIssuer issuer;
    private final AuthProperties props;

    public AuthController(AuthService authService, SessionTokenIssuer issuer, AuthProperties props) {
        this.authService = authService;
        this.issuer = issuer;
        this.props = props;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody(required = false) LoginRequest req,
                                               HttpServletRequest http) {
        String username = req == null ? null : req.username();
        String password = req == null ? null : req.password();
        AuthResult result = authService.login(username, password, throttleKey(username, http));
        return withRefreshCookie(result);
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(HttpServletRequest http) {
        AuthResult result = authService.refresh(readRefreshCookie(http));
        return withRefreshCookie(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest http) {
        authService.logout(readRefreshCookie(http));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearingCookie().toString())
                .build();
    }

    /** 当前登录用户。身份来自内部 JWT 还原的 {@link TenantContext}（网关已用会话 Bearer 换发）。 */
    @GetMapping("/me")
    public ResponseEntity<UserView> me() {
        TenantContext.Tenant t = TenantContext.current();
        if (t == null || "anonymous".equals(t.userId())) {
            throw new AuthException(401, "unauthenticated", "未登录");
        }
        return ResponseEntity.ok(new UserView(t.userId(), t.tenantId(), new ArrayList<>(t.scopes())));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, Object>> handle(AuthException ex) {
        return ResponseEntity.status(ex.status())
                .body(Map.of("error", ex.code(), "message", ex.getMessage()));
    }

    private ResponseEntity<LoginResponse> withRefreshCookie(AuthResult result) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.rawRefreshToken()).toString())
                .body(new LoginResponse(result.accessToken(), result.expiresInSeconds(), result.user()));
    }

    private ResponseCookie refreshCookie(String rawToken) {
        return baseCookie(rawToken, issuer.refreshTtl().toSeconds());
    }

    private ResponseCookie clearingCookie() {
        return baseCookie("", 0);
    }

    private ResponseCookie baseCookie(String value, long maxAgeSeconds) {
        AuthProperties.Cookie c = props.getCookie();
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(c.getName(), value)
                .httpOnly(true)
                .secure(c.isSecure())
                .path(c.getPath())
                .sameSite(c.getSameSite())
                .maxAge(maxAgeSeconds);
        if (c.getDomain() != null && !c.getDomain().isBlank()) {
            b.domain(c.getDomain());
        }
        return b.build();
    }

    private String readRefreshCookie(HttpServletRequest http) {
        Cookie[] cookies = http.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = props.getCookie().getName();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String throttleKey(String username, HttpServletRequest http) {
        String user = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return user + "|" + clientIp(http);
    }

    private static String clientIp(HttpServletRequest http) {
        String fwd = http.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        return http.getRemoteAddr();
    }
}
