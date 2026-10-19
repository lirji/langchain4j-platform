/**
 * 知识域 API（经边缘网关 `/rag/**`）——**双模凭证**：与 admin 不同，知识端点是能力路径，接受 api-key 或 Bearer，
 * 故沿用 runContext 的凭证注入（api-key 覆盖 Bearer），走 authorizedFetch（Bearer 401 单飞续期）。
 *
 * visibility 归属：list 按 tab 请求（tenant 或 public），以**请求的 visibility 为权威**标注结果，不靠 tenantId 猜
 * （docVisibility 仅作兜底/校验）；query 命中用服务端 KnowledgeHit.visibility。删共享需 public-ingest（后端 403 兜底）。
 */
import type { RunContext } from '../types/api'
import { IngestionDuplicateCode, type IngestionDuplicateNotice } from '../types/knowledge'
import type {
  DocumentInfo,
  IngestionJobView,
  IngestionSubmission,
  KnowledgeRuntimeView,
  PagedDocuments,
  Visibility,
} from '../types/knowledge'
import { API_KEY_HEADER, AUTH_HEADER } from './client'
import { authorizedFetch } from './authorizedFetch'
import { ApiError, apiErrorCode } from './errors'
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

/** 分页 query：public 带 visibility，始终带 page/size（后端据 page 命中分页信封重载）。 */
function pagedQuery(visibility: Visibility, page: number, size: number): string {
  const p = new URLSearchParams()
  if (visibility === 'public') p.set('visibility', 'public')
  p.set('page', String(page))
  p.set('size', String(size))
  return `?${p.toString()}`
}

export const fetchRagConfig = (ctx: RunContext): Promise<KnowledgeRuntimeView> =>
  edgeJson<KnowledgeRuntimeView>('/rag/config', ctx)

/** 列文档（完整数组，无分页）——保留给不需要分页的调用方 / 向后兼容。 */
export const listDocuments = (visibility: Visibility, ctx: RunContext): Promise<DocumentInfo[]> =>
  edgeJson<DocumentInfo[]>(`/rag/documents${visQuery(visibility)}`, ctx)

/** 分页列文档：返回 `PagedDocuments` 信封（items + 页码 + 总数）。租户库 / 共享库同一入口，仅 visibility 区分。 */
export const listDocumentsPaged = (
  visibility: Visibility,
  page: number,
  size: number,
  ctx: RunContext,
): Promise<PagedDocuments> =>
  edgeJson<PagedDocuments>(`/rag/documents${pagedQuery(visibility, page, size)}`, ctx)

export const getDocument = (docId: string, visibility: Visibility, ctx: RunContext): Promise<DocumentInfo> =>
  edgeJson<DocumentInfo>(`/rag/documents/${encodeURIComponent(docId)}${visQuery(visibility)}`, ctx)

export const deleteDocument = (docId: string, visibility: Visibility, ctx: RunContext): Promise<void> =>
  edgeJson<void>(`/rag/documents/${encodeURIComponent(docId)}${visQuery(visibility)}`, ctx, {
    method: 'DELETE',
  }).then(() => undefined)

/** 按 jobId 查询 durable ingestion 状态；无列表接口，只能点查。 */
export const getIngestionJob = (jobId: string, ctx: RunContext): Promise<IngestionJobView> =>
  edgeJson<IngestionJobView>(`/rag/ingestions/${encodeURIComponent(jobId)}`, ctx)

/** 只接受明确的重复业务码和有效引用；损坏响应不构造任务，也不把任意 409 当成成功。 */
export function ingestionDuplicate(error: unknown): IngestionDuplicateNotice | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null
  const code = apiErrorCode(error) as IngestionDuplicateCode
  if (!Object.values(IngestionDuplicateCode).includes(code)) return null
  const raw = (error.body as Record<string, unknown>).duplicate
  if (!raw || typeof raw !== 'object') return null
  const value = raw as Record<string, unknown>
  if (typeof value.documentAvailable !== 'boolean') return null
  const text = (key: string) => typeof value[key] === 'string' && value[key].trim() ? value[key] as string : null
  const version = Number.isSafeInteger(value.documentVersion) && Number(value.documentVersion) > 0
    ? value.documentVersion as number : null
  if (code === IngestionDuplicateCode.Ready && (!value.documentAvailable || !text('documentId') || !version)) return null
  if (value.documentAvailable && code !== IngestionDuplicateCode.Ready) return null
  return { code, documentId: text('documentId'), displayName: text('displayName'), documentVersion: version,
    jobId: text('jobId'), documentAvailable: value.documentAvailable }
}

/** 同一提交意图始终复用调用方保存的幂等键；浏览器负责 multipart boundary。 */
export async function submitIngestion(input: IngestionSubmission, ctx: RunContext): Promise<IngestionJobView> {
  const body = new FormData()
  body.append('file', input.file)
  body.append('documentId', input.documentId)
  body.append('documentVersion', String(input.documentVersion))
  body.append('displayName', input.displayName)
  if (input.category) body.append('category', input.category)
  const result = await edgeJson<IngestionJobView>('/rag/ingestions', ctx, {
    method: 'POST', headers: { 'Idempotency-Key': input.idempotencyKey }, body,
  })
  // 受理回执损坏也是未知结果，不能清空原提交信息后生成第二个任务。
  if (!result?.jobId || !result.status || result.documentId !== input.documentId || result.documentVersion !== input.documentVersion) {
    throw new ApiError(502, '入库回执不完整，请用原提交信息确认结果。')
  }
  return result
}

/** 人工重试恢复原任务，后端再次验证提交者、版本和编辑权限。 */
export const retryIngestion = (jobId: string, ctx: RunContext): Promise<IngestionJobView> =>
  edgeJson<IngestionJobView>(`/rag/ingestions/${encodeURIComponent(jobId)}/retry`, ctx, { method: 'POST' })
