<script setup lang="ts">
/**
 * 用户组编辑 /admin/groups/:name（name==='new' 为新建）。继承式 RBAC。
 *
 * 编辑态有**两个各自独立的写**（不同端点、各带 version）：
 *   1) 说明 + 角色 → PUT /groups/{name}（saveGroup）
 *   2) 成员集合   → PUT /groups/{name}/members（saveMembers）
 * 二者互不影响、各自脏检查、各自触发 version_conflict → VersionConflictDialog（按来源展示差异字段）。
 * RolePicker 选组角色（未知角色原样保留）+ ScopePicker readonly 预览有效 scopes（本地预测）+ MembersEditor 管成员。
 * 删除经 DangerConfirmDialog；后端 409 group_in_use（组仍有成员）就地提示。新建重名 409 group_exists 就地提示。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAdminGroupsStore } from '../../stores/adminGroups'
import { useAdminRolesStore } from '../../stores/adminRoles'
import { apiErrorCode, humanizeError, isVersionConflict } from '../../api/errors'
import RolePicker from '../../components/admin/RolePicker.vue'
import ScopePicker from '../../components/admin/ScopePicker.vue'
import MembersEditor from '../../components/admin/MembersEditor.vue'
import VersionConflictDialog from '../../components/admin/VersionConflictDialog.vue'
import DangerConfirmDialog from '../../components/admin/DangerConfirmDialog.vue'
import InfoNote from '../_shared/InfoNote.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import type { CreateGroupRequest, GroupView, UpdateGroupRequest } from '../../types/admin'

const props = defineProps<{ name: string }>()

const store = useAdminGroupsStore()
const roles = useAdminRolesStore()
const router = useRouter()

const isNew = computed(() => props.name === 'new')

// ── 本地草稿 ──
const draft = reactive<{ description: string; roles: string[] }>({ description: '', roles: [] })
const membersDraft = ref<string[]>([])
const newName = ref('')

const savingProfile = ref(false)
const savingMembers = ref(false)
const localError = ref<string | null>(null)
const localSuccess = ref<string | null>(null)
const conflictOpen = ref(false)
const conflictKind = ref<'profile' | 'members' | null>(null)
const deleteOpen = ref(false)
const groupInUse = ref(false)
const groupExists = ref(false)

/** 角色可选项（真实角色名）。 */
const roleOptions = computed<string[]>(() => roles.roleNames)

/** 有效 scopes 预测（⋃ 所选角色 scopes）——仅供判断，保存后以服务端为准。 */
const rolesByName = computed(() => new Map(roles.roles.map((r) => [r.name, r.scopes])))
const predictedScopes = computed<string[]>(() => {
  const set = new Set<string>()
  for (const rn of draft.roles) (rolesByName.value.get(rn) ?? []).forEach((s) => set.add(s))
  return [...set]
})

const memberCount = computed(() => (isNew.value ? 0 : store.selected?.memberCount ?? 0))
const profileDirty = computed(() => {
  const base = store.selected
  if (!base) return false
  const rolesChanged = draft.roles.length !== base.roles.length || draft.roles.some((r) => !base.roles.includes(r))
  return draft.description !== base.description || rolesChanged
})
const membersDirty = computed(() => {
  const base = store.members
  return membersDraft.value.length !== base.length || membersDraft.value.some((m) => !base.includes(m))
})

// ── 冲突弹窗字段随来源切换（profile: 说明/角色；members: 成员数） ──
const conflictFields = computed(() =>
  conflictKind.value === 'members'
    ? [{ key: 'memberCount', label: '成员数' }]
    : [
        { key: 'description', label: '说明' },
        { key: 'roles', label: '角色' },
      ],
)
const conflictDraft = computed<Record<string, unknown>>(() =>
  conflictKind.value === 'members'
    ? { memberCount: membersDraft.value.length }
    : { description: draft.description, roles: draft.roles },
)
const conflictCurrent = computed<Record<string, unknown>>(() =>
  conflictKind.value === 'members'
    ? { memberCount: store.conflictLatest?.memberCount }
    : { description: store.conflictLatest?.description, roles: store.conflictLatest?.roles },
)

function syncDraft(g: GroupView): void {
  draft.description = g.description
  draft.roles = [...g.roles]
}

watch(
  () => store.selected,
  (g) => {
    if (g && !isNew.value && g.name === props.name && !savingProfile.value) syncDraft(g)
  },
  { immediate: true },
)
watch(
  () => store.members,
  (m) => {
    if (!savingMembers.value) membersDraft.value = [...m]
  },
  { immediate: true },
)

onMounted(() => {
  if (roles.status === 'idle') void roles.load()
  if (!isNew.value) {
    void store.loadDetail(props.name)
    void store.loadMembers(props.name)
  }
})

function goList(): void {
  void router.push({ name: 'admin-groups' })
}

async function createGroupAction(): Promise<void> {
  localError.value = null
  localSuccess.value = null
  groupExists.value = false
  savingProfile.value = true
  try {
    const req: CreateGroupRequest = {
      name: newName.value.trim(),
      description: draft.description,
      roles: draft.roles,
    }
    await store.createGroupAction(req)
    goList()
  } catch (e) {
    if (apiErrorCode(e) === 'group_exists') groupExists.value = true
    else localError.value = humanizeError(e, undefined, { credentialMode: 'bearer' })
  } finally {
    savingProfile.value = false
  }
}

async function saveProfile(): Promise<void> {
  localError.value = null
  localSuccess.value = null
  const base = store.selected
  if (!base) return
  if (!profileDirty.value) {
    localSuccess.value = '说明与角色没有需要保存的改动。'
    return
  }
  savingProfile.value = true
  try {
    const req: UpdateGroupRequest = { description: draft.description, roles: draft.roles }
    await store.saveGroup(props.name, req, base.version)
    localSuccess.value = `用户组 ${props.name} 的说明与角色已保存。成员的权限在其下次刷新令牌后生效。`
  } catch (e) {
    if (isVersionConflict(e)) {
      conflictKind.value = 'profile'
      conflictOpen.value = true
    } else {
      localError.value = humanizeError(e, undefined, { credentialMode: 'bearer' })
    }
  } finally {
    savingProfile.value = false
  }
}

async function saveMembers(): Promise<void> {
  localError.value = null
  localSuccess.value = null
  const base = store.selected
  if (!base) return
  if (!membersDirty.value) {
    localSuccess.value = '成员没有需要保存的改动。'
    return
  }
  savingMembers.value = true
  try {
    await store.saveMembers(props.name, [...membersDraft.value], base.version)
    localSuccess.value = `用户组 ${props.name} 的成员已保存。相关成员的权限在其下次刷新令牌后生效。`
  } catch (e) {
    if (isVersionConflict(e)) {
      conflictKind.value = 'members'
      conflictOpen.value = true
    } else {
      localError.value = humanizeError(e, undefined, { credentialMode: 'bearer' })
    }
  } finally {
    savingMembers.value = false
  }
}

function onConflictReload(): void {
  // 采纳服务端最新为新基线：写回 selected → watch 自动 syncDraft。成员冲突再拉一次最新成员列表。
  store.acceptConflictLatest()
  if (conflictKind.value === 'members') void store.loadMembers(props.name)
  conflictOpen.value = false
  conflictKind.value = null
}
function onConflictClose(): void {
  conflictOpen.value = false
  conflictKind.value = null
  store.clearConflict()
}

async function confirmDelete(): Promise<void> {
  deleteOpen.value = false
  localError.value = null
  groupInUse.value = false
  const version = store.selected?.version ?? 0
  try {
    await store.deleteGroupAction(props.name, version) // 带 If-Match
    goList()
  } catch (e) {
    if (apiErrorCode(e) === 'group_in_use') groupInUse.value = true
    else localError.value = humanizeError(e, undefined, { credentialMode: 'bearer' })
  }
}
</script>

<template>
  <section class="ge">
    <button type="button" class="ge__back" @click="goList">‹ 返回用户组列表</button>

    <EmptyState
      v-if="!isNew && store.selectedStatus === 'loading'"
      variant="loading"
      title="加载用户组详情…"
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
      <header class="ge__head">
        <h2 class="ge__title">{{ isNew ? '新建用户组' : '编辑用户组' }}</h2>
        <p v-if="!isNew && store.selected" class="ge__ident">
          👪 {{ store.selected.name }} · v{{ store.selected.version }} · {{ memberCount }} 位成员
        </p>
      </header>

      <!-- 说明 + 角色（编辑态为独立写；新建态与成员合并入创建） -->
      <div class="ge__form">
        <div v-if="isNew" class="ge__field">
          <label class="ge__label" for="ge-name">组名</label>
          <input
            id="ge-name"
            v-model="newName"
            class="form-control"
            type="text"
            autocomplete="off"
            spellcheck="false"
          />
          <p class="ge__hint">创建后不可改名。</p>
        </div>

        <div class="ge__field">
          <label class="ge__label" for="ge-desc">说明</label>
          <input id="ge-desc" v-model="draft.description" class="form-control" type="text" maxlength="200" />
        </div>

        <div class="ge__field">
          <span class="ge__label">组角色（成员经此组继承）</span>
          <RolePicker v-model="draft.roles" :options="roleOptions" />
        </div>

        <div class="ge__field">
          <span class="ge__label">有效 scopes 预览（预测）</span>
          <ScopePicker :model-value="predictedScopes" readonly />
          <p class="ge__hint">= ⋃ 所选组角色的 scopes；下次登录 / 刷新后由服务端最终确定。</p>
        </div>

        <InfoNote v-if="groupExists" tone="danger">该组名已存在，请换一个名称。</InfoNote>

        <!-- 编辑态：说明 + 角色 的独立保存 -->
        <div v-if="!isNew" class="ge__row-actions">
          <button
            type="button"
            class="btn btn--primary"
            :class="{ 'btn--loading': savingProfile }"
            :disabled="savingProfile || !profileDirty"
            @click="saveProfile"
          >
            保存说明与角色
          </button>
        </div>
      </div>

      <!-- 成员（仅编辑态；成员端点在组存在后可用） -->
      <div v-if="!isNew" class="ge__form ge__form--members">
        <div class="ge__field">
          <span class="ge__label">组成员（{{ membersDraft.length }} 人）</span>
          <EmptyState
            v-if="store.membersStatus === 'loading'"
            variant="loading"
            title="加载成员…"
          />
          <template v-else>
            <MembersEditor v-model="membersDraft" />
            <InfoNote v-if="store.membersStatus === 'error'" tone="danger">
              {{ store.membersError ?? '成员加载失败。' }}
            </InfoNote>
          </template>
        </div>
        <div class="ge__row-actions">
          <button
            type="button"
            class="btn btn--primary"
            :class="{ 'btn--loading': savingMembers }"
            :disabled="savingMembers || !membersDirty || store.membersStatus === 'loading'"
            @click="saveMembers"
          >
            保存成员
          </button>
        </div>
      </div>

      <InfoNote v-if="isNew" tone="info">创建成功后进入编辑页即可管理组成员。</InfoNote>

      <InfoNote v-if="groupInUse" tone="danger">
        该用户组仍有成员，无法删除。请先在上方成员区清空成员并保存后再删除。
      </InfoNote>
      <InfoNote v-if="localError" tone="danger">{{ localError }}</InfoNote>
      <InfoNote v-if="localSuccess" tone="success">{{ localSuccess }}</InfoNote>

      <!-- 底部操作：新建=创建；编辑=删除 -->
      <div class="ge__actions">
        <button type="button" class="btn" @click="goList">取消</button>
        <button
          v-if="isNew"
          type="button"
          class="btn btn--primary"
          :class="{ 'btn--loading': savingProfile }"
          :disabled="savingProfile"
          @click="createGroupAction"
        >
          创建用户组
        </button>
        <button v-else type="button" class="btn btn--danger ge__del" @click="deleteOpen = true">
          删除用户组
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
      :open="deleteOpen"
      title="删除用户组"
      :message="`此操作不可撤销，将永久删除用户组 ${props.name}。若仍有成员则无法删除。`"
      confirm-label="确认删除"
      :require-text="props.name"
      @confirm="confirmDelete"
      @cancel="deleteOpen = false"
    />
  </section>
</template>

<style scoped>
.ge {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  max-width: 720px;
}
.ge__back {
  align-self: flex-start;
  background: none;
  border: none;
  color: var(--text-muted);
  font-size: var(--fs-sm);
  cursor: pointer;
  padding: 0;
}
.ge__back:hover {
  color: var(--primary);
}
.ge__head {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}
.ge__title {
  font-size: var(--fs-lg);
  font-weight: var(--fw-bold);
}
.ge__ident {
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.ge__form {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.ge__form--members {
  padding-top: var(--space-4);
  border-top: 1px solid var(--border);
}
.ge__field {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.ge__label {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
}
.ge__hint {
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.ge__row-actions {
  display: flex;
  gap: var(--space-2);
}
.ge__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.ge__del {
  margin-left: auto;
}
</style>
