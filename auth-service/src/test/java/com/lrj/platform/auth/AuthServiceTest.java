package com.lrj.platform.auth;

import com.lrj.platform.security.InternalSecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AuthServiceTest：验证 {@link AuthService} 的账号密码登录与会话生命周期。覆盖种子账号登录、用户名大小写不敏感、
 * 角色 scope 展开进有效 scopes、错误密码/未知用户 401、禁用账号 403、失败节流 429，以及刷新令牌轮转
 * （旧令牌作废、新令牌可用）、未知/过期会话 401 与登出撤销。
 */
class AuthServiceTest {

    private PasswordHasher hasher;
    private AuthProperties props;
    private InMemoryUserAccountStore userStore;
    private InMemoryRefreshSessionStore sessionStore;
    private SessionTokenIssuer issuer;
    private AuthService service;

    @BeforeEach
    void setUp() {
        hasher = new PasswordHasher();
        props = new AuthProperties();
        props.getRbac().setEnabled(true);   // 测 RBAC-on 行为（角色展开进有效 scopes）
        userStore = new InMemoryUserAccountStore(hasher, props);
        sessionStore = new InMemoryRefreshSessionStore();
        issuer = new SessionTokenIssuer(new InternalSecurityProperties());
        EffectivePermissionResolver resolver = EffectivePermissionResolver.twoLayer(new InMemoryRoleStore());
        RegistrationRuleEngine rules = new RegistrationRuleEngine(props);
        service = new AuthService(userStore, sessionStore, hasher, issuer,
                new LoginThrottle(props), resolver, rules,
                new PasswordPolicy(props), new RegistrationThrottle(props),
                new InMemoryRbacMutationExecutor(), props);
    }

    @Test
    void loginSucceedsWithSeedAccount() {
        AuthResult r = service.login("alice", "demo12345", "alice|ip");
        assertThat(r.accessToken()).isNotBlank();
        assertThat(r.rawRefreshToken()).isNotBlank();
        assertThat(r.expiresInSeconds()).isPositive();
        assertThat(r.user().username()).isEqualTo("alice");
        assertThat(r.user().tenant()).isEqualTo("acme");
        assertThat(r.user().scopes()).contains("chat", "ingest");
    }

    @Test
    void loginIsCaseInsensitiveOnUsername() {
        assertThat(service.login("ALICE", "demo12345", "k").user().tenant()).isEqualTo("acme");
    }

    @Test
    void loginExpandsRoleScopesIntoEffectiveScopes() {
        // alice 挂 admin 角色 → 有效 scopes 含只来自角色展开的 role-admin / public-ingest。
        AuthResult r = service.login("alice", "demo12345", "k");
        assertThat(r.user().scopes()).contains("role-admin", "public-ingest");
    }

    @Test
    void wrongPasswordAndUnknownUserBothReturn401() {
        assertThatThrownBy(() -> service.login("alice", "nope", "k1"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(401));
        assertThatThrownBy(() -> service.login("ghost", "whatever", "k2"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(401));
    }

    @Test
    void disabledAccountReturns403() {
        UserAccountStore disabled = username -> Optional.of(
                new UserAccount("dan", hasher.hash("pw"), "acme", "dan", java.util.Set.of("chat"), false));
        AuthService svc = new AuthService(disabled, sessionStore, hasher, issuer,
                new LoginThrottle(props), EffectivePermissionResolver.twoLayer(new InMemoryRoleStore()),
                new RegistrationRuleEngine(props),
                new PasswordPolicy(props), new RegistrationThrottle(props),
                new InMemoryRbacMutationExecutor(), props);
        assertThatThrownBy(() -> svc.login("dan", "pw", "k"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test
    void throttleReturns429WhenExceeded() {
        props.getThrottle().setMaxAttempts(1);
        try {
            service.login("alice", "wrong", "same-key");
        } catch (AuthException ignored) {
            // 预期 401，记一次失败
        }
        assertThatThrownBy(() -> service.login("alice", "demo12345", "same-key"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(429));
    }

    @Test
    void refreshRotatesAndInvalidatesOldToken() {
        AuthResult first = service.login("alice", "demo12345", "k");
        AuthResult second = service.refresh(first.rawRefreshToken());

        assertThat(second.accessToken()).isNotBlank();
        assertThat(second.rawRefreshToken()).isNotEqualTo(first.rawRefreshToken());
        // 旧刷新令牌已作废（轮转）
        assertThatThrownBy(() -> service.refresh(first.rawRefreshToken()))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(401));
        // 新刷新令牌仍可用
        assertThat(service.refresh(second.rawRefreshToken())).isNotNull();
    }

    @Test
    void refreshWithUnknownTokenReturns401() {
        assertThatThrownBy(() -> service.refresh("not-a-real-token"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(401));
        assertThatThrownBy(() -> service.refresh(null))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(401));
    }

    @Test
    void refreshWithExpiredSessionReturns401() {
        String raw = issuer.generateRefreshToken();
        Instant now = Instant.now();
        sessionStore.create(new RefreshSession(
                issuer.hashRefreshToken(raw), "alice", now.minusSeconds(100), now.minusSeconds(10), false));
        assertThatThrownBy(() -> service.refresh(raw))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(401));
    }

    @Test
    void logoutRevokesRefreshToken() {
        AuthResult r = service.login("alice", "demo12345", "k");
        service.logout(r.rawRefreshToken());
        assertThatThrownBy(() -> service.refresh(r.rawRefreshToken()))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(401));
    }
}
