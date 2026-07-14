<script setup lang="ts">
import { computed, reactive } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useCatalogStore } from '../../stores/catalog'
import { useUiStore } from '../../stores/ui'
import { useFavoritesStore } from '../../stores/favorites'
import { usePermission } from '../../composables/usePermission'
import { buildNavigationModel, type NavModuleVM } from '../../navigation/navigationModel'
import { STATE_META, STATE_ORDER } from '../../config/stateMeta'
import NavGroupSection from './navigation/NavGroupSection.vue'
import NavCapabilityRow from './navigation/NavCapabilityRow.vue'
import NavEmptyState from './navigation/NavEmptyState.vue'
import DensityToggle from './DensityToggle.vue'

/**
 * 侧栏编排容器：只负责连接 store/route、搜索框、折叠/展开状态、焦点与子组件装配。
 * 纯导航建模在 navigationModel（可单测）；三级渲染在 NavGroupSection/NavModuleRow/NavCapabilityRow。
 * 唯一主 active：总览用 route.name、管理用路径前缀、模块/能力用 params —— 互不误亮。
 */
const catalog = useCatalogStore()
const ui = useUiStore()
const favorites = useFavoritesStore()
const route = useRoute()
const { canAdmin } = usePermission()
const { modules } = storeToRefs(catalog)

const FAV_GROUP_ID = '__fav'
const GROUP_STATE_KEY = 'showcase.navGroups'

// ── 当前路由 → 唯一 active 判定（总览/管理不再借 !moduleId 误亮）──
const activeModuleId = computed(() => String(route.params.moduleId ?? ''))
const activeCapId = computed(() => String(route.params.capId ?? ''))
const isOverview = computed(() => route.name === 'overview')
const isAdminUsers = computed(() => route.path.startsWith('/admin/users'))
const isAdminRoles = computed(() => route.path.startsWith('/admin/roles'))

const model = computed(() =>
  buildNavigationModel({
    modules: modules.value,
    favoriteIds: favorites.ids,
    query: ui.filter,
    activeModuleId: activeModuleId.value,
    activeCapId: activeCapId.value,
  }),
)
const hasQuery = computed(() => model.value.hasQuery)

// ── 分组折叠态（持久化，严格校验：只接受 plain object 的 boolean 值）──
function readGroupState(): Record<string, boolean> {
  try {
    const raw = localStorage.getItem(GROUP_STATE_KEY)
    if (!raw) return {}
    const p: unknown = JSON.parse(raw)
    if (!p || typeof p !== 'object' || Array.isArray(p)) return {}
    const out: Record<string, boolean> = {}
    for (const [k, v] of Object.entries(p as Record<string, unknown>)) {
      if (typeof v === 'boolean') out[k] = v
    }
    return out
  } catch {
    return {}
  }
}
const groupCollapsed = reactive<Record<string, boolean>>(readGroupState())
function persistGroupState(): void {
  try {
    localStorage.setItem(GROUP_STATE_KEY, JSON.stringify(groupCollapsed))
  } catch {
    /* 忽略 */
  }
}
function isGroupOpen(id: string): boolean {
  if (hasQuery.value) return true // 搜索命中自动展开所有组
  return !groupCollapsed[id]
}
function toggleGroup(id: string): void {
  groupCollapsed[id] = !groupCollapsed[id]
  persistGroupState()
}

// ── 模块展开态（内存态；当前模块强制展开但不写回偏好）──
const manualExpand = reactive<Record<string, boolean>>({})
function isModuleOpen(m: NavModuleVM): boolean {
  if (hasQuery.value) return true
  if (m.id in manualExpand) return manualExpand[m.id]
  return m.ancestorActive
}
const openModuleIds = computed<string[]>(() => {
  const ids: string[] = []
  for (const g of model.value.groups) for (const m of g.modules) if (isModuleOpen(m)) ids.push(m.id)
  return ids
})
function onToggleModule(id: string): void {
  manualExpand[id] = !openModuleIds.value.includes(id)
}

function onNavigate(): void {
  ui.closeSidebar()
}
function clearFilter(): void {
  ui.filter = ''
}
</script>

<template>
  <nav class="nav" aria-label="能力导航">
    <div class="nav__scroll">
      <div class="nav__search">
        <svg class="nav__search-ico" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="11" cy="11" r="7" /><path d="M20 20l-3.4-3.4" /></svg>
        <input
          id="sidenav-filter"
          v-model="ui.filter"
          type="search"
          class="nav__search-input"
          placeholder="筛选能力…"
          aria-label="筛选能力"
        />
        <kbd class="nav__search-kbd" title="按 / 聚焦筛选">/</kbd>
      </div>

      <!-- 总览 -->
      <RouterLink
        to="/"
        class="nav__overview"
        :class="{ active: isOverview }"
        :aria-current="isOverview ? 'page' : undefined"
        @click="onNavigate"
      >
        <span class="nav__overview-chip" aria-hidden="true">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="4" width="7" height="7" rx="1.5" /><rect x="13" y="4" width="7" height="7" rx="1.5" /><rect x="4" y="13" width="7" height="7" rx="1.5" /><rect x="13" y="13" width="7" height="7" rx="1.5" /></svg>
        </span>
        总览 Overview
      </RouterLink>

      <!-- 平台管理：仅 role-admin（canAdmin）出现，静态（不折叠），slate 强调 -->
      <section v-if="canAdmin" class="nav__admin" data-accent="slate">
        <div class="nav__admin-head">
          <span class="nav__grp-bar" aria-hidden="true" />
          <span class="nav__grp-label">平台管理</span>
          <span class="nav__admin-tag">仅管理员</span>
        </div>
        <ul class="nav__admin-list">
          <li>
            <RouterLink :to="{ name: 'admin-users' }" class="nav__admin-link" :class="{ active: isAdminUsers }" :aria-current="isAdminUsers ? 'page' : undefined" @click="onNavigate">
              <span class="nav__admin-chip" aria-hidden="true">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="9" cy="8" r="3" /><path d="M3.5 19.5c0-3 2.5-5 5.5-5s5.5 2 5.5 5" /><path d="M16 5.5a3 3 0 0 1 0 6" /><path d="M17.5 14.6c2.2.4 3.9 2.1 3.9 4.9" /></svg>
              </span>
              <span class="nav__admin-name">用户管理</span>
            </RouterLink>
          </li>
          <li>
            <RouterLink :to="{ name: 'admin-roles' }" class="nav__admin-link" :class="{ active: isAdminRoles }" :aria-current="isAdminRoles ? 'page' : undefined" @click="onNavigate">
              <span class="nav__admin-chip" aria-hidden="true">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3.5l7 2.6v5.4c0 4.2-2.9 7-7 8.5-4.1-1.5-7-4.3-7-8.5V6.1z" /><path d="M9.3 12l1.9 1.9 3.5-3.7" /></svg>
              </span>
              <span class="nav__admin-name">角色管理</span>
            </RouterLink>
          </li>
        </ul>
      </section>

      <!-- 收藏虚拟分组：gold 强调，扁平能力行 -->
      <section v-if="model.favorites.length" class="nav__fav" data-accent="gold">
        <button
          type="button"
          class="nav__fav-head"
          :aria-expanded="isGroupOpen(FAV_GROUP_ID)"
          @click="toggleGroup(FAV_GROUP_ID)"
        >
          <span class="nav__fav-star" aria-hidden="true">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor" stroke="none"><path d="M12 3.6l2.6 5.2 5.8.9-4.2 4.1 1 5.7-5.2-2.7-5.2 2.7 1-5.7-4.2-4.1 5.8-.9z" /></svg>
          </span>
          <span class="nav__grp-label">收藏</span>
          <span class="nav__grp-count">{{ model.favorites.length }}</span>
        </button>
        <ul v-if="isGroupOpen(FAV_GROUP_ID)" class="nav__fav-list">
          <li v-for="c in model.favorites" :key="c.id">
            <NavCapabilityRow :cap="c" flat @navigate="onNavigate" />
          </li>
        </ul>
      </section>

      <!-- 搜索零结果 -->
      <NavEmptyState v-if="model.isEmpty" :query="model.query" @clear="clearFilter" />

      <!-- 语义分组 -->
      <NavGroupSection
        v-for="g in model.groups"
        :key="g.id"
        :group="g"
        :open="isGroupOpen(g.id)"
        :open-module-ids="openModuleIds"
        @toggle-group="toggleGroup(g.id)"
        @toggle-module="onToggleModule"
        @navigate="onNavigate"
      />
    </div>

    <div class="nav__footer">
      <div class="nav__legend" aria-label="状态图例">
        <span v-for="s in STATE_ORDER" :key="s" class="nav__lg">
          <i class="nav__lg-dot" :data-state="s" aria-hidden="true" />{{ STATE_META[s].label }}
        </span>
      </div>
      <div class="nav__footer-bar">
        <DensityToggle />
        <span class="nav__version" :title="`目录版本 ${catalog.catalog?.version ?? '未知'}`">
          v{{ catalog.catalog?.version ?? '—' }}
        </span>
      </div>
    </div>
  </nav>
</template>

<style scoped>
.nav {
  display: flex;
  flex-direction: column;
  min-height: 100%;
  font-size: var(--nav-fs);
  background: var(--glass-bg-strong);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.4);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.4);
}
.nav__scroll {
  flex: 1 1 auto;
  padding: var(--space-3);
}

/* 搜索 */
.nav__search {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: var(--space-3);
  padding: 0 10px;
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  transition: border-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease);
}
.nav__search:focus-within {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px var(--primary-border);
  background: var(--surface);
}
.nav__search-ico {
  flex: 0 0 auto;
  color: var(--text-subtle);
}
.nav__search-input {
  flex: 1;
  min-width: 0;
  height: 34px;
  color: var(--text);
  background: transparent;
  border: none;
  outline: none;
  font-size: var(--nav-fs);
}
.nav__search-input::placeholder {
  color: var(--text-subtle);
}
.nav__search-kbd {
  flex: 0 0 auto;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-subtle);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 0 5px;
  line-height: 16px;
}

/* 总览 */
.nav__overview {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  margin-bottom: var(--space-2);
  color: var(--text);
  border-radius: var(--radius);
  font-size: var(--nav-fs);
  font-weight: var(--fw-semibold);
  text-decoration: none;
}
.nav__overview-chip {
  width: var(--nav-chip);
  height: var(--nav-chip);
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: var(--surface-2);
  color: var(--text-subtle);
}
.nav__overview:hover {
  background: var(--surface-2);
  text-decoration: none;
}
.nav__overview.active {
  background: var(--primary-soft);
  color: var(--primary);
}
.nav__overview.active .nav__overview-chip {
  background: var(--surface);
  color: var(--primary);
  box-shadow: inset 0 0 0 1px var(--primary-border);
}

/* 分组通用小件（组头色条/标签/计数），复用于 admin/fav 与 NavGroupSection 保持一致视觉 */
.nav__grp-bar {
  width: 3px;
  height: 13px;
  flex: 0 0 auto;
  border-radius: var(--radius-pill);
  background: var(--g);
}
.nav__grp-label {
  flex: 1;
  min-width: 0;
  font-size: var(--nav-fs);
  font-weight: var(--fw-bold);
  letter-spacing: 0.02em;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.nav__grp-count {
  flex: 0 0 auto;
  font-size: var(--nav-fs);
  color: var(--text-subtle);
  font-variant-numeric: tabular-nums;
}

/* 平台管理 */
.nav__admin {
  margin-top: var(--space-3);
}
.nav__admin-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px 4px;
}
.nav__admin-tag {
  flex: 0 0 auto;
  font-size: 10px;
  font-weight: var(--fw-semibold);
  color: var(--g-text);
  background: var(--g-soft);
  padding: 1px 7px;
  border-radius: var(--radius-pill);
}
.nav__admin-list {
  list-style: none;
  margin: 4px 0 0;
  padding: 0;
}
.nav__admin-link {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  color: var(--text);
  border-radius: var(--radius);
  text-decoration: none;
  margin-bottom: 2px;
}
.nav__admin-link:hover {
  background: var(--surface-2);
  text-decoration: none;
}
.nav__admin-chip {
  width: var(--nav-chip);
  height: var(--nav-chip);
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: var(--g-soft);
  color: var(--g);
}
.nav__admin-name {
  flex: 1;
  min-width: 0;
  font-size: var(--nav-fs);
  font-weight: var(--fw-semibold);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.nav__admin-link.active {
  background: var(--g-soft);
}
.nav__admin-link.active .nav__admin-name {
  color: var(--g-text);
}
.nav__admin-link.active .nav__admin-chip {
  background: var(--surface);
  box-shadow: inset 0 0 0 1px var(--g-line);
}

/* 收藏 */
.nav__fav {
  margin-top: var(--space-3);
}
.nav__fav-head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 8px 4px;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  text-align: left;
  cursor: pointer;
}
.nav__fav-head:hover {
  background: var(--surface-2);
}
.nav__fav-star {
  display: inline-flex;
  color: var(--g);
}
.nav__fav-list {
  list-style: none;
  margin: 4px 0 0;
  padding: 0;
}

/* 底部固定条 */
.nav__footer {
  position: sticky;
  bottom: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: var(--glass-bg-strong);
  border-top: 1px solid var(--glass-border);
}
.nav__legend {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
}
.nav__lg {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 10px;
  color: var(--text-subtle);
}
.nav__lg-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--neutral);
}
.nav__lg-dot[data-state='ready'] {
  background: var(--success);
}
.nav__lg-dot[data-state='ready-degraded'] {
  background: var(--success);
  box-shadow: 0 0 0 1.4px var(--surface), 0 0 0 2.6px var(--warning);
}
.nav__lg-dot[data-state='flag-off'] {
  background: transparent;
  box-shadow: inset 0 0 0 1.5px var(--neutral);
}
.nav__lg-dot[data-state='scope-required'] {
  width: 7px;
  height: 7px;
  border-radius: 2px;
  transform: rotate(45deg);
  background: var(--warning);
}
.nav__lg-dot[data-state='display-only'] {
  width: 7px;
  height: 7px;
  border-radius: 2px;
  background: var(--danger);
}
.nav__footer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}
.nav__version {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
</style>
