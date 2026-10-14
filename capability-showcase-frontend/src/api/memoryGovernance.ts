import type { RunContext } from '../types/api'
import { authorizedFetch } from './authorizedFetch'
import { ApiError } from './errors'
import { API_KEY_HEADER, AUTH_HEADER } from './client'
export interface MemoryEntry {
  recordId: number; id: string; text: string; type: string | null; createdAtEpochMs: number
  sourceChatId: string | null; state: 'PENDING' | 'ACTIVE'; version: number; pinned: boolean; expiresAtEpochMs: number | null
}
export interface MemorySnapshot { enabled: boolean; generation: number; items: MemoryEntry[] }
export interface MemoryAudit { auditId: number; recordId: number; action: string; version: number; occurredAtEpochMs: number }
export interface MemoryDecision {
  expectedVersion: number; action: 'APPROVE' | 'REJECT' | 'FORGET' | 'UPDATE' | 'PIN' | 'UNPIN'
  text?: string; type?: string | null; expiresAtEpochMs?: number | null
}
/** 沿用平台凭证选择和401续期；身份由服务端确定。 */
export async function governanceJson<T>(path: string, ctx: RunContext, method = 'GET', body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  if (ctx.apiKey) headers[API_KEY_HEADER] = ctx.apiKey
  else if (ctx.accessToken) headers[AUTH_HEADER] = `Bearer ${ctx.accessToken}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await authorizedFetch(`${ctx.edgeBaseUrl}${path}`, {
    method, headers, signal: ctx.signal, body: body === undefined ? undefined : JSON.stringify(body),
  })
  const data = res.status === 204 ? null : await res.json().catch(() => null)
  if (!res.ok) throw new ApiError(res.status, `HTTP ${res.status}`, data)
  return data as T
}
export const fetchMemory = (ctx: RunContext) => governanceJson<MemorySnapshot>('/memory/governance', ctx)
export const decideMemory = (id: number, body: MemoryDecision, ctx: RunContext) =>
  governanceJson<void>(`/memory/governance/${id}/decision`, ctx, 'POST', body)
export const setMemoryEnabled = (enabled: boolean, expectedGeneration: number, ctx: RunContext) =>
  governanceJson<void>('/memory/governance/settings', ctx, 'PUT', { enabled, expectedGeneration })
export const fetchMemoryAudit = (ctx: RunContext) => governanceJson<MemoryAudit[]>('/memory/governance/audit?limit=50', ctx)
