import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { humanizeError, isVersionConflict } from '../api/errors'
import { clearTenantRoles, fetchTenant, fetchTenants, replaceTenantRoles } from '../api/admin'
import type { TenantView } from '../types/admin'

/**
 * 租户基础角色管理 store（继承式 RBAC 的一支来源）。**仅内存。** 镜像 adminRoles.ts：
 * `GET /tenants` 一次拉全（无分页），写成功局部失效；version_conflict 抓服务端最新到 conflictLatest
 * 后重抛（不覆盖 selected/草稿）。主键为 `tenant`。
 *
 * 首次绑定：某租户尚无基础角色记录时 version 为 -1（If-Match: -1 由视图层携带）。
 */
export const useAdminTenantsStore = defineStore('adminTenants', () => {
  const tenants = ref<TenantView[]>([])
  const status = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const error = ref<string | null>(null)
  const selected = ref<TenantView | null>(null)
  const selectedStatus = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const selectedError = ref<string | null>(null)
  /** 版本冲突时的服务端最新值——独立于 selected，不覆盖草稿基线。 */
  const conflictLatest = ref<TenantView | null>(null)

  /** 各租户的基础角色映射（供 UserEditor 预测该用户租户的基础角色 scopes）。 */
  const baseRolesByTenant = computed(() => new Map(tenants.value.map((t) => [t.tenant, t.baseRoles])))

  async function load(): Promise<void> {
    status.value = 'loading'
    error.value = null
    try {
      tenants.value = await fetchTenants()
      status.value = 'ready'
    } catch (e) {
      error.value = humanizeError(e, undefined, { credentialMode: 'bearer' })
      status.value = 'error'
    }
  }

  async function loadDetail(tenant: string): Promise<void> {
    selectedStatus.value = 'loading'
    selectedError.value = null
    try {
      selected.value = await fetchTenant(tenant)
      selectedStatus.value = 'ready'
    } catch (e) {
      selectedError.value = humanizeError(e, undefined, { credentialMode: 'bearer' })
      selectedStatus.value = 'error'
    }
  }

  function applyLocal(next: TenantView): void {
    const i = tenants.value.findIndex((t) => t.tenant === next.tenant)
    if (i >= 0) tenants.value.splice(i, 1, next)
    else tenants.value.push(next) // 首次绑定：列表原本可能无此租户记录
    if (selected.value?.tenant === next.tenant) selected.value = next
  }
  function removeLocal(tenant: string): void {
    const i = tenants.value.findIndex((t) => t.tenant === tenant)
    if (i >= 0) tenants.value.splice(i, 1)
    if (selected.value?.tenant === tenant) selected.value = null
  }

  async function saveTenantRoles(tenant: string, roles: string[], version: number): Promise<TenantView> {
    try {
      const v = await replaceTenantRoles(tenant, roles, version)
      applyLocal(v)
      conflictLatest.value = null
      return v
    } catch (e) {
      if (isVersionConflict(e)) conflictLatest.value = await fetchTenant(tenant)
      throw e
    }
  }
  /** 采纳冲突时的服务端最新为新基线（放弃本地草稿）。 */
  function acceptConflictLatest(): TenantView | null {
    const v = conflictLatest.value
    if (v) applyLocal(v)
    conflictLatest.value = null
    return v
  }
  function clearConflict(): void {
    conflictLatest.value = null
  }
  /** 清空该租户基础角色（DELETE，带 If-Match）：成功后局部移除。 */
  async function clearTenantRolesAction(tenant: string, version: number): Promise<void> {
    await clearTenantRoles(tenant, version)
    removeLocal(tenant)
  }

  return {
    tenants,
    status,
    error,
    selected,
    selectedStatus,
    selectedError,
    conflictLatest,
    baseRolesByTenant,
    load,
    loadDetail,
    applyLocal,
    removeLocal,
    saveTenantRoles,
    acceptConflictLatest,
    clearConflict,
    clearTenantRolesAction,
  }
})
