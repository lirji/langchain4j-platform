/**
 * 反思编排（`POST /agent/reflexive/stream`）阶段事件流的纯解析器。
 *
 * 把命名事件序列折叠成可渲染的视图模型。SSE 事件契约（对齐后端 ReflexionController /
 * ReflexionService + platform-protocol 的 ReflexionAttempt / ReflexionReply）：
 * - `attempt-start` → `{"n":N}`
 * - `answer`        → `{"n":N,"answer":"<完整答案>"}`
 * - `critique`      → ReflexionAttempt `{n,answer,aggregate,correctness,completeness,clarity,mainIssue}`
 *                     （分值 0-1；`mainIssue:"n/a"` 表示已足够好）
 * - `done`          → ReflexionReply `{question,finalAnswer,attempts[],acceptedByThreshold,tenantId}`
 *                     （`acceptedByThreshold` = 是否收敛）
 * - `error`         → `{"error":".."}`（仅失败时）
 *
 * 健壮性铁律：解析失败 / 字段缺失一律「降级、绝不抛」——能定位轮次的挂 {@link ReflexionRound.rawFallback}，
 * 否则进 {@link ReflexionStreamView.unknown}，交由原始「事件流」兜底显示。
 */
import type { SseEvent } from '../../api/sse'
import { tryParseJson } from '../../utils/json'

export type RoundStatus = 'answering' | 'critiquing' | 'scored'

export interface ReflexionScores {
  aggregate?: number
  correctness?: number
  completeness?: number
  clarity?: number
}

export interface ReflexionRound {
  n: number
  answer?: string
  scores?: ReflexionScores
  mainIssue?: string
  status: RoundStatus
  /** 该轮某事件解析失败时原样保留的负载，供 UI 兜底显示。 */
  rawFallback?: string
}

export interface ReflexionStreamView {
  rounds: ReflexionRound[]
  /** 当前 / 最终答案：done 的 finalAnswer 优先，否则最新一轮的 answer。 */
  currentAnswer: string
  /** 是否收敛（达阈值）；null = 尚未 finalize。 */
  converged: boolean | null
  /** 是否已收到 done 事件。 */
  finalized: boolean
  /** error 事件文本（若有）。 */
  errorText?: string
  /** 未识别 / 无法定位轮次的事件，供原始「事件流」兜底。 */
  unknown: SseEvent[]
}

function asRecord(v: unknown): Record<string, unknown> | undefined {
  return v && typeof v === 'object' && !Array.isArray(v)
    ? (v as Record<string, unknown>)
    : undefined
}
function numOr(v: unknown): number | undefined {
  return typeof v === 'number' && Number.isFinite(v) ? v : undefined
}
function strOr(v: unknown): string | undefined {
  return typeof v === 'string' ? v : undefined
}

export function parseReflexionEvents(events: SseEvent[]): ReflexionStreamView {
  const byN = new Map<number, ReflexionRound>()
  const order: number[] = []
  const unknown: SseEvent[] = []
  let finalAnswer: string | undefined
  let converged: boolean | null = null
  let finalized = false
  let errorText: string | undefined

  function round(n: number): ReflexionRound {
    let r = byN.get(n)
    if (!r) {
      r = { n, status: 'answering' }
      byN.set(n, r)
      order.push(n)
    }
    return r
  }

  function scoresOf(o: Record<string, unknown>): ReflexionScores {
    return {
      aggregate: numOr(o.aggregate),
      correctness: numOr(o.correctness),
      completeness: numOr(o.completeness),
      clarity: numOr(o.clarity),
    }
  }

  for (const ev of events) {
    const name = ev.event || 'message'
    const data = asRecord(tryParseJson(ev.data))
    const n = data ? numOr(data.n) : undefined

    if (name === 'attempt-start') {
      if (n != null) round(n)
      else unknown.push(ev)
      continue
    }

    if (name === 'answer') {
      if (data && n != null) {
        const r = round(n)
        const ans = strOr(data.answer)
        if (ans != null) {
          r.answer = ans
          r.status = 'critiquing'
        } else {
          r.rawFallback = ev.data
        }
      } else {
        unknown.push(ev)
      }
      continue
    }

    if (name === 'critique') {
      if (data && n != null) {
        const r = round(n)
        r.status = 'scored'
        r.scores = scoresOf(data)
        const issue = strOr(data.mainIssue)
        if (issue != null) r.mainIssue = issue
        if (r.answer == null) {
          const a = strOr(data.answer)
          if (a != null) r.answer = a
        }
      } else {
        unknown.push(ev)
      }
      continue
    }

    if (name === 'done') {
      finalized = true
      if (data) {
        const fa = strOr(data.finalAnswer)
        if (fa != null) finalAnswer = fa
        if (typeof data.acceptedByThreshold === 'boolean') converged = data.acceptedByThreshold
        // 逐轮 critique 缺失时，用 attempts[] 兜底补齐轮次轨迹。
        if (order.length === 0 && Array.isArray(data.attempts)) {
          for (const a of data.attempts) {
            const ar = asRecord(a)
            if (!ar) continue
            const an = numOr(ar.n)
            if (an == null) continue
            const r = round(an)
            r.status = 'scored'
            r.answer = strOr(ar.answer) ?? r.answer
            r.scores = scoresOf(ar)
            const issue = strOr(ar.mainIssue)
            if (issue != null) r.mainIssue = issue
          }
        }
      }
      continue
    }

    if (name === 'error') {
      errorText = (data && strOr(data.error)) ?? ev.data
      continue
    }

    // message 或其它未识别事件名 → 原始兜底
    unknown.push(ev)
  }

  order.sort((a, b) => a - b)
  const rounds = order.map((k) => byN.get(k)!)
  const latestAnswer = [...rounds].reverse().find((r) => r.answer != null)?.answer
  const currentAnswer = finalAnswer ?? latestAnswer ?? ''

  return { rounds, currentAnswer, converged, finalized, errorText, unknown }
}
