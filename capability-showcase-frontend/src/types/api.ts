/** 请求/响应相关的共享类型。 */

/** 一次调用的运行上下文（凭证只在此处注入到请求头）。 */
export interface RunContext {
  /** 手输 API Key（高级/直连覆盖）。非空时优先以 X-Api-Key 注入。 */
  apiKey: string
  /** 登录会话访问令牌。apiKey 为空且此值非空时以 Authorization: Bearer 注入。 */
  accessToken?: string
  /** direct mode 网关基址，默认空串（同源）。 */
  edgeBaseUrl: string
  signal?: AbortSignal
}

/** 组装后的请求计划（纯数据，便于测试与 curl 预览）。 */
export interface RequestPlan {
  method: string
  url: string
  headers: Record<string, string>
  body?: string | FormData
}

/** 非流式调用的结果。 */
export interface RunResult {
  status: number
  data: unknown
  text?: string
  traceId?: string | null
  headers: Headers
}
