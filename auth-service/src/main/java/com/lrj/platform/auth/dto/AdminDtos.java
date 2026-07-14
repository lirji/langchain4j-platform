package com.lrj.platform.auth.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RBAC admin API 的请求/响应 DTO 集合（本地包，非跨服务契约，不放 platform-protocol）。
 * 密码只入不出——{@link UserAdminView} 不含任何口令字段。
 *
 * <p>契约要点：建户可指定 {@code directScopes}；{@link UpdateUserRequest} 是 PATCH 语义（字段 null=不改，
 * {@code directScopes:[]}=显式清空），且<b>不含 roles</b>（角色只经 {@code PUT /users/{u}/roles} 全量替换，
 * 组只经 {@code PUT /users/{u}/groups} 全量替换）；视图给 {@code directScopes} / {@code roles} / {@code groups} /
 * {@code effectiveScopes}（三层合并：直配 ∪ 个人角色 ∪ 租户基础角色 ∪ 组角色）。
 */
public final class AdminDtos {

    private AdminDtos() {}

    // ---- 用户 ----

    public record CreateUserRequest(String username, String password, String tenant,
                                    Set<String> roles, Set<String> directScopes, Boolean enabled) {}

    /** PATCH：null 字段表示不修改；{@code directScopes:[]} 表示清空。roles/groups 不在此，用专用 PUT 替换。 */
    public record UpdateUserRequest(String tenant, String password, Set<String> directScopes, Boolean enabled) {}

    /** PUT /users/{username}/roles：幂等全量替换角色。 */
    public record ReplaceRolesRequest(Set<String> roles) {}

    /** PUT /users/{username}/groups：幂等全量替换用户所属组。 */
    public record ReplaceUserGroupsRequest(Set<String> groups) {}

    /**
     * 用户视图（不含口令）。{@code version} 为乐观锁版本，回写时经 {@code If-Match} 带回做冲突检测。
     * {@code groups} 是该用户所属的组名集；{@code effectiveScopes} 已含租户/组继承（受 inheritanceEnabled）。
     */
    public record UserAdminView(String username, String userId, String tenant,
                                Set<String> directScopes, Set<String> roles, Set<String> groups,
                                Set<String> effectiveScopes, boolean enabled, long version) {}

    /**
     * 用户有效权限的分层归因（GET /users/{u}/effective-permissions）。前端据此准确展示每条 scope 的来源，
     * 而非用本地目录猜测。{@code sources}：scope → 来源标签列表（direct / role:x / tenant:x / group:g:x）。
     */
    public record EffectivePermissionsView(Set<String> directScopes, Set<String> personalRoleScopes,
                                           Set<String> tenantScopes, Set<String> groupScopes,
                                           Set<String> effectiveScopes, Map<String, List<String>> sources) {}

    // ---- 角色 ----

    public record CreateRoleRequest(String name, Set<String> scopes, String description) {}

    /** PUT /roles/{name}：全量替换 scopes/description（name 取自路径，body 不再重复）。 */
    public record UpdateRoleRequest(Set<String> scopes, String description) {}

    /**
     * 角色视图。{@code version} 为乐观锁版本；{@code assignedUserCount}/{@code boundGroupCount}/{@code boundTenantCount}
     * 为被用户/用户组/租户引用的数量（编辑/删除影响预览：任一非空则不可删）。
     */
    public record RoleView(String name, Set<String> scopes, String description,
                           long version, int assignedUserCount, int boundGroupCount, int boundTenantCount) {}

    // ---- 租户基础角色 ----

    /** PUT /tenants/{tenant}/roles：幂等全量替换租户基础角色。 */
    public record ReplaceTenantRolesRequest(Set<String> roles) {}

    /**
     * 租户基础角色视图。{@code baseRoles} 为租户级绑定的角色名（全体成员继承）；{@code effectiveBaseScopes} 为其展开；
     * {@code memberCount} 为该租户下用户数；{@code version} 为乐观锁版本（无绑定为 -1，首次绑定用 If-Match: -1）。
     */
    public record TenantView(String tenant, Set<String> baseRoles, Set<String> effectiveBaseScopes,
                             int memberCount, long version) {}

    // ---- 用户组 ----

    public record CreateGroupRequest(String name, String description, Set<String> roles) {}

    /** PUT /groups/{name}：全量替换 description/roles（name 取自路径）。 */
    public record UpdateGroupRequest(String description, Set<String> roles) {}

    /** PUT /groups/{name}/members：幂等全量替换组成员。 */
    public record ReplaceMembersRequest(Set<String> members) {}

    /**
     * 用户组视图。{@code roles} 为组绑定角色（成员继承）；{@code effectiveScopes} 为其展开；{@code memberCount}
     * 为成员数；{@code version} 为乐观锁版本。成员清单另经 {@code GET /groups/{name}/members} 取。
     */
    public record GroupView(String name, String description, Set<String> roles, Set<String> effectiveScopes,
                            int memberCount, long version) {}
}
