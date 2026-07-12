import type { SseEvent } from '../../api/sse'

/** 本会话内被追踪的一个异步任务（时间线节点）。 */
export interface TrackedTask {
  taskId: string
  kind?: string
  status: string
  updatedAt: string
  streaming?: boolean
  error?: string | null
  events: SseEvent[]
  /** SSE 订阅次数；>1 表示发生过重连（重新订阅）。 */
  subscribes?: number
  /** 最近一个 SSE 事件的 id —— 即 Last-Event-ID 断点续订检查点。 */
  lastEventId?: string
}
