import type { Capability } from '../types/catalog'
import { AUTH_MODE } from '../config'

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

/** 凭证模式（与 stores/session 的 CredentialMode 同义；此处内联避免 store→errors 反向依赖）。 */
export type CredentialModeHint = 'bearer' | 'api-key' | 'none'

/** humanizeError 的可选上下文：按凭证模式给出更贴切的 401/403 文案。 */
export interface HumanizeOptions {
  credentialMode?: CredentialModeHint
}

/**
 * 是否为乐观锁冲突：契约升级后陈旧写为 HTTP 412 `precondition_failed`；同时兼容旧的 409 `version_conflict`。
 * （缺 If-Match 的 428 precondition_required 不属此列——那是客户端漏带版本，不进冲突弹窗。）
 */
export function isVersionConflict(err: unknown): boolean {
  if (err instanceof ApiError && err.status === 412) return true
  const code = apiErrorCode(err)
  return code === 'precondition_failed' || code === 'version_conflict'
}

/** 从 ApiError 响应体里提取业务判别码（`{error,message}` 的 error 值）；无则 null。供冲突弹窗分流。 */
export function apiErrorCode(err: unknown): string | null {
  if (err instanceof ApiError && err.body && typeof err.body === 'object') {
    const c = (err.body as Record<string, unknown>).error
    return typeof c === 'string' ? c : null
  }
  return null
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
 *
 * `opts.credentialMode` 让 401/403 文案区分"登录会话（Bearer）"与"手输 API Key"两种身份；
 * 不传则回落到既有（api-key 视角）文案，保既有调用点行为不变。
 * 管理域 409 家族（version_conflict/last_admin/role_in_use/username_taken/role_exists）与 503
 * rbac_writes_disabled：后端 message 已是中文人话，直接透传。
 */
export function humanizeError(err: unknown, cap?: Capability, opts?: HumanizeOptions): string {
  if (isAbortError(err)) return '已取消本次请求。'

  const mode = opts?.credentialMode
  if (err instanceof ApiError) {
    const server = extractServerMessage(err.body)
    const code = apiErrorCode(err)
    switch (err.status) {
      case 400:
        return `请求参数有误（400）。${server ?? '请检查表单填写。'}`
      case 401:
        // Bearer：authorizedFetch 续期失败后到此；非 Bearer：按 AUTH_MODE 出对应文案（oidc 不提 api-key）。
        if (mode === 'bearer') {
          return AUTH_MODE === 'apikey' ? '登录已过期（401），请重新登录。' : '登录已过期（401），请重新用 Casdoor 登录。'
        }
        if (AUTH_MODE === 'oidc') return '未登录或登录已过期（401），请用 Casdoor 登录。'
        return 'API Key 缺失或无效（401）。请在顶栏填写有效的 X-Api-Key。'
      case 403: {
        const scopes = cap?.requiredScopes?.length ? `（需要 scope：${cap.requiredScopes.join(', ')}）` : ''
        if (mode === 'bearer') {
          return `无权限（403）。当前账号缺少所需 scope${scopes}（由角色授予），请联系管理员授予对应角色。`
        }
        if (AUTH_MODE === 'oidc') {
          return `无权限（403）。当前账号缺少所需 scope${scopes}（由角色授予），请联系管理员授予对应角色。`
        }
        return `无权限（403）。当前 API Key 缺少所需 scope${scopes}。请更换具备该 scope 的 Key。`
      }
      case 404:
        return `未找到（404）。资源 ID 可能不存在，或该能力所属特性未启用。${server ?? ''}`.trim()
      case 409:
        // 管理域业务冲突（version_conflict 等）后端 message 已足够清晰，直接透传。
        if (code && server) return server
        return `冲突（409）。可能是重复的 taskId、租约（lease）被他人持有，或任务已处于终态。${
          server ?? ''
        }`.trim()
      // 前向兼容：若后端未来把陈旧写升级为 412/428（FINAL_PLAN §9.2），与 409 version_conflict 同语义。
      case 412:
        return server ?? '版本冲突（412）：资源已被他人修改，请刷新后重做。'
      case 428:
        return server ?? '缺少前置版本（428）：请携带当前版本重试。'
      case 422:
        // 业务门禁结果（如 /eval/gate 检出回归），非网络/参数错误。响应体含门禁明细。
        return `门禁未通过（422）。这是业务判定结果（如评测检出回归），响应体为门禁明细。${
          server ?? ''
        }`.trim()
      case 429:
        return '请求过于频繁（429），已触发网关限流。请稍后再试。'
      case 503:
        // RBAC 管理写入灰度未开：这是有意为之的开关态，非故障。
        if (code === 'rbac_writes_disabled') return server ?? 'RBAC 管理写入未开启（灰度），暂不可提交。'
        return `服务端错误（503）。${server ?? '请稍后重试或检查后端服务是否可用。'}`
      case 500:
      case 502:
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

/**
 * OIDC 回调错误人话化（state/nonce/code/取消）。oidc-client-ts 抛的多是英文技术信息，
 * 直接展示会泄露内部细节且不友好——按关键词归类为可读中文（DR-1）。
 */
export function humanizeOidcCallbackError(err: unknown): string {
  const raw = (err instanceof Error ? err.message : String(err ?? '')).toLowerCase()
  if (raw.includes('access_denied') || raw.includes('cancel')) {
    return '你取消了登录，或授权被拒绝。可重新登录。'
  }
  if (raw.includes('state')) {
    return '登录校验未通过（state 不匹配，可能回调已过期或被篡改）。请重新登录。'
  }
  if (raw.includes('nonce')) {
    return '登录校验未通过（nonce 不匹配）。请重新登录。'
  }
  if (raw.includes('expired') || raw.includes('invalid_grant') || raw.includes('code')) {
    return '登录凭证已失效（回调 code 过期或已被使用）。请重新登录。'
  }
  return '登录未能完成，请重试。'
}
