package com.lrj.platform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * RBAC 后台用例（三条分配路径之一：admin 手动指派）。纯逻辑，无 HTTP——由 {@code AdminController}
 * 在校验 {@code role-admin} scope 后调用。所有写用例经 {@link RbacMutationExecutor} 原子执行
 * （内存全局锁 / JDBC 事务），使"读当前态 → 校验（最后管理员/引用完整性）→ 多表写 → 撤销 refresh"
 * 相对并发确定。
 *
 * <p>继承式 RBAC：有效 scopes 由 {@link EffectivePermissionResolver} 合成 —— 个人直配 ∪ 个人角色 ∪
 * <b>租户基础角色</b> ∪ <b>用户组角色</b>（后两层受 {@code inheritanceEnabled} 灰度）。因此本类新增租户基础
 * 角色（{@link TenantPolicyStore}）、用户组（{@link GroupStore}）、组成员（{@link UserGroupStore}）三类用例，
 * 且"最后管理员保护""降权撤销"均扩展覆盖这两个新的降权来源（租户/组绑定收缩、移出组、删组）。
 *
 * <p>租户隔离与 RBAC 正交：这里能改用户的租户与角色，但改角色**不影响**其能看到哪份数据。权限降低
 * （禁用 / 有效 scopes 收缩 / 删号 / 角色 scope 缩减 / 租户或组绑定收缩 / 移出组）会撤销受影响用户的 refresh
 * sessions，尽快切断续期；已签发的 access JWT 仍受 TTL 约束（不可即时撤回，无状态 JWT）。
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

    public record VersionedGroup(Group group, long version) {}

    /** 租户基础角色快照（租户非一等实体，故用轻量视图承载 名/绑定角色/版本）。 */
    public record TenantPolicy(String tenant, Set<String> baseRoles, long version) {}

    private final UserAccountStore userStore;
    private final RoleStore roleStore;
    private final EffectivePermissionResolver resolver;
    private final TenantPolicyStore tenantPolicyStore;
    private final GroupStore groupStore;
    private final UserGroupStore userGroupStore;
    private final PasswordHasher passwordHasher;
    private final PasswordPolicy passwordPolicy;
    private final RefreshSessionStore sessionStore;
    private final AuthProperties props;
    private final RbacMutationExecutor tx;

    public AdminService(UserAccountStore userStore, RoleStore roleStore, EffectivePermissionResolver resolver,
                        TenantPolicyStore tenantPolicyStore, GroupStore groupStore, UserGroupStore userGroupStore,
                        PasswordHasher passwordHasher, PasswordPolicy passwordPolicy,
                        RefreshSessionStore sessionStore, AuthProperties props,
                        RbacMutationExecutor mutationExecutor) {
        this.userStore = userStore;
        this.roleStore = roleStore;
        this.resolver = resolver;
        this.tenantPolicyStore = tenantPolicyStore;
        this.groupStore = groupStore;
        this.userGroupStore = userGroupStore;
        this.passwordHasher = passwordHasher;
        this.passwordPolicy = passwordPolicy;
        this.sessionStore = sessionStore;
        this.props = props;
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
        resolver.requireRolesExist(roles);
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
        resolver.requireRolesExist(roles);
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
        resolver.requireRolesExist(roles);
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

    /** 角色被用户组引用的数量（删除影响预览）。 */
    public int boundGroupCount(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return 0;
        }
        return groupStore.groupsUsingRole(roleName.trim().toLowerCase(Locale.ROOT)).size();
    }

    /** 角色被租户基础绑定引用的数量（删除影响预览）。 */
    public int boundTenantCount(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return 0;
        }
        return tenantPolicyStore.tenantsUsingRole(roleName.trim().toLowerCase(Locale.ROOT)).size();
    }

    /** 视图用：账号的有效 scopes（三层合并），供 admin 查看该用户实际能力。 */
    public Set<String> effectiveScopesOf(UserAccount u) {
        return resolver.effectiveScopes(u);
    }

    /** 视图用：账号有效权限的分层归因（供 /effective-permissions 端点准确展示来源）。 */
    public EffectivePermissionResolver.EffectivePermissions effectivePermissionsOf(UserAccount u) {
        return resolver.resolve(u);
    }

    /** 视图用：某用户所属的组名集（UserAdminView.groups）。 */
    public Set<String> userGroups(String username) {
        return userGroupStore.groupsOf(username);
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
     * 已被他人改过的资源）。删最后一个 admin 前置 409。同时清该用户 refresh sessions 与组成员行。
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
            userGroupStore.removeAllForUser(cur.get().username());
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
     * 删角色。{@code expectedVersion} 非 null 时启用乐观锁（版本不匹配 412）。被任何用户/用户组/租户引用则 409
     * （不级联解绑）；不存在也返回（幂等）。
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
            int users = userStore.findByRole(key).size();
            int groups = groupStore.groupsUsingRole(key).size();
            int tenants = tenantPolicyStore.tenantsUsingRole(key).size();
            if (users + groups + tenants > 0) {
                throw new AuthException(409, "role_in_use",
                        "角色被 " + users + " 个用户 / " + groups + " 个用户组 / " + tenants + " 个租户引用，不能删除");
            }
            roleStore.delete(key);
            log.info("admin deleteRole name={}", key);
        });
    }

    // ---- 租户基础角色（继承最外层）----

    /** 租户全集：实际用到的租户 ∪ 已配策略的租户（去重排序）。 */
    public List<String> listTenantNames() {
        Set<String> names = new java.util.TreeSet<>(userStore.distinctTenants());
        names.addAll(tenantPolicyStore.listPolicyTenants());
        return new ArrayList<>(names);
    }

    /** 租户成员数（视图 memberCount）。 */
    public int tenantMemberCount(String tenant) {
        return tenant == null || tenant.isBlank() ? 0 : userStore.findByTenant(tenant.trim()).size();
    }

    /** 租户基础角色（列表视图轻量读；详情用 {@link #getTenantVersioned} 原子读）。 */
    public Set<String> tenantBaseRoles(String tenant) {
        return tenant == null ? Set.of() : tenantPolicyStore.rolesOf(tenant.trim());
    }

    /** 租户策略当前版本（无绑定为 -1）。 */
    public long tenantVersion(String tenant) {
        return tenant == null ? -1L : tenantPolicyStore.versionOf(tenant.trim());
    }

    /** 视图用：把一组角色名展开成 scopes（租户/组的有效 scopes 预览）。 */
    public Set<String> expandRoles(Set<String> roleNames) {
        return resolver.expand(roleNames);
    }

    /** 租户基础角色详情：事务内原子读 {@code (绑定角色, 版本)}，供 If-Match 基线。 */
    public TenantPolicy getTenantVersioned(String tenant) {
        String key = requireSafeTenant(tenant);
        return tx.execute(() -> new TenantPolicy(key, tenantPolicyStore.rolesOf(key), tenantPolicyStore.versionOf(key)));
    }

    /**
     * 全量替换租户基础角色（PUT）。requireRolesExist；最后管理员保护；租户内降权撤销。{@code expectedVersion}
     * 非 null 启用乐观锁（首次绑定用 {@code -1} 表示"期望尚无策略行"）。返回含事务内读到的新版本。
     */
    public TenantPolicy replaceTenantRoles(String tenant, Set<String> roles, Long expectedVersion) {
        String key = requireSafeTenant(tenant);
        resolver.requireRolesExist(roles);
        Set<String> norm = UserAccount.normalize(roles);
        return tx.execute(() -> {
            Set<String> oldRoles = tenantPolicyStore.rolesOf(key);
            if (!norm.containsAll(oldRoles)) {
                assertAdminRemainsAfterTenantChange(key, norm);
            }
            boolean wrote = expectedVersion != null
                    ? tenantPolicyStore.replaceRolesIfVersion(key, norm, expectedVersion)
                    : orTrue(() -> tenantPolicyStore.replaceRoles(key, norm));
            requireWritten(wrote);
            revokeTenantUsersOnShrink(key, oldRoles, norm);
            log.info("admin replaceTenantRoles tenant={} roles={}", key, norm);
            return new TenantPolicy(key, norm, tenantPolicyStore.versionOf(key));
        });
    }

    /** 清空租户基础角色（DELETE）。等价于替换为空集（含降权撤销与最后管理员保护）。 */
    public void clearTenantRoles(String tenant, Long expectedVersion) {
        String key = requireSafeTenant(tenant);
        tx.run(() -> {
            Set<String> oldRoles = tenantPolicyStore.rolesOf(key);
            if (oldRoles.isEmpty()) {
                return; // 幂等：本无绑定
            }
            if (expectedVersion != null && tenantPolicyStore.versionOf(key) != expectedVersion) {
                throw new AuthException(412, "precondition_failed", "租户策略已被修改，请刷新后重试");
            }
            assertAdminRemainsAfterTenantChange(key, Set.of());
            tenantPolicyStore.clear(key);
            revokeTenantUsersOnShrink(key, oldRoles, Set.of());
            log.info("admin clearTenantRoles tenant={}", key);
        });
    }

    // ---- 用户组 ----

    public List<Group> listGroups() {
        return groupStore.findAll();
    }

    public long groupVersion(String name) {
        return groupStore.versionOf(name);
    }

    public int memberCount(String group) {
        return group == null || group.isBlank() ? 0 : userGroupStore.membersOf(group).size();
    }

    public List<String> groupMembers(String group) {
        return userGroupStore.membersOf(group);
    }

    /** 组详情读：事务内原子读 {@code (组, 版本)}。 */
    public Optional<VersionedGroup> getGroupVersioned(String name) {
        return tx.execute(() -> groupStore.findByName(name)
                .map(g -> new VersionedGroup(g, groupStore.versionOf(g.name()))));
    }

    /** 建组（POST）：已存在返回 409。requireRolesExist。 */
    public Group createGroup(String name, String description, Set<String> roles) {
        if (name == null || name.isBlank()) {
            throw new AuthException(400, "invalid_group", "组名不能为空");
        }
        Group group = new Group(name, description, roles);
        requireValidGroupName(group.name());
        resolver.requireRolesExist(group.roles());
        return tx.execute(() -> {
            if (!groupStore.createIfAbsent(group)) {
                throw new AuthException(409, "group_exists", "用户组已存在: " + group.name());
            }
            log.info("admin createGroup name={} roles={}", group.name(), group.roles());
            return group;
        });
    }

    /**
     * 改组（PUT）：全量替换 description/roles；不存在 404。含最后管理员保护与成员降权撤销。
     * {@code expectedVersion} 非 null 启用乐观锁（冲突 409）。返回含事务内读到的新版本。
     */
    public VersionedGroup updateGroup(String name, String description, Set<String> roles, Long expectedVersion) {
        Group group = new Group(name, description, roles);
        requireValidGroupName(group.name());
        resolver.requireRolesExist(group.roles());
        return tx.execute(() -> {
            Group existing = groupStore.findByName(group.name())
                    .orElseThrow(() -> new AuthException(404, "group_not_found", "用户组不存在: " + group.name()));
            Set<String> oldRoles = existing.roles();
            if (!group.roles().containsAll(oldRoles)) {
                assertAdminRemainsAfterGroupRoleChange(group.name(), group.roles());
            }
            long baseVersion = expectedVersion != null ? expectedVersion : groupStore.versionOf(group.name());
            requireWritten(groupStore.updateIfVersion(group, baseVersion));
            revokeGroupMembersOnRoleShrink(group.name(), oldRoles, group.roles());
            log.info("admin updateGroup name={} roles={}", group.name(), group.roles());
            return new VersionedGroup(group, groupStore.versionOf(group.name()));
        });
    }

    /** 删组：有成员则 409（不级联解绑，先清空成员再删）；不存在也返回（幂等）。 */
    public void deleteGroup(String name, Long expectedVersion) {
        if (name == null || name.isBlank()) {
            return;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        tx.run(() -> {
            if (groupStore.findByName(key).isEmpty()) {
                return; // 幂等
            }
            if (expectedVersion != null && groupStore.versionOf(key) != expectedVersion) {
                throw new AuthException(412, "precondition_failed", "用户组已被修改，请刷新后重试");
            }
            int members = userGroupStore.membersOf(key).size();
            if (members > 0) {
                throw new AuthException(409, "group_in_use",
                        "用户组有 " + members + " 个成员，请先清空成员再删除");
            }
            groupStore.delete(key);
            log.info("admin deleteGroup name={}", key);
        });
    }

    /**
     * 全量替换组成员（PUT /groups/{g}/members）。校验成员用户存在；最后管理员保护；被移出的成员降权撤销。
     * {@code expectedVersion} 非 null 启用乐观锁（bump 组版本）。返回含事务内读到的新版本。
     */
    public VersionedGroup replaceGroupMembers(String name, Set<String> members, Long expectedVersion) {
        String key = name == null ? null : name.trim().toLowerCase(Locale.ROOT);
        Set<String> normMembers = normalizeUsernames(members);
        return tx.execute(() -> {
            Group group = groupStore.findByName(key)
                    .orElseThrow(() -> new AuthException(404, "group_not_found", "用户组不存在"));
            requireUsersExist(normMembers);
            assertAdminRemainsAfterMembershipChange(group.name(), normMembers);
            List<String> before = userGroupStore.membersOf(group.name());
            long baseVersion = expectedVersion != null ? expectedVersion : groupStore.versionOf(group.name());
            requireWritten(groupStore.touchVersionIfVersion(group.name(), baseVersion));
            userGroupStore.replaceMembersForGroup(group.name(), normMembers);
            // 被移出的成员失去该组角色 → 撤销其 refresh session。
            for (String u : before) {
                if (!normMembers.contains(u)) {
                    sessionStore.revokeByUsername(u);
                }
            }
            log.info("admin replaceGroupMembers group={} members={}", group.name(), normMembers.size());
            return new VersionedGroup(group, groupStore.versionOf(group.name()));
        });
    }

    // ---- 用户↔组（用户侧）----

    /**
     * 全量替换某用户的组集（PUT /users/{u}/groups）。校验组存在；最后管理员保护；若组集收缩则撤销该用户 session。
     * {@code expectedVersion} 非 null 启用乐观锁（bump 用户版本）。返回含事务内读到的新版本。
     */
    public VersionedUser replaceUserGroups(String username, Set<String> groups, Long expectedVersion) {
        Set<String> normGroups = normalizeGroupNames(groups);
        return tx.execute(() -> {
            UserAccount cur = userStore.findByUsername(username)
                    .orElseThrow(() -> new AuthException(404, "user_not_found", "用户不存在"));
            requireGroupsExist(normGroups);
            assertAdminRemainsAfterUserGroupsChange(cur.username(), normGroups);
            Set<String> before = userGroupStore.groupsOf(cur.username());
            long baseVersion = expectedVersion != null ? expectedVersion : userStore.versionOf(cur.username());
            requireWritten(userStore.touchVersionIfVersion(cur.username(), baseVersion));
            userGroupStore.replaceGroupsForUser(cur.username(), normGroups);
            if (!normGroups.containsAll(before)) {   // 组集收缩 → 可能失去继承 scope
                sessionStore.revokeByUsername(cur.username());
            }
            log.info("admin replaceUserGroups user={} groups={}", cur.username(), normGroups);
            return new VersionedUser(userStore.findByUsername(cur.username()).orElse(cur),
                    userStore.versionOf(cur.username()));
        });
    }

    // ---- 校验 / 不变式 ----

    /** 条件写返回 false 即版本不匹配（用户/角色/组已在事务内确认存在），映射为 412 前置条件失败（乐观锁冲突）。 */
    private static void requireWritten(boolean wrote) {
        if (!wrote) {
            throw new AuthException(412, "precondition_failed", "资源已被其他管理员修改，请刷新后重试");
        }
    }

    private static boolean orTrue(Runnable r) {
        r.run();
        return true;
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

    private static void requireValidGroupName(String name) {
        if (name == null || !name.matches(Group.NAME_PATTERN)) {
            throw new AuthException(400, "invalid_group_name",
                    "组名格式非法（小写字母开头，可含字母/数字/下划线/连字符，长度 1–64）");
        }
    }

    private static void requireValidScopes(Set<String> scopes) {
        for (String s : scopes) {
            if (!s.matches("^[a-z][a-z0-9:-]{0,63}$")) {
                throw new AuthException(400, "invalid_scope", "scope 格式非法: " + s);
            }
        }
    }

    private void requireUsersExist(Set<String> usernames) {
        for (String u : usernames) {
            if (userStore.findByUsername(u).isEmpty()) {
                throw new AuthException(400, "unknown_user", "用户不存在: " + u);
            }
        }
    }

    private void requireGroupsExist(Set<String> groups) {
        for (String g : groups) {
            if (groupStore.findByName(g).isEmpty()) {
                throw new AuthException(400, "unknown_group", "用户组不存在: " + g);
            }
        }
    }

    private static Set<String> normalizeUsernames(Set<String> in) {
        Set<String> out = new LinkedHashSet<>();
        if (in != null) {
            for (String s : in) {
                if (s != null && !s.isBlank()) {
                    out.add(s.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private static Set<String> normalizeGroupNames(Set<String> in) {
        return normalizeUsernames(in); // 同规则：trim + 小写 + 去空
    }

    private boolean hasEffectiveRoleAdmin(UserAccount u) {
        return u.enabled() && resolver.effectiveScopes(u).contains(ROLE_ADMIN_SCOPE);
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

    /**
     * 前瞻"最后管理员"通用守卫：在给定世界覆盖下（{@code groupNamesFn} 覆盖每用户的组集，其余三函数覆盖
     * 租户角色/组角色/角色 scope 的单维度），是否仍有启用的 role-admin。传 null 用 store 默认。inheritance 关时
     * 租户/组两层不参与评估（与 token 现实一致）。
     */
    private void assertAdminRemainsProspective(Function<UserAccount, Set<String>> groupNamesFn,
                                               Function<String, Set<String>> tenantRolesFn,
                                               Function<String, Set<String>> groupRolesFn,
                                               Function<String, Set<String>> roleScopesFn) {
        for (UserAccount u : userStore.findAll()) {
            if (!u.enabled()) {
                continue;
            }
            Set<String> gn = groupNamesFn != null ? groupNamesFn.apply(u) : null;
            if (resolver.effectiveScopesProspective(u, gn, tenantRolesFn, groupRolesFn, roleScopesFn)
                    .contains(ROLE_ADMIN_SCOPE)) {
                return;
            }
        }
        throw new AuthException(409, "last_admin", "该改动会移除最后一个启用的 role-admin 用户");
    }

    /** 预检：把某角色的 scopes 换成 {@code newScopes} 后，仍须至少一个启用的 role-admin（含经租户/组获得者）。 */
    private void assertAdminRemainsAfterRoleChange(String roleName, Set<String> newScopes) {
        String key = roleName.trim().toLowerCase(Locale.ROOT);
        assertAdminRemainsProspective(null, null, null,
                rn -> rn.equalsIgnoreCase(key) ? newScopes : roleStore.findByName(rn).map(Role::scopes).orElse(Set.of()));
    }

    /** 预检：把某租户的基础角色换成 {@code newRoles} 后，仍须至少一个启用的 role-admin。 */
    private void assertAdminRemainsAfterTenantChange(String tenant, Set<String> newRoles) {
        String t = tenant.trim();
        assertAdminRemainsProspective(null,
                tt -> tt.equals(t) ? newRoles : tenantPolicyStore.rolesOf(tt), null, null);
    }

    /** 预检：把某组的角色换成 {@code newRoles} 后，仍须至少一个启用的 role-admin。 */
    private void assertAdminRemainsAfterGroupRoleChange(String group, Set<String> newRoles) {
        String g = group.trim().toLowerCase(Locale.ROOT);
        assertAdminRemainsProspective(null, null,
                gg -> gg.equalsIgnoreCase(g) ? newRoles : groupStore.findByName(gg).map(Group::roles).orElse(Set.of()),
                null);
    }

    /** 预检：把某组的成员换成 {@code newMembers} 后（被移出者失去组角色），仍须至少一个启用的 role-admin。 */
    private void assertAdminRemainsAfterMembershipChange(String group, Set<String> newMembers) {
        String g = group.trim().toLowerCase(Locale.ROOT);
        assertAdminRemainsProspective(u -> {
            Set<String> gs = new LinkedHashSet<>(userGroupStore.groupsOf(u.username()));
            if (newMembers.contains(u.username().trim().toLowerCase(Locale.ROOT))) {
                gs.add(g);
            } else {
                gs.remove(g);
            }
            return gs;
        }, null, null, null);
    }

    /** 预检：把某用户的组集换成 {@code newGroups} 后，仍须至少一个启用的 role-admin。 */
    private void assertAdminRemainsAfterUserGroupsChange(String username, Set<String> newGroups) {
        String un = username.trim();
        assertAdminRemainsProspective(
                u -> u.username().equalsIgnoreCase(un) ? newGroups : userGroupStore.groupsOf(u.username()),
                null, null, null);
    }

    private void revokeIfDowngraded(UserAccount before, UserAccount after) {
        boolean disabled = before.enabled() && !after.enabled();
        boolean scopesShrank = !resolver.effectiveScopes(after)
                .containsAll(resolver.effectiveScopes(before));
        if (disabled || scopesShrank) {
            sessionStore.revokeByUsername(after.username());
        }
    }

    /**
     * 角色 scope 收缩时撤销所有可能受影响用户的 refresh session：直配该角色者 ∪（继承开时）该角色作为租户基础/
     * 组绑定所覆盖的用户。保守撤销（不算 per-user 差异），尽快切断续期。
     */
    private void revokeUsersOnRoleShrink(String roleName, Set<String> oldScopes, Set<String> newScopes) {
        if (newScopes.containsAll(oldScopes)) {
            return;
        }
        Set<String> affected = new LinkedHashSet<>();
        for (UserAccount u : userStore.findByRole(roleName)) {
            affected.add(u.username());
        }
        if (props.getRbac().isInheritanceEnabled()) {
            for (String t : tenantPolicyStore.tenantsUsingRole(roleName)) {
                for (UserAccount u : userStore.findByTenant(t)) {
                    affected.add(u.username());
                }
            }
            for (String g : groupStore.groupsUsingRole(roleName)) {
                affected.addAll(userGroupStore.membersOf(g));
            }
        }
        for (String u : affected) {
            sessionStore.revokeByUsername(u);
        }
    }

    /** 租户基础角色收缩时撤销该租户全体用户 refresh session（保守撤销）。 */
    private void revokeTenantUsersOnShrink(String tenant, Set<String> oldRoles, Set<String> newRoles) {
        if (newRoles.containsAll(oldRoles)) {
            return;
        }
        for (UserAccount u : userStore.findByTenant(tenant)) {
            sessionStore.revokeByUsername(u.username());
        }
    }

    /** 组角色收缩时撤销该组成员 refresh session（保守撤销）。 */
    private void revokeGroupMembersOnRoleShrink(String group, Set<String> oldRoles, Set<String> newRoles) {
        if (newRoles.containsAll(oldRoles)) {
            return;
        }
        for (String u : userGroupStore.membersOf(group)) {
            sessionStore.revokeByUsername(u);
        }
    }
}
