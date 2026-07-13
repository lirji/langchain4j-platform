package com.lrj.platform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * RBAC 后台用例（三条分配路径之一：admin 手动指派）。纯逻辑，无 HTTP——由 {@code AdminController}
 * 在校验 {@code role-admin} scope 后调用。所有写用例经 {@link RbacMutationExecutor} 原子执行
 * （内存全局锁 / JDBC 事务），使"读当前态 → 校验（最后管理员/引用完整性）→ 多表写 → 撤销 refresh"
 * 相对并发确定。
 *
 * <p>租户隔离与 RBAC 正交：这里能改用户的租户与角色，但改角色**不影响**其能看到哪份数据。
 * 权限降低（禁用 / 有效 scopes 收缩 / 删号 / 角色 scope 缩减）会撤销受影响用户的 refresh sessions，
 * 尽快切断续期；已签发的 access JWT 仍受 TTL 约束（不可即时撤回，无状态 JWT）。
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    static final String ROLE_ADMIN_SCOPE = "role-admin";

    /**
     * 写结果携带在**同一事务/临界区内**读到的版本号——避免 controller 在事务外二次 versionOf 造成 TOCTOU
     * （否则并发写可能让响应变成"本次字段 + 他人版本"，客户端据此 If-Match 反而覆盖他人写）。
     */
    public record VersionedUser(UserAccount account, long version) {}

    public record VersionedRole(Role role, long version) {}

    private final UserAccountStore userStore;
    private final RoleStore roleStore;
    private final RoleService roleService;
    private final PasswordHasher passwordHasher;
    private final PasswordPolicy passwordPolicy;
    private final RefreshSessionStore sessionStore;
    private final RbacMutationExecutor tx;

    public AdminService(UserAccountStore userStore, RoleStore roleStore, RoleService roleService,
                        PasswordHasher passwordHasher, PasswordPolicy passwordPolicy,
                        RefreshSessionStore sessionStore, RbacMutationExecutor mutationExecutor) {
        this.userStore = userStore;
        this.roleStore = roleStore;
        this.roleService = roleService;
        this.passwordHasher = passwordHasher;
        this.passwordPolicy = passwordPolicy;
        this.sessionStore = sessionStore;
        this.tx = mutationExecutor;
    }

    // ---- 用户 ----

    public UserAccount createUser(String username, String password, String tenant,
                                  Set<String> roles, boolean enabled) {
        return createUser(username, password, tenant, Set.of(), roles, enabled);
    }

    /** 建户（可指定直配 scopes）。原子 createIfAbsent，避免并发同名覆盖。 */
    public UserAccount createUser(String username, String password, String tenant,
                                  Set<String> directScopes, Set<String> roles, boolean enabled) {
        if (username == null || username.isBlank()) {
            throw new AuthException(400, "invalid_user", "用户名不能为空");
        }
        passwordPolicy.validate(password);
        String safeTenant = requireSafeTenant(tenant);
        roleService.requireRolesExist(roles);
        UserAccount user = new UserAccount(username.trim(), passwordHasher.hash(password),
                safeTenant, username.trim(), directScopes, roles, enabled);
        return tx.execute(() -> {
            if (!userStore.createIfAbsent(user)) {
                throw new AuthException(409, "username_taken", "用户名已被占用");
            }
            log.info("admin createUser user={} tenant={} roles={} directScopes={}",
                    user.username(), safeTenant, roles, directScopes);
            return user;
        });
    }

    /** 全量更新租户/角色/直配 scopes/启用态（密码为空则不改）。 */
    public UserAccount updateUser(String username, String tenant, Set<String> roles,
                                  boolean enabled, String newPassword) {
        String safeTenant = requireSafeTenant(tenant);
        roleService.requireRolesExist(roles);
        if (newPassword != null && !newPassword.isBlank()) {
            passwordPolicy.validate(newPassword);
        }
        return tx.execute(() -> {
            UserAccount cur = userStore.findByUsername(username)
                    .orElseThrow(() -> new AuthException(404, "user_not_found", "用户不存在"));
            String hash = (newPassword == null || newPassword.isBlank())
                    ? cur.passwordHash() : passwordHasher.hash(newPassword);
            UserAccount updated = new UserAccount(cur.username(), hash, safeTenant, cur.userId(),
                    cur.scopes(), roles, enabled);
            assertAdminRemains(cur.username(), updated);
            userStore.update(updated);
            revokeIfDowngraded(cur, updated);
            log.info("admin updateUser user={} tenant={} roles={} enabled={}",
                    cur.username(), safeTenant, roles, enabled);
            return updated;
        });
    }

    public VersionedUser assignRoles(String username, Set<String> roles) {
        return assignRoles(username, roles, null);
    }

    /**
     * 全量替换角色（PUT）。{@code expectedVersion} 非 null 时启用乐观锁：仅当当前版本匹配才写，
     * 否则 409（防两管理员并发静默覆盖）；null 时不做版本检查（向后兼容）。返回含事务内读到的新版本。
     */
    public VersionedUser assignRoles(String username, Set<String> roles, Long expectedVersion) {
        roleService.requireRolesExist(roles);
        return tx.execute(() -> {
            UserAccount cur = userStore.findByUsername(username)
                    .orElseThrow(() -> new AuthException(404, "user_not_found", "用户不存在"));
            UserAccount updated = new UserAccount(cur.username(), cur.passwordHash(), cur.tenant(),
                    cur.userId(), cur.scopes(), roles, cur.enabled());
            assertAdminRemains(cur.username(), updated);
            boolean wrote = expectedVersion != null
                    ? userStore.replaceRolesIfVersion(cur.username(), roles, expectedVersion)
                    : userStore.replaceRoles(cur.username(), roles);
            requireWritten(wrote);
            revokeIfDowngraded(cur, updated);
            log.info("admin assignRoles user={} roles={}", cur.username(), roles);
            return new VersionedUser(updated, userStore.versionOf(cur.username()));
        });
    }

    public VersionedUser patchUser(String username, String tenant, String password,
                                   Set<String> directScopes, Boolean enabled) {
        return patchUser(username, tenant, password, directScopes, enabled, null);
    }

    /**
     * PATCH：只改传入的非 null 字段（{@code directScopes} 传空集=清空）；不改角色。
     * {@code expectedVersion} 非 null 时启用乐观锁（冲突 409），null 时不做版本检查。返回含事务内读到的新版本。
     */
    public VersionedUser patchUser(String username, String tenant, String password,
                                   Set<String> directScopes, Boolean enabled, Long expectedVersion) {
        String safeTenant = tenant == null ? null : requireSafeTenant(tenant);
        if (password != null && !password.isBlank()) {
            passwordPolicy.validate(password);
        }
        return tx.execute(() -> {
            UserAccount cur = userStore.findByUsername(username)
                    .orElseThrow(() -> new AuthException(404, "user_not_found", "用户不存在"));
            String newTenant = safeTenant != null ? safeTenant : cur.tenant();
            String newHash = (password != null && !password.isBlank())
                    ? passwordHasher.hash(password) : cur.passwordHash();
            Set<String> newScopes = directScopes != null ? directScopes : cur.scopes();
            boolean newEnabled = enabled != null ? enabled : cur.enabled();
            UserAccount updated = new UserAccount(cur.username(), newHash, newTenant, cur.userId(),
                    newScopes, cur.roles(), newEnabled);
            assertAdminRemains(cur.username(), updated);
            boolean wrote = expectedVersion != null
                    ? userStore.updateProfileIfVersion(cur.username(), newTenant, newHash, newScopes, newEnabled, expectedVersion)
                    : userStore.updateProfile(cur.username(), newTenant, newHash, newScopes, newEnabled);
            requireWritten(wrote);
            revokeIfDowngraded(cur, updated);
            log.info("admin patchUser user={} tenant={} enabled={}", cur.username(), newTenant, newEnabled);
            return new VersionedUser(updated, userStore.versionOf(cur.username()));
        });
    }

    public Optional<UserAccount> getUser(String username) {
        return userStore.findByUsername(username);
    }

    /**
     * 详情读：在同一事务/临界区内原子读取 {@code (账号, 版本)}——该版本正是编辑器后续 {@code If-Match} 的基线，
     * 必须与账号字段来自同一快照，否则并发写会造成"旧字段 + 新版本"、客户端据此反而覆盖他人写（GET 侧 TOCTOU）。
     */
    public Optional<VersionedUser> getUserVersioned(String username) {
        return tx.execute(() -> userStore.findByUsername(username)
                .map(u -> new VersionedUser(u, userStore.versionOf(u.username()))));
    }

    /** 用户当前乐观锁版本（视图/If-Match 用）。 */
    public long userVersion(String username) {
        return userStore.versionOf(username);
    }

    /** 角色当前乐观锁版本（视图/If-Match 用）。 */
    public long roleVersion(String name) {
        return roleStore.versionOf(name);
    }

    /** 角色绑定的用户数（编辑影响预览 / 删除引用保护提示）。 */
    public int assignedUserCount(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return 0;
        }
        return userStore.findByRole(roleName.trim().toLowerCase(Locale.ROOT)).size();
    }

    /** 视图用：账号的有效 scopes（角色展开 ∪ 直配），供 admin 查看该用户实际能力。 */
    public Set<String> effectiveScopesOf(UserAccount u) {
        return roleService.effectiveScopes(u);
    }

    public List<UserAccount> listUsers() {
        return userStore.findAll();
    }

    public List<UserAccount> listUsers(int offset, int limit) {
        return userStore.findPage(offset, limit);
    }

    public int countUsers() {
        return userStore.count();
    }

    public void deleteUser(String username) {
        deleteUser(username, null);
    }

    /**
     * 删号（幂等：不存在也不报错）。{@code expectedVersion} 非 null 时启用乐观锁：版本不匹配 412（防删掉陈旧页面上
     * 已被他人改过的资源）。删最后一个 admin 前置 409。同时清该用户 refresh sessions。
     */
    public void deleteUser(String username, Long expectedVersion) {
        tx.run(() -> {
            Optional<UserAccount> cur = userStore.findByUsername(username);
            if (cur.isEmpty()) {
                return; // 幂等：已不存在
            }
            if (expectedVersion != null && userStore.versionOf(cur.get().username()) != expectedVersion) {
                throw new AuthException(412, "precondition_failed", "用户已被修改，请刷新后重试");
            }
            assertAdminRemains(cur.get().username(), null);
            userStore.delete(username);
            sessionStore.deleteByUsername(cur.get().username());
            log.info("admin deleteUser user={}", cur.get().username());
        });
    }

    // ---- 角色 ----

    public List<Role> listRoles() {
        return roleStore.findAll();
    }

    public Optional<Role> getRole(String name) {
        return roleStore.findByName(name);
    }

    /** 角色详情读：事务内原子读 {@code (角色, 版本)}，语义同 {@link #getUserVersioned}。 */
    public Optional<VersionedRole> getRoleVersioned(String name) {
        return tx.execute(() -> roleStore.findByName(name)
                .map(r -> new VersionedRole(r, roleStore.versionOf(r.name()))));
    }

    /** 建角色（POST）：已存在返回 409（不覆盖）。 */
    public Role createRole(String name, Set<String> scopes, String description) {
        if (name == null || name.isBlank()) {
            throw new AuthException(400, "invalid_role", "角色名不能为空");
        }
        Role role = new Role(name, scopes, description);
        requireValidRoleName(role.name());
        requireValidScopes(role.scopes());
        return tx.execute(() -> {
            if (!roleStore.createIfAbsent(role)) {
                throw new AuthException(409, "role_exists", "角色已存在: " + role.name());
            }
            log.info("admin createRole name={} scopes={}", role.name(), role.scopes());
            return role;
        });
    }

    public VersionedRole updateRole(String name, Set<String> scopes, String description) {
        return updateRole(name, scopes, description, null);
    }

    /**
     * 改角色（PUT）：全量替换 scopes/description；不存在返回 404。含最后管理员保护与收缩撤销。
     * {@code expectedVersion} 非 null 时启用乐观锁（冲突 409），null 时不做版本检查。返回含事务内读到的新版本。
     */
    public VersionedRole updateRole(String name, Set<String> scopes, String description, Long expectedVersion) {
        Role role = new Role(name, scopes, description);
        requireValidRoleName(role.name());
        requireValidScopes(role.scopes());
        return tx.execute(() -> {
            Role existing = roleStore.findByName(role.name())
                    .orElseThrow(() -> new AuthException(404, "role_not_found", "角色不存在: " + role.name()));
            Set<String> oldScopes = existing.scopes();
            if (!role.scopes().containsAll(oldScopes)) {
                assertAdminRemainsAfterRoleChange(role.name(), role.scopes());
            }
            boolean wrote = expectedVersion != null
                    ? roleStore.updateIfVersion(role, expectedVersion)
                    : roleStore.update(role);
            requireWritten(wrote);
            revokeUsersOnRoleShrink(role.name(), oldScopes, role.scopes());
            log.info("admin updateRole name={} scopes={}", role.name(), role.scopes());
            return new VersionedRole(role, roleStore.versionOf(role.name()));
        });
    }

    public Role saveRole(String name, Set<String> scopes, String description) {
        if (name == null || name.isBlank()) {
            throw new AuthException(400, "invalid_role", "角色名不能为空");
        }
        Role role = new Role(name, scopes, description);
        requireValidRoleName(role.name());
        requireValidScopes(role.scopes());
        return tx.execute(() -> {
            Optional<Role> existing = roleStore.findByName(role.name());
            Set<String> oldScopes = existing.map(Role::scopes).orElse(Set.of());
            // 若该角色 scope 收缩，可能使某用户失去 role-admin：预检最后管理员不变。
            if (!role.scopes().containsAll(oldScopes)) {
                assertAdminRemainsAfterRoleChange(role.name(), role.scopes());
            }
            roleStore.save(role);
            revokeUsersOnRoleShrink(role.name(), oldScopes, role.scopes());
            log.info("admin saveRole name={} scopes={}", role.name(), role.scopes());
            return role;
        });
    }

    public void deleteRole(String name) {
        deleteRole(name, null);
    }

    /**
     * 删角色。{@code expectedVersion} 非 null 时启用乐观锁（版本不匹配 412）。被任何用户引用则 409（不级联解绑）；
     * 不存在也返回（幂等）。
     */
    public void deleteRole(String name, Long expectedVersion) {
        if (name == null || name.isBlank()) {
            return;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        tx.run(() -> {
            if (expectedVersion != null && roleStore.findByName(key).isPresent()
                    && roleStore.versionOf(key) != expectedVersion) {
                throw new AuthException(412, "precondition_failed", "角色已被修改，请刷新后重试");
            }
            List<UserAccount> assigned = userStore.findByRole(key);
            if (!assigned.isEmpty()) {
                throw new AuthException(409, "role_in_use",
                        "角色被 " + assigned.size() + " 个用户引用，不能删除");
            }
            roleStore.delete(key);
            log.info("admin deleteRole name={}", key);
        });
    }

    // ---- 校验 / 不变式 ----

    /** 条件写返回 false 即版本不匹配（用户/角色已在事务内确认存在），映射为 412 前置条件失败（乐观锁冲突）。 */
    private static void requireWritten(boolean wrote) {
        if (!wrote) {
            throw new AuthException(412, "precondition_failed", "资源已被其他管理员修改，请刷新后重试");
        }
    }

    private static String requireSafeTenant(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            throw new AuthException(400, "invalid_tenant", "租户不能为空");
        }
        String t = tenant.trim();
        if (RegistrationRuleEngine.RESERVED_PUBLIC_TENANT.equalsIgnoreCase(t)) {
            throw new AuthException(400, "reserved_tenant", "不允许使用保留租户");
        }
        return t;
    }

    private static void requireValidRoleName(String name) {
        if (name == null || !name.matches(Role.NAME_PATTERN)) {
            throw new AuthException(400, "invalid_role_name",
                    "角色名格式非法（小写字母开头，可含字母/数字/下划线/连字符，长度 1–64）");
        }
    }

    private static void requireValidScopes(Set<String> scopes) {
        for (String s : scopes) {
            if (!s.matches("^[a-z][a-z0-9:-]{0,63}$")) {
                throw new AuthException(400, "invalid_scope", "scope 格式非法: " + s);
            }
        }
    }

    private boolean hasEffectiveRoleAdmin(UserAccount u) {
        return u.enabled() && roleService.effectiveScopes(u).contains(ROLE_ADMIN_SCOPE);
    }

    /** 预检：把受影响用户替换为 {@code prospective}（null=删除/失效）后，仍须至少一个启用的 role-admin。 */
    private void assertAdminRemains(String affectedUsername, UserAccount prospective) {
        for (UserAccount u : userStore.findAll()) {
            UserAccount effective = u.username().equalsIgnoreCase(affectedUsername) ? prospective : u;
            if (effective != null && hasEffectiveRoleAdmin(effective)) {
                return;
            }
        }
        throw new AuthException(409, "last_admin", "不能移除最后一个启用的 role-admin 用户");
    }

    /** 预检：把某角色的 scopes 换成 {@code newScopes} 后，仍须至少一个启用的 role-admin。 */
    private void assertAdminRemainsAfterRoleChange(String roleName, Set<String> newScopes) {
        for (UserAccount u : userStore.findAll()) {
            if (!u.enabled()) {
                continue;
            }
            Set<String> eff = new LinkedHashSet<>(u.scopes());
            for (String rn : u.roles()) {
                Set<String> rs = rn.equalsIgnoreCase(roleName)
                        ? newScopes : roleStore.findByName(rn).map(Role::scopes).orElse(Set.of());
                eff.addAll(rs);
            }
            if (eff.contains(ROLE_ADMIN_SCOPE)) {
                return;
            }
        }
        throw new AuthException(409, "last_admin", "该改动会移除最后一个启用的 role-admin 用户");
    }

    private void revokeIfDowngraded(UserAccount before, UserAccount after) {
        boolean disabled = before.enabled() && !after.enabled();
        boolean scopesShrank = !roleService.effectiveScopes(after)
                .containsAll(roleService.effectiveScopes(before));
        if (disabled || scopesShrank) {
            sessionStore.revokeByUsername(after.username());
        }
    }

    private void revokeUsersOnRoleShrink(String roleName, Set<String> oldScopes, Set<String> newScopes) {
        if (newScopes.containsAll(oldScopes)) {
            return;
        }
        for (UserAccount u : userStore.findByRole(roleName)) {
            sessionStore.revokeByUsername(u.username());
        }
    }
}
