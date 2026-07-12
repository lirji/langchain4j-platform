<script setup lang="ts">
import { computed, reactive } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useCatalogStore } from '../../stores/catalog'
import { useUiStore } from '../../stores/ui'
import { useFavoritesStore } from '../../stores/favorites'
import type { Capability, CapabilityState, Module } from '../../types/catalog'
import { GROUP_ORDER, groupIdForModule } from '../../config/moduleGroups'
import MethodBadge from '../capability/badges/MethodBadge.vue'
import DensityToggle from './DensityToggle.vue'

const catalog = useCatalogStore()
const ui = useUiStore()
const favorites = useFavoritesStore()
const route = useRoute()
const { modules, catalog: cat } = storeToRefs(catalog)

const manualExpand = reactive<Record<string, boolean>>({})

// 分组折叠态（持久化，非敏感）。true = 收起。
const GROUP_STATE_KEY = 'showcase.navGroups'
const FAV_GROUP_ID = '__fav'
function readGroupState(): Record<string, boolean> {
  try {
    const raw = localStorage.getItem(GROUP_STATE_KEY)
    if (raw) {
      const p: unknown = JSON.parse(raw)
      if (p && typeof p === 'object') return p as Record<string, boolean>
    }
  } catch {
    /* 忽略 */
  }
  return {}
}
const groupCollapsed = reactive<Record<string, boolean>>(readGroupState())
function persistGroupState(): void {
  try {
    localStorage.setItem(GROUP_STATE_KEY, JSON.stringify(groupCollapsed))
  } catch {
    /* 忽略 */
  }
}

const query = computed(() => ui.filter.trim().toLowerCase())
const activeModuleId = computed(() => String(route.params.moduleId ?? ''))
const activeCapId = computed(() => String(route.params.capId ?? ''))

function capMatches(c: Capability): boolean {
  const q = query.value
  if (!q) return true
  return (
    c.title.toLowerCase().includes(q) ||
    c.id.toLowerCase().includes(q) ||
    c.path.toLowerCase().includes(q) ||
    c.description.toLowerCase().includes(q) ||
    (c.tags ?? []).some((t) => t.toLowerCase().includes(q))
  )
}

interface NavModule {
  module: Module
  caps: Capability[]
  hasCaps: boolean
}

const navModules = computed<NavModule[]>(() => {
  const q = query.value
  const out: NavModule[] = []
  for (const m of modules.value) {
    const all = m.capabilities ?? []
    const caps = q ? all.filter(capMatches) : all
    const moduleMatch = q ? m.title.toLowerCase().includes(q) || m.id.toLowerCase().includes(q) : true
    if (q && caps.length === 0 && !moduleMatch) continue
    out.push({ module: m, caps: q ? caps : all, hasCaps: all.length > 0 })
  }
  return out
})

interface NavGroup {
  id: string
  label: string
  modules: NavModule[]
}

const navGroups = computed<NavGroup[]>(() => {
  const byGroup = new Map<string, NavModule[]>()
  for (const nm of navModules.value) {
    const gid = groupIdForModule(nm.module.id)
    const arr = byGroup.get(gid) ?? []
    arr.push(nm)
    byGroup.set(gid, arr)
  }
  const out: NavGroup[] = []
  for (const g of GROUP_ORDER) {
    const mods = byGroup.get(g.id)
    if (mods && mods.length) out.push({ id: g.id, label: g.label, modules: mods })
  }
  return out
})

// 收藏虚拟分组：受当前筛选影响。
const favCaps = computed<Capability[]>(() => {
  const out: Capability[] = []
  for (const id of favorites.ids) {
    const c = catalog.capabilityById(id)
    if (c && capMatches(c)) out.push(c)
  }
  return out
})

function isGroupOpen(id: string): boolean {
  if (query.value) return true // 搜索命中时自动展开所有组
  return !groupCollapsed[id]
}
function toggleGroup(id: string): void {
  const currentlyOpen = !groupCollapsed[id]
  groupCollapsed[id] = currentlyOpen
  persistGroupState()
}

function isModuleOpen(m: Module): boolean {
  if (query.value) return true
  if (m.id in manualExpand) return manualExpand[m.id]
  return activeModuleId.value === m.id
}
function toggleModule(m: Module): void {
  manualExpand[m.id] = !isModuleOpen(m)
}

function onNavigate(): void {
  ui.closeSidebar()
}

const stateDotTone: Record<CapabilityState, string> = {
  ready: 'ok',
  'ready-degraded': 'ok-warn',
  'flag-off': 'off',
  'scope-required': 'warn',
  'display-only': 'danger',
}

/** 模块图标：让侧栏一眼可辨（更直观）。未知模块用通用积木块。 */
const MODULE_ICON: Record<string, string> = {
  chat: '💬',
  rag: '📚',
  agent: '🤖',
  tasks: '⏳',
  workflow: '🔀',
  analytics: '📊',
  multimodal: '🎨',
  'interop-eval': '🔌',
  channel: '📡',
  voice: '🎙️',
  vision: '🖼️',
  knowledge: '📚',
  async: '⏳',
  eval: '🧪',
  interop: '🔌',
}
function moduleIcon(id: string): string {
  return MODULE_ICON[id] ?? '🧩'
}
</script>

<template>
  <nav class="nav" aria-label="能力导航">
    <div class="nav__scroll">
      <div class="nav__search">
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

      <RouterLink
        to="/"
        class="nav__overview"
        :class="{ active: !activeModuleId }"
        @click="onNavigate"
      >
        <span class="nav__overview-ico" aria-hidden="true">🏠</span> 总览 Overview
      </RouterLink>

      <!-- 收藏虚拟分组 -->
      <section v-if="favCaps.length" class="nav__group">
        <button
          type="button"
          class="nav__group-head"
          :aria-expanded="isGroupOpen(FAV_GROUP_ID)"
          @click="toggleGroup(FAV_GROUP_ID)"
        >
          <span class="nav__group-chevron" aria-hidden="true">
            {{ isGroupOpen(FAV_GROUP_ID) ? '▾' : '▸' }}
          </span>
          <span class="nav__group-label eyebrow">★ 收藏</span>
          <span class="nav__group-count">{{ favCaps.length }}</span>
        </button>
        <ul v-if="isGroupOpen(FAV_GROUP_ID)" class="nav__caps nav__caps--flat">
          <li v-for="c in favCaps" :key="c.id">
            <RouterLink
              :to="`/m/${c.module}/${c.id}`"
              class="nav__cap"
              :class="{ active: activeCapId === c.id }"
              @click="onNavigate"
            >
              <MethodBadge :method="c.method" />
              <span class="nav__cap-title">{{ c.title }}</span>
              <span
                class="nav__dot"
                :data-tone="stateDotTone[c.state]"
                :title="c.state"
                aria-hidden="true"
              />
            </RouterLink>
          </li>
        </ul>
      </section>

      <!-- 模块分组 -->
      <section v-for="g in navGroups" :key="g.id" class="nav__group">
        <button
          type="button"
          class="nav__group-head"
          :aria-expanded="isGroupOpen(g.id)"
          @click="toggleGroup(g.id)"
        >
          <span class="nav__group-chevron" aria-hidden="true">
            {{ isGroupOpen(g.id) ? '▾' : '▸' }}
          </span>
          <span class="nav__group-label eyebrow">{{ g.label }}</span>
        </button>

        <ul v-if="isGroupOpen(g.id)" class="nav__modules">
          <li v-for="nm in g.modules" :key="nm.module.id" class="nav__module">
            <div class="nav__module-head" :class="{ active: activeModuleId === nm.module.id }">
              <RouterLink :to="`/m/${nm.module.id}`" class="nav__module-link" @click="onNavigate">
                <span class="nav__module-ico" aria-hidden="true">{{ moduleIcon(nm.module.id) }}</span>
                <span class="nav__module-title">{{ nm.module.title }}</span>
                <span class="nav__module-count">{{ (nm.module.capabilities ?? []).length }}</span>
              </RouterLink>
              <button
                v-if="nm.hasCaps"
                type="button"
                class="nav__chevron"
                :aria-expanded="isModuleOpen(nm.module)"
                :aria-label="`展开/收起 ${nm.module.title}`"
                @click="toggleModule(nm.module)"
              >
                {{ isModuleOpen(nm.module) ? '▾' : '▸' }}
              </button>
            </div>

            <ul v-if="isModuleOpen(nm.module) && nm.caps.length" class="nav__caps">
              <li v-for="c in nm.caps" :key="c.id">
                <RouterLink
                  :to="`/m/${nm.module.id}/${c.id}`"
                  class="nav__cap"
                  :class="{ active: activeCapId === c.id }"
                  @click="onNavigate"
                >
                  <MethodBadge :method="c.method" />
                  <span class="nav__cap-title">{{ c.title }}</span>
                  <span
                    class="nav__dot"
                    :data-tone="stateDotTone[c.state]"
                    :title="c.state"
                    aria-hidden="true"
                  />
                </RouterLink>
              </li>
            </ul>

            <p v-else-if="isModuleOpen(nm.module) && !nm.hasCaps" class="nav__placeholder">
              {{ nm.module.priority }} · 能力待补（占位）
            </p>
          </li>
        </ul>
      </section>
    </div>

    <div class="nav__footer">
      <DensityToggle />
      <span class="nav__version" :title="`目录版本 ${cat?.version ?? '未知'}`">
        v{{ cat?.version ?? '—' }}
      </span>
    </div>
  </nav>
</template>

<style scoped>
.nav {
  display: flex;
  flex-direction: column;
  min-height: 100%;
  font-size: var(--fs-base);
  background: var(--glass-bg-strong);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.4);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.4);
}
.nav__scroll {
  flex: 1 1 auto;
  padding: var(--space-3);
}
.nav__search {
  position: relative;
  margin-bottom: var(--space-3);
}
.nav__search-input {
  width: 100%;
  padding: 8px 40px 8px 12px;
  color: var(--text);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  transition: border-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease);
}
.nav__search-input:focus {
  outline: none;
  border-color: var(--primary);
  box-shadow: 0 0 0 3px var(--primary-border);
}
.nav__search-kbd {
  position: absolute;
  right: 8px;
  top: 50%;
  transform: translateY(-50%);
  pointer-events: none;
}
.nav__overview {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  margin-bottom: var(--space-3);
  color: var(--text);
  border: 1px solid transparent;
  border-radius: var(--radius);
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
}
.nav__overview-ico {
  font-size: 16px;
}
.nav__overview:hover {
  background: var(--surface-2);
  text-decoration: none;
}
.nav__overview.active {
  color: var(--primary);
  background: var(--primary-soft);
  border-color: var(--primary-border);
}

/* 分组：轻量 uppercase 小标题，靠间距分隔（去掉厚重分隔线） */
.nav__group {
  margin-top: var(--space-4);
}
.nav__group:first-of-type {
  margin-top: var(--space-2);
}
.nav__group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 4px 8px;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  text-align: left;
  cursor: pointer;
}
.nav__group-head:hover {
  background: var(--surface-2);
}
.nav__group-chevron {
  width: 12px;
  color: var(--text-subtle);
  font-size: 9px;
  flex-shrink: 0;
}
.nav__group-label {
  flex: 1;
  color: var(--text-subtle);
  font-size: 12px;
  font-weight: var(--fw-semibold);
  letter-spacing: 0.06em;
  text-transform: uppercase;
}
.nav__group-count {
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  background: var(--surface-3);
  border-radius: var(--radius-pill);
  padding: 0 7px;
}

.nav__modules {
  list-style: none;
  margin: 4px 0 0;
  padding: 0;
}
.nav__module {
  margin-bottom: 2px;
}
.nav__module-head {
  display: flex;
  align-items: center;
  gap: 2px;
  border-radius: var(--radius);
}
/* 模块行：图标方块 + 标题 + 计数药丸，整行为可点药丸 */
.nav__module-link {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
  padding: 8px 8px;
  color: var(--text);
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
  border-radius: var(--radius);
  transition: background var(--dur) var(--ease), color var(--dur) var(--ease);
}
.nav__module-link:hover {
  text-decoration: none;
  background: var(--surface-2);
}
.nav__module-ico {
  width: 28px;
  height: 28px;
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  border-radius: 8px;
  background: var(--surface-2);
  border: 1px solid var(--border);
}
.nav__module-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.nav__module-count {
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  background: var(--surface-3);
  border-radius: var(--radius-pill);
  padding: 0 7px;
  min-width: 20px;
  text-align: center;
  flex: 0 0 auto;
}
/* 选中模块：填充软主色 + 左侧强调条 + 主色文字 */
.nav__module-head.active .nav__module-link {
  background: var(--primary-soft);
  color: var(--primary);
  box-shadow: inset 3px 0 0 var(--primary);
}
.nav__module-head.active .nav__module-ico {
  background: var(--surface);
  border-color: var(--primary-border);
}
.nav__module-head.active .nav__module-count {
  color: var(--primary);
  background: var(--surface);
}
.nav__chevron {
  width: 24px;
  height: 30px;
  flex: 0 0 auto;
  color: var(--text-subtle);
  background: transparent;
  border: none;
  font-size: 11px;
  border-radius: var(--radius-sm);
  cursor: pointer;
}
.nav__chevron:hover {
  background: var(--surface-2);
  color: var(--text);
}
.nav__caps {
  list-style: none;
  margin: 2px 0 6px;
  padding-left: 10px;
  border-left: 1px solid var(--border);
  margin-left: 21px;
}
.nav__caps--flat {
  padding-left: 0;
  border-left: none;
  margin-left: 0;
}
.nav__cap {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
  color: var(--text-muted);
  font-size: var(--fs-sm);
  border-radius: var(--radius-sm);
  transition: background var(--dur) var(--ease), color var(--dur) var(--ease),
    transform var(--dur-fast) var(--ease-out);
}
.nav__cap:hover {
  background: var(--surface-2);
  color: var(--text);
  text-decoration: none;
  transform: translateX(2px);
}
.nav__cap.active {
  background: var(--primary-soft);
  color: var(--primary);
  box-shadow: inset 0 0 0 1px var(--primary-border);
}
.nav__cap-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.nav__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  background: var(--neutral);
}
.nav__dot[data-tone='ok'] {
  background: var(--success);
  box-shadow: 0 0 6px -1px var(--success);
}
.nav__dot[data-tone='ok-warn'] {
  background: var(--success);
  box-shadow: 0 0 0 2px var(--warning-soft), inset 0 0 0 1px var(--warning);
}
.nav__dot[data-tone='off'] {
  background: var(--neutral);
}
.nav__dot[data-tone='warn'] {
  background: var(--warning);
  box-shadow: 0 0 6px -1px var(--warning);
}
.nav__dot[data-tone='danger'] {
  background: var(--danger);
  box-shadow: 0 0 6px -1px var(--danger);
}
.nav__placeholder {
  padding: 4px 10px 8px 24px;
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}

/* 底部固定条 */
.nav__footer {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  background: var(--glass-bg-strong);
  border-top: 1px solid var(--glass-border);
}
.nav__version {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
</style>
