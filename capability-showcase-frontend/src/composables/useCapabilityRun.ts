import { computed, onScopeDispose, reactive, ref, toValue, type MaybeRefOrGetter } from 'vue'
import type { Capability } from '../types/catalog'
import type { RunResult } from '../types/api'
import type { FormValues } from '../utils/validation'
import type { SseEvent } from '../api/sse'
import { isStreamingKind, runCapability } from '../api/client'
import { streamCapability, sseStreamShape, isReflexionStream } from '../api/sse'
import { ApiError, humanizeError, isAbortError } from '../api/errors'
import { executionGate } from '../utils/gate'
import { useSessionStore } from '../stores/session'

export type RunPhase = 'idle' | 'running' | 'success' | 'error' | 'streaming' | 'done' | 'aborted'
export type SseStatus = 'idle' | 'streaming' | 'done' | 'aborted' | 'error'

export interface SseState {
  /** 逐 token 拼接的转录文本（message 事件）。 */
  tokens: string
  /** 原始事件流（含命名事件），供"事件流"视图。 */
  events: SseEvent[]
  status: SseStatus
  /** blocked / grounding-warning 等旁路提示。 */
  note: string | null
}

const MAX_EVENTS = 2000

/** 单个能力的执行状态机：统一 json/multipart/none 的一次性调用与 sse 的流式调用。 */
export function useCapabilityRun(capSource: MaybeRefOrGetter<Capability>) {
  const session = useSessionStore()

  const phase = ref<RunPhase>('idle')
  const result = ref<RunResult | null>(null)
  const errorMessage = ref<string | null>(null)
  const httpStatus = ref<number | null>(null)
  const traceId = ref<string | null>(null)
  const elapsedMs = ref(0)

  const sse = reactive<SseState>({ tokens: '', events: [], status: 'idle', note: null })

  let controller: AbortController | null = null
  let streamHandle: { abort: () => void } | null = null
  let startedAt = 0

  const cap = computed(() => toValue(capSource))
  const isSse = computed(() => isStreamingKind(cap.value.requestKind))
  // 流形态：token 流走 SseConsole 拼接视图；stage 流（命名事件流）走专用渲染。
  const streamShape = computed(() => sseStreamShape(cap.value))
  // stage 流子类：反思式（含 answer+critique）走 SseStageConsole，其余走通用 SseEventTimeline。
  const isReflexion = computed(() => isReflexionStream(cap.value))
  const running = computed(() => phase.value === 'running' || phase.value === 'streaming')

  function resetOutputs(): void {
    result.value = null
    errorMessage.value = null
    httpStatus.value = null
    traceId.value = null
    elapsedMs.value = 0
    sse.tokens = ''
    sse.events = []
    sse.status = 'idle'
    sse.note = null
  }

  function reset(): void {
    abort()
    resetOutputs()
    phase.value = 'idle'
  }

  async function run(values: FormValues, opts: { confirmed?: boolean } = {}): Promise<void> {
    // 传入凭证模式 + 有效 scopes：Bearer 缺 scope 精确预判，api-key 保持 unknown（反应式）。
    const pc = session.permissionContext()
    const gate = executionGate(cap.value, {
      hasApiKey: session.hasCredential,
      confirmed: opts.confirmed,
      credentialMode: pc.credentialMode,
      effectiveScopes: pc.effectiveScopes,
    })
    if (!gate.allowed) {
      resetOutputs()
      errorMessage.value = gate.reason ?? '当前不可执行。'
      phase.value = 'error'
      return
    }
    resetOutputs()
    startedAt = Date.now()
    if (isSse.value) runStream(values)
    else await runOnce(values)
  }

  async function runOnce(values: FormValues): Promise<void> {
    phase.value = 'running'
    controller = new AbortController()
    try {
      const res = await runCapability(cap.value, values, session.runContext(controller.signal))
      result.value = res
      httpStatus.value = res.status
      traceId.value = res.traceId ?? null
      phase.value = 'success'
    } catch (e) {
      if (isAbortError(e)) {
        phase.value = 'aborted'
        errorMessage.value = '已取消本次请求。'
      } else {
        if (e instanceof ApiError) httpStatus.value = e.status
        errorMessage.value = humanizeError(e, cap.value)
        result.value = e instanceof ApiError && e.body != null
          ? { status: e.status, data: e.body, headers: new Headers() }
          : null
        phase.value = 'error'
      }
    } finally {
      elapsedMs.value = Date.now() - startedAt
    }
  }

  function runStream(values: FormValues): void {
    phase.value = 'streaming'
    sse.status = 'streaming'
    // 流内业务 error 事件：流可能正常收尾（onDone('complete')），但语义是失败，需据此改终态。
    let streamHadError = false
    streamHandle = streamCapability(cap.value, values, session.runContext(), {
      onOpen: (res) => {
        httpStatus.value = res.status
        traceId.value = res.headers.get('X-Trace-Id')
      },
      onToken: (token) => {
        sse.tokens += token
      },
      onEvent: (ev) => {
        if (sse.events.length < MAX_EVENTS) sse.events.push(ev)
      },
      onNamed: (name, data) => {
        if (name === 'error') {
          streamHadError = true
          errorMessage.value = data || '流式过程中发生错误。'
        } else if (name === 'blocked') {
          sse.note = `已被安全护栏拦截${data ? `：${data}` : ''}`
        } else if (name === 'grounding-warning') {
          sse.note = `接地校验警告${data ? `：${data}` : ''}`
        }
      },
      onError: (e) => {
        if (e instanceof ApiError) httpStatus.value = e.status
        errorMessage.value = humanizeError(e, cap.value)
      },
      onDone: (reason) => {
        elapsedMs.value = Date.now() - startedAt
        if (reason === 'abort') {
          sse.status = 'aborted'
          phase.value = 'aborted'
        } else if (reason === 'error' || streamHadError) {
          sse.status = 'error'
          phase.value = 'error'
        } else {
          sse.status = 'done'
          phase.value = 'done'
        }
      },
    })
  }

  function abort(): void {
    controller?.abort()
    controller = null
    streamHandle?.abort()
    streamHandle = null
  }

  onScopeDispose(abort)

  return {
    cap,
    phase,
    result,
    errorMessage,
    httpStatus,
    traceId,
    elapsedMs,
    sse,
    isSse,
    streamShape,
    isReflexion,
    running,
    run,
    abort,
    reset,
  }
}
