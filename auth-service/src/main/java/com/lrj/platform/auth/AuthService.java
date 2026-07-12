package com.lrj.platform.auth;

import com.lrj.platform.auth.dto.UserView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;

/**
 * 登录编排：密码校验 → 签发会话访问 JWT + 建刷新会话；刷新令牌轮转（撤旧建新）实现静默续期；登出撤销。
 *
 * <p>失败以 {@link AuthException} 抛出（带 HTTP 状态），由 controller 统一映射。为降低账号枚举风险，
 * 未知用户与错误密码统一返回 401。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserAccountStore userStore;
    private final RefreshSessionStore sessionStore;
    private final PasswordHasher passwordHasher;
    private final SessionTokenIssuer issuer;
    private final LoginThrottle throttle;

    public AuthService(UserAccountStore userStore,
                       RefreshSessionStore sessionStore,
                       PasswordHasher passwordHasher,
                       SessionTokenIssuer issuer,
                       LoginThrottle throttle) {
        this.userStore = userStore;
        this.sessionStore = sessionStore;
        this.passwordHasher = passwordHasher;
        this.issuer = issuer;
        this.throttle = throttle;
    }

    /** 账号密码登录。{@code throttleKey} = 用户名|客户端IP，用于爆破节流。 */
    public AuthResult login(String username, String password, String throttleKey) {
        if (throttle.isBlocked(throttleKey)) {
            throw new AuthException(429, "too_many_attempts", "登录尝试过于频繁，请稍后再试");
        }
        Optional<UserAccount> found = userStore.findByUsername(username);
        if (found.isEmpty()) {
            throttle.recordFailure(throttleKey);
            throw new AuthException(401, "invalid_credentials", "用户名或密码错误");
        }
        UserAccount user = found.get();
        if (!user.enabled()) {
            throw new AuthException(403, "account_disabled", "账号已被禁用");
        }
        if (!passwordHasher.matches(password, user.passwordHash())) {
            throttle.recordFailure(throttleKey);
            throw new AuthException(401, "invalid_credentials", "用户名或密码错误");
        }
        throttle.reset(throttleKey);
        log.info("login ok user={} tenant={}", user.username(), user.tenant());
        return issueFor(user);
    }

    /** 用刷新令牌换新会话（轮转：撤销旧刷新令牌，签发新的）。 */
    public AuthResult refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new AuthException(401, "no_refresh_token", "缺少刷新令牌");
        }
        String hash = issuer.hashRefreshToken(rawRefreshToken);
        RefreshSession session = sessionStore.findByTokenHash(hash)
                .orElseThrow(() -> new AuthException(401, "invalid_refresh_token", "刷新令牌无效"));
        if (!session.isActive(Instant.now())) {
            throw new AuthException(401, "expired_refresh_token", "刷新令牌已失效");
        }
        UserAccount user = userStore.findByUsername(session.username())
                .filter(UserAccount::enabled)
                .orElseThrow(() -> {
                    sessionStore.revoke(hash);
                    return new AuthException(401, "account_unavailable", "账号不可用");
                });
        // 轮转：旧令牌一次性作废，再签发新的（防重放）。
        sessionStore.revoke(hash);
        return issueFor(user);
    }

    /** 登出：撤销该刷新令牌对应的会话（幂等）。 */
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        sessionStore.revoke(issuer.hashRefreshToken(rawRefreshToken));
    }

    private AuthResult issueFor(UserAccount user) {
        String accessToken = issuer.mintAccessToken(user);
        String rawRefresh = issuer.generateRefreshToken();
        Instant now = Instant.now();
        sessionStore.create(new RefreshSession(
                issuer.hashRefreshToken(rawRefresh),
                user.username(),
                now,
                now.plus(issuer.refreshTtl()),
                false));
        UserView view = new UserView(user.username(), user.tenant(), new ArrayList<>(user.scopes()));
        return new AuthResult(accessToken, issuer.accessTtlSeconds(), rawRefresh, view);
    }
}
