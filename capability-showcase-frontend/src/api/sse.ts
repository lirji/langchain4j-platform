import type { Capability } from '../types/catalog'
import type { RunContext } from '../types/api'
import type { FormValues } from '../utils/validation'
import { assembleRequest, AUTH_HEADER } from './client'
import { ApiError, isAbortError } from './errors'
import { useAuthStore } from '../stores/auth'
import { tryParseJson } from '../utils/json'

/** 一个解析出的 SSE 事件。event 缺省为 'message'。 */
export interface SseEvent {
  event: string
  data: string
  id?: string
}

/**
 * SSE 流形态：
 * - `'token'` 逐字流（后端逐 token 以无名 `message` 事件下发，走 SseConsole 拼接视图）。
 * - `'stage'` 阶段事件流（后端按阶段发命名事件，如反思编排的 attempt-start/answer/critique/done，
 *   走 SseStageConsole 按轮次渲染）。
 *
 * 判定依据 = manifest 的 `sseEvents` 是否含 `'message'`。向后兼容：未声明 / 空 / 含 message → `token`
 * （保持现状，不影响 chat.stream 等真 token 流）；仅「非空且不含 message」→ `stage`。
 */
export type SseStreamShape = 'token' | 'stage'

export function sseStreamShape(cap: Capability): SseStreamShape {
  const events = cap.sseEvents
  if (!events || events.length === 0) return 'token'
  return events.includes('message') ? 'token' : 'stage'
}

/**
 * stage 流的渲染子类判定：`sseEvents` 同时含 `answer` + `critique` 语义事件 → 反思式
 * （走 SseStageConsole 的答案/评分渲染）；否则为通用命名事件流（如任务状态快照流，走 SseEventTimeline）。
 * 声明式判据（按事件语义，不 hardcode 能力 id），便于将来新增其它 stage 流时自动归类。
 */
export function isReflexionStream(cap: Capability): boolean {
  const e = cap.sseEvents
  return !!e && e.includes('answer') && e.includes('critique')
}

/**
 * 增量 SSE 帧解析器（纯逻辑，不依赖 fetch，便于单测）。
 *
 * 处理要点：
 * - 以行为单位解析（`\n` 分行），行尾 `\r` 归一（CRLF → LF）；
 * - 空行 = 帧边界，派发累积的 event/data；
 * - `:` 开头为注释/心跳，忽略；
 * - `field: value` 中 value 的「一个」前导空格剥除（SSE 规范）；
 * - 跨 push 的半帧通过内部 buffer 拼接；
 * - flush() 补发末帧（流结束但无收尾空行）。
 */
export class SseParser {
  private buffer = ''
  private eventType = ''
  private dataLines: string[] = []
  private lastId: string | undefined

  push(chunk: string): SseEvent[] {
    this.buffer += chunk
    const out: SseEvent[] = []
    let nl: number
    while ((nl = this.buffer.indexOf('\n')) >= 0) {
      let line = this.buffer.slice(0, nl)
      this.buffer = this.buffer.slice(nl + 1)
      if (line.endsWith('\r')) line = line.slice(0, -1)
      this.processLine(line, out)
    }
    return out
  }

  /** 流结束时调用：处理残留的半行并派发未收尾的末帧。 */
  flush(): SseEvent[] {
    const out: SseEvent[] = []
    if (this.buffer.length > 0) {
      let line = this.buffer
      this.buffer = ''
      if (line.endsWith('\r')) line = line.slice(0, -1)
      // 残留一定不是空行分隔（否则早已在 push 中派发），按非空行累积。
      this.processLine(line, out)
    }
    this.dispatch(out)
    return out
  }

  private processLine(line: string, out: SseEvent[]): void {
    if (line === '') {
      this.dispatch(out)
      return
    }
    if (line.startsWith(':')) return // 注释 / 心跳
    const colon = line.indexOf(':')
    let field: string
    let value: string
    if (colon === -1) {
      field = line
      value = ''
    } else {
      field = line.slice(0, colon)
      value = line.slice(colon + 1)
      if (value.startsWith(' ')) value = value.slice(1) // 仅剥一个前导空格
    }
    switch (field) {
      case 'event':
        this.eventType = value
        break
      case 'data':
        this.dataLines.push(value)
        break
      case 'id':
        this.lastId = value
        break
      case 'retry':
      default:
        break
    }
  }

  private dispatch(out: SseEvent[]): void {
    if (this.eventType === '' && this.dataLines.length === 0) return
    out.push({
      event: this.eventType || 'message',
      data: this.dataLines.join('\n'),
      id: this.lastId,
    })
    this.eventType = ''
    this.dataLines = []
  }
}

export type SseDoneReason = 'complete' | 'abort' | 'error'

/** SSE 消费回调。消费端把 event 名映射为语义：message=逐 token，其余为命名事件。 */
export interface SseHandlers {
  onStart?: () => void
  onOpen?: (res: Response) => void
  onEvent?: (ev: SseEvent) => void
  onToken?: (token: string) => void
  onNamed?: (name: string, data: string) => void
  onError?: (err: unknown) => void
  onDone?: (reason: SseDoneReason) => void
}

function dispatchEvent(ev: SseEvent, h: SseHandlers): void {
  h.onEvent?.(ev)
  const name = ev.event || 'message'
  if (name === 'message') h.onToken?.(ev.data)
  else h.onNamed?.(name, ev.data)
}

/**
 * 消费一个 ReadableStream（fetch().body）为 SSE 事件。
 * - 正常读完 → onDone('complete')
 * - AbortError → onDone('abort')（不触发 onError）
 * - 其它异常 → onError(err) + onDone('error')
 */
export async function consumeSseStream(
  body: ReadableStream<Uint8Array>,
  handlers: SseHandlers,
): Promise<void> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  const parser = new SseParser()
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      if (value) {
        const text = decoder.decode(value, { stream: true })
        if (text) for (const ev of parser.push(text)) dispatchEvent(ev, handlers)
      }
    }
    const tail = decoder.decode()
    if (tail) for (const ev of parser.push(tail)) dispatchEvent(ev, handlers)
    for (const ev of parser.flush()) dispatchEvent(ev, handlers)
    handlers.onDone?.('complete')
  } catch (err) {
    if (isAbortError(err)) {
      handlers.onDone?.('abort')
      return
    }
    handlers.onError?.(err)
    handlers.onDone?.('error')
  }
}

/**
 * 发起一次 SSE 调用：fetch + ReadableStream + TextDecoder（EventSource 无法带 X-Api-Key header）。
 * 返回 { abort } 供上层取消（触发 onDone('abort')）。
 */
export function streamCapability(
  cap: Capability,
  values: FormValues,
  ctx: RunContext,
  handlers: SseHandlers,
): { abort: () => void } {
  const controller = new AbortController()
  // 记录最近事件 id：中途断流后用 Last-Event-ID 续订，让服务端从断点续发、不重复已消费的 token（DR-1）。
  let lastEventId: string | undefined
  let resubscribed = false

  // 用指定令牌 + 可选 Last-Event-ID 重建请求并发起（复用同一 controller，使上层 abort() 始终有效）。
  const fetchWith = (accessToken?: string, lastId?: string): Promise<Response> => {
    const plan = assembleRequest(cap, values, {
      ...ctx,
      ...(accessToken ? { accessToken } : {}),
      signal: controller.signal,
    })
    const headers = lastId ? { ...plan.headers, 'Last-Event-ID': lastId } : plan.headers
    return fetch(plan.url, {
      method: plan.method,
      headers,
      body: plan.body,
      signal: controller.signal,
    })
  }

  const firstPlan = assembleRequest(cap, values, { ...ctx, signal: controller.signal })
  const usedBearer = Boolean(firstPlan.headers[AUTH_HEADER])

  // 追踪 lastEventId，其余事件透传给上层（用户 onToken/onNamed 在续订前后连续收到）。
  const tracking: SseHandlers = {
    ...handlers,
    onEvent: (ev) => {
      if (ev.id) lastEventId = ev.id
      handlers.onEvent?.(ev)
    },
  }

  // 消费一次流并归结 done 原因（onError 暂存、不立即上抛——是否上抛由是否续订成功决定）。
  const consumeOnce = (body: ReadableStream<Uint8Array>): Promise<{ reason: SseDoneReason; error?: unknown }> =>
    new Promise((resolve) => {
      let captured: unknown
      void consumeSseStream(body, {
        ...tracking,
        onError: (e) => {
          captured = e
        },
        onDone: (reason) => resolve({ reason, error: captured }),
      })
    })

  handlers.onStart?.()
  void (async () => {
    try {
      let res = await fetchWith()
      // 握手 401 且本次用的是会话 Bearer → 单飞续期 → 用新令牌重建请求，复用同一 controller 重试一次。
      if (res.status === 401 && usedBearer) {
        const newToken = await useAuthStore().refresh()
        if (newToken) res = await fetchWith(newToken)
      }
      if (!res.ok) {
        // body 只能读一次：先取文本，再尝试 JSON。非 JSON 错误体保留原文，不丢失。
        const errText = await res.text().catch(() => '')
        const parsed = tryParseJson(errText)
        const errBody: unknown = parsed !== undefined ? parsed : errText || null
        handlers.onError?.(new ApiError(res.status, `HTTP ${res.status} ${res.statusText}`.trim(), errBody))
        handlers.onDone?.('error')
        return
      }
      handlers.onOpen?.(res)
      if (!res.body) {
        handlers.onError?.(new Error('响应无可读流（body 为空）。'))
        handlers.onDone?.('error')
        return
      }

      let { reason, error } = await consumeOnce(res.body)
      // 中途断流（error）且用的是 Bearer：可能是流跨越了 access token 有效期。续期后带 Last-Event-ID 续订一次。
      // 局限：SSE 无「因鉴权关闭」的协议信号，干净 EOF(complete) 与鉴权关闭不可区分，故仅在 error 关闭时尝试。
      if (reason === 'error' && usedBearer && !resubscribed) {
        resubscribed = true
        const newToken = await useAuthStore().refresh()
        if (newToken) {
          const res2 = await fetchWith(newToken, lastEventId)
          if (res2.ok && res2.body) {
            handlers.onOpen?.(res2)
            ;({ reason, error } = await consumeOnce(res2.body))
          }
        }
      }

      if (reason === 'error') handlers.onError?.(error)
      handlers.onDone?.(reason)
    } catch (err) {
      if (isAbortError(err)) {
        handlers.onDone?.('abort')
        return
      }
      handlers.onError?.(err)
      handlers.onDone?.('error')
    }
  })()

  return { abort: () => controller.abort() }
}
