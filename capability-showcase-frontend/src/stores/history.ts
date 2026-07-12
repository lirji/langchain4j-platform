import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { FormValues } from '../utils/validation'

/**
 * 请求历史 store：仅会话级内存，绝不持久化。
 *
 * 安全约束：请求参数（params）可能含敏感内容（提示词、密钥片段、业务数据），
 * 因此整个历史只存内存，刷新即清空，不写 localStorage / sessionStorage。
 */
export interface HistoryEntry {
  /** 唯一 id（调用方生成，如 crypto.randomUUID / 时间戳+随机）。 */
  id: string
  capId: string
  method: string
  path: string
  status: number | null
  elapsedMs: number
  traceId: string | null
  /** 记录时刻（Date.now()）。 */
  at: number
  /** 入参快照，仅内存供重放；不落盘。 */
  params: FormValues
  ok: boolean
}

const MAX_ENTRIES = 50

export const useHistoryStore = defineStore('history', () => {
  /** 最新在前。 */
  const entries = ref<HistoryEntry[]>([])
  /** 待重放的入参：由历史抽屉写入，CapabilityRunner（批2）消费回填。 */
  const pendingReplay = ref<{ capId: string; params: FormValues } | null>(null)

  function record(entry: HistoryEntry): void {
    entries.value.unshift(entry)
    if (entries.value.length > MAX_ENTRIES) entries.value.length = MAX_ENTRIES
  }

  function clear(): void {
    entries.value = []
  }

  /** 请求重放：暂存入参，由目标能力详情页在挂载时消费。 */
  function requestReplay(capId: string, params: FormValues): void {
    pendingReplay.value = { capId, params }
  }

  /** 消费重放入参（命中同一 capId 才返回并清空）。供批2 Runner 使用。 */
  function consumeReplay(capId: string): FormValues | null {
    const p = pendingReplay.value
    if (p && p.capId === capId) {
      pendingReplay.value = null
      return p.params
    }
    return null
  }

  return { entries, pendingReplay, record, clear, requestReplay, consumeReplay }
})
