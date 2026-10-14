import type { RunContext } from '../types/api'
import { authorizedFetch } from './authorizedFetch'
import { ApiError } from './errors'
import { API_KEY_HEADER, AUTH_HEADER } from './client'
const HTTP_NO_CONTENT = 204
/** 沿用平台凭证选择和401续期；身份由服务端确定。 */
export async function governanceJson<T>(path: string, ctx: RunContext, method = 'GET', body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  if (ctx.apiKey) headers[API_KEY_HEADER] = ctx.apiKey
  else if (ctx.accessToken) headers[AUTH_HEADER] = `Bearer ${ctx.accessToken}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await authorizedFetch(`${ctx.edgeBaseUrl}${path}`, {
    method, headers, signal: ctx.signal, body: body === undefined ? undefined : JSON.stringify(body),
  })
  const data = res.status === HTTP_NO_CONTENT ? null : await res.json().catch(() => null)
  if (!res.ok) throw new ApiError(res.status, `HTTP ${res.status}`, data)
  return data as T
}
