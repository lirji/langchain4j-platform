<script setup lang="ts">
/**
 * 阶段事件流控制台 —— 用于「非逐 token」的 SSE 能力（当前即反思编排 /agent/reflexive/stream）。
 *
 * 与 SseConsole（逐 token 拼接视图）分工：这类流按阶段发命名事件（attempt-start/answer/critique/done），
 * 从不发无名 message，因此拼接视图会永远卡在「等待首个 token」。本组件改为按轮次渲染：顶部「当前/最终
 * 答案」（Markdown 消毒渲染）+ 每轮评分时间线，并保留原始「事件流」tab 兜底。所有数据从 events 派生
 * （经纯解析器 parseReflexionEvents），无需额外状态管道。
 */
import { computed, ref } from 'vue'
import type { SseEvent } from '../../api/sse'
import type { SseStatus } from '../../composables/useCapabilityRun'
import { parseReflexionEvents, type ReflexionRound } from './reflexionStream'
import { renderMarkdown } from '../../utils/markdown'

const props = defineProps<{
  events: SseEvent[]
  status: SseStatus
  note?: string | null
  error?: string | null
  elapsedMs?: number
  traceId?: string | null
}>()

const view = ref<'stage' | 'events'>('stage')

const model = computed(() => parseReflexionEvents(props.events))
const hasContent = computed(
  () => model.value.rounds.length > 0 || model.value.currentAnswer !== '',
)
const answerHtml = computed(() => renderMarkdown(model.value.currentAnswer))

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

const STATUS_TEXT: Record<ReflexionRound['status'], string> = {
  answering: '作答中',
  critiquing: '评审中',
  scored: '已评分',
}

function pct(v: number | undefined): string {
  return v == null ? '—' : `${Math.round(v * 100)}%`
}
function pctWidth(v: number | undefined): string {
  const clamped = Math.max(0, Math.min(1, v ?? 0))
  return `${clamped * 100}%`
}
function meters(r: ReflexionRound): { k: string; v: number | undefined }[] {
  return [
    { k: '正确性', v: r.scores?.correctness },
    { k: '完整性', v: r.scores?.completeness },
    { k: '清晰度', v: r.scores?.clarity },
  ]
}
function eventTone(name: string): string {
  if (name === 'done') return 'ok'
  if (name === 'error') return 'danger'
  if (name === 'critique') return 'warn'
  return 'neutral'
}
</script>

<template>
  <div class="stage" :data-streaming="status === 'streaming'">
    <div class="stage__bar">
      <span class="stage__status" :data-status="status">
        <span class="stage__pulse" aria-hidden="true" />
        {{ statusLabel }}
      </span>
      <span v-if="elapsedMs" class="stage__meta">{{ elapsedMs }} ms</span>
      <span v-if="traceId" class="stage__meta" :title="`X-Trace-Id: ${traceId}`">
        trace {{ traceId.slice(0, 8) }}…
      </span>
      <span class="stage__meta">{{ events.length }} 事件</span>
      <span class="stage__spacer" />
      <div class="stage__tabs" role="tablist">
        <button
          type="button"
          role="tab"
          :aria-selected="view === 'stage'"
          class="stage__tab"
          :class="{ active: view === 'stage' }"
          @click="view = 'stage'"
        >
          轮次
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="view === 'events'"
          class="stage__tab"
          :class="{ active: view === 'events' }"
          @click="view = 'events'"
        >
          事件流
        </button>
      </div>
    </div>

    <p v-if="note" class="stage__note" role="status">{{ note }}</p>
    <p v-if="error" class="stage__error" role="alert">{{ error }}</p>

    <!-- 轮次视图 -->
    <div v-show="view === 'stage'" class="stage__body" aria-live="polite">
      <template v-if="hasContent">
        <section v-if="model.currentAnswer" class="stage__answer">
          <div class="stage__answer-head">
            <span class="stage__answer-label">{{ model.finalized ? '最终答案' : '当前答案' }}</span>
            <span
              v-if="model.finalized && model.converged !== null"
              class="stage__badge"
              :data-ok="model.converged"
            >
              {{ model.converged ? '已收敛（达阈值）' : '未达阈值（用尽轮次）' }}
            </span>
          </div>
          <!-- eslint-disable-next-line vue/no-v-html -->
          <div class="stage__answer-md" v-html="answerHtml" />
        </section>

        <ol class="stage__timeline">
          <li v-for="r in model.rounds" :key="r.n" class="stage__round">
            <span class="stage__node" aria-hidden="true">{{ r.n }}</span>
            <div class="stage__round-body">
              <div class="stage__round-head">
                <span class="stage__round-title">第 {{ r.n }} 轮</span>
                <span class="stage__chip" :data-status="r.status">{{ STATUS_TEXT[r.status] }}</span>
              </div>
              <pre v-if="r.rawFallback" class="stage__raw">{{ r.rawFallback }}</pre>
              <div v-if="r.scores" class="stage__scores">
                <div class="stage__agg">
                  <span class="stage__agg-label">聚合分</span>
                  <span class="stage__agg-val">{{ pct(r.scores.aggregate) }}</span>
                </div>
                <div class="stage__meters">
                  <div v-for="m in meters(r)" :key="m.k" class="stage__meter">
                    <span class="stage__meter-label">{{ m.k }}</span>
                    <span class="stage__meter-track">
                      <span class="stage__meter-fill" :style="{ width: pctWidth(m.v) }" />
                    </span>
                    <span class="stage__meter-val">{{ pct(m.v) }}</span>
                  </div>
                </div>
                <p v-if="r.mainIssue && r.mainIssue !== 'n/a'" class="stage__issue">
                  待改进：{{ r.mainIssue }}
                </p>
              </div>
            </div>
          </li>
        </ol>
      </template>
      <span v-else class="stage__placeholder">
        {{
          status === 'streaming'
            ? '正在进行第 1 轮推理…（反思编排按轮次推进，非逐字流式）'
            : '尚无输出。执行反思编排后按轮次显示进度。'
        }}
      </span>
    </div>

    <!-- 原始事件流视图 -->
    <ul v-show="view === 'events'" class="stage__events">
      <li v-for="(ev, i) in events" :key="i" class="stage__event">
        <span class="stage__event-name" :data-tone="eventTone(ev.event)">{{ ev.event }}</span>
        <span class="stage__event-data">{{ ev.data }}</span>
      </li>
      <li v-if="!events.length" class="stage__placeholder stage__placeholder--events">尚无事件。</li>
    </ul>
  </div>
</template>

<style scoped>
.stage {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
}
.stage__bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--border);
  flex-wrap: wrap;
}
.stage__status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  font-weight: 700;
  color: var(--text-muted);
}
.stage__pulse {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--neutral, var(--text-subtle));
}
.stage__status[data-status='streaming'] {
  color: var(--stream);
}
.stage__status[data-status='streaming'] .stage__pulse {
  background: var(--stream-strong);
  animation: stage-pulse 1.1s ease-in-out infinite;
}
.stage__status[data-status='done'] {
  color: var(--success);
}
.stage__status[data-status='done'] .stage__pulse {
  background: var(--success);
}
.stage__status[data-status='error'] {
  color: var(--danger);
}
.stage__status[data-status='error'] .stage__pulse {
  background: var(--danger);
}
.stage__status[data-status='aborted'] .stage__pulse {
  background: var(--warning);
}
@keyframes stage-pulse {
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
.stage__meta {
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  font-family: var(--font-mono);
}
.stage__spacer {
  flex: 1;
}
.stage__tabs {
  display: inline-flex;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.stage__tab {
  padding: 2px 10px;
  font-size: var(--fs-xs);
  color: var(--text-muted);
  background: var(--surface);
  border: none;
}
.stage__tab.active {
  color: var(--stream);
  background: var(--stream-soft);
}
.stage__note {
  margin: 0;
  padding: var(--space-2) var(--space-3);
  font-size: var(--fs-sm);
  color: var(--warning);
  background: var(--warning-soft);
  border-bottom: 1px solid var(--warning-border);
}
.stage__error {
  margin: 0;
  padding: var(--space-2) var(--space-3);
  font-size: var(--fs-sm);
  color: var(--danger);
  background: var(--danger-soft);
  border-bottom: 1px solid var(--danger-border);
}
.stage__body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.stage__placeholder {
  color: var(--text-subtle);
  font-size: var(--fs-sm);
}
.stage__placeholder--events {
  display: block;
  padding: var(--space-2) 0;
}

/* 当前 / 最终答案 */
.stage__answer {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  overflow: hidden;
}
.stage__answer-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--border);
}
.stage__answer-label {
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--text-muted);
}
.stage__badge {
  font-size: var(--fs-xs);
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  color: var(--warning);
  background: var(--warning-soft);
  border: 1px solid var(--warning-border);
}
.stage__badge[data-ok='true'] {
  color: var(--success);
  background: var(--success-soft, transparent);
  border-color: var(--success);
}
.stage__answer-md {
  padding: var(--space-3);
  font-size: var(--fs-sm);
  line-height: 1.6;
  color: var(--text);
  word-break: break-word;
}
.stage__answer-md :deep(pre) {
  overflow-x: auto;
  padding: var(--space-2) var(--space-3);
  background: var(--code-bg);
  border: 1px solid var(--code-border);
  border-radius: var(--radius);
}
.stage__answer-md :deep(code) {
  font-family: var(--font-mono);
  font-size: 0.92em;
}
.stage__answer-md :deep(p) {
  margin: 0 0 var(--space-2);
}
.stage__answer-md :deep(p:last-child) {
  margin-bottom: 0;
}

/* 每轮时间线 */
.stage__timeline {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.stage__round {
  position: relative;
  display: flex;
  gap: var(--space-3);
  padding-left: 2px;
}
.stage__round:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 13px;
  top: 26px;
  bottom: calc(-1 * var(--space-3));
  width: 2px;
  background: var(--border);
}
.stage__node {
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  color: var(--primary);
  background: var(--primary-soft);
  border: 1px solid var(--primary-border);
  border-radius: var(--radius-pill);
  z-index: 1;
}
.stage__round-body {
  flex: 1;
  min-width: 0;
  padding-bottom: var(--space-1);
}
.stage__round-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: 6px;
}
.stage__round-title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-bold);
  color: var(--text);
}
.stage__chip {
  font-size: var(--fs-xs);
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  color: var(--text-muted);
  background: var(--surface-2);
  border: 1px solid var(--border);
}
.stage__chip[data-status='answering'] {
  color: var(--stream);
  background: var(--stream-soft);
  border-color: var(--stream-border);
}
.stage__chip[data-status='scored'] {
  color: var(--success);
}
.stage__raw {
  margin: 0 0 6px;
  padding: var(--space-2);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text-muted);
  background: var(--code-bg);
  border: 1px dashed var(--border);
  border-radius: var(--radius-sm);
  white-space: pre-wrap;
  word-break: break-word;
}
.stage__scores {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.stage__agg {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
}
.stage__agg-label {
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.stage__agg-val {
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  font-weight: var(--fw-bold);
  color: var(--text);
}
.stage__meters {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.stage__meter {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.stage__meter-label {
  flex-shrink: 0;
  width: 3.5em;
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.stage__meter-track {
  flex: 1;
  height: 6px;
  border-radius: var(--radius-pill);
  background: var(--surface-2);
  overflow: hidden;
}
.stage__meter-fill {
  display: block;
  height: 100%;
  border-radius: var(--radius-pill);
  background: var(--primary);
}
.stage__meter-val {
  flex-shrink: 0;
  width: 3ch;
  text-align: right;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text-muted);
}
.stage__issue {
  margin: 4px 0 0;
  font-size: var(--fs-xs);
  color: var(--warning);
}

/* 原始事件流 */
.stage__events {
  flex: 1;
  min-height: 0;
  overflow: auto;
  margin: 0;
  padding: var(--space-2) var(--space-3);
  list-style: none;
}
.stage__event {
  display: flex;
  gap: 8px;
  padding: 4px 0;
  border-bottom: 1px dashed var(--border);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
}
.stage__event-name {
  flex-shrink: 0;
  min-width: 120px;
  font-weight: 700;
  color: var(--text-muted);
}
.stage__event-name[data-tone='ok'] {
  color: var(--success);
}
.stage__event-name[data-tone='danger'] {
  color: var(--danger);
}
.stage__event-name[data-tone='warn'] {
  color: var(--warning);
}
.stage__event-data {
  min-width: 0;
  color: var(--text);
  word-break: break-word;
}
</style>
