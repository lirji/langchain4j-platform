package com.lrj.platform.auth;

import com.lrj.platform.security.InternalSecurityProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 自助注册（三条分配路径之一）：默认关闭、默认角色、按邮箱域规则映射、保留租户拒绝。 */
class RegistrationTest {

    private final PasswordHasher hasher = new PasswordHasher();

    private AuthService serviceWith(AuthProperties props) {
        // 自助注册须 rbac + registration 同时开；本套件专测注册，统一开 rbac。
        props.getRbac().setEnabled(true);
        InMemoryUserAccountStore userStore = new InMemoryUserAccountStore(hasher, props);
        RoleService roleService = new RoleService(new InMemoryRoleStore());
        return new AuthService(userStore, new InMemoryRefreshSessionStore(), hasher,
                new SessionTokenIssuer(new InternalSecurityProperties()),
                new LoginThrottle(props), roleService, new RegistrationRuleEngine(props),
                new PasswordPolicy(props), new RegistrationThrottle(props),
                new InMemoryRbacMutationExecutor(), props);
    }

    @Test
    void registration_disabledByDefault_returns403() {
        AuthProperties props = new AuthProperties();
        assertThatThrownBy(() -> serviceWith(props).register("newbie", "secret1", "k"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test
    void registration_enabled_grantsDefaultTenantAndRole() {
        AuthProperties props = new AuthProperties();
        props.getRegistration().setEnabled(true);
        props.getRegistration().setDefaultTenant("public-tenant");
        props.getRegistration().setDefaultRole("viewer");

        AuthResult r = serviceWith(props).register("plainname", "secret1", "k");
        assertThat(r.user().tenant()).isEqualTo("public-tenant");
        // viewer 展开成 chat
        assertThat(r.user().scopes()).containsExactly("chat");
    }

    @Test
    void registration_ruleMapsByEmailDomain() {
        AuthProperties props = new AuthProperties();
        props.getRegistration().setEnabled(true);
        AuthProperties.Registration.Rule rule = new AuthProperties.Registration.Rule();
        rule.setEmailDomain("acme.com");
        rule.setTenant("acme");
        rule.setRoles(java.util.List.of("editor"));
        props.getRegistration().setRules(java.util.List.of(rule));

        AuthResult r = serviceWith(props).register("neo@acme.com", "secret1", "k");
        assertThat(r.user().tenant()).isEqualTo("acme");
        // editor 展开成 chat + ingest
        assertThat(r.user().scopes()).containsExactlyInAnyOrder("chat", "ingest");
    }

    @Test
    void registration_intoReservedPublicTenant_isRejected() {
        AuthProperties props = new AuthProperties();
        props.getRegistration().setEnabled(true);
        props.getRegistration().setDefaultTenant("__public__");
        assertThatThrownBy(() -> serviceWith(props).register("someone", "secret1", "k"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void registration_duplicateUsername_conflicts() {
        AuthProperties props = new AuthProperties();
        props.getRegistration().setEnabled(true);
        AuthService svc = serviceWith(props);
        svc.register("dup", "secret1", "k");
        assertThatThrownBy(() -> svc.register("dup", "secret1", "k2"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(409));
    }
}
