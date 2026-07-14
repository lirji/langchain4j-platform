<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useUiStore } from '../../stores/ui'
import { useCatalogStore } from '../../stores/catalog'
import { useFavoritesStore } from '../../stores/favorites'
import { useHistoryStore } from '../../stores/history'
import { useFocusTrap } from '../../composables/useFocusTrap'
import { usePermission } from '../../composables/usePermission'
import type { Capability } from '../../types/catalog'
import { stateTone } from '../../config/stateMeta'
import MethodBadge from '../capability/badges/MethodBadge.vue'

/**
 * 命令面板（⌘K）：模糊匹配扁平能力（标题/id/path/tag）+ 操作项。
 * 键盘 ↑↓ 选择 / ↵ 跳转或执行 / Esc 关闭；焦点陷阱 + 归还焦点。
 * 收藏置顶"收藏"组、历史来源"最近"组。
 */
const ui = useUiStore()
const catalog = useCatalogStore()
const favorites = useFavoritesStore()
const history = useHistoryStore()
const router = useRouter()
// 管理入口可见性：与顶栏/侧栏同一 permission 源，避免"一处隐藏一处可点"的旁路。
const { canAdmin } = usePermission()

const query = ref('')
const activeIndex = ref(0)
const inputEl = ref<HTMLInputElement | null>(null)
const panelEl = ref<HTMLElement | null>(null)

const q = computed(() => query.value.trim().toLowerCase())

// ── 数据项 ──
interface CmdCap {
  kind: 'cap'
  key: string
  cap: Capability
  moduleTitle: string
}
interface CmdAction {
  kind: 'action'
  key: string
  label: string
  hint: string
  icon: string
  run: () => void
}
type CmdItem = CmdCap | CmdAction
interface CmdGroup {
  id: string
  label: string
  items: CmdItem[]
}

function fuzzy(needle: string, haystack: string): boolean {
  if (!needle) return true
  const t = haystack.toLowerCase()
  if (t.includes(needle)) return true
  let i = 0
  for (const ch of t) {
    if (ch === needle[i]) i++
    if (i === needle.length) return true
  }
  return false
}

function capHay(c: Capability): string {
  return `${c.title} ${c.id} ${c.path} ${(c.tags ?? []).join(' ')}`.toLowerCase()
}
function moduleTitleOf(c: Capability): string {
  return catalog.moduleById(c.module)?.title ?? c.module
}
function toCapItem(c: Capability): CmdCap {
  return { kind: 'cap', key: `cap.${c.id}`, cap: c, moduleTitle: moduleTitleOf(c) }
}

const actions = computed<CmdAction[]>(() => {
  const items: CmdAction[] = [
  {
    kind: 'action',
    key: 'act.overview',
    label: '回到总览',
    hint: '首页',
    icon: '◍',
    run: () => {
      void router.push('/')
      ui.closeCmdk()
    },
  },
  {
    kind: 'action',
    key: 'act.history',
    label: '打开请求历史',
    hint: '⌘J',
    icon: '🕘',
    run: () => ui.openHistory(),
  },
  {
    kind: 'action',
    key: 'act.shortcuts',
    label: '快捷键帮助',
    hint: '⌘/',
    icon: '⌨',
    run: () => ui.openShortcuts(),
  },
  {
    kind: 'action',
    key: 'act.theme',
    label: '切换主题',
    hint: '⌘⇧L',
    icon: '◐',
    run: () => {
      ui.cycleTheme()
      ui.closeCmdk()
    },
  },
  {
    kind: 'action',
    key: 'act.density',
    label: '切换密度',
    hint: '⌘⇧D',
    icon: '▤',
    run: () => {
      ui.cycleDensity()
      ui.closeCmdk()
    },
  },
  ]
  // 管理入口动作：仅 role-admin 收录（fuzzy 亦搜不到），与侧栏/顶栏同源门禁。
  if (canAdmin.value) {
    items.push(
      {
        kind: 'action',
        key: 'act.admin',
        label: '打开管理中心',
        hint: '用户 / 角色',
        icon: '⚙',
        run: () => {
          void router.push({ name: 'admin-users' })
          ui.closeCmdk()
        },
      },
      {
        kind: 'action',
        key: 'act.admin.users',
        label: '用户管理',
        hint: '管理中心',
        icon: '👥',
        run: () => {
          void router.push({ name: 'admin-users' })
          ui.closeCmdk()
        },
      },
      {
        kind: 'action',
        key: 'act.admin.roles',
        label: '角色管理',
        hint: '管理中心',
        icon: '🛡',
        run: () => {
          void router.push({ name: 'admin-roles' })
          ui.closeCmdk()
        },
      },
    )
  }
  return items
})

const matchedCaps = computed<Capability[]>(() => {
  const caps = catalog.allCapabilities
  return q.value ? caps.filter((c) => fuzzy(q.value, capHay(c))) : caps
})

const favCaps = computed<Capability[]>(() =>
  matchedCaps.value.filter((c) => favorites.isFav(c.id)),
)

const recentCaps = computed<Capability[]>(() => {
  const seen = new Set<string>()
  const out: Capability[] = []
  for (const e of history.entries) {
    if (seen.has(e.capId)) continue
    seen.add(e.capId)
    const c = catalog.capabilityById(e.capId)
    if (c && fuzzy(q.value, capHay(c))) out.push(c)
    if (out.length >= 6) break
  }
  return out
})

const mainCaps = computed<Capability[]>(() => {
  const exclude = new Set<string>([...favCaps.value, ...recentCaps.value].map((c) => c.id))
  return matchedCaps.value.filter((c) => !exclude.has(c.id))
})

const matchedActions = computed<CmdAction[]>(() =>
  q.value
    ? actions.value.filter((a) => fuzzy(q.value, `${a.label} ${a.hint}`.toLowerCase()))
    : actions.value,
)

const groups = computed<CmdGroup[]>(() => {
  const g: CmdGroup[] = []
  if (favCaps.value.length) g.push({ id: 'fav', label: '收藏', items: favCaps.value.map(toCapItem) })
  if (recentCaps.value.length)
    g.push({ id: 'recent', label: '最近', items: recentCaps.value.map(toCapItem) })
  if (mainCaps.value.length) g.push({ id: 'caps', label: '能力', items: mainCaps.value.map(toCapItem) })
  if (matchedActions.value.length) g.push({ id: 'actions', label: '操作', items: matchedActions.value })
  return g
})

const flat = computed<CmdItem[]>(() => groups.value.flatMap((g) => g.items))
const indexByKey = computed(() => {
  const m = new Map<string, number>()
  flat.value.forEach((it, i) => m.set(it.key, i))
  return m
})
const activeDescId = computed(() =>
  flat.value.length ? `cmdk-opt-${activeIndex.value}` : undefined,
)

function highlight(text: string): { pre: string; mark: string; post: string } | null {
  if (!q.value) return null
  const idx = text.toLowerCase().indexOf(q.value)
  if (idx < 0) return null
  return {
    pre: text.slice(0, idx),
    mark: text.slice(idx, idx + q.value.length),
    post: text.slice(idx + q.value.length),
  }
}

function scrollActiveIntoView(): void {
  void nextTick(() => {
    document.getElementById(`cmdk-opt-${activeIndex.value}`)?.scrollIntoView({ block: 'nearest' })
  })
}

function move(delta: number): void {
  const n = flat.value.length
  if (!n) return
  activeIndex.value = (activeIndex.value + delta + n) % n
  scrollActiveIntoView()
}

function selectAt(i: number): void {
  const item = flat.value[i]
  if (!item) return
  if (item.kind === 'cap') {
    void router.push(`/m/${item.cap.module}/${item.cap.id}`)
    ui.closeCmdk()
  } else {
    item.run()
  }
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    move(1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    move(-1)
  } else if (e.key === 'Enter') {
    e.preventDefault()
    selectAt(activeIndex.value)
  }
  // Esc 由焦点陷阱统一处理
}

watch(
  () => ui.cmdkOpen,
  (open) => {
    if (open) {
      query.value = ''
      activeIndex.value = 0
    }
  },
)
watch(q, () => {
  activeIndex.value = 0
})

useFocusTrap({
  active: () => ui.cmdkOpen,
  container: panelEl,
  onEscape: () => ui.closeCmdk(),
  initialFocus: () => inputEl.value,
})
</script>

<template>
  <Teleport to="body">
    <div v-if="ui.cmdkOpen" class="cmdk" role="presentation">
      <div class="cmdk__scrim" aria-hidden="true" @click="ui.closeCmdk()" />
      <div ref="panelEl" class="cmdk__panel" role="dialog" aria-modal="true" aria-label="命令面板">
        <div class="cmdk__inputwrap">
          <span class="cmdk__inputicon" aria-hidden="true">⌕</span>
          <input
            ref="inputEl"
            v-model="query"
            type="text"
            class="cmdk__input"
            role="combobox"
            aria-expanded="true"
            aria-controls="cmdk-listbox"
            :aria-activedescendant="activeDescId"
            aria-autocomplete="list"
            autocomplete="off"
            spellcheck="false"
            placeholder="搜索能力，或输入命令…"
            @keydown="onKeydown"
          />
          <kbd class="cmdk__esc">Esc</kbd>
        </div>

        <ul v-if="flat.length" id="cmdk-listbox" class="cmdk__list" role="listbox">
          <template v-for="g in groups" :key="g.id">
            <li class="cmdk__grouphead eyebrow" role="presentation">{{ g.label }}</li>
            <li
              v-for="item in g.items"
              :id="`cmdk-opt-${indexByKey.get(item.key)}`"
              :key="item.key"
              class="cmdk__opt"
              role="option"
              :aria-selected="indexByKey.get(item.key) === activeIndex"
              @click="selectAt(indexByKey.get(item.key) ?? -1)"
              @mousemove="activeIndex = indexByKey.get(item.key) ?? activeIndex"
            >
              <template v-if="item.kind === 'cap'">
                <MethodBadge :method="item.cap.method" />
                <span class="cmdk__title">
                  <template v-if="highlight(item.cap.title)">
                    {{ highlight(item.cap.title)!.pre
                    }}<mark>{{ highlight(item.cap.title)!.mark }}</mark
                    >{{ highlight(item.cap.title)!.post }}
                  </template>
                  <template v-else>{{ item.cap.title }}</template>
                </span>
                <span class="cmdk__module">{{ item.moduleTitle }}</span>
                <span
                  class="cmdk__dot"
                  :data-tone="stateTone(item.cap.state)"
                  :title="item.cap.state"
                  aria-hidden="true"
                />
              </template>
              <template v-else>
                <span class="cmdk__actionicon" aria-hidden="true">{{ item.icon }}</span>
                <span class="cmdk__title">{{ item.label }}</span>
                <kbd v-if="item.hint" class="cmdk__hint">{{ item.hint }}</kbd>
              </template>
            </li>
          </template>
        </ul>
        <div v-else class="cmdk__empty">无匹配结果</div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.cmdk {
  position: fixed;
  inset: 0;
  z-index: var(--z-cmdk);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 15vh var(--space-4) var(--space-4);
}
.cmdk__scrim {
  position: absolute;
  inset: 0;
  background: rgba(2, 6, 23, 0.5);
}
.cmdk__panel {
  position: relative;
  width: 100%;
  max-width: 640px;
  max-height: 70vh;
  display: flex;
  flex-direction: column;
  background: var(--glass-bg-strong);
  -webkit-backdrop-filter: blur(var(--glass-blur-strong)) saturate(1.4);
  backdrop-filter: blur(var(--glass-blur-strong)) saturate(1.4);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
  animation: cmdk-in var(--dur-base) var(--ease-out);
}
@keyframes cmdk-in {
  from {
    opacity: 0;
    transform: translateY(-8px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}
.cmdk__inputwrap {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 0 var(--space-3);
  border-bottom: 1px solid var(--glass-border);
}
.cmdk__inputicon {
  color: var(--text-subtle);
  font-size: 16px;
}
.cmdk__input {
  flex: 1;
  min-width: 0;
  height: 48px;
  border: none;
  background: transparent;
  color: var(--text);
  font-size: var(--fs-lg);
}
.cmdk__input:focus {
  outline: none;
}
.cmdk__input::placeholder {
  color: var(--text-subtle);
}
.cmdk__list {
  list-style: none;
  margin: 0;
  padding: var(--space-2);
  overflow-y: auto;
}
.cmdk__grouphead {
  padding: var(--space-2) var(--space-2) 4px;
}
.cmdk__opt {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--row-py) var(--space-2);
  border-radius: var(--radius-sm);
  cursor: pointer;
}
.cmdk__opt[aria-selected='true'] {
  background: var(--primary-soft);
  box-shadow: inset 0 0 0 1px var(--primary-border);
}
.cmdk__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text);
}
.cmdk__title mark {
  background: transparent;
  color: var(--primary);
  font-weight: var(--fw-bold);
}
.cmdk__module {
  flex-shrink: 0;
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.cmdk__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  background: var(--neutral);
}
.cmdk__dot[data-tone='ok'] {
  background: var(--success);
}
.cmdk__dot[data-tone='ok-warn'] {
  background: var(--success);
  box-shadow: 0 0 0 2px var(--warning-soft), inset 0 0 0 1px var(--warning);
}
.cmdk__dot[data-tone='off'] {
  background: var(--neutral);
}
.cmdk__dot[data-tone='warn'] {
  background: var(--warning);
}
.cmdk__dot[data-tone='danger'] {
  background: var(--danger);
}
.cmdk__actionicon {
  width: 18px;
  text-align: center;
  flex-shrink: 0;
}
.cmdk__hint {
  flex-shrink: 0;
}
.cmdk__empty {
  padding: var(--space-6);
  text-align: center;
  color: var(--text-subtle);
  font-size: var(--fs-sm);
}
</style>
