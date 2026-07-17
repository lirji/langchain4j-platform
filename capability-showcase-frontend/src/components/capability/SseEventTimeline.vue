<script setup lang="ts">
/**
 * 通用命名事件时间线 —— 用于「非逐 token、非反思」的阶段事件流（如任务状态快照流
 * /agent/tasks/{id}/stream、/async/tasks/{id}/stream）。
 *
 * 这类流每条事件的名字是任务状态（PENDING/RUNNING/SUCCEEDED/…）或动态进度名，data 是完整快照，
 * 从不发无名 message，因此拼接视图会永远卡「等待首个 token」。本组件按到达顺序把命名事件渲染成
 * 时间线（事件名 chip + 格式化 data），并高亮最新一条。所有数据从 events 派生，无需额外状态。
 */
import { computed } from 'vue'
import type { SseEvent } from '../../api/sse'
import type { SseStatus } from '../../composables/useCapabilityRun'
import { tryParseJson, prettyJson } from '../../utils/json'

const props = defineProps<{
  events: SseEvent[]
  status: SseStatus
  note?: string | null
  error?: string | null
  elapsedMs?: number
  traceId?: string | null
}>()

const statusLabel = computed(
  () =>
    ({
      idle: '待机',
      streaming: '流式中…',
      done: '已完成',
      aborted: '已中断',
      error: '出错',
    })[props.status],
)

const lastIndex = computed(() => props.events.length - 1)

function fmtData(data: string): string {
  const parsed = tryParseJson(data)
  return parsed === undefined ? data : prettyJson(parsed)
}

function tone(name: string): string {
  const s = (name || '').toUpperCase()
  if (s === 'SUCCEEDED' || name === 'done') return 'ok'
  if (s === 'FAILED' || name === 'error') return 'danger'
  if (s === 'CANCELLED') return 'warn'
  if (s === 'RUNNING') return 'active'
  return 'neutral'
}
</script>

<template>
  <div class="evtl" :data-streaming="status === 'streaming'">
    <div class="evtl__bar">
      <span class="evtl__status" :data-status="status">
        <span class="evtl__pulse" aria-hidden="true" />
        {{ statusLabel }}
      </span>
      <span v-if="elapsedMs" class="evtl__meta">{{ elapsedMs }} ms</span>
      <span v-if="traceId" class="evtl__meta" :title="`X-Trace-Id: ${traceId}`">
        trace {{ traceId.slice(0, 8) }}…
      </span>
      <span class="evtl__meta">{{ events.length }} 事件</span>
    </div>

    <p v-if="note" class="evtl__note" role="status">{{ note }}</p>
    <p v-if="error" class="evtl__error" role="alert">{{ error }}</p>

    <div class="evtl__body" aria-live="polite">
      <ol v-if="events.length" class="evtl__list">
        <li
          v-for="(ev, i) in events"
          :key="i"
          class="evtl__item"
          :data-current="i === lastIndex && status === 'streaming'"
        >
          <span class="evtl__node" :data-tone="tone(ev.event)" aria-hidden="true">{{ i + 1 }}</span>
          <div class="evtl__content">
            <span class="evtl__name" :data-tone="tone(ev.event)">{{ ev.event }}</span>
            <pre class="evtl__data">{{ fmtData(ev.data) }}</pre>
          </div>
        </li>
      </ol>
      <span v-else class="evtl__placeholder">
        {{
          status === 'streaming'
            ? '等待任务事件…（按状态推送，非逐字流式）'
            : '尚无事件。执行后按任务状态推送显示。'
        }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.evtl {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
}
.evtl__bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--border);
  flex-wrap: wrap;
}
.evtl__status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  font-weight: 700;
  color: var(--text-muted);
}
.evtl__pulse {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--neutral, var(--text-subtle));
}
.evtl__status[data-status='streaming'] {
  color: var(--stream);
}
.evtl__status[data-status='streaming'] .evtl__pulse {
  background: var(--stream-strong);
  animation: evtl-pulse 1.1s ease-in-out infinite;
}
.evtl__status[data-status='done'] {
  color: var(--success);
}
.evtl__status[data-status='done'] .evtl__pulse {
  background: var(--success);
}
.evtl__status[data-status='error'] {
  color: var(--danger);
}
.evtl__status[data-status='error'] .evtl__pulse {
  background: var(--danger);
}
.evtl__status[data-status='aborted'] .evtl__pulse {
  background: var(--warning);
}
@keyframes evtl-pulse {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.4;
    transform: scale(0.7);
  }
}
.evtl__meta {
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  font-family: var(--font-mono);
}
.evtl__note {
  margin: 0;
  padding: var(--space-2) var(--space-3);
  font-size: var(--fs-sm);
  color: var(--warning);
  background: var(--warning-soft);
  border-bottom: 1px solid var(--warning-border);
}
.evtl__error {
  margin: 0;
  padding: var(--space-2) var(--space-3);
  font-size: var(--fs-sm);
  color: var(--danger);
  background: var(--danger-soft);
  border-bottom: 1px solid var(--danger-border);
}
.evtl__body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: var(--space-3);
}
.evtl__placeholder {
  color: var(--text-subtle);
  font-size: var(--fs-sm);
}
.evtl__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.evtl__item {
  position: relative;
  display: flex;
  gap: var(--space-3);
  padding-left: 2px;
}
.evtl__item:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 13px;
  top: 26px;
  bottom: calc(-1 * var(--space-3));
  width: 2px;
  background: var(--border);
}
.evtl__node {
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  color: var(--text-muted);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  z-index: 1;
}
.evtl__node[data-tone='ok'] {
  color: var(--success);
  border-color: var(--success-border);
  background: var(--success-soft);
}
.evtl__node[data-tone='danger'] {
  color: var(--danger);
  border-color: var(--danger-border);
  background: var(--danger-soft);
}
.evtl__node[data-tone='warn'] {
  color: var(--warning);
  border-color: var(--warning-border);
  background: var(--warning-soft);
}
.evtl__node[data-tone='active'] {
  color: var(--primary);
  border-color: var(--primary-border);
  background: var(--primary-soft);
}
.evtl__item[data-current='true'] .evtl__node {
  box-shadow: 0 0 0 3px var(--stream-border);
}
.evtl__content {
  flex: 1;
  min-width: 0;
  padding-bottom: var(--space-1);
}
.evtl__name {
  display: inline-block;
  margin-bottom: 4px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--text-muted);
}
.evtl__name[data-tone='ok'] {
  color: var(--success);
}
.evtl__name[data-tone='danger'] {
  color: var(--danger);
}
.evtl__name[data-tone='warn'] {
  color: var(--warning);
}
.evtl__name[data-tone='active'] {
  color: var(--stream);
}
.evtl__data {
  margin: 0;
  padding: var(--space-2);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  line-height: 1.5;
  color: var(--text);
  background: var(--code-bg);
  border: 1px solid var(--code-border);
  border-radius: var(--radius-sm);
  overflow-x: auto;
  white-space: pre;
  word-break: normal;
}
</style>
