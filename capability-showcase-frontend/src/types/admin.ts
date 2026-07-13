/**
 * RBAC 管理域 DTO —— 与后端 `com.lrj.platform.auth.dto.AdminDtos` / `AuthPublicConfig` 一一对应。
 * JSON 里后端 `Set<String>` → 数组；`long version` → number。密码只入不出（视图无口令字段）。
 */

/** 用户视图（不含口令）。version 为乐观锁版本，写回经 If-Match 带回做冲突检测。 */
export interface UserAdminView {
  username: string
  userId: string
  tenant: string
  directScopes: string[]
  roles: string[]
  effectiveScopes: string[]
  enabled: boolean
  version: number
}

/** 角色视图。version 乐观锁版本；assignedUserCount 绑定用户数（编辑/删除影响预览）。 */
export interface RoleView {
  name: string
  scopes: string[]
  description: string
  version: number
  assignedUserCount: number
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

/** 统一错误体 `{error,message}`（后端 AuthExceptionHandler）。 */
export interface AuthErrorBody {
  error: string
  message: string
}

/**
 * 409 家族判别码（当前实现均为 HTTP 409，用 `error` 字段区分）：
 * version_conflict=乐观锁冲突；username_taken/role_exists=重名；role_in_use=角色被引用；last_admin=最后管理员保护。
 */
export type AdminConflictCode =
  | 'version_conflict'
  | 'username_taken'
  | 'role_exists'
  | 'role_in_use'
  | 'last_admin'

/** 分页结果：items + 服务端总数（X-Total-Count）。 */
export interface PagedResult<T> {
  items: T[]
  total: number
}
