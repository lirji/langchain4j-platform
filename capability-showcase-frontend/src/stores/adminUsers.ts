import { ref } from 'vue'
import { defineStore } from 'pinia'
import { usePagedQuery } from '../composables/usePagedQuery'
import { humanizeError, isVersionConflict } from '../api/errors'
import {
  createUser,
  deleteUser,
  fetchUser,
  fetchUsers,
  patchUser,
  replaceUserRoles,
} from '../api/admin'
import type { CreateUserRequest, UpdateUserRequest, UserAdminView } from '../types/admin'

/**
 * 用户管理 store。**仅内存，绝不持久化身份。**
 *
 * 单一职责边界：列表分页/中止/乱序保护全部委托 usePagedQuery；本 store 只叠加"跨视图详情快照 +
 * 乐观锁版本 + 局部失效 + 写动作"。写成功就地失效（不整表重拉）；version_conflict 自动刷新 selected
 * 基线后**重抛**（不吞——交视图打开冲突弹窗做 draft vs 服务端最新的 diff）。
 *
 * 注：后端 listUsers 当前仅认 offset/limit（无服务端筛选），故 fetcher 不转发 filters；
 * 视图侧对当前页做客户端窄化即可（见 UsersView）。待后端补服务端筛选再接 setFilter。
 */
export const useAdminUsersStore = defineStore('adminUsers', () => {
  const list = usePagedQuery<UserAdminView>({
    pageSize: 50,
    fetcher: ({ offset, limit, signal }) => fetchUsers({ offset, limit }, signal),
  })

  // ── 详情快照：编辑草稿的服务端基线 + version（冲突时用于 diff） ──
  const selected = ref<UserAdminView | null>(null)
  const selectedStatus = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const selectedError = ref<string | null>(null)
  /**
   * 版本冲突时抓到的服务端最新值——**独立于 selected**，绝不覆盖驱动草稿的 selected，
   * 从而不触发编辑器 watch→syncDraft，保住用户草稿（冲突弹窗做 draft vs 此值的差异）。
   */
  const conflictLatest = ref<UserAdminView | null>(null)

  async function loadDetail(username: string): Promise<void> {
    selectedStatus.value = 'loading'
    selectedError.value = null
    try {
      selected.value = await fetchUser(username)
      selectedStatus.value = 'ready'
    } catch (e) {
      selectedError.value = humanizeError(e, undefined, { credentialMode: 'bearer' })
      selectedStatus.value = 'error'
    }
  }

  // ── 局部失效：写成功后就地替换，不整表重拉 ──
  function applyLocal(next: UserAdminView): void {
    list.patchItem((u) => u.username === next.username, next)
    if (selected.value?.username === next.username) selected.value = next
  }
  function removeLocal(username: string): void {
    list.removeItem((u) => u.username === username)
    if (selected.value?.username === username) selected.value = null
  }

  // ── 写动作：成功局部失效；version_conflict 抓服务端最新到 conflictLatest（不动 selected）后重抛（不吞） ──
  async function saveUserPatch(username: string, req: UpdateUserRequest, version: number): Promise<UserAdminView> {
    try {
      const v = await patchUser(username, req, version)
      applyLocal(v)
      conflictLatest.value = null
      return v
    } catch (e) {
      if (isVersionConflict(e)) conflictLatest.value = await fetchUser(username)
      throw e
    }
  }
  async function saveUserRoles(username: string, roles: string[], version: number): Promise<UserAdminView> {
    try {
      const v = await replaceUserRoles(username, roles, version)
      applyLocal(v)
      conflictLatest.value = null
      return v
    } catch (e) {
      if (isVersionConflict(e)) conflictLatest.value = await fetchUser(username)
      throw e
    }
  }
  /** 采纳冲突时的服务端最新为新基线（放弃本地草稿）：写回 selected + 列表，触发编辑器 syncDraft。返回该值。 */
  function acceptConflictLatest(): UserAdminView | null {
    const v = conflictLatest.value
    if (v) applyLocal(v)
    conflictLatest.value = null
    return v
  }
  function clearConflict(): void {
    conflictLatest.value = null
  }
  async function createUserAction(req: CreateUserRequest): Promise<UserAdminView> {
    const v = await createUser(req)
    list.reload() // 计数变化 → 整页重拉
    return v
  }
  async function deleteUserAction(username: string, version: number): Promise<void> {
    await deleteUser(username, version) // 带 If-Match：删陈旧资源 → 412
    removeLocal(username)
    list.reload()
  }

  return {
    // 列表（透传 usePagedQuery 的响应式）
    items: list.items,
    total: list.total,
    offset: list.offset,
    pageSize: list.pageSize,
    status: list.status,
    error: list.error,
    hasNext: list.hasNext,
    hasPrev: list.hasPrev,
    nextPage: list.nextPage,
    prevPage: list.prevPage,
    reload: list.reload,
    load: list.load,
    // 详情 + 写
    selected,
    selectedStatus,
    selectedError,
    conflictLatest,
    loadDetail,
    applyLocal,
    removeLocal,
    saveUserPatch,
    saveUserRoles,
    acceptConflictLatest,
    clearConflict,
    createUserAction,
    deleteUserAction,
  }
})
