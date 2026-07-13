/**
 * 知识域 API（经边缘网关 `/rag/**`）——**双模凭证**：与 admin 不同，知识端点是能力路径，接受 api-key 或 Bearer，
 * 故沿用 runContext 的凭证注入（api-key 覆盖 Bearer），走 authorizedFetch（Bearer 401 单飞续期）。
 *
 * visibility 归属：list 按 tab 请求（tenant 或 public），以**请求的 visibility 为权威**标注结果，不靠 tenantId 猜
 * （docVisibility 仅作兜底/校验）；query 命中用服务端 KnowledgeHit.visibility。删共享需 public-ingest（后端 403 兜底）。
 */
import type { RunContext } from '../types/api'
import type { DocumentInfo, KnowledgeRuntimeView, Visibility } from '../types/knowledge'
import { API_KEY_HEADER, AUTH_HEADER } from './client'
import { authorizedFetch } from './authorizedFetch'
import { ApiError } from './errors'
import { tryParseJson } from '../utils/json'

/** 凭证互斥二选一（复用能力路径逻辑）：手输 api-key 优先，否则登录 Bearer。 */
function credHeaders(ctx: RunContext): Record<string, string> {
  const h: Record<string, string> = {}
  if (ctx.apiKey) h[API_KEY_HEADER] = ctx.apiKey
  else if (ctx.accessToken) h[AUTH_HEADER] = `Bearer ${ctx.accessToken}`
  return h
}

async function edgeJson<T>(path: string, ctx: RunContext, init: RequestInit = {}): Promise<T> {
  const res = await authorizedFetch(`${ctx.edgeBaseUrl}${path}`, {
    ...init,
    headers: { ...credHeaders(ctx), ...(init.headers as Record<string, string> | undefined) },
    signal: ctx.signal,
  })
  const text = await res.text().catch(() => '')
  const data = text ? (tryParseJson(text) ?? text) : null
  if (!res.ok) throw new ApiError(res.status, `HTTP ${res.status}`.trim(), data)
  return data as T
}

/** 缺省=当前租户（无 query，向后兼容）；public 显式带 ?visibility=public。 */
function visQuery(visibility: Visibility): string {
  return visibility === 'public' ? '?visibility=public' : ''
}

export const fetchRagConfig = (ctx: RunContext): Promise<KnowledgeRuntimeView> =>
  edgeJson<KnowledgeRuntimeView>('/rag/config', ctx)

export const listDocuments = (visibility: Visibility, ctx: RunContext): Promise<DocumentInfo[]> =>
  edgeJson<DocumentInfo[]>(`/rag/documents${visQuery(visibility)}`, ctx)

export const getDocument = (docId: string, visibility: Visibility, ctx: RunContext): Promise<DocumentInfo> =>
  edgeJson<DocumentInfo>(`/rag/documents/${encodeURIComponent(docId)}${visQuery(visibility)}`, ctx)

export const deleteDocument = (docId: string, visibility: Visibility, ctx: RunContext): Promise<void> =>
  edgeJson<void>(`/rag/documents/${encodeURIComponent(docId)}${visQuery(visibility)}`, ctx, {
    method: 'DELETE',
  }).then(() => undefined)
