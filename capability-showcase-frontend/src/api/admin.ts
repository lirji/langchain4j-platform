/**
 * RBAC 管理域 API（`/auth/admin/**`）——**Bearer-only**。
 *
 * 硬约束（业务规则 7 / §1.3）：绝不引用 sessionStore/api-key；Authorization 只来自 authStore.accessToken，
 * 即便顶栏填了 api-key 也**不**注入 X-Api-Key（避免身份混淆）。走 authorizedFetch 复用 401 单飞续期；
 * 写请求带**裸版本号** If-Match（非 ETag 引号语义）；解析 X-Total-Count 得分页总数。
 */
import { AUTH_BASE_URL } from '../config'
import { useAuthStore } from '../stores/auth'
import { authorizedFetch } from './authorizedFetch'
import { ApiError } from './errors'
import { tryParseJson } from '../utils/json'
import type {
  CreateGroupRequest,
  CreateRoleRequest,
  CreateUserRequest,
  EffectivePermissionsView,
  GroupView,
  PagedResult,
  RoleView,
  TenantView,
  UpdateGroupRequest,
  UpdateRoleRequest,
  UpdateUserRequest,
  UserAdminView,
} from '../types/admin'

/** Bearer-only 请求头；ifMatch 为版本号（PATCH/PUT 用）。 */
function adminInit(method: string, body?: unknown, ifMatch?: number, signal?: AbortSignal): RequestInit {
  const auth = useAuthStore()
  const headers: Record<string, string> = {}
  if (auth.accessToken) headers['Authorization'] = `Bearer ${auth.accessToken}` // 唯一凭证来源
  if (ifMatch != null) headers['If-Match'] = String(ifMatch)
  const init: RequestInit = { method, headers, credentials: 'include', signal }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(body)
  }
  return init
}

async function adminJson<T>(path: string, init: RequestInit): Promise<{ data: T; res: Response }> {
  const res = await authorizedFetch(`${AUTH_BASE_URL}${path}`, init) // Bearer 触发单飞 refresh
  const text = await res.text().catch(() => '')
  const data = text ? (tryParseJson(text) ?? text) : null
  if (!res.ok) throw new ApiError(res.status, `HTTP ${res.status}`.trim(), data)
  return { data: data as T, res }
}

// —— 用户 ——

export async function fetchUsers(
  params: { offset: number; limit: number; q?: string; tenant?: string; role?: string; enabled?: boolean },
  signal?: AbortSignal,
): Promise<PagedResult<UserAdminView>> {
  const qs = new URLSearchParams({ offset: String(params.offset), limit: String(params.limit) })
  // 契约依赖：q/tenant/role/enabled 为 FINAL_PLAN §9.2 提议的服务端筛选，当前 listUsers 仅认 offset/limit。
  // 后端就绪前，调用方（store）应据能力开关决定是否发这些筛选（当前默认不发；见 03-frontend-arch §7 风险表）。
  if (params.q) qs.set('q', params.q)
  if (params.tenant) qs.set('tenant', params.tenant)
  if (params.role) qs.set('role', params.role)
  if (params.enabled != null) qs.set('enabled', String(params.enabled))
  const { data, res } = await adminJson<UserAdminView[]>(
    `/auth/admin/users?${qs.toString()}`,
    adminInit('GET', undefined, undefined, signal),
  )
  const total = Number(res.headers.get('X-Total-Count') ?? data.length)
  return { items: data, total: Number.isFinite(total) ? total : data.length }
}

export const fetchUser = (u: string): Promise<UserAdminView> =>
  adminJson<UserAdminView>(`/auth/admin/users/${encodeURIComponent(u)}`, adminInit('GET')).then((r) => r.data)

export const createUser = (req: CreateUserRequest): Promise<UserAdminView> =>
  adminJson<UserAdminView>('/auth/admin/users', adminInit('POST', req)).then((r) => r.data)

export const patchUser = (u: string, req: UpdateUserRequest, version: number): Promise<UserAdminView> =>
  adminJson<UserAdminView>(`/auth/admin/users/${encodeURIComponent(u)}`, adminInit('PATCH', req, version)).then(
    (r) => r.data,
  )

export const replaceUserRoles = (u: string, roles: string[], version: number): Promise<UserAdminView> =>
  adminJson<UserAdminView>(
    `/auth/admin/users/${encodeURIComponent(u)}/roles`,
    adminInit('PUT', { roles }, version),
  ).then((r) => r.data)

export const deleteUser = (u: string, version: number): Promise<void> =>
  adminJson<void>(`/auth/admin/users/${encodeURIComponent(u)}`, adminInit('DELETE', undefined, version)).then(
    () => undefined,
  )

// —— 角色 ——

export const fetchRoles = (): Promise<RoleView[]> =>
  adminJson<RoleView[]>('/auth/admin/roles', adminInit('GET')).then((r) => r.data)

export const fetchRole = (n: string): Promise<RoleView> =>
  adminJson<RoleView>(`/auth/admin/roles/${encodeURIComponent(n)}`, adminInit('GET')).then((r) => r.data)

export const createRole = (req: CreateRoleRequest): Promise<RoleView> =>
  adminJson<RoleView>('/auth/admin/roles', adminInit('POST', req)).then((r) => r.data)

export const updateRole = (n: string, req: UpdateRoleRequest, version: number): Promise<RoleView> =>
  adminJson<RoleView>(`/auth/admin/roles/${encodeURIComponent(n)}`, adminInit('PUT', req, version)).then(
    (r) => r.data,
  )

export const deleteRole = (n: string, version: number): Promise<void> =>
  adminJson<void>(`/auth/admin/roles/${encodeURIComponent(n)}`, adminInit('DELETE', undefined, version)).then(
    () => undefined,
  )

// —— 租户基础角色（继承式 RBAC） ——

export const fetchTenants = (): Promise<TenantView[]> =>
  adminJson<TenantView[]>('/auth/admin/tenants', adminInit('GET')).then((r) => r.data)

export const fetchTenant = (tenant: string): Promise<TenantView> =>
  adminJson<TenantView>(`/auth/admin/tenants/${encodeURIComponent(tenant)}`, adminInit('GET')).then((r) => r.data)

/** 全量替换租户基础角色。首次绑定时租户 version 为 -1（用 If-Match: -1）。 */
export const replaceTenantRoles = (tenant: string, roles: string[], version: number): Promise<TenantView> =>
  adminJson<TenantView>(
    `/auth/admin/tenants/${encodeURIComponent(tenant)}/roles`,
    adminInit('PUT', { roles }, version),
  ).then((r) => r.data)

export const clearTenantRoles = (tenant: string, version: number): Promise<void> =>
  adminJson<void>(
    `/auth/admin/tenants/${encodeURIComponent(tenant)}/roles`,
    adminInit('DELETE', undefined, version),
  ).then(() => undefined)

// —— 用户组（继承式 RBAC） ——

export const fetchGroups = (): Promise<GroupView[]> =>
  adminJson<GroupView[]>('/auth/admin/groups', adminInit('GET')).then((r) => r.data)

export const fetchGroup = (name: string): Promise<GroupView> =>
  adminJson<GroupView>(`/auth/admin/groups/${encodeURIComponent(name)}`, adminInit('GET')).then((r) => r.data)

export const createGroup = (req: CreateGroupRequest): Promise<GroupView> =>
  adminJson<GroupView>('/auth/admin/groups', adminInit('POST', req)).then((r) => r.data)

export const updateGroup = (name: string, req: UpdateGroupRequest, version: number): Promise<GroupView> =>
  adminJson<GroupView>(`/auth/admin/groups/${encodeURIComponent(name)}`, adminInit('PUT', req, version)).then(
    (r) => r.data,
  )

export const deleteGroup = (name: string, version: number): Promise<void> =>
  adminJson<void>(`/auth/admin/groups/${encodeURIComponent(name)}`, adminInit('DELETE', undefined, version)).then(
    () => undefined,
  )

export const fetchGroupMembers = (name: string): Promise<string[]> =>
  adminJson<string[]>(`/auth/admin/groups/${encodeURIComponent(name)}/members`, adminInit('GET')).then((r) => r.data)

/** 全量替换组成员（与角色/说明 PUT 是各自独立的写，各带 version）。 */
export const replaceGroupMembers = (name: string, members: string[], version: number): Promise<GroupView> =>
  adminJson<GroupView>(
    `/auth/admin/groups/${encodeURIComponent(name)}/members`,
    adminInit('PUT', { members }, version),
  ).then((r) => r.data)

// —— 用户 ↔ 组 + 归因 ——

/** 全量替换用户所属用户组。 */
export const replaceUserGroups = (u: string, groups: string[], version: number): Promise<UserAdminView> =>
  adminJson<UserAdminView>(
    `/auth/admin/users/${encodeURIComponent(u)}/groups`,
    adminInit('PUT', { groups }, version),
  ).then((r) => r.data)

/** 服务端权威的有效权限归因（只读；四支来源 + sources 标签）。 */
export const fetchUserEffectivePermissions = (u: string): Promise<EffectivePermissionsView> =>
  adminJson<EffectivePermissionsView>(
    `/auth/admin/users/${encodeURIComponent(u)}/effective-permissions`,
    adminInit('GET'),
  ).then((r) => r.data)
