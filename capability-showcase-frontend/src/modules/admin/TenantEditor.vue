<script setup lang="ts">
/**
 * 租户基础角色编辑 /admin/tenants/:tenant（继承式 RBAC）。
 *
 * RolePicker 选租户基础角色（未知角色原样保留）+ ScopePicker readonly 预览有效基础 scopes（本地预测，
 * 保存后由服务端最终确定）。保存带 version：**首次绑定时租户 version 为 -1，直接用 If-Match: -1**
 * （由 store.selected.version 自然携带）。清空按钮走 DELETE（经 DangerConfirmDialog）。
 * version_conflict → VersionConflictDialog。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAdminTenantsStore } from '../../stores/adminTenants'
import { useAdminRolesStore } from '../../stores/adminRoles'
import { humanizeError, isVersionConflict } from '../../api/errors'
import RolePicker from '../../components/admin/RolePicker.vue'
import ScopePicker from '../../components/admin/ScopePicker.vue'
import VersionConflictDialog from '../../components/admin/VersionConflictDialog.vue'
import DangerConfirmDialog from '../../components/admin/DangerConfirmDialog.vue'
import InfoNote from '../_shared/InfoNote.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import type { TenantView } from '../../types/admin'

const props = defineProps<{ tenant: string }>()

const store = useAdminTenantsStore()
const roles = useAdminRolesStore()
const router = useRouter()

const draft = reactive<{ roles: string[] }>({ roles: [] })

const saving = ref(false)
const localError = ref<string | null>(null)
const localSuccess = ref<string | null>(null)
const conflictOpen = ref(false)
const clearOpen = ref(false)

/** 首次绑定：租户尚无基础角色记录，version 为 -1。 */
const isFirstBinding = computed(() => (store.selected?.version ?? -1) < 0)
const memberCount = computed(() => store.selected?.memberCount ?? 0)

/** 角色可选项（真实角色名）。 */
const roleOptions = computed<string[]>(() => roles.roleNames)

/** 有效基础 scopes 预测（⋃ 所选角色 scopes）——仅供判断，保存后以服务端为准。 */
const rolesByName = computed(() => new Map(roles.roles.map((r) => [r.name, r.scopes])))
const predictedScopes = computed<string[]>(() => {
  const set = new Set<string>()
  for (const rn of draft.roles) (rolesByName.value.get(rn) ?? []).forEach((s) => set.add(s))
  return [...set]
})

const rolesChanged = computed(() => {
  const cur = store.selected?.baseRoles ?? []
  return draft.roles.length !== cur.length || draft.roles.some((r) => !cur.includes(r))
})
const canClear = computed(() => (store.selected?.baseRoles.length ?? 0) > 0)

const conflictFields = [{ key: 'roles', label: '基础角色' }]
const conflictDraft = computed<Record<string, unknown>>(() => ({ roles: draft.roles }))
const conflictCurrent = computed<Record<string, unknown>>(() => ({ roles: store.conflictLatest?.baseRoles }))

function syncDraft(t: TenantView): void {
  draft.roles = [...t.baseRoles]
}

watch(
  () => store.selected,
  (t) => {
    if (t && t.tenant === props.tenant) syncDraft(t)
  },
  { immediate: true },
)

onMounted(() => {
  if (roles.status === 'idle') void roles.load()
  void store.loadDetail(props.tenant)
})

function goList(): void {
  void router.push({ name: 'admin-tenants' })
}

async function save(): Promise<void> {
  localError.value = null
  localSuccess.value = null
  const base = store.selected
  if (!base) return
  saving.value = true
  try {
    await store.saveTenantRoles(props.tenant, draft.roles, base.version) // 首次绑定 base.version=-1
    localSuccess.value = `租户 ${props.tenant} 的基础角色已保存。该租户成员的权限在其下次刷新令牌后生效。`
  } catch (e) {
    if (isVersionConflict(e)) conflictOpen.value = true
    else localError.value = humanizeError(e, undefined, { credentialMode: 'bearer' })
  } finally {
    saving.value = false
  }
}

function onConflictReload(): void {
  store.acceptConflictLatest() // 采纳服务端最新 → watch 自动 syncDraft（放弃本地草稿）
  conflictOpen.value = false
}
function onConflictClose(): void {
  conflictOpen.value = false
  store.clearConflict()
}

async function confirmClear(): Promise<void> {
  clearOpen.value = false
  localError.value = null
  const version = store.selected?.version ?? 0
  try {
    await store.clearTenantRolesAction(props.tenant, version) // 带 If-Match
    goList()
  } catch (e) {
    if (isVersionConflict(e)) conflictOpen.value = true
    else localError.value = humanizeError(e, undefined, { credentialMode: 'bearer' })
  }
}
</script>

<template>
  <section class="te">
    <button type="button" class="te__back" @click="goList">‹ 返回租户列表</button>

    <EmptyState
      v-if="store.selectedStatus === 'loading'"
      variant="loading"
      title="加载租户详情…"
    />
    <EmptyState
      v-else-if="store.selectedStatus === 'error'"
      variant="error"
      title="加载失败"
      :description="store.selectedError ?? '请稍后重试。'"
      action-label="重试"
      @action="store.loadDetail(props.tenant)"
    />

    <template v-else>
      <header class="te__head">
        <h2 class="te__title">编辑租户基础角色</h2>
        <p v-if="store.selected" class="te__ident">
          🏢 {{ store.selected.tenant }} · v{{ store.selected.version }} · {{ memberCount }} 位成员
        </p>
      </header>

      <InfoNote v-if="isFirstBinding" tone="info">
        该租户尚未配置基础角色，保存即为<strong>首次绑定</strong>（以 If-Match: -1 提交）。
      </InfoNote>

      <div class="te__form">
        <!-- 基础角色 -->
        <div class="te__field">
          <span class="te__label">租户基础角色（对全体成员生效）</span>
          <RolePicker v-model="draft.roles" :options="roleOptions" />
        </div>

        <!-- 有效基础 scopes 预览（只读） -->
        <div class="te__field">
          <span class="te__label">有效基础 scopes 预览（预测）</span>
          <ScopePicker :model-value="predictedScopes" readonly />
          <p class="te__hint">= ⋃ 所选基础角色的 scopes；下次登录 / 刷新后由服务端最终确定。</p>
        </div>

        <InfoNote v-if="memberCount > 0 && rolesChanged" tone="warning">
          修改基础角色将影响该租户 <strong>{{ memberCount }}</strong> 位成员的有效权限（下次刷新后生效）。
        </InfoNote>
      </div>

      <InfoNote v-if="localError" tone="danger">{{ localError }}</InfoNote>
      <InfoNote v-if="localSuccess" tone="success">{{ localSuccess }}</InfoNote>

      <div class="te__actions">
        <button type="button" class="btn" @click="goList">取消</button>
        <button
          type="button"
          class="btn btn--primary"
          :class="{ 'btn--loading': saving }"
          :disabled="saving"
          @click="save"
        >
          保存基础角色
        </button>
        <button
          type="button"
          class="btn btn--danger te__clear"
          :disabled="!canClear"
          :title="canClear ? '' : '当前无基础角色，无需清空'"
          @click="clearOpen = true"
        >
          清空基础角色
        </button>
      </div>
    </template>

    <VersionConflictDialog
      :open="conflictOpen"
      :draft="conflictDraft"
      :current="conflictCurrent"
      :fields="conflictFields"
      @reload="onConflictReload"
      @close="onConflictClose"
    />
    <DangerConfirmDialog
      :open="clearOpen"
      title="清空租户基础角色"
      :message="`将移除租户 ${props.tenant} 的全部基础角色，其 ${memberCount} 位成员会相应失去这些基础权限（下次刷新后生效）。`"
      confirm-label="确认清空"
      @confirm="confirmClear"
      @cancel="clearOpen = false"
    />
  </section>
</template>

<style scoped>
.te {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  max-width: 720px;
}
.te__back {
  align-self: flex-start;
  background: none;
  border: none;
  color: var(--text-muted);
  font-size: var(--fs-sm);
  cursor: pointer;
  padding: 0;
}
.te__back:hover {
  color: var(--primary);
}
.te__head {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.te__title {
  font-size: var(--fs-lg);
  font-weight: var(--fw-bold);
}
.te__ident {
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.te__form {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.te__field {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.te__label {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
}
.te__hint {
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.te__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.te__clear {
  margin-left: auto;
}
</style>
