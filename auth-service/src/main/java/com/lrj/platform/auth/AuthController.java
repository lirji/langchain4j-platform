package com.lrj.platform.auth;

import com.lrj.platform.auth.dto.AuthPublicConfig;
import com.lrj.platform.auth.dto.LoginRequest;
import com.lrj.platform.auth.dto.LoginResponse;
import com.lrj.platform.auth.dto.RegisterRequest;
import com.lrj.platform.auth.dto.UserView;
import com.lrj.platform.security.TenantContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Locale;

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

    /** 自助注册（默认关闭，见 {@code app.auth.registration.enabled}）。成功即建号并直接登录。 */
    @PostMapping("/register")
    public ResponseEntity<LoginResponse> register(@RequestBody(required = false) RegisterRequest req,
                                                  HttpServletRequest http) {
        String username = req == null ? null : req.username();
        String password = req == null ? null : req.password();
        // 注册按客户端 IP 独立节流（成功/失败都计数），区别于登录的 username+IP。
        AuthResult result = authService.register(username, password, clientIp(http));
        return withRefreshCookie(result);
    }

    /**
     * 公开最小配置（边缘 open 路径，无需 Bearer）。前端在渲染登录/注册页前拉取，据此决定是否显示注册入口、
     * 以及前端侧密码长度提示。只回非敏感项，见 {@link AuthPublicConfig}。
     */
    @GetMapping("/public-config")
    public AuthPublicConfig publicConfig() {
        boolean registrationEnabled = props.getRbac().isEnabled() && props.getRegistration().isEnabled();
        AuthProperties.PasswordPolicy pp = props.getPasswordPolicy();
        return new AuthPublicConfig(registrationEnabled, pp.getMinLength(), pp.getMaxLength());
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

    /**
     * 客户端 IP：默认只取 {@code remoteAddr}。仅当 {@code app.auth.client-ip.trust-forwarded-for=true}
     * （即 edge/Ingress 已覆盖而非透传外部 XFF）时才读 {@code X-Forwarded-For} 首项，否则调用者可伪造
     * XFF 轮换限流 key。
     */
    private String clientIp(HttpServletRequest http) {
        if (props.getClientIp().isTrustForwardedFor()) {
            String fwd = http.getHeader("X-Forwarded-For");
            if (fwd != null && !fwd.isBlank()) {
                int comma = fwd.indexOf(',');
                return (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
            }
        }
        return http.getRemoteAddr();
    }
}
