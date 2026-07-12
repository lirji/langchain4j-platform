import type { Capability } from '../types/catalog'

/** 业务/HTTP 错误的统一载体。 */
export class ApiError extends Error {
  readonly status: number
  readonly body?: unknown

  constructor(status: number, message: string, body?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.body = body
  }
}

/** 判断是否为 fetch/流被中止（AbortController.abort()）。 */
export function isAbortError(err: unknown): boolean {
  return (
    !!err &&
    typeof err === 'object' &&
    (err as { name?: string }).name === 'AbortError'
  )
}

/** 从错误响应体里尽量提取一句可读的服务端消息。 */
function extractServerMessage(body: unknown): string | undefined {
  if (body == null) return undefined
  if (typeof body === 'string') return body.trim() || undefined
  if (typeof body === 'object') {
    const b = body as Record<string, unknown>
    for (const key of ['message', 'error', 'detail', 'reason']) {
      const v = b[key]
      if (typeof v === 'string' && v.trim()) return v.trim()
    }
  }
  return undefined
}

/**
 * 把异常翻译成「人话」，特别处理 403 scope、409 冲突、401 无 Key、429 限流。
 */
export function humanizeError(err: unknown, cap?: Capability): string {
  if (isAbortError(err)) return '已取消本次请求。'

  if (err instanceof ApiError) {
    const server = extractServerMessage(err.body)
    switch (err.status) {
      case 400:
        return `请求参数有误（400）。${server ?? '请检查表单填写。'}`
      case 401:
        return 'API Key 缺失或无效（401）。请在顶栏填写有效的 X-Api-Key。'
      case 403: {
        const scopes = cap?.requiredScopes?.length
          ? `（需要 scope：${cap.requiredScopes.join(', ')}）`
          : ''
        return `无权限（403）。当前 API Key 缺少所需 scope${scopes}。请更换具备该 scope 的 Key。`
      }
      case 404:
        return `未找到（404）。资源 ID 可能不存在，或该能力所属特性未启用。${server ?? ''}`.trim()
      case 409:
        return `冲突（409）。可能是重复的 taskId、租约（lease）被他人持有，或任务已处于终态。${
          server ?? ''
        }`.trim()
      case 422:
        // 业务门禁结果（如 /eval/gate 检出回归），非网络/参数错误。响应体含门禁明细。
        return `门禁未通过（422）。这是业务判定结果（如评测检出回归），响应体为门禁明细。${
          server ?? ''
        }`.trim()
      case 429:
        return '请求过于频繁（429），已触发网关限流。请稍后再试。'
      case 500:
      case 502:
      case 503:
        return `服务端错误（${err.status}）。${server ?? '请稍后重试或检查后端服务是否可用。'}`
      default:
        return `请求失败（${err.status}）。${server ?? ''}`.trim()
    }
  }

  if (err instanceof TypeError) {
    // fetch 网络层错误通常抛 TypeError
    return '网络请求失败：无法连接到服务。请确认后端 / 网关已启动，且代理配置正确。'
  }
  return err instanceof Error ? err.message : String(err)
}
