package com.lrj.platform.auth;

import com.lrj.platform.security.InternalSecurityProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Stage 2 核心业务逻辑：最后管理员保护、角色引用完整性、降权撤销 refresh、并发注册原子、双开关。 */
class RbacCoreLogicTest {

    private final PasswordHasher hasher = new PasswordHasher();

    private AdminService adminService(UserAccountStore users, RoleStore roles, RefreshSessionStore sessions) {
        return new AdminService(users, roles, new RoleService(roles), hasher,
                new PasswordPolicy(new AuthProperties()), sessions, new InMemoryRbacMutationExecutor());
    }

    private AuthService authService(AuthProperties props, UserAccountStore users, RefreshSessionStore sessions) {
        return new AuthService(users, sessions, hasher,
                new SessionTokenIssuer(new InternalSecurityProperties()), new LoginThrottle(props),
                new RoleService(new InMemoryRoleStore()), new RegistrationRuleEngine(props),
                new PasswordPolicy(props), new RegistrationThrottle(props),
                new InMemoryRbacMutationExecutor(), props);
    }

    // ---- 最后管理员保护 ----

    @Test
    void cannotRemoveRoleAdminFromLastAdmin() {
        var users = new InMemoryUserAccountStore(hasher, new AuthProperties());  // alice=admin 唯一
        var admin = adminService(users, new InMemoryRoleStore(), new InMemoryRefreshSessionStore());
        assertThatThrownBy(() -> admin.assignRoles("alice", Set.of("viewer")))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(409));
        assertThatThrownBy(() -> admin.updateUser("alice", "acme", Set.of("admin"), false, null))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(409));
        assertThatThrownBy(() -> admin.deleteUser("alice"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void canRemoveAdminWhenAnotherAdminExists() {
        var users = new InMemoryUserAccountStore(hasher, new AuthProperties());
        var admin = adminService(users, new InMemoryRoleStore(), new InMemoryRefreshSessionStore());
        admin.createUser("admin2", "secret1", "acme", Set.of("admin"), true);
        admin.assignRoles("alice", Set.of("viewer"));   // 现在 admin2 仍是 admin → 放行
        assertThat(users.findByUsername("alice").orElseThrow().roles()).containsExactly("viewer");
    }

    @Test
    void editingRoleScopesCannotStripLastAdminRoleAdmin() {
        var users = new InMemoryUserAccountStore(hasher, new AuthProperties());
        var roles = new InMemoryRoleStore();
        var admin = adminService(users, roles, new InMemoryRefreshSessionStore());
        // 把 admin 角色的 scopes 改成不含 role-admin → alice 失去 role-admin 且无其他 admin → 409
        assertThatThrownBy(() -> admin.saveRole("admin", Set.of("chat"), "削权"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(409));
    }

    // ---- 角色引用完整性 ----

    @Test
    void deleteRole_referenced_409_unreferenced_ok() {
        var users = new InMemoryUserAccountStore(hasher, new AuthProperties());  // bob=viewer
        var roles = new InMemoryRoleStore();
        var admin = adminService(users, roles, new InMemoryRefreshSessionStore());
        assertThatThrownBy(() -> admin.deleteRole("viewer"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(409));
        admin.deleteRole("approver");   // 无人引用 → 放行
        assertThat(roles.findByName("approver")).isEmpty();
    }

    @Test
    void createUser_withUnknownRole_rejected() {
        var users = new InMemoryUserAccountStore(hasher, new AuthProperties());
        var admin = adminService(users, new InMemoryRoleStore(), new InMemoryRefreshSessionStore());
        assertThatThrownBy(() -> admin.createUser("x", "secret1", "acme", Set.of("wizard"), true))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(400));
    }

    // ---- 降权撤销 refresh ----

    @Test
    void disablingUser_revokesRefreshSessions() {
        var users = new InMemoryUserAccountStore(hasher, new AuthProperties());
        var sessions = new InMemoryRefreshSessionStore();
        var admin = adminService(users, new InMemoryRoleStore(), sessions);
        Instant now = Instant.now();
        sessions.create(new RefreshSession("h-bob", "bob", now, now.plusSeconds(3600), false));
        admin.updateUser("bob", "globex", Set.of(), false, null);   // 禁用 bob
        assertThat(sessions.findByTokenHash("h-bob").orElseThrow().revoked()).isTrue();
    }

    @Test
    void shrinkingRoleScopes_revokesHoldersRefreshSessions() {
        var users = new InMemoryUserAccountStore(hasher, new AuthProperties());
        var roles = new InMemoryRoleStore();
        var sessions = new InMemoryRefreshSessionStore();
        var admin = adminService(users, roles, sessions);
        Instant now = Instant.now();
        sessions.create(new RefreshSession("h-analyst", "analyst-a", now, now.plusSeconds(3600), false));
        // analyst 角色从 {chat,analytics} 收缩为 {chat} → 持有者 analyst-a 的 refresh 被撤销
        admin.saveRole("analyst", Set.of("chat"), "收缩");
        assertThat(sessions.findByTokenHash("h-analyst").orElseThrow().revoked()).isTrue();
    }

    // ---- 注册：原子 + 双开关 ----

    @Test
    void registerRequiresBothRbacAndRegistrationFlags() {
        AuthProperties p = new AuthProperties();
        p.getRegistration().setEnabled(true);   // 只开 registration，rbac 仍关
        var svc = authService(p, new InMemoryUserAccountStore(hasher, p), new InMemoryRefreshSessionStore());
        assertThatThrownBy(() -> svc.register("x", "secret1", "ip"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test
    void concurrentRegister_sameUsername_onlyOneSucceeds() throws Exception {
        AuthProperties p = new AuthProperties();
        p.getRbac().setEnabled(true);
        p.getRegistration().setEnabled(true);
        var users = new InMemoryUserAccountStore(hasher, p);
        var svc = authService(p, users, new InMemoryRefreshSessionStore());

        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            String ip = "ip-" + i;   // 不同 IP，避开注册节流
            pool.submit(() -> {
                try {
                    start.await();
                    svc.register("racer", "secret1", ip);
                    ok.incrementAndGet();
                } catch (AuthException e) {
                    if (e.status() == 409) {
                        conflict.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(ok.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(n - 1);
        assertThat(users.findByUsername("racer")).isPresent();
    }
}
