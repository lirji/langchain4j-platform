<script setup lang="ts">
import { computed, ref } from 'vue'
import type { SseEvent } from '../../api/sse'
import type { SseStatus } from '../../composables/useCapabilityRun'
import CopyButton from '../common/CopyButton.vue'
import { countMatches, highlightSegments } from '../../utils/highlight'
import { downloadText } from '../../utils/download'

const props = defineProps<{
  tokens: string
  events: SseEvent[]
  status: SseStatus
  note?: string | null
  error?: string | null
  elapsedMs?: number
  traceId?: string | null
}>()

const view = ref<'transcript' | 'events'>('transcript')
const query = ref('')
const wrap = ref(true)

const matchCount = computed(() => countMatches(props.tokens, query.value))
const segments = computed(() => highlightSegments(props.tokens, query.value))

function downloadTranscript(): void {
  downloadText('transcript.txt', props.tokens, 'text/plain')
}

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

function eventTone(name: string): string {
  if (name === 'done') return 'ok'
  if (name === 'error' || name === 'blocked') return 'danger'
  if (name === 'grounding-warning') return 'warn'
  return 'neutral'
}
</script>

<template>
  <div class="sse" :data-streaming="status === 'streaming'">
    <div class="sse__bar">
      <span class="sse__status" :data-status="status">
        <span class="sse__pulse" aria-hidden="true" />
        {{ statusLabel }}
      </span>
      <span v-if="elapsedMs" class="sse__meta">{{ elapsedMs }} ms</span>
      <span v-if="traceId" class="sse__meta" :title="`X-Trace-Id: ${traceId}`">
        trace {{ traceId.slice(0, 8) }}…
      </span>
      <span class="sse__meta">{{ events.length }} 事件</span>
      <span class="sse__spacer" />

      <label v-show="view === 'transcript'" class="sse__search">
        <input
          v-model="query"
          type="search"
          class="sse__search-input"
          placeholder="搜索转录…"
          aria-label="搜索转录文本"
        />
        <span v-if="query.trim()" class="sse__search-count" aria-live="polite">{{ matchCount }}</span>
      </label>

      <button
        v-show="view === 'transcript'"
        type="button"
        class="sse__act"
        :class="{ 'is-on': !wrap }"
        :aria-pressed="!wrap"
        title="切换自动换行"
        @click="wrap = !wrap"
      >
        换行
      </button>

      <button
        v-show="view === 'transcript'"
        type="button"
        class="sse__act"
        :disabled="!tokens"
        title="下载转录 (.txt)"
        @click="downloadTranscript"
      >
        下载
      </button>

      <div class="sse__tabs" role="tablist">
        <button
          type="button"
          role="tab"
          :aria-selected="view === 'transcript'"
          class="sse__tab"
          :class="{ active: view === 'transcript' }"
          @click="view = 'transcript'"
        >
          转录
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="view === 'events'"
          class="sse__tab"
          :class="{ active: view === 'events' }"
          @click="view = 'events'"
        >
          事件流
        </button>
      </div>
      <CopyButton v-if="tokens" :text="tokens" compact />
    </div>

    <p v-if="note" class="sse__note" role="status">{{ note }}</p>
    <p v-if="error" class="sse__error" role="alert">{{ error }}</p>

    <!-- 拼接视图（逐 token 追加） -->
    <div
      v-show="view === 'transcript'"
      class="sse__transcript"
      :class="{ 'sse__transcript--nowrap': !wrap }"
      aria-live="polite"
      aria-atomic="false"
    >
      <template v-if="tokens"><template
          v-if="query.trim()"
        ><span
          v-for="(s, i) in segments"
          :key="i"
          :class="{ 'sse__hit': s.hit }"
        >{{ s.text }}</span></template><template v-else>{{ tokens }}</template><span
          v-if="status === 'streaming'"
          class="sse__cursor"
          aria-hidden="true"
        >▋</span></template>
      <span v-else class="sse__placeholder">
        {{ status === 'streaming' ? '等待首个 token…' : '尚无输出。执行流式能力后逐 token 显示。' }}
      </span>
    </div>

    <!-- 事件流视图（原始命名事件） -->
    <ul v-show="view === 'events'" class="sse__events">
      <li v-for="(ev, i) in events" :key="i" class="sse__event">
        <span class="sse__event-name" :data-tone="eventTone(ev.event)">{{ ev.event }}</span>
        <span class="sse__event-data">{{ ev.data }}</span>
      </li>
      <li v-if="!events.length" class="sse__placeholder sse__placeholder--events">尚无事件。</li>
    </ul>
  </div>
</template>

<style scoped>
.sse {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
}
.sse__bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--border);
  flex-wrap: wrap;
}
.sse__status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  font-weight: 700;
  color: var(--text-muted);
}
.sse__pulse {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--neutral);
}
.sse__status[data-status='streaming'] {
  color: var(--stream);
}
.sse__status[data-status='streaming'] .sse__pulse {
  background: var(--stream-strong);
  animation: pulse 1.1s ease-in-out infinite;
}
.sse__status[data-status='done'] {
  color: var(--success);
}
.sse__status[data-status='done'] .sse__pulse {
  background: var(--success);
}
.sse__status[data-status='error'] {
  color: var(--danger);
}
.sse__status[data-status='error'] .sse__pulse {
  background: var(--danger);
}
.sse__status[data-status='aborted'] .sse__pulse {
  background: var(--warning);
}
@keyframes pulse {
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
.sse__meta {
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  font-family: var(--font-mono);
}
.sse__spacer {
  flex: 1;
}
/* 转录操作条按钮：与 ResponseViewer 统一的小控件规格。 */
.sse__act {
  display: inline-flex;
  align-items: center;
  height: var(--control-h-sm);
  padding: 0 var(--space-2);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
  background: transparent;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  transition: color var(--dur) var(--ease), background var(--dur) var(--ease),
    border-color var(--dur) var(--ease);
}
.sse__act:hover:not(:disabled) {
  color: var(--text);
  background: var(--surface-2);
}
.sse__act:active:not(:disabled),
.sse__act.is-on {
  color: var(--stream);
  background: var(--stream-soft);
  border-color: var(--stream-border);
}
.sse__act:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.sse__search {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.sse__search-input {
  width: 118px;
  height: var(--control-h-sm);
  padding: 0 var(--space-2);
  font-size: var(--fs-xs);
  color: var(--text);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.sse__search-input:focus {
  outline: none;
  border-color: var(--stream);
  box-shadow: 0 0 0 3px var(--stream-border);
}
.sse__search-count {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-subtle);
  min-width: 2ch;
  text-align: right;
}
.sse__tabs {
  display: inline-flex;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.sse__tab {
  padding: 2px 10px;
  font-size: var(--fs-xs);
  color: var(--text-muted);
  background: var(--surface);
  border: none;
}
.sse__tab.active {
  color: var(--stream);
  background: var(--stream-soft);
}
.sse__note {
  margin: 0;
  padding: var(--space-2) var(--space-3);
  font-size: var(--fs-sm);
  color: var(--warning);
  background: var(--warning-soft);
  border-bottom: 1px solid var(--warning-border);
}
.sse__error {
  margin: 0;
  padding: var(--space-2) var(--space-3);
  font-size: var(--fs-sm);
  color: var(--danger);
  background: var(--danger-soft);
  border-bottom: 1px solid var(--danger-border);
}
.sse__transcript {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: var(--space-3);
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  transition: box-shadow var(--dur) var(--ease);
}
.sse__transcript--nowrap {
  white-space: pre;
  word-break: normal;
}
.sse__hit {
  border-radius: 3px;
  padding: 0 1px;
  color: var(--text);
  background: var(--warning-soft);
  box-shadow: 0 0 0 1px var(--warning-border);
}
/* 流式时：转录区青色 inset 描边 + 顶部流光扫描线 */
.sse[data-streaming='true'] .sse__transcript {
  box-shadow: inset 0 0 0 1px var(--stream-border);
}
.sse[data-streaming='true'] .sse__transcript::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  width: 40%;
  height: 2px;
  background: linear-gradient(90deg, transparent, var(--stream-strong), transparent);
  animation: stream-scan 1.4s var(--ease-in-out) infinite;
  pointer-events: none;
}
@keyframes stream-scan {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(300%);
  }
}
.sse__cursor {
  color: var(--stream-strong);
  text-shadow: 0 0 8px var(--stream-strong);
  animation: blink 1s step-end infinite;
}
@keyframes blink {
  50% {
    opacity: 0;
  }
}
.sse__placeholder {
  color: var(--text-subtle);
}
.sse__events {
  flex: 1;
  min-height: 0;
  overflow: auto;
  margin: 0;
  padding: var(--space-2) var(--space-3);
  list-style: none;
}
.sse__event {
  display: flex;
  gap: 8px;
  padding: 4px 0;
  border-bottom: 1px dashed var(--border);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
}
.sse__event-name {
  flex-shrink: 0;
  min-width: 120px;
  font-weight: 700;
  color: var(--text-muted);
}
/* 手机档：事件名列宽收窄，给数据列留空间 */
@media (max-width: 640px) {
  .sse__event-name {
    min-width: 80px;
  }
}
.sse__event-name[data-tone='ok'] {
  color: var(--success);
}
.sse__event-name[data-tone='danger'] {
  color: var(--danger);
}
.sse__event-name[data-tone='warn'] {
  color: var(--warning);
}
.sse__event-data {
  color: var(--text);
  word-break: break-word;
  white-space: pre-wrap;
}
.sse__placeholder--events {
  border: none;
}
</style>
