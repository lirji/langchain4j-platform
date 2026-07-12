import type { Capability, ParamSpec } from '../types/catalog'
import type { RequestPlan, RunContext, RunResult } from '../types/api'
import type { FormValues } from '../utils/validation'
import { ApiError } from './errors'
import { authorizedFetch } from './authorizedFetch'
import { tryParseJson } from '../utils/json'

/** HTTP 头名（大小写不敏感，统一常量避免拼写漂移）。 */
export const API_KEY_HEADER = 'X-Api-Key'
export const AUTH_HEADER = 'Authorization'
export const TRACE_ID_HEADER = 'X-Trace-Id'

/** 替换路径模板中的 {name} 占位符，并拼接 query 串。纯函数。 */
export function buildTargetUrl(
  path: string,
  params: ParamSpec[],
  values: FormValues,
  edgeBaseUrl = '',
): string {
  let resolved = path
  for (const p of params) {
    if (p.in !== 'path') continue
    const raw = values[p.name]
    const val = raw == null ? '' : String(raw)
    resolved = resolved.split(`{${p.name}}`).join(encodeURIComponent(val))
  }

  const qs = new URLSearchParams()
  for (const p of params) {
    if (p.in !== 'query') continue
    const raw = values[p.name]
    if (raw == null || raw === '') continue
    qs.append(p.name, String(raw))
  }
  const query = qs.toString()
  return `${edgeBaseUrl}${resolved}${query ? `?${query}` : ''}`
}

/** 组装 JSON 请求体（仅 in:body 参数）。number/integer 转数字，json/array/object 解析。纯函数。 */
export function buildJsonBody(params: ParamSpec[], values: FormValues): Record<string, unknown> {
  const body: Record<string, unknown> = {}
  for (const p of params) {
    if (p.in !== 'body') continue
    let v = values[p.name]
    if (v === undefined || v === null || v === '') continue
    if (p.type === 'json' || p.type === 'array' || p.type === 'object') {
      v = JSON.parse(String(v))
    } else if (p.type === 'number' || p.type === 'integer') {
      v = typeof v === 'number' ? v : Number(v)
    } else if (p.type === 'boolean') {
      v = Boolean(v)
    }
    body[p.name] = v
  }
  return body
}

/** 组装 multipart FormData（仅 in:form-data 参数）。绝不手动设 Content-Type。纯函数。 */
export function buildFormData(params: ParamSpec[], values: FormValues): FormData {
  const fd = new FormData()
  for (const p of params) {
    if (p.in !== 'form-data') continue
    const v = values[p.name]
    if (p.type === 'file') {
      if (v instanceof File) fd.append(p.name, v)
    } else if (v != null && v !== '') {
      fd.append(p.name, String(v))
    }
  }
  return fd
}

function hasBodyParams(cap: Capability): boolean {
  return cap.params.some((p) => p.in === 'body')
}

/**
 * 组装业务 header 参数（仅 in:header）。纯函数。
 * 安全：绝不允许 header 参数覆盖平台凭证头 X-Api-Key / Authorization（大小写不敏感），
 * 凭证只来自 ctx.apiKey / ctx.accessToken。
 */
export function buildHeaderParams(params: ParamSpec[], values: FormValues): Record<string, string> {
  const headers: Record<string, string> = {}
  for (const p of params) {
    if (p.in !== 'header') continue
    const lower = p.name.toLowerCase()
    if (lower === API_KEY_HEADER.toLowerCase() || lower === AUTH_HEADER.toLowerCase()) continue
    const v = values[p.name]
    if (v == null || v === '') continue
    headers[p.name] = String(v)
  }
  return headers
}

/** requestKind 是否走 multipart 请求体。 */
function isMultipartBody(kind: Capability['requestKind']): boolean {
  return kind === 'multipart' || kind === 'multipart-sse'
}

/** requestKind 是否为流式（SSE 响应）。 */
export function isStreamingKind(kind: Capability['requestKind']): boolean {
  return kind === 'sse' || kind === 'multipart-sse'
}

/**
 * 把能力 + 表单值组装成纯数据的 RequestPlan。API Key 仅在此处注入到请求头 —— 唯一注入点。
 * 按 requestKind 分派：json/sse → JSON 体；multipart/multipart-sse → FormData；none → 无体。
 * header 参数（in:header）作为业务 header 注入，但不得覆盖 X-Api-Key。
 */
export function assembleRequest(cap: Capability, values: FormValues, ctx: RunContext): RequestPlan {
  const url = buildTargetUrl(cap.path, cap.params, values, ctx.edgeBaseUrl)
  const headers: Record<string, string> = {}
  // 业务 header 参数先注入；平台凭证头后置注入，确保永远来自 ctx、不被业务 header 覆盖。
  // 凭证互斥二选一：手输 api-key 为显式覆盖优先，否则用登录会话 Bearer（网关双模均接受）。
  Object.assign(headers, buildHeaderParams(cap.params, values))
  if (ctx.apiKey) headers[API_KEY_HEADER] = ctx.apiKey
  else if (ctx.accessToken) headers[AUTH_HEADER] = `Bearer ${ctx.accessToken}`

  let body: string | FormData | undefined

  if (cap.requestKind === 'json' || cap.requestKind === 'sse') {
    if (hasBodyParams(cap)) {
      headers['Content-Type'] = 'application/json'
      body = JSON.stringify(buildJsonBody(cap.params, values))
    }
  } else if (isMultipartBody(cap.requestKind)) {
    // 不设 Content-Type：浏览器会自动带上带 boundary 的 multipart/form-data。
    body = buildFormData(cap.params, values)
  }

  if (isStreamingKind(cap.requestKind)) {
    headers['Accept'] = 'text/event-stream'
  }

  return { method: cap.method, url, headers, body }
}

async function readBody(res: Response): Promise<{ data: unknown; text?: string }> {
  const contentType = res.headers.get('Content-Type') ?? ''
  if (contentType.includes('application/json')) {
    try {
      return { data: await res.json() }
    } catch {
      return { data: null }
    }
  }
  const text = await res.text()
  const parsed = tryParseJson(text)
  return { data: parsed ?? text, text }
}

/**
 * 执行一次非流式（json/multipart/none）调用。失败抛 ApiError（携带解析后的响应体）。
 */
export async function runCapability(
  cap: Capability,
  values: FormValues,
  ctx: RunContext,
): Promise<RunResult> {
  const plan = assembleRequest(cap, values, ctx)
  // 走 authorizedFetch：会话 Bearer 遇 401 时静默续期并重试一次。
  const res = await authorizedFetch(plan.url, {
    method: plan.method,
    headers: plan.headers,
    body: plan.body,
    signal: ctx.signal,
  })
  const traceId = res.headers.get(TRACE_ID_HEADER)
  const { data, text } = await readBody(res)
  if (!res.ok) {
    throw new ApiError(res.status, `HTTP ${res.status} ${res.statusText}`.trim(), data ?? text)
  }
  return { status: res.status, data, text, traceId, headers: res.headers }
}
