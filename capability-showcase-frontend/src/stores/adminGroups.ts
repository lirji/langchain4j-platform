import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { humanizeError, isVersionConflict } from '../api/errors'
import {
  createGroup,
  deleteGroup,
  fetchGroup,
  fetchGroupMembers,
  fetchGroups,
  replaceGroupMembers,
  updateGroup,
} from '../api/admin'
import type { CreateGroupRequest, GroupView, UpdateGroupRequest } from '../types/admin'

/**
 * 用户组管理 store（继承式 RBAC 的一支来源）。**仅内存。** 镜像 adminRoles.ts：
 * `GET /groups` 一次拉全（无分页），写成功局部失效；version_conflict 抓服务端最新到 conflictLatest
 * 后重抛（不覆盖 selected/草稿）。主键为 `name`。
 *
 * 成员管理与「说明 + 角色」是**各自独立的写**（不同端点、各带 version）：saveGroup 改 description/roles，
 * saveMembers 改成员集合；两者都可能触发 version_conflict，统一抓 fetchGroup 到 conflictLatest。
 * 组角色 scopes 供 UserEditor 预测所选组的有效权限。
 */
export const useAdminGroupsStore = defineStore('adminGroups', () => {
  const groups = ref<GroupView[]>([])
  const status = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const error = ref<string | null>(null)
  const selected = ref<GroupView | null>(null)
  const selectedStatus = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const selectedError = ref<string | null>(null)
  /** 版本冲突时的服务端最新值——独立于 selected，不覆盖草稿基线。 */
  const conflictLatest = ref<GroupView | null>(null)

  // ── 成员：独立子状态（GroupEditor 的 MembersEditor 用） ──
  const members = ref<string[]>([])
  const membersStatus = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const membersError = ref<string | null>(null)

  const groupNames = computed(() => groups.value.map((g) => g.name))
  /** 各组的角色映射（供 UserEditor 预测所选组的角色 scopes）。 */
  const rolesByGroup = computed(() => new Map(groups.value.map((g) => [g.name, g.roles])))

  async function load(): Promise<void> {
    status.value = 'loading'
    error.value = null
    try {
      groups.value = await fetchGroups()
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
      selected.value = await fetchGroup(name)
      selectedStatus.value = 'ready'
    } catch (e) {
      selectedError.value = humanizeError(e, undefined, { credentialMode: 'bearer' })
      selectedStatus.value = 'error'
    }
  }

  async function loadMembers(name: string): Promise<void> {
    membersStatus.value = 'loading'
    membersError.value = null
    try {
      members.value = await fetchGroupMembers(name)
      membersStatus.value = 'ready'
    } catch (e) {
      membersError.value = humanizeError(e, undefined, { credentialMode: 'bearer' })
      membersStatus.value = 'error'
    }
  }

  function applyLocal(next: GroupView): void {
    const i = groups.value.findIndex((g) => g.name === next.name)
    if (i >= 0) groups.value.splice(i, 1, next)
    if (selected.value?.name === next.name) selected.value = next
  }
  function removeLocal(name: string): void {
    const i = groups.value.findIndex((g) => g.name === name)
    if (i >= 0) groups.value.splice(i, 1)
    if (selected.value?.name === name) selected.value = null
  }

  async function saveGroup(name: string, req: UpdateGroupRequest, version: number): Promise<GroupView> {
    try {
      const v = await updateGroup(name, req, version)
      applyLocal(v)
      conflictLatest.value = null
      return v
    } catch (e) {
      if (isVersionConflict(e)) conflictLatest.value = await fetchGroup(name)
      throw e
    }
  }
  async function saveMembers(name: string, next: string[], version: number): Promise<GroupView> {
    try {
      const v = await replaceGroupMembers(name, next, version)
      applyLocal(v) // 成员数 / version 前进
      members.value = [...next] // PUT 全量替换：本地即为提交值
      conflictLatest.value = null
      return v
    } catch (e) {
      if (isVersionConflict(e)) conflictLatest.value = await fetchGroup(name)
      throw e
    }
  }
  /** 采纳冲突时的服务端最新为新基线（放弃本地草稿）。 */
  function acceptConflictLatest(): GroupView | null {
    const v = conflictLatest.value
    if (v) applyLocal(v)
    conflictLatest.value = null
    return v
  }
  function clearConflict(): void {
    conflictLatest.value = null
  }
  async function createGroupAction(req: CreateGroupRequest): Promise<GroupView> {
    const v = await createGroup(req)
    await load()
    return v
  }
  async function deleteGroupAction(name: string, version: number): Promise<void> {
    await deleteGroup(name, version) // 带 If-Match；有成员时后端 409 group_in_use
    removeLocal(name)
  }

  return {
    groups,
    status,
    error,
    selected,
    selectedStatus,
    selectedError,
    conflictLatest,
    members,
    membersStatus,
    membersError,
    groupNames,
    rolesByGroup,
    load,
    loadDetail,
    loadMembers,
    applyLocal,
    removeLocal,
    saveGroup,
    saveMembers,
    acceptConflictLatest,
    clearConflict,
    createGroupAction,
    deleteGroupAction,
  }
})
