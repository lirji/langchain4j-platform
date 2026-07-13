package com.lrj.platform.auth.dto;

import java.util.Set;

/**
 * RBAC admin API 的请求/响应 DTO 集合（本地包，非跨服务契约，不放 platform-protocol）。
 * 密码只入不出——{@link UserAdminView} 不含任何口令字段。
 *
 * <p>契约要点：建户可指定 {@code directScopes}；{@link UpdateUserRequest} 是 PATCH 语义（字段 null=不改，
 * {@code directScopes:[]}=显式清空），且<b>不含 roles</b>（角色只经 {@code PUT /users/{u}/roles} 全量替换）；
 * 视图同时给 {@code directScopes} / {@code roles} / {@code effectiveScopes}（角色展开 ∪ 直配）。
 */
public final class AdminDtos {

    private AdminDtos() {}

    // ---- 用户 ----

    public record CreateUserRequest(String username, String password, String tenant,
                                    Set<String> roles, Set<String> directScopes, Boolean enabled) {}

    /** PATCH：null 字段表示不修改；{@code directScopes:[]} 表示清空。roles 不在此，用专用 PUT 替换。 */
    public record UpdateUserRequest(String tenant, String password, Set<String> directScopes, Boolean enabled) {}

    /** PUT /users/{username}/roles：幂等全量替换角色。 */
    public record ReplaceRolesRequest(Set<String> roles) {}

    /** 用户视图（不含口令）。{@code version} 为乐观锁版本，回写时经 {@code If-Match} 带回做冲突检测。 */
    public record UserAdminView(String username, String userId, String tenant,
                                Set<String> directScopes, Set<String> roles,
                                Set<String> effectiveScopes, boolean enabled, long version) {}

    // ---- 角色 ----

    public record CreateRoleRequest(String name, Set<String> scopes, String description) {}

    /** PUT /roles/{name}：全量替换 scopes/description（name 取自路径，body 不再重复）。 */
    public record UpdateRoleRequest(Set<String> scopes, String description) {}

    /** 角色视图。{@code version} 为乐观锁版本；{@code assignedUserCount} 为绑定用户数（编辑/删除影响预览）。 */
    public record RoleView(String name, Set<String> scopes, String description,
                           long version, int assignedUserCount) {}
}
