<script setup lang="ts">
import { computed, ref } from 'vue'
import type { TrackedTask } from './types'

const props = defineProps<{ tasks: TrackedTask[]; disabled?: boolean }>()
defineEmits<{ stream: [id: string]; refresh: [id: string]; cancel: [id: string] }>()

const STAGES = ['PENDING', 'RUNNING', 'TERMINAL'] as const

const TERMINALS = ['SUCCEEDED', 'FAILED', 'CANCELLED'] as const

/** 状态过滤分组：终态各自成组，其余非终态（PENDING/RUNNING/LEASED…）归入 RUNNING(运行中)。 */
type FilterKey = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
const FILTERS: { key: FilterKey; label: string; tone: string }[] = [
  { key: 'RUNNING', label: '运行中', tone: 'active' },
  { key: 'SUCCEEDED', label: '成功', tone: 'ok' },
  { key: 'FAILED', label: '失败', tone: 'danger' },
  { key: 'CANCELLED', label: '取消', tone: 'warn' },
]

function groupOf(status: string): FilterKey {
  const s = status.toUpperCase()
  if (s === 'SUCCEEDED') return 'SUCCEEDED'
  if (s === 'FAILED') return 'FAILED'
  if (s === 'CANCELLED') return 'CANCELLED'
  return 'RUNNING'
}

const counts = computed<Record<FilterKey, number>>(() => {
  const acc: Record<FilterKey, number> = { RUNNING: 0, SUCCEEDED: 0, FAILED: 0, CANCELLED: 0 }
  for (const t of props.tasks) acc[groupOf(t.status)] += 1
  return acc
})

/** 当前激活的过滤（null = 全部）。 */
const activeFilter = ref<FilterKey | null>(null)
function toggleFilter(key: FilterKey): void {
  activeFilter.value = activeFilter.value === key ? null : key
}

const visibleTasks = computed<TrackedTask[]>(() =>
  activeFilter.value === null
    ? props.tasks
    : props.tasks.filter((t) => groupOf(t.status) === activeFilter.value),
)

function reachedIndex(status: string): number {
  const s = status.toUpperCase()
  if ((TERMINALS as readonly string[]).includes(s)) return 2
  if (s === 'RUNNING' || s === 'LEASED') return 1
  return 0
}
function terminalTone(status: string): string {
  const s = status.toUpperCase()
  if (s === 'SUCCEEDED') return 'ok'
  if (s === 'FAILED') return 'danger'
  if (s === 'CANCELLED') return 'warn'
  return 'neutral'
}
function stageLabel(stage: string, status: string): string {
  if (stage !== 'TERMINAL') return stage
  const s = status.toUpperCase()
  return (TERMINALS as readonly string[]).includes(s) ? s : '终态'
}
function isTerminal(status: string): boolean {
  return (TERMINALS as readonly string[]).includes(status.toUpperCase())
}
</script>

<template>
  <div class="tl">
    <p v-if="!tasks.length" class="tl__empty">
      尚无被追踪的任务。创建任务或执行「任务详情」后会出现在此时间线。
    </p>

    <template v-else>
      <!-- 状态过滤条（点击切换；再次点击取消） -->
      <div class="tl__filters" role="group" aria-label="按状态过滤任务">
        <button
          type="button"
          class="tl__filter"
          :data-active="activeFilter === null"
          @click="activeFilter = null"
        >
          全部 <span class="tl__filter-count">{{ tasks.length }}</span>
        </button>
        <button
          v-for="f in FILTERS"
          :key="f.key"
          type="button"
          class="tl__filter"
          :data-tone="f.tone"
          :data-active="activeFilter === f.key"
          :disabled="counts[f.key] === 0"
          :aria-pressed="activeFilter === f.key"
          @click="toggleFilter(f.key)"
        >
          {{ f.label }} <span class="tl__filter-count">{{ counts[f.key] }}</span>
        </button>
      </div>

      <p v-if="!visibleTasks.length" class="tl__empty">当前过滤下没有任务。</p>

      <ul v-else class="tl__list">
        <li v-for="t in visibleTasks" :key="t.taskId" class="tl__item">
        <div class="tl__row">
          <code class="tl__id" :title="t.taskId">{{ t.taskId }}</code>
          <span v-if="t.kind" class="tl__kind">{{ t.kind }}</span>
          <span
            v-if="(t.subscribes ?? 0) > 1"
            class="tl__reconnect"
            :title="`SSE 已重新订阅 ${(t.subscribes ?? 1) - 1} 次`"
          >
            ↻ 重连 ×{{ (t.subscribes ?? 1) - 1 }}
          </span>
          <span
            v-if="t.lastEventId"
            class="tl__resume"
            :title="`Last-Event-ID 断点续订检查点：${t.lastEventId}`"
          >
            续订点 {{ t.lastEventId }}
          </span>
          <span class="tl__time">{{ t.updatedAt }}</span>
          <span class="tl__spacer" />
          <div class="tl__actions">
            <button
              type="button"
              class="tl__btn"
              :disabled="disabled || t.streaming || isTerminal(t.status)"
              @click="$emit('stream', t.taskId)"
            >
              {{ t.streaming ? '流式中…' : 'SSE 订阅' }}
            </button>
            <button type="button" class="tl__btn" :disabled="disabled" @click="$emit('refresh', t.taskId)">
              刷新
            </button>
            <button
              type="button"
              class="tl__btn tl__btn--danger"
              :disabled="disabled || isTerminal(t.status)"
              @click="$emit('cancel', t.taskId)"
            >
              取消
            </button>
          </div>
        </div>

        <div class="tl__stages" :data-live="!isTerminal(t.status)">
          <template v-for="(stage, i) in STAGES" :key="stage">
            <span
              class="tl__node"
              :data-reached="i <= reachedIndex(t.status)"
              :data-current="i === 1 && reachedIndex(t.status) === 1"
              :data-tone="stage === 'TERMINAL' ? terminalTone(t.status) : 'active'"
            >
              {{ stageLabel(stage, t.status) }}
            </span>
            <span v-if="i < STAGES.length - 1" class="tl__conn" :data-reached="i < reachedIndex(t.status)" />
          </template>
        </div>

        <p v-if="t.error" class="tl__error">{{ t.error }}</p>
        <p v-if="t.events.length" class="tl__events">
          {{ t.events.length }} 事件 · 最近：{{ t.events[t.events.length - 1].event }}<template v-if="t.dropped"> · 已丢弃 {{ t.dropped }}</template>
        </p>
        </li>
      </ul>
    </template>
  </div>
</template>

<style scoped>
.tl__empty {
  padding: var(--space-4);
  font-size: var(--fs-sm);
  color: var(--text-subtle);
  text-align: center;
}

/* 状态过滤条 */
.tl__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: var(--space-3);
}
.tl__filter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  padding: 3px 10px;
  color: var(--text-muted);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  transition: color var(--dur) var(--ease), background var(--dur) var(--ease),
    border-color var(--dur) var(--ease);
}
.tl__filter:hover:not(:disabled) {
  color: var(--text);
  background: var(--surface-2);
}
.tl__filter:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.tl__filter:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--primary-border);
}
.tl__filter-count {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-subtle);
}
.tl__filter[data-active='true'] {
  color: var(--primary);
  background: var(--primary-soft);
  border-color: var(--primary-border);
}
.tl__filter[data-active='true'] .tl__filter-count {
  color: var(--primary);
}
.tl__filter[data-tone='ok'][data-active='true'] {
  color: var(--success);
  background: var(--success-soft);
  border-color: var(--success-border);
}
.tl__filter[data-tone='danger'][data-active='true'] {
  color: var(--danger);
  background: var(--danger-soft);
  border-color: var(--danger-border);
}
.tl__filter[data-tone='warn'][data-active='true'] {
  color: var(--warning);
  background: var(--warning-soft);
  border-color: var(--warning-border);
}

/* 重连 / 续订检查点指示 */
.tl__reconnect {
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--stream);
  background: var(--stream-soft);
  border: 1px solid var(--stream-border);
  border-radius: var(--radius-sm);
  padding: 0 6px;
}
.tl__resume {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-subtle);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 0 6px;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tl__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.tl__item {
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-2);
}
.tl__row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: var(--space-3);
  flex-wrap: wrap;
}
.tl__id {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text);
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tl__kind {
  font-size: var(--fs-xs);
  color: var(--primary);
  background: var(--primary-soft);
  border-radius: var(--radius-sm);
  padding: 0 6px;
}
.tl__time {
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  font-family: var(--font-mono);
}
.tl__spacer {
  flex: 1;
}
.tl__actions {
  display: flex;
  gap: 6px;
}
.tl__btn {
  font-size: var(--fs-xs);
  padding: 3px 8px;
  color: var(--text-muted);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.tl__btn:hover:not(:disabled) {
  color: var(--text);
  background: var(--surface-3);
}
.tl__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.tl__btn--danger:hover:not(:disabled) {
  color: var(--danger);
}
.tl__stages {
  display: flex;
  align-items: center;
}
.tl__node {
  position: relative;
  font-size: var(--fs-xs);
  font-weight: 700;
  padding: 3px 10px;
  border-radius: var(--radius-pill);
  color: var(--text-subtle);
  background: var(--surface);
  border: 1px solid var(--border);
}
.tl__node[data-reached='true'][data-tone='active'] {
  color: var(--primary);
  background: linear-gradient(135deg, var(--primary-soft), transparent 86%);
  border-color: var(--primary-border);
  box-shadow: var(--glow-primary);
}
.tl__node[data-reached='true'][data-tone='ok'] {
  color: var(--success);
  background: linear-gradient(135deg, var(--success-soft), transparent 86%);
  border-color: var(--success-border);
  box-shadow: 0 0 0 1px var(--success-border), 0 6px 18px -8px var(--success);
}
.tl__node[data-reached='true'][data-tone='danger'] {
  color: var(--danger);
  background: linear-gradient(135deg, var(--danger-soft), transparent 86%);
  border-color: var(--danger-border);
  box-shadow: var(--glow-danger);
}
.tl__node[data-reached='true'][data-tone='warn'] {
  color: var(--warning);
  background: linear-gradient(135deg, var(--warning-soft), transparent 86%);
  border-color: var(--warning-border);
  box-shadow: 0 0 0 1px var(--warning-border), 0 6px 18px -8px var(--warning);
}
/* 活动（RUNNING）节点：向外扩散的脉冲环（仅 transform/opacity） */
.tl__node[data-current='true']::after {
  content: '';
  position: absolute;
  inset: -1px;
  border-radius: inherit;
  box-shadow: 0 0 0 2px var(--primary);
  opacity: 0;
  pointer-events: none;
  animation: node-pulse 1.6s var(--ease-in-out) infinite;
}
@keyframes node-pulse {
  0% {
    transform: scale(1);
    opacity: 0.6;
  }
  100% {
    transform: scale(1.28);
    opacity: 0;
  }
}
.tl__conn {
  flex: 1;
  height: 2px;
  min-width: 24px;
  background: var(--border);
}
/* 已连通：蓝→青渐变；仅在非终态时流动 */
.tl__conn[data-reached='true'] {
  background: linear-gradient(90deg, var(--primary), var(--stream-strong));
  background-size: 200% 100%;
}
.tl__stages[data-live='true'] .tl__conn[data-reached='true'] {
  animation: conn-flow 1.6s linear infinite;
}
@keyframes conn-flow {
  from {
    background-position: 200% 0;
  }
  to {
    background-position: 0 0;
  }
}
.tl__error {
  margin-top: var(--space-2);
  font-size: var(--fs-xs);
  color: var(--danger);
}
.tl__events {
  margin-top: var(--space-2);
  font-size: var(--fs-xs);
  color: var(--stream);
  font-family: var(--font-mono);
}
</style>
