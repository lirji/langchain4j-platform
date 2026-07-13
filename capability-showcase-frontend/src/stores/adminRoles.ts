import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { humanizeError, isVersionConflict } from '../api/errors'
import { createRole, deleteRole, fetchRole, fetchRoles, updateRole } from '../api/admin'
import type { CreateRoleRequest, RoleView, UpdateRoleRequest } from '../types/admin'

/**
 * 角色管理 store。**仅内存。** 角色数量少，`GET /roles` 一次拉全，不用分页。
 * 写成功局部失效；version_conflict 刷新 selected 后重抛；role_in_use(409) 由后端保护，视图捕获后
 * 引导到"该角色的绑定用户"筛选（见 RoleEditor）。scope 编辑对**未知 scope** 原样保留回写。
 */
export const useAdminRolesStore = defineStore('adminRoles', () => {
  const roles = ref<RoleView[]>([])
  const status = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const error = ref<string | null>(null)
  const selected = ref<RoleView | null>(null)
  const selectedStatus = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const selectedError = ref<string | null>(null)
  /** 版本冲突时的服务端最新值——独立于 selected，不覆盖草稿基线（见 adminUsers 同名字段说明）。 */
  const conflictLatest = ref<RoleView | null>(null)

  const roleNames = computed(() => roles.value.map((r) => r.name))
  const inUse = computed(() => new Set(roles.value.filter((r) => r.assignedUserCount > 0).map((r) => r.name)))

  async function load(): Promise<void> {
    status.value = 'loading'
    error.value = null
    try {
      roles.value = await fetchRoles()
      status.value = 'ready'
    } catch (e) {
      error.value = humanizeError(e, undefined, { credentialMode: 'bearer' })
      status.value = 'error'
    }
  }

  async function loadDetail(name: string): Promise<void> {
    selectedStatus.value = 'loading'
    selectedError.value = null
    try {
      selected.value = await fetchRole(name)
      selectedStatus.value = 'ready'
    } catch (e) {
      selectedError.value = humanizeError(e, undefined, { credentialMode: 'bearer' })
      selectedStatus.value = 'error'
    }
  }

  function applyLocal(next: RoleView): void {
    const i = roles.value.findIndex((r) => r.name === next.name)
    if (i >= 0) roles.value.splice(i, 1, next)
    if (selected.value?.name === next.name) selected.value = next
  }
  function removeLocal(name: string): void {
    const i = roles.value.findIndex((r) => r.name === name)
    if (i >= 0) roles.value.splice(i, 1)
    if (selected.value?.name === name) selected.value = null
  }

  async function saveRole(name: string, req: UpdateRoleRequest, version: number): Promise<RoleView> {
    try {
      const v = await updateRole(name, req, version)
      applyLocal(v)
      conflictLatest.value = null
      return v
    } catch (e) {
      if (isVersionConflict(e)) conflictLatest.value = await fetchRole(name)
      throw e
    }
  }
  /** 采纳冲突时的服务端最新为新基线（放弃本地草稿）。 */
  function acceptConflictLatest(): RoleView | null {
    const v = conflictLatest.value
    if (v) applyLocal(v)
    conflictLatest.value = null
    return v
  }
  function clearConflict(): void {
    conflictLatest.value = null
  }
  async function createRoleAction(req: CreateRoleRequest): Promise<RoleView> {
    const v = await createRole(req)
    await load()
    return v
  }
  async function deleteRoleAction(name: string, version: number): Promise<void> {
    await deleteRole(name, version) // 带 If-Match
    removeLocal(name)
  }

  return {
    roles,
    status,
    error,
    selected,
    selectedStatus,
    selectedError,
    conflictLatest,
    roleNames,
    inUse,
    load,
    loadDetail,
    applyLocal,
    removeLocal,
    saveRole,
    acceptConflictLatest,
    clearConflict,
    createRoleAction,
    deleteRoleAction,
  }
})
