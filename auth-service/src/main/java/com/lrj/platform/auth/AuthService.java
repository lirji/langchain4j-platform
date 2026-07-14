package com.lrj.platform.auth;

import com.lrj.platform.auth.dto.UserView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

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
    private final EffectivePermissionResolver resolver;
    private final RegistrationRuleEngine registrationRules;
    private final PasswordPolicy passwordPolicy;
    private final RegistrationThrottle registrationThrottle;
    private final RbacMutationExecutor mutationExecutor;
    private final AuthProperties props;

    public AuthService(UserAccountStore userStore,
                       RefreshSessionStore sessionStore,
                       PasswordHasher passwordHasher,
                       SessionTokenIssuer issuer,
                       LoginThrottle throttle,
                       EffectivePermissionResolver resolver,
                       RegistrationRuleEngine registrationRules,
                       PasswordPolicy passwordPolicy,
                       RegistrationThrottle registrationThrottle,
                       RbacMutationExecutor mutationExecutor,
                       AuthProperties props) {
        this.userStore = userStore;
        this.sessionStore = sessionStore;
        this.passwordHasher = passwordHasher;
        this.issuer = issuer;
        this.throttle = throttle;
        this.resolver = resolver;
        this.registrationRules = registrationRules;
        this.passwordPolicy = passwordPolicy;
        this.registrationThrottle = registrationThrottle;
        this.mutationExecutor = mutationExecutor;
        this.props = props;
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

    /**
     * 自助注册（三条分配路径之一）：按规则映射到租户 + 默认/规则角色，原子建号后直接登录。
     * 须同时开 {@code rbac.enabled} 与 {@code registration.enabled}（否则在 direct-only 灰度态会
     * 建出无有效 scopes 的 role-only 死账号）。{@code clientIp} 用于按 IP 的注册节流（成功/失败都计数）。
     * 建号 + 建刷新会话在 {@code mutationExecutor} 内同事务完成（JDBC）。
     */
    public AuthResult register(String username, String password, String clientIp) {
        if (!props.getRbac().isEnabled() || !props.getRegistration().isEnabled()) {
            throw new AuthException(403, "registration_disabled", "自助注册未开启");
        }
        registrationThrottle.checkAndRecord(clientIp);
        passwordPolicy.validate(password);
        if (username == null || username.isBlank()) {
            throw new AuthException(400, "invalid_registration", "用户名不能为空");
        }
        RegistrationRuleEngine.Assignment a = registrationRules.resolve(username);
        resolver.requireRolesExist(a.roles());
        // BCrypt 哈希在锁/事务外完成（CPU 密集，不占临界区）。
        String hash = passwordHasher.hash(password);
        UserAccount user = new UserAccount(
                username.trim(), hash, a.tenant(), username.trim(), Set.of(), a.roles(), true);
        return mutationExecutor.execute(() -> {
            if (!userStore.createIfAbsent(user)) {
                throw new AuthException(409, "username_taken", "用户名已被占用");
            }
            log.info("register ok user={} tenant={} roles={}", user.username(), user.tenant(), user.roles());
            return issueFor(user);
        });
    }

    private AuthResult issueFor(UserAccount user) {
        // RBAC 关闭时只用直配 scopes（灰度态不展开角色，现有用户 direct scopes 保底）；开启时经 resolver
        // 合成（含租户基础/用户组继承，受 inheritanceEnabled 再控）。
        Set<String> effective = props.getRbac().isEnabled()
                ? resolver.effectiveScopes(user)
                : new java.util.LinkedHashSet<>(user.scopes());
        String accessToken = issuer.mintAccessToken(user, effective);
        String rawRefresh = issuer.generateRefreshToken();
        Instant now = Instant.now();
        sessionStore.create(new RefreshSession(
                issuer.hashRefreshToken(rawRefresh),
                user.username(),
                now,
                now.plus(issuer.refreshTtl()),
                false));
        UserView view = new UserView(user.username(), user.tenant(), new ArrayList<>(effective));
        return new AuthResult(accessToken, issuer.accessTtlSeconds(), rawRefresh, view);
    }
}
