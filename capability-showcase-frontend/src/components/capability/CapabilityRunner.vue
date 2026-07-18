<script setup lang="ts">
import { computed, onMounted, ref, toRef, watch } from 'vue'
import type { Capability, ParamSpec } from '../../types/catalog'
import type { FormValues } from '../../utils/validation'
import { useSessionStore } from '../../stores/session'
import { useHistoryStore } from '../../stores/history'
import { useBreakpoint } from '../../composables/useBreakpoint'
import { useCapabilityRun } from '../../composables/useCapabilityRun'
import { executionGate } from '../../utils/gate'
import { toCurl } from '../../utils/curl'
import { tryParseJson } from '../../utils/json'
import CapabilityHeader from './CapabilityHeader.vue'
import DynamicForm from '../form/DynamicForm.vue'
import ResponseViewer from './ResponseViewer.vue'
import SseConsole from './SseConsole.vue'
import SseStageConsole from './SseStageConsole.vue'
import SseEventTimeline from './SseEventTimeline.vue'
import CopyButton from '../common/CopyButton.vue'

const props = defineProps<{ cap: Capability }>()
const emit = defineEmits<{ result: [payload: { cap: Capability; data: unknown; status: number | null }] }>()

const session = useSessionStore()
const history = useHistoryStore()
const capRef = toRef(props, 'cap')
const run = useCapabilityRun(capRef)

const values = ref<FormValues>({})
const formRef = ref<InstanceType<typeof DynamicForm> | null>(null)
const confirmed = ref(false)
const showCurl = ref(false)
const { isPhone } = useBreakpoint()
const resEl = ref<HTMLElement | null>(null)

const isDanger = computed(() => !props.cap.executableByDefault)
const gate = computed(() => {
  // 合并凭证模式 + 有效 scopes：Bearer 缺 scope 精确禁用，api-key 保持 unknown（反应式提示）。
  const pc = session.permissionContext()
  return executionGate(props.cap, {
    hasApiKey: session.hasCredential,
    confirmed: confirmed.value,
    credentialMode: pc.credentialMode,
    effectiveScopes: pc.effectiveScopes,
  })
})
const curlText = computed(() => toCurl(props.cap, values.value, { edgeBaseUrl: session.edgeBaseUrl }))

/** 能否载入示例：有整体 example，或任一参数带 defaultValue / example。 */
const canLoadExample = computed(
  () =>
    !!props.cap.example ||
    props.cap.params.some(
      (p) => p.example != null || (p.defaultValue !== undefined && p.defaultValue !== null),
    ),
)

/** 示例预设：优先 examples[]（多示例 chip），否则退化为单个 example，无则空。 */
const presets = computed(() => {
  const list = props.cap.examples
  if (Array.isArray(list) && list.length) {
    return list.filter((e) => e && typeof e.body === 'string')
  }
  if (props.cap.example) return [{ label: '载入示例', body: props.cap.example }]
  return []
})
/** 多于一个预设时渲染 chip 行；否则沿用单个「载入示例」按钮。 */
const hasMultiplePresets = computed(() => presets.value.length > 1)

/** 把参数级默认 / 示例 + 请求体（JSON 字符串，缺省取 cap.example）合并为一组表单值（仅回填，不改 run）。 */
function buildExampleValues(bodyJson?: string): FormValues {
  const next: FormValues = { ...values.value }
  for (const p of props.cap.params) {
    if (p.type === 'file') continue // 文件无法程序化回填
    if (p.example != null) next[p.name] = coerceForField(p, p.example)
    else if (p.defaultValue !== undefined && p.defaultValue !== null) next[p.name] = p.defaultValue
  }
  const parsed = tryParseJson(bodyJson ?? props.cap.example)
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
    const body = parsed as Record<string, unknown>
    for (const p of props.cap.params) {
      if (p.type === 'file') continue
      if (Object.prototype.hasOwnProperty.call(body, p.name)) {
        next[p.name] = coerceForField(p, body[p.name])
      }
    }
  }
  return next
}

/** JSON/array/object 字段的表单模型是字符串；其余原样。 */
function coerceForField(p: ParamSpec, value: unknown): unknown {
  if (p.type === 'json' || p.type === 'array' || p.type === 'object') {
    if (typeof value === 'string') return value
    try {
      return JSON.stringify(value, null, 2)
    } catch {
      return String(value)
    }
  }
  return value
}

function loadExample(bodyJson?: string): void {
  if (bodyJson === undefined && !canLoadExample.value) return
  values.value = buildExampleValues(bodyJson)
}

/** 点选某个命名预设：把其请求体载入表单。 */
function loadPreset(preset: { body: string }): void {
  loadExample(preset.body)
}

function newHistoryId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `h-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

// 执行结束（成功/失败/中断）后写一条内存历史。用 watch 捕获终态，
// 兼容一次性调用与 SSE（后者 run() 在流开始即返回，终态稍后到达）。
let pendingRecord = false
let recordSnapshot: FormValues = {}

watch(
  () => run.phase.value,
  (phase) => {
    const terminal =
      phase === 'success' || phase === 'done' || phase === 'error' || phase === 'aborted'
    if (!terminal || !pendingRecord) return
    pendingRecord = false
    history.record({
      id: newHistoryId(),
      capId: props.cap.id,
      method: props.cap.method,
      path: props.cap.path,
      status: run.httpStatus.value,
      elapsedMs: run.elapsedMs.value,
      traceId: run.traceId.value,
      at: Date.now(),
      params: recordSnapshot,
      ok: phase === 'success' || phase === 'done',
    })
  },
)

/** 向上找最近的真正滚动容器（overflow-y auto/scroll）。 */
function nearestScroller(node: HTMLElement | null): HTMLElement | null {
  for (let el = node?.parentElement ?? null; el; el = el.parentElement) {
    const oy = getComputedStyle(el).overflowY
    if (oy === 'auto' || oy === 'scroll') return el
  }
  return null
}

/**
 * 手机档执行后把响应区滚入视口。**不能用 scrollIntoView(block:'start')**：
 * 它会连 overflow:hidden 的 app-shell 一起滚，把顶栏(☰)顶出屏外且无法自行恢复
 * （真机 bug：结果出来后菜单栏消失、刷新才回来）。只滚最近的滚动容器（.app-main）。
 */
function scrollResponseIntoView(): void {
  const el = resEl.value
  if (!el) return
  const scroller = nearestScroller(el)
  if (!scroller) {
    // 兜底（jsdom 无布局时走此路径，测试据此断言）
    el.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
    return
  }
  const top =
    el.getBoundingClientRect().top - scroller.getBoundingClientRect().top + scroller.scrollTop - 8
  scroller.scrollTo({ top, behavior: 'smooth' })
}

async function execute(): Promise<void> {
  const errs = formRef.value?.validate() ?? {}
  if (Object.keys(errs).length > 0) return
  recordSnapshot = { ...values.value }
  pendingRecord = true
  await run.run(values.value, { confirmed: confirmed.value })
  // 手机档单列堆叠：响应区在表单下方屏外，执行返回（SSE 为流开始）后自动滚过去。
  if (isPhone.value) scrollResponseIntoView()
  if (run.phase.value === 'success' && run.result.value) {
    emit('result', {
      cap: props.cap,
      data: run.result.value.data,
      status: run.result.value.status,
    })
  }
}

// 历史抽屉「重放」写入 pendingReplay 并跳转；本组件挂载时消费回填，形成闭环。
// 详情页对每个能力用 :key=cap.id 重挂载，故 onMounted 亦覆盖能力切换。
onMounted(() => {
  const replay = history.consumeReplay(props.cap.id)
  if (replay) values.value = { ...replay }
})

function onKeydown(e: KeyboardEvent): void {
  if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
    e.preventDefault()
    if (!run.running.value && gate.value.allowed) void execute()
  } else if (e.key === 'Escape' && run.running.value) {
    e.preventDefault()
    run.abort()
  }
}
</script>

<template>
  <div class="runner page page--wide" @keydown="onKeydown">
    <CapabilityHeader :cap="cap" />

    <div class="runner__grid">
      <!-- 请求区 -->
      <section class="runner__pane runner__req" aria-label="请求参数">
        <h2 class="runner__h">请求</h2>
        <DynamicForm ref="formRef" v-model="values" :params="cap.params" :id-prefix="cap.id" />

        <div class="runner__actions">
          <template v-if="run.running.value">
            <button type="button" class="btn btn--danger" @click="run.abort()">停止 (Esc)</button>
          </template>
          <template v-else>
            <label v-if="isDanger" class="runner__confirm">
              <input v-model="confirmed" type="checkbox" />
              我已知晓这是破坏性操作
            </label>
            <button
              v-if="isDanger"
              type="button"
              class="btn btn--danger"
              :disabled="!gate.allowed"
              @click="execute"
            >
              危险执行
            </button>
            <button
              v-else
              type="button"
              class="btn"
              :class="run.isSse.value ? 'btn--stream' : 'btn--primary'"
              :disabled="!gate.allowed"
              @click="execute"
            >
              {{ run.isSse.value ? '开始流式 ⌘⏎' : '执行 ⌘⏎' }}
            </button>
            <button type="button" class="btn btn--ghost" @click="showCurl = !showCurl">
              {{ showCurl ? '隐藏' : '预览' }} curl
            </button>
            <button
              v-if="!hasMultiplePresets"
              type="button"
              class="btn btn--ghost"
              :disabled="!canLoadExample"
              title="用目录示例 / 默认值填充表单"
              @click="loadExample()"
            >
              载入示例
            </button>
          </template>
        </div>

        <div
          v-if="!run.running.value && hasMultiplePresets"
          class="runner__presets"
          role="group"
          aria-label="示例预设"
        >
          <span class="runner__presets-label">示例：</span>
          <button
            v-for="(p, i) in presets"
            :key="i"
            type="button"
            class="btn btn--ghost btn--chip"
            :title="p.description || '一键把此示例载入表单'"
            @click="loadPreset(p)"
          >
            {{ p.label }}
          </button>
        </div>

        <p v-if="!gate.allowed && gate.reason" class="runner__reason" role="status">
          {{ gate.reason }}
        </p>
        <p v-else-if="gate.hint" class="runner__hint" role="status">{{ gate.hint }}</p>

        <div v-if="showCurl" class="runner__curl">
          <div class="runner__curl-bar">
            <span>curl 预览（凭证以占位符呈现，不含明文）</span>
            <CopyButton :text="curlText" compact />
          </div>
          <pre class="runner__curl-code">{{ curlText }}</pre>
        </div>
      </section>

      <!-- 响应区 -->
      <section ref="resEl" class="runner__pane runner__res" aria-label="响应">
        <h2 class="runner__h">响应</h2>
        <div class="runner__res-body">
          <SseConsole
            v-if="run.isSse.value && run.streamShape.value === 'token'"
            :tokens="run.sse.tokens"
            :events="run.sse.events"
            :status="run.sse.status"
            :note="run.sse.note"
            :error="run.errorMessage.value"
            :elapsed-ms="run.elapsedMs.value"
            :trace-id="run.traceId.value"
          />
          <SseStageConsole
            v-else-if="run.isSse.value && run.isReflexion.value"
            :events="run.sse.events"
            :status="run.sse.status"
            :note="run.sse.note"
            :error="run.errorMessage.value"
            :elapsed-ms="run.elapsedMs.value"
            :trace-id="run.traceId.value"
          />
          <SseEventTimeline
            v-else-if="run.isSse.value"
            :events="run.sse.events"
            :status="run.sse.status"
            :note="run.sse.note"
            :error="run.errorMessage.value"
            :elapsed-ms="run.elapsedMs.value"
            :trace-id="run.traceId.value"
          />
          <ResponseViewer
            v-else
            :phase="run.phase.value"
            :data="run.result.value?.data"
            :text="run.result.value?.text"
            :http-status="run.httpStatus.value"
            :trace-id="run.traceId.value"
            :elapsed-ms="run.elapsedMs.value"
            :error="run.errorMessage.value"
          />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.runner {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  min-height: 0;
  /* 关键：在 flex 列父容器（.mon/.mm/.ch/.ie 等）里，带 auto 横向 margin 的子项若无确定宽度，
     会收缩到内容 max-content 宽度，导致运行器宽度随内容浮动、页页不一。显式 width:100% 让它
     撑满父容器（由 page/page--wide 的 max-width 封顶），宽度不再受内容影响。 */
  width: 100%;
}
.runner__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--space-4);
  align-items: start;
}
.runner__pane {
  background: var(--glass-bg-strong);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.4);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.4);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-glass);
  min-width: 0;
}
.runner__req {
  padding: var(--space-4);
}
.runner__h {
  font-size: var(--fs-xs);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--text-subtle);
  margin-bottom: var(--space-3);
}
.runner__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  margin-top: var(--space-2);
}
.runner__confirm {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-sm);
  color: var(--danger);
}
.runner__presets {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-top: var(--space-2);
}
.runner__presets-label {
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.btn--chip {
  padding: 3px 10px;
  font-size: var(--fs-sm);
  border-radius: 999px;
}
.runner__reason {
  margin-top: var(--space-2);
  font-size: var(--fs-sm);
  color: var(--warning);
}
.runner__hint {
  margin-top: var(--space-2);
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.runner__curl {
  margin-top: var(--space-3);
  border: 1px solid var(--code-border);
  border-radius: var(--radius);
  overflow: hidden;
}
.runner__curl-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 10px;
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  background: var(--surface-2);
}
.runner__curl-code {
  margin: 0;
  padding: var(--space-3);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text);
  background: var(--code-bg);
  overflow-x: auto;
}
.runner__res {
  display: flex;
  flex-direction: column;
  padding: var(--space-4) var(--space-4) 0;
}
.runner__res-body {
  flex: 1;
  min-height: 320px;
  border: 1px solid var(--code-border);
  border-radius: var(--radius);
  margin-bottom: var(--space-4);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: var(--code-bg);
}

/* 桌面：响应面随请求区滚动而吸顶（内部视图各自滚动）；≤1023 取消吸顶、维持堆叠。 */
@media (min-width: 1024px) {
  .runner__res {
    position: sticky;
    top: var(--space-4);
    max-height: calc(100dvh - var(--header-h) - var(--space-6));
  }
}
@media (max-width: 1023px) {
  .runner__grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
/* 手机档：执行主按钮独占一行易点，响应区最小高度收窄少占首屏 */
@media (max-width: 640px) {
  .runner__actions .btn--primary,
  .runner__actions .btn--stream,
  .runner__actions .btn--danger {
    flex: 1 1 100%;
  }
  .runner__res-body {
    min-height: 200px;
  }
}
</style>
