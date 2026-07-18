<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUiStore } from '../../stores/ui'
import { useHistoryStore, type HistoryEntry } from '../../stores/history'
import { useCatalogStore } from '../../stores/catalog'
import { useSessionStore } from '../../stores/session'
import { useFocusTrap } from '../../composables/useFocusTrap'
import { toCurl } from '../../utils/curl'
import MethodBadge from '../capability/badges/MethodBadge.vue'
import CopyButton from './CopyButton.vue'

/**
 * 请求历史抽屉（⌘J）：右侧滑出玻璃抽屉。列出会话级内存历史，
 * 支持按结果过滤 / 清空 / 重放（回填留给批2 Runner 消费）/ 复制 curl。
 */
const ui = useUiStore()
const history = useHistoryStore()
const catalog = useCatalogStore()
const session = useSessionStore()
const router = useRouter()

const panelEl = ref<HTMLElement | null>(null)
const closeBtn = ref<HTMLButtonElement | null>(null)
const filter = ref<'all' | 'ok' | 'fail'>('all')

const filtered = computed<HistoryEntry[]>(() => {
  if (filter.value === 'ok') return history.entries.filter((e) => e.ok)
  if (filter.value === 'fail') return history.entries.filter((e) => !e.ok)
  return history.entries
})

function fmtTime(at: number): string {
  try {
    return new Date(at).toLocaleTimeString()
  } catch {
    return ''
  }
}
function capTitle(e: HistoryEntry): string {
  return catalog.capabilityById(e.capId)?.title ?? e.capId
}
function curlFor(e: HistoryEntry): string {
  const cap = catalog.capabilityById(e.capId)
  if (!cap) return `# 能力已不在目录：${e.capId}`
  return toCurl(cap, e.params, { edgeBaseUrl: session.edgeBaseUrl })
}

function replay(e: HistoryEntry): void {
  const cap = catalog.capabilityById(e.capId)
  history.requestReplay(e.capId, e.params)
  ui.closeHistory()
  if (cap) void router.push(`/m/${cap.module}/${cap.id}`)
}

useFocusTrap({
  active: () => ui.historyOpen,
  container: panelEl,
  onEscape: () => ui.closeHistory(),
  initialFocus: () => closeBtn.value,
})
</script>

<template>
  <Teleport to="body">
    <Transition name="drawer">
      <div v-if="ui.historyOpen" class="drawer">
        <div class="drawer__scrim" aria-hidden="true" @click="ui.closeHistory()" />
        <aside ref="panelEl" class="drawer__panel" role="dialog" aria-modal="true" aria-label="请求历史">
          <header class="drawer__head">
            <h2 class="drawer__title">请求历史</h2>
            <button
              ref="closeBtn"
              type="button"
              class="drawer__close"
              aria-label="关闭历史"
              @click="ui.closeHistory()"
            >
              ✕
            </button>
          </header>

          <div class="drawer__toolbar">
            <div class="drawer__filters" role="group" aria-label="按结果过滤">
              <button
                type="button"
                class="drawer__filter"
                :class="{ 'is-active': filter === 'all' }"
                :aria-pressed="filter === 'all'"
                @click="filter = 'all'"
              >
                全部
              </button>
              <button
                type="button"
                class="drawer__filter"
                :class="{ 'is-active': filter === 'ok' }"
                :aria-pressed="filter === 'ok'"
                @click="filter = 'ok'"
              >
                成功
              </button>
              <button
                type="button"
                class="drawer__filter"
                :class="{ 'is-active': filter === 'fail' }"
                :aria-pressed="filter === 'fail'"
                @click="filter = 'fail'"
              >
                失败
              </button>
            </div>
            <button
              type="button"
              class="drawer__clear"
              :disabled="!history.entries.length"
              @click="history.clear()"
            >
              清空
            </button>
          </div>

          <div v-if="!filtered.length" class="drawer__empty">
            <p class="drawer__empty-title">暂无请求历史</p>
            <p class="drawer__empty-desc">本会话内运行能力后，记录会出现在这里（仅内存，刷新即清空）。</p>
          </div>

          <ul v-else class="drawer__list">
            <li v-for="e in filtered" :key="e.id" class="entry">
              <div class="entry__row">
                <span class="entry__dot" :data-ok="e.ok" aria-hidden="true" />
                <span class="entry__status">{{ e.status ?? '—' }}</span>
                <MethodBadge :method="e.method" />
                <span class="entry__title">{{ capTitle(e) }}</span>
              </div>
              <div class="entry__meta">
                <span class="entry__path">{{ e.path }}</span>
              </div>
              <div class="entry__meta">
                <span>{{ e.elapsedMs }}ms</span>
                <span aria-hidden="true">·</span>
                <span>{{ fmtTime(e.at) }}</span>
                <span v-if="e.traceId" class="entry__trace" :title="`traceId: ${e.traceId}`">
                  #{{ e.traceId }}
                </span>
              </div>
              <div class="entry__actions">
                <button type="button" class="entry__btn" @click="replay(e)">重放</button>
                <CopyButton :text="curlFor(e)" label="复制 curl" compact />
              </div>
            </li>
          </ul>
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.drawer {
  position: fixed;
  inset: 0;
  z-index: var(--z-drawer);
}
.drawer__scrim {
  position: absolute;
  inset: 0;
  background: rgba(2, 6, 23, 0.5);
}
.drawer__panel {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: 360px;
  max-width: 92vw;
  padding-bottom: var(--safe-bottom);
  display: flex;
  flex-direction: column;
  background: var(--glass-bg-strong);
  -webkit-backdrop-filter: blur(var(--glass-blur-strong)) saturate(1.4);
  backdrop-filter: blur(var(--glass-blur-strong)) saturate(1.4);
  border-left: 1px solid var(--glass-border);
  box-shadow: var(--shadow-lg);
}
.drawer__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--glass-border);
}
.drawer__title {
  font-size: var(--fs-md);
  font-weight: var(--fw-bold);
}
.drawer__close {
  width: var(--control-h-sm);
  height: var(--control-h-sm);
  color: var(--text-muted);
  background: transparent;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.drawer__close:hover {
  color: var(--text);
  background: var(--surface-2);
}
.drawer__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-4);
  border-bottom: 1px solid var(--glass-border);
}
.drawer__filters {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}
.drawer__filter {
  padding: 0 10px;
  height: var(--control-h-sm);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--text-subtle);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
}
.drawer__filter.is-active {
  color: var(--primary);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}
.drawer__clear {
  padding: 0 var(--space-3);
  height: var(--control-h-sm);
  font-size: var(--fs-xs);
  color: var(--text-muted);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.drawer__clear:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.drawer__empty {
  padding: var(--space-6) var(--space-4);
  text-align: center;
  color: var(--text-subtle);
}
.drawer__empty-title {
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
}
.drawer__empty-desc {
  margin-top: var(--space-2);
  font-size: var(--fs-sm);
}
.drawer__list {
  list-style: none;
  margin: 0;
  padding: var(--space-2);
  overflow-y: auto;
  flex: 1;
}
.entry {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: var(--row-py) var(--space-2);
  border-radius: var(--radius-sm);
  border: 1px solid transparent;
}
.entry:hover {
  background: var(--surface-2);
  border-color: var(--border);
}
.entry__row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.entry__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  background: var(--neutral);
}
.entry__dot[data-ok='true'] {
  background: var(--success);
}
.entry__dot[data-ok='false'] {
  background: var(--danger);
}
.entry__status {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  color: var(--text-muted);
  min-width: 30px;
}
.entry__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: var(--fw-medium);
}
.entry__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.entry__path {
  font-family: var(--font-mono);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.entry__trace {
  font-family: var(--font-mono);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 12ch;
}
.entry__actions {
  display: flex;
  gap: var(--space-2);
  margin-top: 4px;
  opacity: 0;
  transition: opacity var(--dur) var(--ease);
}
.entry:hover .entry__actions,
.entry:focus-within .entry__actions {
  opacity: 1;
}
/* 触屏无 hover：条目操作（重跑/删除）常显；触控目标抬升 */
@media (hover: none) {
  .entry__actions {
    opacity: 1;
  }
}
@media (pointer: coarse) {
  .entry__btn {
    min-height: 32px;
    padding: 4px 10px;
  }
}
/* 手机档：抽屉全宽 */
@media (max-width: 640px) {
  .drawer__panel {
    width: 100vw;
    max-width: 100vw;
    border-left: none;
  }
}
.entry__btn {
  padding: 2px 8px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.entry__btn:hover {
  color: var(--primary);
  border-color: var(--primary-border);
}

/* 滑出过渡：scrim 淡入 + 面板右移入场（reduced-motion 由全局降级） */
.drawer-enter-active,
.drawer-leave-active {
  transition: opacity var(--dur) var(--ease);
}
.drawer-enter-active .drawer__panel,
.drawer-leave-active .drawer__panel {
  transition: transform var(--dur) var(--ease-out);
}
.drawer-enter-from,
.drawer-leave-to {
  opacity: 0;
}
.drawer-enter-from .drawer__panel,
.drawer-leave-to .drawer__panel {
  transform: translateX(100%);
}
</style>
