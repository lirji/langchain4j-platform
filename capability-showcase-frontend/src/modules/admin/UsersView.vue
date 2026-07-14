<script setup lang="ts">
/**
 * 用户列表 /admin/users。
 *
 * 分页：委托 store（usePagedQuery）的 offset/limit + X-Total-Count（prevPage/nextPage）。
 * 筛选：**后端 listUsers 当前仅认 offset/limit、无服务端筛选**，故这里对**当前页做客户端文本窄化**
 *   （username/tenant/role/enabled）。翻页后筛选作用于新一页——已在页脚注明，避免误解为全量搜索。
 * 行内不做启停（乐观写在 UserEditor 里闭环）；此处 enabled 只读展示。行点击进 admin-user 详情/编辑。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAdminUsersStore } from '../../stores/adminUsers'
import EmptyState from '../../components/common/EmptyState.vue'
import StatCard from '../../components/common/StatCard.vue'
import CopyButton from '../../components/common/CopyButton.vue'
import InfoNote from '../_shared/InfoNote.vue'
import type { UserAdminView } from '../../types/admin'

const store = useAdminUsersStore()
const router = useRouter()
const route = useRoute()

const q = ref('')
const enabledFilter = ref<'all' | 'enabled' | 'disabled'>('all')
/** 精确角色筛选（来自 RoleEditor 的 ?role=<name> 深链），区别于模糊文本 q。 */
const roleFilter = ref('')

function applyRoleQuery(): void {
  const r = route.query.role
  roleFilter.value = typeof r === 'string' ? r.trim() : ''
}
onMounted(() => {
  if (store.status === 'idle') void store.load()
  applyRoleQuery()
})
// 深链 ?role= 变化时同步（不止 mounted 一次）。
watch(() => route.query.role, applyRoleQuery)

/** 当前页客户端窄化：精确角色匹配 + username/tenant/角色 模糊命中，且启用状态匹配。 */
const visibleRows = computed<UserAdminView[]>(() => {
  const kw = q.value.trim().toLowerCase()
  const role = roleFilter.value
  return store.items.filter((u) => {
    if (enabledFilter.value === 'enabled' && !u.enabled) return false
    if (enabledFilter.value === 'disabled' && u.enabled) return false
    if (role && !u.roles.includes(role)) return false // 精确角色匹配，不误命中 devops/用户名含 ops 等
    if (!kw) return true
    return (
      u.username.toLowerCase().includes(kw) ||
      u.tenant.toLowerCase().includes(kw) ||
      u.roles.some((r) => r.toLowerCase().includes(kw))
    )
  })
})

const hasFilter = computed(
  () => q.value.trim().length > 0 || enabledFilter.value !== 'all' || roleFilter.value !== '',
)

function openUser(username: string): void {
  void router.push({ name: 'admin-user', params: { username } })
}
function newUser(): void {
  void router.push({ name: 'admin-user', params: { username: 'new' } })
}
function clearRoleFilter(): void {
  roleFilter.value = ''
  // 一并从 URL 去掉 role，避免 watch/重入再次套用。
  const { role, ...rest } = route.query
  void role
  void router.replace({ query: rest })
}
function clearFilter(): void {
  q.value = ''
  enabledFilter.value = 'all'
  clearRoleFilter()
}
function rolesSummary(u: UserAdminView): string {
  return u.roles.length ? u.roles.join('、') : '（无角色）'
}
function groupsSummary(u: UserAdminView): string {
  return u.groups.length ? u.groups.join('、') : '（无用户组）'
}
</script>

<template>
  <section class="uv">
    <div class="uv__stats">
      <StatCard label="用户总数" :value="store.total" tone="primary" />
    </div>

    <!-- 筛选栏 -->
    <div class="uv__filters" role="group" aria-label="用户筛选">
      <input
        v-model="q"
        class="form-control uv__search"
        type="search"
        placeholder="🔍 用户名 / 租户 / 角色（当前页）"
        aria-label="按用户名 / 租户 / 角色筛选当前页"
      />
      <div class="uv__seg" role="group" aria-label="按启用状态筛选">
        <button
          v-for="opt in (['all', 'enabled', 'disabled'] as const)"
          :key="opt"
          type="button"
          class="uv__seg-btn"
          :class="{ 'is-active': enabledFilter === opt }"
          :aria-pressed="enabledFilter === opt"
          @click="enabledFilter = opt"
        >
          {{ opt === 'all' ? '全部' : opt === 'enabled' ? '启用' : '禁用' }}
        </button>
      </div>
      <button type="button" class="btn btn--primary uv__new" @click="newUser">＋ 新建用户</button>
    </div>

    <!-- 精确角色筛选（来自 ?role= 深链） -->
    <div v-if="roleFilter" class="uv__rolechip" role="status" aria-live="polite">
      <span>按角色精确筛选：<strong>{{ roleFilter }}</strong>（仅当前页）</span>
      <button type="button" class="uv__link" @click="clearRoleFilter">清除 ×</button>
    </div>

    <!-- 状态：加载 / 错误 / 全空 -->
    <EmptyState
      v-if="store.status === 'loading' && !store.items.length"
      variant="loading"
      title="加载用户…"
    />
    <EmptyState
      v-else-if="store.status === 'error'"
      variant="error"
      title="加载失败"
      :description="store.error ?? '请稍后重试。'"
      action-label="重试"
      @action="store.reload()"
    />
    <EmptyState
      v-else-if="!store.items.length"
      icon="👥"
      title="暂无用户"
      description="创建第一个用户以开始管理。"
      action-label="＋ 新建用户"
      @action="newUser"
    />

    <!-- 列表 -->
    <template v-else>
      <div class="uv__table-wrap" role="region" aria-label="用户列表" tabindex="0">
        <table class="uv__table">
          <thead>
            <tr>
              <th scope="col">用户名</th>
              <th scope="col">租户</th>
              <th scope="col">角色</th>
              <th scope="col">用户组</th>
              <th scope="col">有效权限</th>
              <th scope="col">状态</th>
              <th scope="col">版本</th>
              <th scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="u in visibleRows"
              :key="u.userId"
              class="uv__row"
              tabindex="0"
              @click="openUser(u.username)"
              @keydown.enter="openUser(u.username)"
            >
              <td class="uv__cell uv__cell--name">{{ u.username }}</td>
              <td class="uv__cell">{{ u.tenant }}</td>
              <td class="uv__cell" :title="rolesSummary(u)">
                <span v-if="!u.roles.length" class="uv__muted">（无角色）</span>
                <template v-else>
                  <span v-for="r in u.roles.slice(0, 2)" :key="r" class="uv__role">{{ r }}</span>
                  <span v-if="u.roles.length > 2" class="uv__role uv__role--more">+{{ u.roles.length - 2 }}</span>
                </template>
              </td>
              <td class="uv__cell" :title="groupsSummary(u)">
                <span v-if="!u.groups.length" class="uv__muted">—</span>
                <template v-else>
                  <span v-for="g in u.groups.slice(0, 2)" :key="g" class="uv__group">{{ g }}</span>
                  <span v-if="u.groups.length > 2" class="uv__group uv__group--more">+{{ u.groups.length - 2 }}</span>
                </template>
              </td>
              <td class="uv__cell" :title="u.effectiveScopes.join('、')">{{ u.effectiveScopes.length }} 项</td>
              <td class="uv__cell">
                <span class="uv__state" :data-on="u.enabled">
                  {{ u.enabled ? '● 启用' : '○ 禁用' }}
                </span>
              </td>
              <td class="uv__cell uv__cell--ver">v{{ u.version }}</td>
              <td class="uv__cell uv__cell--actions" @click.stop>
                <button type="button" class="btn btn--sm btn--ghost" @click="openUser(u.username)">编辑</button>
                <CopyButton :text="u.userId" label="复制 ID" compact />
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <p v-if="hasFilter && !visibleRows.length" class="uv__no-match" role="status" aria-live="polite">
        当前页没有匹配的用户。<button type="button" class="uv__link" @click="clearFilter">清空筛选</button>
      </p>

      <InfoNote v-if="hasFilter" tone="neutral">
        筛选仅作用于<strong>当前页</strong>（后端按 offset/limit 分页，暂无服务端搜索）。翻页后需重新查看。
      </InfoNote>

      <!-- 分页 -->
      <div class="uv__pager">
        <span class="uv__pager-info">共 {{ store.total }} 个用户</span>
        <div class="uv__pager-ctrl">
          <button type="button" class="btn btn--sm" :disabled="!store.hasPrev" @click="store.prevPage()">
            ‹ 上一页
          </button>
          <button type="button" class="btn btn--sm" :disabled="!store.hasNext" @click="store.nextPage()">
            下一页 ›
          </button>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.uv {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.uv__stats {
  display: flex;
  gap: var(--space-3);
}
.uv__filters {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.uv__search {
  flex: 1;
  min-width: 220px;
}
.uv__seg {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}
.uv__seg-btn {
  padding: 0 12px;
  height: var(--control-h-sm);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--text-subtle);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
}
.uv__seg-btn.is-active {
  color: var(--primary);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}
.uv__new {
  margin-left: auto;
}
.uv__rolechip {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 6px 12px;
  font-size: var(--fs-sm);
  color: var(--primary);
  background: var(--primary-soft);
  border: 1px solid var(--primary-border);
  border-radius: var(--radius);
}
.uv__table-wrap {
  width: 100%;
  overflow-x: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
}
.uv__table-wrap:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--primary-border);
}
.uv__table {
  border-collapse: collapse;
  width: 100%;
  font-size: var(--fs-sm);
}
.uv__table th[scope='col'] {
  position: sticky;
  top: 0;
  z-index: 1;
  text-align: left;
  padding: var(--row-py) var(--space-3);
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
  background: var(--surface-2);
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}
.uv__row {
  cursor: pointer;
}
.uv__row:hover .uv__cell {
  background: var(--surface-2);
}
.uv__row:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: -2px;
}
.uv__cell {
  padding: var(--row-py) var(--space-3);
  color: var(--text);
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}
.uv__cell--name {
  font-weight: var(--fw-semibold);
}
.uv__cell--ver {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.uv__cell--actions {
  display: flex;
  gap: var(--space-2);
  align-items: center;
}
.uv__muted {
  color: var(--text-subtle);
}
.uv__role {
  display: inline-block;
  margin-right: 4px;
  padding: 1px 8px;
  font-size: var(--fs-xs);
  color: var(--primary);
  background: var(--primary-soft);
  border: 1px solid var(--primary-border);
  border-radius: var(--radius-pill);
}
.uv__role--more {
  color: var(--text-muted);
  background: var(--surface-3);
  border-color: var(--border);
}
.uv__group {
  display: inline-block;
  margin-right: 4px;
  padding: 1px 8px;
  font-size: var(--fs-xs);
  color: var(--stream);
  background: var(--stream-soft);
  border: 1px solid var(--stream-border);
  border-radius: var(--radius-pill);
}
.uv__group--more {
  color: var(--text-muted);
  background: var(--surface-3);
  border-color: var(--border);
}
.uv__state {
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--text-subtle);
}
.uv__state[data-on='true'] {
  color: var(--success);
}
.uv__no-match {
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.uv__link {
  background: none;
  border: none;
  color: var(--primary);
  font-size: var(--fs-sm);
  cursor: pointer;
  padding: 0;
}
.uv__pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
}
.uv__pager-info {
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.uv__pager-ctrl {
  display: flex;
  gap: var(--space-2);
}
</style>
