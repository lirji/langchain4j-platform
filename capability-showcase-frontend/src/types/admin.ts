/**
 * RBAC 管理域 DTO —— 与后端 `com.lrj.platform.auth.dto.AdminDtos` / `AuthPublicConfig` 一一对应。
 * JSON 里后端 `Set<String>` → 数组；`long version` → number。密码只入不出（视图无口令字段）。
 */

/**
 * 用户视图（不含口令）。version 为乐观锁版本，写回经 If-Match 带回做冲突检测。
 * groups 为该用户所属用户组（继承式 RBAC 的一支来源；后端始终返回，无组则为空数组）。
 */
export interface UserAdminView {
  username: string
  userId: string
  tenant: string
  directScopes: string[]
  roles: string[]
  groups: string[]
  effectiveScopes: string[]
  enabled: boolean
  version: number
}

/**
 * 角色视图。version 乐观锁版本；assignedUserCount 绑定用户数（编辑/删除影响预览）。
 * boundGroupCount/boundTenantCount：该角色被多少用户组 / 多少租户基础角色引用（继承式 RBAC 的影响面）。
 */
export interface RoleView {
  name: string
  scopes: string[]
  description: string
  version: number
  assignedUserCount: number
  boundGroupCount: number
  boundTenantCount: number
}

/**
 * 租户基础角色视图（继承式 RBAC：租户内所有成员共享的基础角色）。
 * effectiveBaseScopes = ⋃(baseRoles 的 scopes)；memberCount 该租户成员数。
 * version：乐观锁版本。**首次绑定时租户尚无记录，version 为 -1（用 If-Match: -1）。**
 */
export interface TenantView {
  tenant: string
  baseRoles: string[]
  effectiveBaseScopes: string[]
  memberCount: number
  version: number
}

/**
 * 用户组视图。name 为主键（创建后不可改名）；roles 组绑定的角色；
 * effectiveScopes = ⋃(roles 的 scopes)；memberCount 组成员数；version 乐观锁版本。
 */
export interface GroupView {
  name: string
  description: string
  roles: string[]
  effectiveScopes: string[]
  memberCount: number
  version: number
}

/**
 * 用户有效权限的服务端权威归因（只读）。
 * 四支来源分列（direct / 个人角色 / 租户基础角色 / 用户组），effectiveScopes 为四者之并。
 * sources：scope → 来源标签数组，标签形如 `direct` / `role:<角色名>` / `tenant:<角色名>` / `group:<组名>:<角色名>`。
 */
export interface EffectivePermissionsView {
  directScopes: string[]
  personalRoleScopes: string[]
  tenantScopes: string[]
  groupScopes: string[]
  effectiveScopes: string[]
  sources: Record<string, string[]>
}

export interface CreateUserRequest {
  username: string
  password: string
  tenant: string
  roles: string[]
  directScopes: string[]
  enabled: boolean
}

/** PATCH 语义：undefined=不改；directScopes:[]=显式清空。不含 roles（角色经专用 PUT 全量替换）。 */
export interface UpdateUserRequest {
  tenant?: string
  password?: string
  directScopes?: string[]
  enabled?: boolean
}

export interface ReplaceRolesRequest {
  roles: string[]
}

export interface CreateRoleRequest {
  name: string
  scopes: string[]
  description: string
}

/** PUT /roles/{name}：全量替换 scopes/description（name 取自路径）。 */
export interface UpdateRoleRequest {
  scopes: string[]
  description: string
}

/** PUT /tenants/{tenant}/roles：全量替换该租户的基础角色。 */
export interface ReplaceTenantRolesRequest {
  roles: string[]
}

/** POST /groups：新建用户组（name 创建后不可改名）。 */
export interface CreateGroupRequest {
  name: string
  description: string
  roles: string[]
}

/** PUT /groups/{name}：全量替换 description/roles（name 取自路径；成员经专用端点单独替换）。 */
export interface UpdateGroupRequest {
  description: string
  roles: string[]
}

/** PUT /groups/{name}/members：全量替换组成员（用户名集合）。 */
export interface ReplaceMembersRequest {
  members: string[]
}

/** PUT /users/{u}/groups：全量替换用户所属用户组。 */
export interface ReplaceUserGroupsRequest {
  groups: string[]
}

/** 统一错误体 `{error,message}`（后端 AuthExceptionHandler）。 */
export interface AuthErrorBody {
  error: string
  message: string
}

/**
 * 409 家族判别码（当前实现均为 HTTP 409，用 `error` 字段区分）：
 * version_conflict=乐观锁冲突；username_taken/role_exists/group_exists=重名；
 * role_in_use=角色被引用；group_in_use=用户组仍有成员；last_admin=最后管理员保护。
 */
export type AdminConflictCode =
  | 'version_conflict'
  | 'username_taken'
  | 'role_exists'
  | 'role_in_use'
  | 'group_exists'
  | 'group_in_use'
  | 'last_admin'

/** 分页结果：items + 服务端总数（X-Total-Count）。 */
export interface PagedResult<T> {
  items: T[]
  total: number
}
