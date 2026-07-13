<script setup lang="ts">
/**
 * 角色编辑 /admin/roles/:name（name==='new' 为新建）。
 *
 * ScopePicker 按域分组勾选（**未知 scope 原样保留回写**）+ description；编辑前显示 assignedUserCount（影响预览）。
 * 保存带 version；version_conflict → VersionConflictDialog。删除经 DangerConfirmDialog：
 *   - assignedUserCount>0 → 前端软拦（删除禁用 + 引导去解除绑定的用户筛选）；
 *   - 后端并发返回 409 role_in_use → 就地提示并链接到 `/admin/users?role=<name>`。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAdminRolesStore } from '../../stores/adminRoles'
import { apiErrorCode, humanizeError, isVersionConflict } from '../../api/errors'
import ScopePicker from '../../components/admin/ScopePicker.vue'
import VersionConflictDialog from '../../components/admin/VersionConflictDialog.vue'
import DangerConfirmDialog from '../../components/admin/DangerConfirmDialog.vue'
import InfoNote from '../_shared/InfoNote.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import type { CreateRoleRequest, RoleView, UpdateRoleRequest } from '../../types/admin'

const props = defineProps<{ name: string }>()

const store = useAdminRolesStore()
const router = useRouter()

const isNew = computed(() => props.name === 'new')

const draft = reactive<{ scopes: string[]; description: string }>({ scopes: [], description: '' })
const newName = ref('')

const saving = ref(false)
const localError = ref<string | null>(null)
const localSuccess = ref<string | null>(null)
const conflictOpen = ref(false)
const deleteOpen = ref(false)
const roleInUse = ref(false)

const assignedCount = computed(() => (isNew.value ? 0 : store.selected?.assignedUserCount ?? 0))
const scopesChanged = computed(() => {
  const cur = store.selected?.scopes ?? []
  return draft.scopes.length !== cur.length || draft.scopes.some((s) => !cur.includes(s))
})

const conflictFields = [
  { key: 'scopes', label: 'scopes' },
  { key: 'description', label: '说明' },
]
const conflictDraft = computed<Record<string, unknown>>(() => ({ scopes: draft.scopes, description: draft.description }))
// 冲突"服务端最新"来自独立 conflictLatest（不覆盖 selected/草稿），保证真实差异。
const conflictCurrent = computed<Record<string, unknown>>(() => ({
  scopes: store.conflictLatest?.scopes,
  description: store.conflictLatest?.description,
}))

function syncDraft(r: RoleView): void {
  draft.scopes = [...r.scopes]
  draft.description = r.description
}

watch(
  () => store.selected,
  (r) => {
    if (r && !isNew.value && r.name === props.name) syncDraft(r)
  },
  { immediate: true },
)

onMounted(() => {
  if (!isNew.value) void store.loadDetail(props.name)
})

function goList(): void {
  void router.push({ name: 'admin-roles' })
}

async function save(): Promise<void> {
  localError.value = null
  localSuccess.value = null
  saving.value = true
  try {
    if (isNew.value) {
      const req: CreateRoleRequest = {
        name: newName.value.trim(),
        scopes: draft.scopes,
        description: draft.description,
      }
      await store.createRoleAction(req)
      goList()
      return
    }
    const base = store.selected
    if (!base) return
    const req: UpdateRoleRequest = { scopes: draft.scopes, description: draft.description }
    await store.saveRole(props.name, req, base.version)
    localSuccess.value = `角色 ${props.name} 已保存。绑定用户的权限在其下次刷新令牌后生效。`
  } catch (e) {
    if (isVersionConflict(e)) conflictOpen.value = true
    else localError.value = humanizeError(e, undefined, { credentialMode: 'bearer' })
  } finally {
    saving.value = false
  }
}

function onConflictReload(): void {
  // 采纳服务端最新为新基线：acceptConflictLatest 写回 selected → watch 自动 syncDraft（放弃本地草稿）。
  store.acceptConflictLatest()
  conflictOpen.value = false
}
function onConflictClose(): void {
  conflictOpen.value = false
  store.clearConflict()
}

async function confirmDelete(): Promise<void> {
  deleteOpen.value = false
  localError.value = null
  roleInUse.value = false
  const version = store.selected?.version ?? 0
  try {
    await store.deleteRoleAction(props.name, version) // 带 If-Match
    goList()
  } catch (e) {
    if (apiErrorCode(e) === 'role_in_use') roleInUse.value = true
    else localError.value = humanizeError(e, undefined, { credentialMode: 'bearer' })
  }
}
</script>

<template>
  <section class="re">
    <button type="button" class="re__back" @click="goList">‹ 返回角色列表</button>

    <EmptyState
      v-if="!isNew && store.selectedStatus === 'loading'"
      variant="loading"
      title="加载角色详情…"
    />
    <EmptyState
      v-else-if="!isNew && store.selectedStatus === 'error'"
      variant="error"
      title="加载失败"
      :description="store.selectedError ?? '请稍后重试。'"
      action-label="重试"
      @action="store.loadDetail(props.name)"
    />

    <template v-else>
      <header class="re__head">
        <h2 class="re__title">{{ isNew ? '新建角色' : '编辑角色' }}</h2>
        <p v-if="!isNew && store.selected" class="re__ident">
          🛡 {{ store.selected.name }} · v{{ store.selected.version }} · {{ assignedCount }} 人绑定
        </p>
      </header>

      <div class="re__form">
        <!-- 角色名 -->
        <div class="re__field">
          <label class="re__label" for="re-name">角色名</label>
          <input
            v-if="isNew"
            id="re-name"
            v-model="newName"
            class="form-control"
            type="text"
            autocomplete="off"
            spellcheck="false"
          />
          <template v-else>
            <input id="re-name" class="form-control" type="text" :value="props.name" disabled />
            <p class="re__hint">创建后不可改名（改名 = 新建 + 迁移 + 删旧）。</p>
          </template>
        </div>

        <!-- 说明 -->
        <div class="re__field">
          <label class="re__label" for="re-desc">说明</label>
          <input id="re-desc" v-model="draft.description" class="form-control" type="text" maxlength="200" />
        </div>

        <!-- scopes -->
        <div class="re__field">
          <span class="re__label">权限 scopes（按域分组）</span>
          <ScopePicker v-model="draft.scopes" />
        </div>

        <InfoNote v-if="!isNew && assignedCount > 0 && scopesChanged" tone="warning">
          修改 scopes 将影响 <strong>{{ assignedCount }}</strong> 个绑定用户的有效权限（下次登录 / 刷新后生效）。
        </InfoNote>
      </div>

      <InfoNote v-if="roleInUse" tone="danger">
        该角色仍被引用，无法删除。请先解除绑定：
        <RouterLink :to="{ name: 'admin-users', query: { role: props.name } }" class="re__link">
          查看绑定用户 →
        </RouterLink>
      </InfoNote>
      <InfoNote v-if="localError" tone="danger">{{ localError }}</InfoNote>
      <InfoNote v-if="localSuccess" tone="success">{{ localSuccess }}</InfoNote>

      <div class="re__actions">
        <button type="button" class="btn" @click="goList">取消</button>
        <button
          type="button"
          class="btn btn--primary"
          :class="{ 'btn--loading': saving }"
          :disabled="saving"
          @click="save"
        >
          {{ isNew ? '创建角色' : '保存角色' }}
        </button>
        <template v-if="!isNew">
          <button
            type="button"
            class="btn btn--danger re__del"
            :disabled="assignedCount > 0"
            :title="assignedCount > 0 ? `被 ${assignedCount} 个用户引用，先解除绑定` : ''"
            @click="deleteOpen = true"
          >
            删除角色
          </button>
          <RouterLink
            v-if="assignedCount > 0"
            :to="{ name: 'admin-users', query: { role: props.name } }"
            class="re__link"
          >
            {{ assignedCount }} 人在用，先去解绑 →
          </RouterLink>
        </template>
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
      :open="deleteOpen"
      title="删除角色"
      :message="`此操作不可撤销，将永久删除角色 ${props.name}。`"
      confirm-label="确认删除"
      :require-text="props.name"
      @confirm="confirmDelete"
      @cancel="deleteOpen = false"
    />
  </section>
</template>

<style scoped>
.re {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  max-width: 720px;
}
.re__back {
  align-self: flex-start;
  background: none;
  border: none;
  color: var(--text-muted);
  font-size: var(--fs-sm);
  cursor: pointer;
  padding: 0;
}
.re__back:hover {
  color: var(--primary);
}
.re__head {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.re__title {
  font-size: var(--fs-lg);
  font-weight: var(--fw-bold);
}
.re__ident {
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.re__form {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.re__field {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.re__label {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
}
.re__hint {
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.re__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.re__del {
  margin-left: auto;
}
.re__link {
  font-size: var(--fs-sm);
  color: var(--primary);
  text-decoration: none;
}
.re__link:hover {
  text-decoration: underline;
}
</style>
