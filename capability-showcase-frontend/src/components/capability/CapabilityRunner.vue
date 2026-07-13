<script setup lang="ts">
import { computed, onMounted, ref, toRef, watch } from 'vue'
import type { Capability, ParamSpec } from '../../types/catalog'
import type { FormValues } from '../../utils/validation'
import { useSessionStore } from '../../stores/session'
import { useHistoryStore } from '../../stores/history'
import { useCapabilityRun } from '../../composables/useCapabilityRun'
import { executionGate } from '../../utils/gate'
import { toCurl } from '../../utils/curl'
import { tryParseJson } from '../../utils/json'
import CapabilityHeader from './CapabilityHeader.vue'
import DynamicForm from '../form/DynamicForm.vue'
import ResponseViewer from './ResponseViewer.vue'
import SseConsole from './SseConsole.vue'
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

/** 把参数级默认 / 示例 + 整体 example（JSON 字符串）合并为一组表单值（仅回填，不改 run）。 */
function buildExampleValues(): FormValues {
  const next: FormValues = { ...values.value }
  for (const p of props.cap.params) {
    if (p.type === 'file') continue // 文件无法程序化回填
    if (p.example != null) next[p.name] = coerceForField(p, p.example)
    else if (p.defaultValue !== undefined && p.defaultValue !== null) next[p.name] = p.defaultValue
  }
  const parsed = tryParseJson(props.cap.example)
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

function loadExample(): void {
  if (!canLoadExample.value) return
  values.value = buildExampleValues()
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

async function execute(): Promise<void> {
  const errs = formRef.value?.validate() ?? {}
  if (Object.keys(errs).length > 0) return
  recordSnapshot = { ...values.value }
  pendingRecord = true
  await run.run(values.value, { confirmed: confirmed.value })
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
              type="button"
              class="btn btn--ghost"
              :disabled="!canLoadExample"
              title="用目录示例 / 默认值填充表单"
              @click="loadExample"
            >
              载入示例
            </button>
          </template>
        </div>

        <p v-if="!gate.allowed && gate.reason" class="runner__reason" role="status">
          {{ gate.reason }}
        </p>
        <p v-else-if="gate.hint" class="runner__hint" role="status">{{ gate.hint }}</p>

        <div v-if="showCurl" class="runner__curl">
          <div class="runner__curl-bar">
            <span>curl 预览（API Key 以 <code>$API_KEY</code> 占位，不含明文）</span>
            <CopyButton :text="curlText" compact />
          </div>
          <pre class="runner__curl-code">{{ curlText }}</pre>
        </div>
      </section>

      <!-- 响应区 -->
      <section class="runner__pane runner__res" aria-label="响应">
        <h2 class="runner__h">响应</h2>
        <div class="runner__res-body">
          <SseConsole
            v-if="run.isSse.value"
            :tokens="run.sse.tokens"
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
</style>
