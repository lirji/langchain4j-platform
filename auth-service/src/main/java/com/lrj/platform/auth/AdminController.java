package com.lrj.platform.auth;

import com.lrj.platform.auth.dto.AdminDtos.CreateGroupRequest;
import com.lrj.platform.auth.dto.AdminDtos.CreateRoleRequest;
import com.lrj.platform.auth.dto.AdminDtos.CreateUserRequest;
import com.lrj.platform.auth.dto.AdminDtos.EffectivePermissionsView;
import com.lrj.platform.auth.dto.AdminDtos.GroupView;
import com.lrj.platform.auth.dto.AdminDtos.ReplaceMembersRequest;
import com.lrj.platform.auth.dto.AdminDtos.ReplaceRolesRequest;
import com.lrj.platform.auth.dto.AdminDtos.ReplaceTenantRolesRequest;
import com.lrj.platform.auth.dto.AdminDtos.ReplaceUserGroupsRequest;
import com.lrj.platform.auth.dto.AdminDtos.RoleView;
import com.lrj.platform.auth.dto.AdminDtos.TenantView;
import com.lrj.platform.auth.dto.AdminDtos.UpdateGroupRequest;
import com.lrj.platform.auth.dto.AdminDtos.UpdateRoleRequest;
import com.lrj.platform.auth.dto.AdminDtos.UpdateUserRequest;
import com.lrj.platform.auth.dto.AdminDtos.UserAdminView;
import com.lrj.platform.security.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * RBAC 后台 API（{@code /auth/admin/**}）。整体装配受 {@code app.auth.rbac.enabled} 控制（关则不注册，
 * 灰度期 direct-only 无 admin 面）。每个端点先校验调用方持有 {@code role-admin} scope（否则 403）——
 * 身份来自内部 JWT 还原的 {@link TenantContext}（网关已用会话 Bearer 或 api-key 换发）。写端点再受
 * {@code app.auth.rbac.admin-writes-enabled} 二级开关控制（关则 503，用于"先只读灰度、稳定后再开写"）。
 *
 * <p>继承式 RBAC：除用户/角色外，新增<b>租户基础角色</b>（{@code /tenants/**}）、<b>用户组</b>（{@code /groups/**}）、
 * <b>用户↔组</b>（{@code /users/{u}/groups}）与<b>有效权限归因</b>（{@code /users/{u}/effective-permissions}）。
 * 三层继承是否折进签发的 JWT 由 {@code app.auth.rbac.inheritance-enabled} 再控（关时租户/组绑定不生效，可先配好再开）。
 * 改角色/绑定后需用户重新登录/刷新才在新 JWT 生效；降权会撤销其 refresh session。异常统一由
 * {@link AuthExceptionHandler} 映射。
 */
@RestController
@RequestMapping("/auth/admin")
@ConditionalOnProperty(name = "app.auth.rbac.enabled", havingValue = "true")
public class AdminController {

    private final AdminService admin;
    private final AuthProperties props;

    public AdminController(AdminService admin, AuthProperties props) {
        this.admin = admin;
        this.props = props;
    }

    // ---- 用户 ----

    @GetMapping("/users")
    public ResponseEntity<List<UserAdminView>> listUsers(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        requireRoleAdmin();
        int capped = Math.min(Math.max(1, limit), 200);
        List<UserAdminView> body = admin.listUsers(Math.max(0, offset), capped).stream()
                .map(this::toUserView).toList();
        return ResponseEntity.ok().header("X-Total-Count", String.valueOf(admin.countUsers())).body(body);
    }

    @GetMapping("/users/{username}")
    public UserAdminView getUser(@PathVariable String username) {
        requireRoleAdmin();
        // 用原子 (账号,版本) 读——该版本是编辑器 If-Match 的基线，不能事务外二次读（GET 侧 TOCTOU）。
        return toUserView(admin.getUserVersioned(username)
                .orElseThrow(() -> new AuthException(404, "user_not_found", "用户不存在")));
    }

    @PostMapping("/users")
    public ResponseEntity<UserAdminView> createUser(@RequestBody CreateUserRequest req) {
        requireRoleAdminWrite();
        UserAccount u = admin.createUser(req.username(), req.password(), req.tenant(),
                nz(req.directScopes()), nz(req.roles()), req.enabled() == null || req.enabled());
        return ResponseEntity.status(201).body(toUserView(u));
    }

    @PatchMapping("/users/{username}")
    public UserAdminView patchUser(@PathVariable String username, @RequestBody UpdateUserRequest req,
                                   @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        requireRoleAdminWrite();
        return toUserView(admin.patchUser(username, req.tenant(), req.password(),
                req.directScopes(), req.enabled(), requireIfMatch(ifMatch)));
    }

    @PutMapping("/users/{username}/roles")
    public UserAdminView replaceRoles(@PathVariable String username, @RequestBody ReplaceRolesRequest req,
                                      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        requireRoleAdminWrite();
        return toUserView(admin.assignRoles(username, nz(req.roles()), requireIfMatch(ifMatch)));
    }

    @PutMapping("/users/{username}/groups")
    public UserAdminView replaceUserGroups(@PathVariable String username, @RequestBody ReplaceUserGroupsRequest req,
                                           @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        requireRoleAdminWrite();
        return toUserView(admin.replaceUserGroups(username, nz(req.groups()), requireIfMatch(ifMatch)));
    }

    @GetMapping("/users/{username}/effective-permissions")
    public EffectivePermissionsView getEffectivePermissions(@PathVariable String username) {
        requireRoleAdmin();
        UserAccount u = admin.getUser(username)
                .orElseThrow(() -> new AuthException(404, "user_not_found", "用户不存在"));
        EffectivePermissionResolver.EffectivePermissions ep = admin.effectivePermissionsOf(u);
        return new EffectivePermissionsView(ep.directScopes(), ep.personalRoleScopes(), ep.tenantScopes(),
                ep.groupScopes(), ep.all(), ep.sources());
    }

    @DeleteMapping("/users/{username}")
    public ResponseEntity<Void> deleteUser(@PathVariable String username,
                                           @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        requireRoleAdminWrite();
        admin.deleteUser(username, requireIfMatch(ifMatch));
        return ResponseEntity.noContent().build();
    }

    // ---- 角色 ----

    @GetMapping("/roles")
    public List<RoleView> listRoles() {
        requireRoleAdmin();
        return admin.listRoles().stream().map(this::toRoleView).toList();
    }

    @GetMapping("/roles/{name}")
    public RoleView getRole(@PathVariable String name) {
        requireRoleAdmin();
        return toRoleView(admin.getRoleVersioned(name)
                .orElseThrow(() -> new AuthException(404, "role_not_found", "角色不存在")));
    }

    @PostMapping("/roles")
    public ResponseEntity<RoleView> createRole(@RequestBody CreateRoleRequest req) {
        requireRoleAdminWrite();
        Role r = admin.createRole(req.name(), nz(req.scopes()), req.description());
        return ResponseEntity.status(201).body(toRoleView(r));
    }

    @PutMapping("/roles/{name}")
    public RoleView updateRole(@PathVariable String name, @RequestBody UpdateRoleRequest req,
                               @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        requireRoleAdminWrite();
        return toRoleView(admin.updateRole(name, nz(req.scopes()), req.description(), requireIfMatch(ifMatch)));
    }

    @DeleteMapping("/roles/{name}")
    public ResponseEntity<Void> deleteRole(@PathVariable String name,
                                           @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        requireRoleAdminWrite();
        admin.deleteRole(name, requireIfMatch(ifMatch));
        return ResponseEntity.noContent().build();
    }

    // ---- 租户基础角色 ----

    @GetMapping("/tenants")
    public List<TenantView> listTenants() {
        requireRoleAdmin();
        return admin.listTenantNames().stream().map(this::toTenantView).toList();
    }

    @GetMapping("/tenants/{tenant}")
    public TenantView getTenant(@PathVariable String tenant) {
        requireRoleAdmin();
        return toTenantView(admin.getTenantVersioned(tenant));
    }

    @PutMapping("/tenants/{tenant}/roles")
    public TenantView replaceTenantRoles(@PathVariable String tenant, @RequestBody ReplaceTenantRolesRequest req,
                                         @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        requireRoleAdminWrite();
        return toTenantView(admin.replaceTenantRoles(tenant, nz(req.roles()), requireIfMatch(ifMatch)));
    }

    @DeleteMapping("/tenants/{tenant}/roles")
    public ResponseEntity<Void> clearTenantRoles(@PathVariable String tenant,
                                                 @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        requireRoleAdminWrite();
        admin.clearTenantRoles(tenant, requireIfMatch(ifMatch));
        return ResponseEntity.noContent().build();
    }

    // ---- 用户组 ----

    @GetMapping("/groups")
    public List<GroupView> listGroups() {
        requireRoleAdmin();
        return admin.listGroups().stream().map(this::toGroupView).toList();
    }

    @GetMapping("/groups/{name}")
    public GroupView getGroup(@PathVariable String name) {
        requireRoleAdmin();
        return toGroupView(admin.getGroupVersioned(name)
                .orElseThrow(() -> new AuthException(404, "group_not_found", "用户组不存在")));
    }

    @PostMapping("/groups")
    public ResponseEntity<GroupView> createGroup(@RequestBody CreateGroupRequest req) {
        requireRoleAdminWrite();
        Group g = admin.createGroup(req.name(), req.description(), nz(req.roles()));
        return ResponseEntity.status(201).body(toGroupView(g));
    }

    @PutMapping("/groups/{name}")
    public GroupView updateGroup(@PathVariable String name, @RequestBody UpdateGroupRequest req,
                                 @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        requireRoleAdminWrite();
        return toGroupView(admin.updateGroup(name, req.description(), nz(req.roles()), requireIfMatch(ifMatch)));
    }

    @DeleteMapping("/groups/{name}")
    public ResponseEntity<Void> deleteGroup(@PathVariable String name,
                                            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        requireRoleAdminWrite();
        admin.deleteGroup(name, requireIfMatch(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/groups/{name}/members")
    public List<String> getGroupMembers(@PathVariable String name) {
        requireRoleAdmin();
        admin.getGroupVersioned(name)
                .orElseThrow(() -> new AuthException(404, "group_not_found", "用户组不存在"));
        return admin.groupMembers(name);
    }

    @PutMapping("/groups/{name}/members")
    public GroupView replaceGroupMembers(@PathVariable String name, @RequestBody ReplaceMembersRequest req,
                                         @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        requireRoleAdminWrite();
        return toGroupView(admin.replaceGroupMembers(name, nz(req.members()), requireIfMatch(ifMatch)));
    }

    // ---- 关口 ----

    private static void requireRoleAdmin() {
        TenantContext.Tenant t = TenantContext.current();
        if (t == null || !t.hasScope("role-admin")) {
            throw new AuthException(403, "forbidden", "需要 role-admin 权限");
        }
    }

    /** 写端点：先 role-admin 授权，再检查 admin-writes 灰度开关（关则 503）。 */
    private void requireRoleAdminWrite() {
        requireRoleAdmin();
        if (!props.getRbac().isAdminWritesEnabled()) {
            throw new AuthException(503, "rbac_writes_disabled", "RBAC 管理写入当前未开启（灰度）");
        }
    }

    /** 列表 / 建户用：版本事务外读。列表不作 If-Match 基线（点进详情才走原子读）；建户后版本恒为 0，无并发覆盖窗口。 */
    private UserAdminView toUserView(UserAccount u) {
        return new UserAdminView(u.username(), u.userId(), u.tenant(),
                u.scopes(), u.roles(), admin.userGroups(u.username()), admin.effectiveScopesOf(u), u.enabled(),
                admin.userVersion(u.username()));
    }

    /** 写路径用：版本来自 service 事务内读到的值，避免 TOCTOU 让响应带上他人版本。 */
    private UserAdminView toUserView(AdminService.VersionedUser vu) {
        UserAccount u = vu.account();
        return new UserAdminView(u.username(), u.userId(), u.tenant(),
                u.scopes(), u.roles(), admin.userGroups(u.username()), admin.effectiveScopesOf(u), u.enabled(),
                vu.version());
    }

    private RoleView toRoleView(Role r) {
        return new RoleView(r.name(), r.scopes(), r.description(), admin.roleVersion(r.name()),
                admin.assignedUserCount(r.name()), admin.boundGroupCount(r.name()), admin.boundTenantCount(r.name()));
    }

    private RoleView toRoleView(AdminService.VersionedRole vr) {
        Role r = vr.role();
        return new RoleView(r.name(), r.scopes(), r.description(), vr.version(),
                admin.assignedUserCount(r.name()), admin.boundGroupCount(r.name()), admin.boundTenantCount(r.name()));
    }

    private TenantView toTenantView(String tenant) {
        Set<String> baseRoles = admin.tenantBaseRoles(tenant);
        return new TenantView(tenant, baseRoles, admin.expandRoles(baseRoles),
                admin.tenantMemberCount(tenant), admin.tenantVersion(tenant));
    }

    private TenantView toTenantView(AdminService.TenantPolicy tp) {
        return new TenantView(tp.tenant(), tp.baseRoles(), admin.expandRoles(tp.baseRoles()),
                admin.tenantMemberCount(tp.tenant()), tp.version());
    }

    private GroupView toGroupView(Group g) {
        return new GroupView(g.name(), g.description(), g.roles(), admin.expandRoles(g.roles()),
                admin.memberCount(g.name()), admin.groupVersion(g.name()));
    }

    private GroupView toGroupView(AdminService.VersionedGroup vg) {
        Group g = vg.group();
        return new GroupView(g.name(), g.description(), g.roles(), admin.expandRoles(g.roles()),
                admin.memberCount(g.name()), vg.version());
    }

    /** 写/删端点强制 If-Match：缺失返回 428（precondition_required）；否则解析版本号（非法 400）。 */
    private static long requireIfMatch(String ifMatch) {
        Long v = parseIfMatch(ifMatch);
        if (v == null) {
            throw new AuthException(428, "precondition_required", "缺少 If-Match 版本号（乐观锁必需，请携带资源当前版本）");
        }
        return v;
    }

    /**
     * 解析 {@code If-Match} 头为乐观锁版本号：缺省/空返回 null；去除弱校验前缀与引号后按 long 解析，非法则 400。
     */
    private static Long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2).trim();
        }
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new AuthException(400, "invalid_if_match", "If-Match 版本号格式非法");
        }
    }

    private static <T> Set<T> nz(Set<T> s) {
        return s == null ? Set.of() : s;
    }
}
