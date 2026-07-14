<script setup lang="ts">
/**
 * 用户详情 / 编辑 /admin/users/:username（username==='new' 为新建）。
 *
 * 闭环：loadDetail → 本地草稿（tenant/enabled/password + directScopes 只读 + roles 可编辑）→
 *   保存拆两步 saveUserPatch(profile) + saveUserRoles(roles)，各带当前 version（角色端点在后，用 patch 回传的新 version）。
 * 密码：只入不出，**留空=不修改**（不进请求体）、绝不回显。
 * 冲突：saveUserPatch/saveUserRoles 抛 409 version_conflict → 打开 VersionConflictDialog（草稿 vs store.selected，不覆盖）。
 * 删除：经 DangerConfirmDialog（键入用户名确认）；last_admin 等其它 409 就地 danger 提示。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAdminUsersStore } from '../../stores/adminUsers'
import { useAdminRolesStore } from '../../stores/adminRoles'
import { useAdminTenantsStore } from '../../stores/adminTenants'
import { useAdminGroupsStore } from '../../stores/adminGroups'
import { useAuthStore } from '../../stores/auth'
import { humanizeError, isVersionConflict } from '../../api/errors'
import { fetchUserEffectivePermissions } from '../../api/admin'
import ScopePicker from '../../components/admin/ScopePicker.vue'
import RolePicker from '../../components/admin/RolePicker.vue'
import GroupPicker from '../../components/admin/GroupPicker.vue'
import VersionConflictDialog from '../../components/admin/VersionConflictDialog.vue'
import DangerConfirmDialog from '../../components/admin/DangerConfirmDialog.vue'
import InfoNote from '../_shared/InfoNote.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import CopyButton from '../../components/common/CopyButton.vue'
import type {
  CreateUserRequest,
  EffectivePermissionsView,
  UpdateUserRequest,
  UserAdminView,
} from '../../types/admin'

const props = defineProps<{ username: string }>()

const users = useAdminUsersStore()
const roles = useAdminRolesStore()
const tenants = useAdminTenantsStore()
const groups = useAdminGroupsStore()
const auth = useAuthStore()
const router = useRouter()

const isNew = computed(() => props.username === 'new')

// ── 本地草稿（仅内存；密码只入不出） ──
const draft = reactive<{ tenant: string; enabled: boolean; password: string; roles: string[]; groups: string[] }>({
  tenant: '',
  enabled: true,
  password: '',
  roles: [],
  groups: [],
})
const newUsername = ref('')

const saving = ref(false)
const localError = ref<string | null>(null)
const localSuccess = ref<string | null>(null)
const conflictOpen = ref(false)
const deleteOpen = ref(false)

/** direct scopes：本期只读展示（授权走角色）。新建用户为空。 */
const directScopes = computed<string[]>(() => (isNew.value ? [] : users.selected?.directScopes ?? []))

/** 角色 / 用户组可选项（真实名称）。 */
const roleOptions = computed<string[]>(() => roles.roleNames)
const groupOptions = computed<string[]>(() => groups.groupNames)

/**
 * 有效权限预测（继承式 RBAC）：
 *   直配 ∪ ⋃(所选个人角色 scopes) ∪ ⋃(该用户租户的基础角色 scopes) ∪ ⋃(所选组的角色 scopes)。
 * 纯本地预测，仅供判断；保存后由服务端在下方「归因区」给出权威结果。
 */
const rolesByName = computed(() => new Map(roles.roles.map((r) => [r.name, r.scopes])))
function addRoleScopes(set: Set<string>, roleNames: string[]): void {
  for (const rn of roleNames) (rolesByName.value.get(rn) ?? []).forEach((s) => set.add(s))
}
const predictedScopes = computed<string[]>(() => {
  const set = new Set(directScopes.value)
  addRoleScopes(set, draft.roles) // 个人角色
  addRoleScopes(set, tenants.baseRolesByTenant.get(draft.tenant.trim()) ?? []) // 租户基础角色
  for (const gn of draft.groups) addRoleScopes(set, groups.rolesByGroup.get(gn) ?? []) // 用户组角色
  return [...set]
})

// ── 服务端权威归因（只读；点开时或保存后拉取） ──
const perms = ref<EffectivePermissionsView | null>(null)
const permsStatus = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const permsError = ref<string | null>(null)

async function loadPerms(): Promise<void> {
  if (isNew.value) return
  permsStatus.value = 'loading'
  permsError.value = null
  try {
    perms.value = await fetchUserEffectivePermissions(props.username)
    permsStatus.value = 'ready'
  } catch (e) {
    permsError.value = humanizeError(e, undefined, { credentialMode: 'bearer' })
    permsStatus.value = 'error'
  }
}

/** 每条 scope 的有效来源标签（解析 sources 标签为中文）。 */
interface PermRow {
  scope: string
  sources: string[]
}
const permRows = computed<PermRow[]>(() =>
  (perms.value?.effectiveScopes ?? []).map((scope) => ({
    scope,
    sources: perms.value?.sources[scope] ?? [],
  })),
)
/** 来源标签 → { 中文文案, 类别 }。类别用于配色。 */
function describeSource(src: string): { text: string; kind: 'direct' | 'role' | 'tenant' | 'group' } {
  if (src === 'direct') return { text: '直配', kind: 'direct' }
  if (src.startsWith('role:')) return { text: `个人角色 ${src.slice(5)}`, kind: 'role' }
  if (src.startsWith('tenant:')) return { text: `租户 ${src.slice(7)}`, kind: 'tenant' }
  if (src.startsWith('group:')) {
    const rest = src.slice(6) // <组名>:<角色名>
    const idx = rest.indexOf(':')
    const g = idx >= 0 ? rest.slice(0, idx) : rest
    const r = idx >= 0 ? rest.slice(idx + 1) : ''
    return { text: r ? `组 ${g} / ${r}` : `组 ${g}`, kind: 'group' }
  }
  return { text: src, kind: 'role' }
}

const isSelf = computed(() => !isNew.value && props.username === auth.username)

/** 冲突对话框的差异字段。 */
const conflictFields = [
  { key: 'tenant', label: '租户' },
  { key: 'enabled', label: '启用' },
  { key: 'roles', label: '角色' },
  { key: 'groups', label: '用户组' },
]
/**
 * 冲突时用户"本次实际尝试提交"的值快照——独立于 live draft：两步保存的第一步成功会经 watch 重置 draft，
 * 若弹窗读 live draft 会显示被重置后的值（可能误判"无差异"）。故冲突发生时定格用户尝试值。
 */
const conflictSnapshot = ref<Record<string, unknown> | null>(null)
const conflictDraft = computed<Record<string, unknown>>(
  () =>
    conflictSnapshot.value ?? {
      tenant: draft.tenant,
      enabled: draft.enabled,
      roles: draft.roles,
      groups: draft.groups,
    },
)
// 冲突"服务端最新"来自独立的 conflictLatest（不覆盖 selected/草稿），保证与草稿的真实差异。
const conflictCurrent = computed<Record<string, unknown>>(() => ({
  tenant: users.conflictLatest?.tenant,
  enabled: users.conflictLatest?.enabled,
  roles: users.conflictLatest?.roles,
  groups: users.conflictLatest?.groups,
}))

function syncDraft(u: UserAdminView): void {
  draft.tenant = u.tenant
  draft.enabled = u.enabled
  draft.roles = [...u.roles]
  draft.groups = [...u.groups]
  draft.password = ''
}

watch(
  () => users.selected,
  (u) => {
    // 保存进行中不自动同步草稿——两步写之间 selected 变更不得重置用户正在编辑/尝试的值。
    if (u && !isNew.value && u.username === props.username && !saving.value) syncDraft(u)
  },
  { immediate: true },
)

onMounted(() => {
  if (roles.status === 'idle') void roles.load()
  if (tenants.status === 'idle') void tenants.load() // 预测租户基础角色 scopes
  if (groups.status === 'idle') void groups.load() // GroupPicker 选项 + 预测组角色 scopes
  if (!isNew.value) {
    void users.loadDetail(props.username)
    void loadPerms() // 服务端权威归因
  }
})

function goList(): void {
  void router.push({ name: 'admin-users' })
}

async function save(): Promise<void> {
  localError.value = null
  localSuccess.value = null
  saving.value = true
  try {
    if (isNew.value) {
      const req: CreateUserRequest = {
        username: newUsername.value.trim(),
        password: draft.password,
        tenant: draft.tenant.trim(),
        roles: draft.roles,
        directScopes: [], // 本期新建不设 direct scopes（授权走角色）
        enabled: draft.enabled,
      }
      await users.createUserAction(req)
      goList()
      return
    }
    const base = users.selected
    if (!base) return
    // 保存开始即快照要提交的值——多步写之间 selected 变更会触发 watch→syncDraft，若此时再读 draft 会取到被重置的值。
    const rolesToSend = [...draft.roles]
    const groupsToSend = [...draft.groups]
    // 脏检查：只写真正变化的端点，避免无谓的多写与多次 version bump（并缩小多步部分失败的窗口）。
    const profileDirty =
      draft.tenant.trim() !== base.tenant || draft.enabled !== base.enabled || Boolean(draft.password)
    const rolesDirty =
      rolesToSend.length !== base.roles.length || rolesToSend.some((r) => !base.roles.includes(r))
    const groupsDirty =
      groupsToSend.length !== base.groups.length || groupsToSend.some((g) => !base.groups.includes(g))
    if (!profileDirty && !rolesDirty && !groupsDirty) {
      localSuccess.value = '没有需要保存的改动。'
      return
    }
    // 写前定格本次尝试值（供冲突弹窗做 draft vs 服务端最新的差异，不受 watch 重置影响）；成功后清除。
    conflictSnapshot.value = {
      tenant: draft.tenant.trim(),
      enabled: draft.enabled,
      roles: rolesToSend,
      groups: groupsToSend,
    }
    let version = base.version
    // 1) profile：tenant/enabled 常规提交；password 仅在非空时进请求体（留空=不改）。
    if (profileDirty) {
      const patchReq: UpdateUserRequest = { tenant: draft.tenant.trim(), enabled: draft.enabled }
      if (draft.password) patchReq.password = draft.password
      const patched = await users.saveUserPatch(props.username, patchReq, version)
      version = patched.version // 版本每次写都会 bump；后续端点用最新版本
    }
    // 2) roles：用快照的 roles，避免中途被 syncDraft 重置。
    if (rolesDirty) {
      const r = await users.saveUserRoles(props.username, rolesToSend, version)
      version = r.version
    }
    // 3) groups：与 roles 同构的独立写，用最新 version。
    if (groupsDirty) {
      await users.saveUserGroups(props.username, groupsToSend, version)
    }
    conflictSnapshot.value = null
    draft.password = ''
    localSuccess.value = `用户 ${props.username} 已更新。权限变更在其下次刷新令牌后生效（旧令牌在 TTL 内仍有效）。`
    void loadPerms() // 保存后刷新服务端权威归因
  } catch (e) {
    if (isVersionConflict(e)) {
      conflictOpen.value = true // conflictSnapshot 已在写前定格，弹窗据此对比
    } else {
      conflictSnapshot.value = null
      localError.value = humanizeError(e, undefined, { credentialMode: 'bearer' })
    }
  } finally {
    saving.value = false
  }
}

function onConflictReload(): void {
  // 采纳服务端最新为新基线：acceptConflictLatest 写回 selected → watch 自动 syncDraft（放弃本地草稿）。
  users.acceptConflictLatest()
  conflictSnapshot.value = null
  conflictOpen.value = false
}
function onConflictClose(): void {
  conflictSnapshot.value = null
  conflictOpen.value = false
  users.clearConflict()
}

async function confirmDelete(): Promise<void> {
  deleteOpen.value = false
  localError.value = null
  const base = users.selected
  if (!base) return
  try {
    await users.deleteUserAction(props.username, base.version) // 带 If-Match：删陈旧资源 → 412 提示刷新
    goList()
  } catch (e) {
    localError.value = humanizeError(e, undefined, { credentialMode: 'bearer' })
  }
}
</script>

<template>
  <section class="ue">
    <button type="button" class="ue__back" @click="goList">‹ 返回用户列表</button>

    <!-- 加载 / 错误（仅编辑态） -->
    <EmptyState
      v-if="!isNew && users.selectedStatus === 'loading'"
      variant="loading"
      title="加载用户详情…"
    />
    <EmptyState
      v-else-if="!isNew && users.selectedStatus === 'error'"
      variant="error"
      title="加载失败"
      :description="users.selectedError ?? '请稍后重试。'"
      action-label="重试"
      @action="users.loadDetail(props.username)"
    />

    <template v-else>
      <!-- 头部 -->
      <header class="ue__head">
        <h2 class="ue__title">{{ isNew ? '新建用户' : '编辑用户' }}</h2>
        <p v-if="!isNew && users.selected" class="ue__ident">
          👤 {{ users.selected.username }} · 租户 {{ users.selected.tenant }} · v{{ users.selected.version }}
          <CopyButton :text="users.selected.userId" label="复制 userId" compact />
        </p>
      </header>

      <InfoNote v-if="isSelf" tone="warning">
        你正在编辑<strong>自己的账号</strong>。改动角色 / 启停 / 密码会影响你自己的会话，保存后可能需要重新登录。
      </InfoNote>

      <div class="ue__form">
        <!-- 新建：用户名 -->
        <div v-if="isNew" class="ue__field">
          <label class="ue__label" for="ue-username">用户名</label>
          <input id="ue-username" v-model="newUsername" class="form-control" type="text" autocomplete="off" spellcheck="false" />
        </div>

        <!-- 租户 -->
        <div class="ue__field">
          <label class="ue__label" for="ue-tenant">租户</label>
          <input id="ue-tenant" v-model="draft.tenant" class="form-control" type="text" autocomplete="off" spellcheck="false" />
          <p class="ue__hint">租户为自由文本，禁止使用保留分区 <code>__public__</code>。</p>
        </div>

        <!-- direct scopes 只读 -->
        <div class="ue__field">
          <span class="ue__label">Direct Scopes（只读）</span>
          <ScopePicker :model-value="directScopes" readonly />
          <p class="ue__hint">本期 direct scopes 只读展示，授权统一经角色下发。</p>
        </div>

        <!-- 角色可编辑 -->
        <div class="ue__field">
          <span class="ue__label">个人角色</span>
          <RolePicker v-model="draft.roles" :options="roleOptions" />
        </div>

        <!-- 用户组（仅编辑态；新建后进编辑页管理组） -->
        <div v-if="!isNew" class="ue__field">
          <span class="ue__label">所属用户组</span>
          <GroupPicker v-model="draft.groups" :options="groupOptions" />
          <p class="ue__hint">用户经组继承组角色的 scopes（继承式 RBAC）。</p>
        </div>

        <!-- 有效权限预测 -->
        <div class="ue__field">
          <span class="ue__label">有效权限预览（预测）</span>
          <ScopePicker :model-value="predictedScopes" readonly />
          <p class="ue__hint">
            = 直配 ∪ 个人角色 ∪ 租户基础角色 ∪ 用户组角色 的 scopes；仅本地预测，保存后由下方「服务端权威归因」最终确定。
          </p>
        </div>

        <!-- 状态 -->
        <div class="ue__field">
          <span class="ue__label">状态</span>
          <button
            type="button"
            class="ue__switch"
            role="switch"
            :aria-checked="draft.enabled"
            :data-on="draft.enabled"
            @click="draft.enabled = !draft.enabled"
          >
            <span class="ue__switch-knob" aria-hidden="true" />
            <span class="ue__switch-text">{{ draft.enabled ? '启用' : '禁用' }}</span>
          </button>
        </div>

        <!-- 密码 -->
        <div class="ue__field">
          <label class="ue__label" for="ue-pw">{{ isNew ? '初始密码' : '重置密码（可选）' }}</label>
          <input
            id="ue-pw"
            v-model="draft.password"
            class="form-control"
            type="password"
            autocomplete="new-password"
            :placeholder="isNew ? '设置初始密码' : '留空 = 不修改'"
          />
          <p class="ue__hint">密码只入不出、绝不回显。{{ isNew ? '' : '留空表示保持原密码不变。' }}</p>
        </div>
      </div>

      <!-- 服务端权威归因（只读；每条有效 scope 的出处） -->
      <section v-if="!isNew" class="ue__perms" aria-label="服务端权威的有效权限归因">
        <div class="ue__perms-head">
          <span class="ue__label">服务端权威归因</span>
          <button
            type="button"
            class="btn btn--sm btn--ghost"
            :class="{ 'btn--loading': permsStatus === 'loading' }"
            :disabled="permsStatus === 'loading'"
            @click="loadPerms"
          >
            刷新
          </button>
        </div>
        <EmptyState
          v-if="permsStatus === 'loading' && !perms"
          variant="loading"
          title="加载有效权限归因…"
        />
        <InfoNote v-else-if="permsStatus === 'error'" tone="danger">
          {{ permsError ?? '归因加载失败。' }}
        </InfoNote>
        <p v-else-if="perms && !permRows.length" class="ue__hint">该用户当前无有效 scope。</p>
        <div v-else-if="perms" class="ue__perms-table-wrap" role="region" aria-label="有效权限来源" tabindex="0">
          <table class="ue__perms-table">
            <thead>
              <tr>
                <th scope="col">Scope</th>
                <th scope="col">来源</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in permRows" :key="row.scope">
                <td class="ue__perms-scope"><code>{{ row.scope }}</code></td>
                <td class="ue__perms-src">
                  <span v-if="!row.sources.length" class="ue__muted">—</span>
                  <span
                    v-for="src in row.sources"
                    :key="src"
                    class="ue__src-chip"
                    :data-kind="describeSource(src).kind"
                  >
                    {{ describeSource(src).text }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="ue__hint">来源类别：直配 / 个人角色 / 租户基础角色 / 用户组（继承式 RBAC，由服务端裁定）。</p>
      </section>

      <InfoNote v-if="localError" tone="danger">{{ localError }}</InfoNote>
      <InfoNote v-if="localSuccess" tone="success">{{ localSuccess }}</InfoNote>

      <!-- 操作 -->
      <div class="ue__actions">
        <button type="button" class="btn" @click="goList">取消</button>
        <button
          type="button"
          class="btn btn--primary"
          :class="{ 'btn--loading': saving }"
          :disabled="saving"
          @click="save"
        >
          {{ isNew ? '创建用户' : '保存更改' }}
        </button>
        <button v-if="!isNew" type="button" class="btn btn--danger ue__del" @click="deleteOpen = true">
          删除用户
        </button>
      </div>
    </template>

    <!-- 冲突 / 危险确认（mount-always，仅切换 open） -->
    <VersionConflictDialog
      :open="conflictOpen"
      :draft="conflictDraft"
      :current="conflictCurrent"
      :fields="conflictFields"
      @reload="onConflictReload"
      @close="onConflictClose"
    />
    <DangerConfirmDialog
      :open="deleteOpen"
      title="删除用户"
      :message="`此操作不可撤销，将永久删除用户 ${props.username} 及其角色绑定。`"
      confirm-label="确认删除"
      :require-text="props.username"
      @confirm="confirmDelete"
      @cancel="deleteOpen = false"
    />
  </section>
</template>

<style scoped>
.ue {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  max-width: 720px;
}
.ue__back {
  align-self: flex-start;
  background: none;
  border: none;
  color: var(--text-muted);
  font-size: var(--fs-sm);
  cursor: pointer;
  padding: 0;
}
.ue__back:hover {
  color: var(--primary);
}
.ue__head {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.ue__title {
  font-size: var(--fs-lg);
  font-weight: var(--fw-bold);
}
.ue__ident {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--fs-sm);
  color: var(--text-muted);
  flex-wrap: wrap;
}
.ue__form {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.ue__field {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.ue__label {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
}
.ue__hint {
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.ue__hint code {
  font-family: var(--font-mono);
  padding: 1px 5px;
  border-radius: var(--radius-sm);
  background: var(--surface-2);
}
.ue__switch {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  width: fit-content;
  padding: 4px 12px 4px 4px;
  background: var(--surface-2);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-pill);
  cursor: pointer;
}
.ue__switch-knob {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--neutral);
  transition: background var(--dur) var(--ease), transform var(--dur) var(--ease);
}
.ue__switch[data-on='true'] {
  border-color: var(--success-border);
  background: var(--success-soft);
}
.ue__switch[data-on='true'] .ue__switch-knob {
  background: var(--success);
  transform: translateX(2px);
}
.ue__switch-text {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
}
.ue__actions {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.ue__del {
  margin-left: auto;
}
.ue__muted {
  color: var(--text-subtle);
}
.ue__perms {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-2);
}
.ue__perms-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}
.ue__perms-table-wrap {
  width: 100%;
  overflow-x: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
}
.ue__perms-table-wrap:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--primary-border);
}
.ue__perms-table {
  border-collapse: collapse;
  width: 100%;
  font-size: var(--fs-sm);
}
.ue__perms-table th[scope='col'] {
  text-align: left;
  padding: var(--space-2) var(--space-3);
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
  background: var(--surface-2);
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}
.ue__perms-scope {
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
  vertical-align: top;
}
.ue__perms-scope code {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text);
  background: var(--surface-2);
  padding: 1px 6px;
  border-radius: var(--radius-sm);
}
.ue__perms-src {
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--border);
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.ue__src-chip {
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  border: 1px solid var(--border);
  color: var(--text-muted);
  background: var(--surface-3);
}
.ue__src-chip[data-kind='direct'] {
  color: var(--primary);
  background: var(--primary-soft);
  border-color: var(--primary-border);
}
.ue__src-chip[data-kind='role'] {
  color: var(--success);
  background: var(--success-soft);
  border-color: var(--success-border);
}
.ue__src-chip[data-kind='tenant'] {
  color: var(--warning);
  background: var(--warning-soft);
  border-color: var(--warning-border);
}
.ue__src-chip[data-kind='group'] {
  color: var(--stream);
  background: var(--stream-soft);
  border-color: var(--stream-border);
}
@media (prefers-reduced-motion: reduce) {
  .ue__switch-knob {
    transition: none;
  }
}
</style>
