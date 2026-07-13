/**
 * 知识域类型 —— 与后端 `platform-protocol` 的 `KnowledgeRuntimeView` / `DocumentInfo`（`lifecycle`）/
 * `KnowledgeHit` 对齐。visibility 一律由服务端权威给出，前端不靠 docId/名称推断。
 */

/** 文档/命中的可见性：当前租户库 或 共享（公共）库。 */
export type Visibility = 'tenant' | 'public'

/** 后端 `DocumentInfo`：Instant → ISO-8601 字符串；category 可空。 */
export interface DocumentInfo {
  docId: string
  tenantId: string
  displayName: string
  contentType: string
  sizeBytes: number
  segmentCount: number
  version: number
  uploadedAt: string
  category: string | null
}

/** 后端 `GET /rag/config`（KnowledgeRuntimeView）。 */
export interface KnowledgeRuntimeView {
  contractVersion: number
  publicEnabled: boolean
  sharedImagesSupported: boolean
}

/** 强类型化的检索命中（结构化解析 /rag/query 结果时用）。visibility 服务端权威。 */
export interface KnowledgeHitView {
  id: string
  score: number
  docId: string
  displayName: string
  category: string | null
  index: string
  text: string
  source: string
  visibility: Visibility
}

/** 共享库保留分区 tenantId（对齐后端 `PublicKb.TENANT_ID`）；仅作兜底/校验，不作为权威来源。 */
export const PUBLIC_TENANT_ID = '__public__'

/** 兜底：由 DocumentInfo.tenantId 归类 visibility（列表以请求的 tab 为权威，此函数仅校验）。 */
export function docVisibility(info: DocumentInfo): Visibility {
  return info.tenantId === PUBLIC_TENANT_ID ? 'public' : 'tenant'
}
