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
    // 继承层存储（每个测试实例一份，JUnit 默认按方法新建实例）；共享给 resolver 与 AdminService 以保持一致。
    private final InMemoryTenantPolicyStore tenantStore = new InMemoryTenantPolicyStore();
    private final InMemoryGroupStore groupStore = new InMemoryGroupStore();
    private final InMemoryUserGroupStore userGroupStore = new InMemoryUserGroupStore();

    private AdminService adminService(UserAccountStore users, RoleStore roles, RefreshSessionStore sessions) {
        return adminService(users, roles, sessions, new AuthProperties());
    }

    /** 继承层测试用：可传入开了 inheritanceEnabled 的 props，并共享租户/组存储供断言。 */
    private AdminService adminService(UserAccountStore users, RoleStore roles, RefreshSessionStore sessions,
                                      AuthProperties props) {
        return new AdminService(users, roles,
                new EffectivePermissionResolver(new RoleService(roles), roles,
                        tenantStore, groupStore, userGroupStore, props),
                tenantStore, groupStore, userGroupStore,
                hasher, new PasswordPolicy(props), sessions, props, new InMemoryRbacMutationExecutor());
    }

    private AuthService authService(AuthProperties props, UserAccountStore users, RefreshSessionStore sessions) {
        return new AuthService(users, sessions, hasher,
                new SessionTokenIssuer(new InternalSecurityProperties()), new LoginThrottle(props),
                EffectivePermissionResolver.twoLayer(new InMemoryRoleStore()), new RegistrationRuleEngine(props),
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

    // ---- 继承层：租户基础角色 / 用户组（inheritance on）----

    private static AuthProperties inheritanceProps() {
        AuthProperties p = new AuthProperties();
        p.getRbac().setInheritanceEnabled(true);
        return p;
    }

    private static AuthProperties noSeed() {
        AuthProperties p = new AuthProperties();
        p.getSeed().setEnabled(false);
        return p;
    }

    @Test
    void tenantBaseRoleShrink_revokesTenantUsersSessions() {
        var props = inheritanceProps();
        var users = new InMemoryUserAccountStore(hasher, new AuthProperties());   // bob 在租户 globex
        var sessions = new InMemoryRefreshSessionStore();
        var admin = adminService(users, new InMemoryRoleStore(), sessions, props);
        admin.replaceTenantRoles("globex", Set.of("editor"), -1L);   // 首次绑定：If-Match -1
        Instant now = Instant.now();
        sessions.create(new RefreshSession("h-bob", "bob", now, now.plusSeconds(3600), false));
        admin.clearTenantRoles("globex", admin.tenantVersion("globex"));   // 清空 → 租户内用户降权撤销
        assertThat(sessions.findByTokenHash("h-bob").orElseThrow().revoked()).isTrue();
    }

    @Test
    void groupRoleShrink_revokesMembersSessions() {
        var props = inheritanceProps();
        var users = new InMemoryUserAccountStore(hasher, new AuthProperties());
        var sessions = new InMemoryRefreshSessionStore();
        var admin = adminService(users, new InMemoryRoleStore(), sessions, props);
        admin.createGroup("eng", "工程", Set.of("editor"));
        admin.replaceGroupMembers("eng", Set.of("bob"), admin.groupVersion("eng"));
        Instant now = Instant.now();
        sessions.create(new RefreshSession("h-bob", "bob", now, now.plusSeconds(3600), false));
        admin.updateGroup("eng", "工程", Set.of("viewer"), admin.groupVersion("eng"));   // editor→viewer 收缩(ingest 丢失)
        assertThat(sessions.findByTokenHash("h-bob").orElseThrow().revoked()).isTrue();
    }

    @Test
    void movingMemberOutOfGroup_revokesThatUser() {
        var props = inheritanceProps();
        var users = new InMemoryUserAccountStore(hasher, new AuthProperties());
        var sessions = new InMemoryRefreshSessionStore();
        var admin = adminService(users, new InMemoryRoleStore(), sessions, props);
        admin.createGroup("eng", "", Set.of("editor"));
        admin.replaceGroupMembers("eng", Set.of("bob"), admin.groupVersion("eng"));
        Instant now = Instant.now();
        sessions.create(new RefreshSession("h-bob", "bob", now, now.plusSeconds(3600), false));
        admin.replaceGroupMembers("eng", Set.of(), admin.groupVersion("eng"));   // 移出 bob
        assertThat(sessions.findByTokenHash("h-bob").orElseThrow().revoked()).isTrue();
    }

    @Test
    void lastAdminViaGroup_cannotBeStripped() {
        var props = inheritanceProps();
        var users = new InMemoryUserAccountStore(hasher, noSeed());   // 空库，无种子 admin
        var admin = adminService(users, new InMemoryRoleStore(), new InMemoryRefreshSessionStore(), props);
        admin.createUser("root", "secret1", "acme", Set.of(), true);   // 无角色
        admin.createGroup("admins", "", Set.of("admin"));
        admin.replaceGroupMembers("admins", Set.of("root"), admin.groupVersion("admins"));   // root 仅经组获 role-admin
        // 移出唯一成员 / 削掉组的 admin 角色 → 均会移除最后一个 role-admin → 409
        assertThatThrownBy(() -> admin.replaceGroupMembers("admins", Set.of(), admin.groupVersion("admins")))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(409));
        assertThatThrownBy(() -> admin.updateGroup("admins", "", Set.of("viewer"), admin.groupVersion("admins")))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void lastAdminViaTenant_cannotBeCleared() {
        var props = inheritanceProps();
        var users = new InMemoryUserAccountStore(hasher, noSeed());
        var admin = adminService(users, new InMemoryRoleStore(), new InMemoryRefreshSessionStore(), props);
        admin.createUser("root", "secret1", "acme", Set.of(), true);
        admin.replaceTenantRoles("acme", Set.of("admin"), -1L);   // root 仅经租户基础获 role-admin
        assertThatThrownBy(() -> admin.clearTenantRoles("acme", admin.tenantVersion("acme")))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void deleteRole_referencedByGroupOrTenant_409() {
        var users = new InMemoryUserAccountStore(hasher, new AuthProperties());
        var admin = adminService(users, new InMemoryRoleStore(), new InMemoryRefreshSessionStore(), inheritanceProps());
        admin.createGroup("eng", "", Set.of("editor"));       // editor 无用户引用，但被组引用
        assertThatThrownBy(() -> admin.deleteRole("editor"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(409));
        admin.replaceTenantRoles("acme", Set.of("approver"), -1L);   // approver 被租户引用
        assertThatThrownBy(() -> admin.deleteRole("approver"))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void groupMemberAndUserGroupBoundaries() {
        var users = new InMemoryUserAccountStore(hasher, new AuthProperties());
        var admin = adminService(users, new InMemoryRoleStore(), new InMemoryRefreshSessionStore(), inheritanceProps());
        admin.createGroup("eng", "", Set.of("viewer"));
        // 未知用户加入组 → 400
        assertThatThrownBy(() -> admin.replaceGroupMembers("eng", Set.of("ghost"), admin.groupVersion("eng")))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(400));
        // 未知组分配给用户 → 400
        assertThatThrownBy(() -> admin.replaceUserGroups("bob", Set.of("ghost"), admin.userVersion("bob")))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(400));
        // 组有成员时删除 → 409
        admin.replaceGroupMembers("eng", Set.of("bob"), admin.groupVersion("eng"));
        assertThatThrownBy(() -> admin.deleteGroup("eng", admin.groupVersion("eng")))
                .isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void userGroupAssignment_reflectsInEffectiveScopes_whenInheritanceOn() {
        var props = inheritanceProps();
        var users = new InMemoryUserAccountStore(hasher, new AuthProperties());
        var admin = adminService(users, new InMemoryRoleStore(), new InMemoryRefreshSessionStore(), props);
        admin.createGroup("eng", "", Set.of("editor"));   // chat,ingest
        admin.replaceUserGroups("bob", Set.of("eng"), admin.userVersion("bob"));   // bob=viewer(chat) + 组 editor
        assertThat(admin.effectiveScopesOf(users.findByUsername("bob").orElseThrow()))
                .containsExactlyInAnyOrder("chat", "ingest");
        assertThat(admin.userGroups("bob")).containsExactly("eng");
    }
}
