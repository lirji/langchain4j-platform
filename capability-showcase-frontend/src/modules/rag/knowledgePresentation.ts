import { ApiError, apiErrorCode, humanizeError } from '../../api/errors'
import { IngestionStatus, type IngestionJobView } from '../../types/knowledge'

/** 文件选择范围来自已验收格式；它是选择提示，真实格式由后端内容检测决定。 */
export const DOCUMENT_ACCEPT = '.txt,.md,.pdf,.doc,.docx,.xls,.xlsx,.csv,.ppt,.pptx,.html,.htm,.rtf,.odt'
export const DOCUMENT_FORMAT_HINT = '支持 PDF、Word、Markdown、Excel、CSV、TXT、PowerPoint、HTML、RTF 和 ODT。复杂版面、合并表头及修订内容请结合解析提示核对。'
export const OCR_HINT = '标准部署默认启用扫描及混合 PDF 的选择性 OCR，管理员可显式关闭。本页无法读取或修改运行开关，请以检索结果的实际提取方式与解析提示为准。'
export const INGESTION_PREPARATION_HINT = '入库先进行文档解析、可选上下文增强和向量生成，再执行下方写入步骤。准备期间，下方步骤显示“等待处理”；准备阶段暂不显示细分进度。'
export const TRACKED_JOB_LIMIT = 8
export const POLL_INTERVAL_MS = 2500
export const POLL_MAX_ROUNDS = 120
const REJECTED_HTTP_STATUSES = new Set([400, 401, 403, 404, 405, 409, 413, 415, 422, 429])
/** 超时等未明确拒绝的响应也可能已经受理，不能仅按 4xx 粗略判断。 */
export const isRejectedSubmission = (error: unknown) => error instanceof ApiError && REJECTED_HTTP_STATUSES.has(error.status)

const JOB_LABELS: Record<string, string> = {
  RECEIVED: '已受理，等待处理', PROCESSING: '入库处理中', READY: '已可检索', PARTIAL: '部分处理失败',
  FAILED: '处理失败', MANUAL_REVIEW: '需要人工处理', DELETING: '删除处理中', DELETED: '已删除',
}
const STAGE_LABELS: Record<string, string> = {
  PREPARE: '解析与分块准备', PREPARATION: '解析与分块准备', PARSING: '文档解析', EMBEDDING: '向量生成',
  VECTOR: '向量索引', ELASTICSEARCH: '全文索引', GRAPH: '关系图谱', AUTHORIZATION: '文档授权', REGISTRY: '文档版本发布',
}
const SINK_LABELS: Record<string, string> = { PENDING: '等待处理', RUNNING: '处理中', SUCCEEDED: '已完成', FAILED: '失败' }
export const jobLabel = (status: string) => JOB_LABELS[status] ?? `未知状态（${status}）`
export const stageLabel = (stage?: string | null) => stage ? STAGE_LABELS[stage] ?? stage : '未提供'
export const sinkLabel = (state: string) => SINK_LABELS[state] ?? `未知状态（${state}）`
export function jobTone(status: string): string {
  if (status === IngestionStatus.Ready) return 'success'
  if ([IngestionStatus.Failed, IngestionStatus.ManualReview, IngestionStatus.Partial].includes(status as IngestionStatus)) return 'warning'
  return 'neutral'
}
export function mayRetry(job: IngestionJobView): boolean {
  return [IngestionStatus.Partial, IngestionStatus.Failed, IngestionStatus.ManualReview].includes(job.status as IngestionStatus)
}
export function shouldPoll(job: IngestionJobView): boolean {
  return [IngestionStatus.Received, IngestionStatus.Processing, IngestionStatus.Deleting].includes(job.status as IngestionStatus)
    || ([IngestionStatus.Partial, IngestionStatus.Failed].includes(job.status as IngestionStatus) && !!job.nextRetryAt)
}

/** 错误码只映射到安全建议，不展示服务端堆栈或依赖地址。 */
const ERROR_HINTS: Record<string, string> = {
  DOCUMENT_EMPTY: '没有可提取正文。请检查空白文件；扫描件需管理员确认 OCR 配置。',
  DOCUMENT_ENCRYPTED: '文件已加密，请提供经授权解密后的副本。',
  DOCUMENT_PARSE_FAILED: '文件损坏或格式无法解析，请检查原文件。',
  DOCUMENT_TEXT_LIMIT: '提取正文超过限制，请拆分文件或联系管理员评估额度。',
  DOCUMENT_INPUT_LIMIT: '原文件超过解析大小限制，请拆分文件。',
  DOCUMENT_PAGE_LIMIT: '文件页数超过限制，请拆分文件。',
  DOCUMENT_STRUCTURE_LIMIT: '文档结构超过限制，请减少复杂嵌套或拆分文件。',
  DOCUMENT_RESOURCE_LIMIT: '解析资源超限，请拆分文件或联系管理员。',
  DOCUMENT_PARSE_TIMEOUT: '解析超时，请检查文件复杂度和服务资源。',
  DOCUMENT_OCR_UNAVAILABLE: 'OCR 引擎或语言数据不可用，请联系管理员。',
  DOCUMENT_OCR_FAILED: 'OCR 未能完成，请检查原文件并联系管理员。',
  DOCUMENT_OCR_LIMIT: 'OCR 页数或像素超过限制，请拆分扫描件。',
  DOCUMENT_OCR_TIMEOUT: 'OCR 执行超时，请检查文件和服务资源。',
  DOCUMENT_OCR_QUALITY: 'OCR 结果为空或置信度不足，请提供更清晰的扫描件。',
  DOCUMENT_PARSER_PROCESS_FAILED: '独立解析进程异常，请联系管理员排查。',
  DOCUMENT_PARSER_TERMINATION_FAILED: '解析进程未能正常停止，需要管理员处理。',
  DOCUMENT_PARSER_UNAVAILABLE: '解析服务暂时不可用，请恢复服务后重试。',
  DOCUMENT_PARSER_BUSY: '解析资源繁忙，请等待自动重试或稍后刷新。',
  DOCUMENT_SOURCE_READ_FAILED: '无法读取已上传原文，请检查存储服务后重试。',
  INGESTION_INPUT_LIMIT: '文件超过上传限制，请拆分文件。',
  INGESTION_INPUT_SIZE: '上传内容大小不一致，请重新选择原文件。',
  INGESTION_UPLOAD_BUSY: '上传容量暂满，请稍后使用原提交信息重试。',
  INGESTION_INPUT_UNAVAILABLE: '上传暂未完成，请使用原提交信息确认结果。',
  INGESTION_MODEL_INPUT_LIMIT: '内容超过模型输入预算，请拆分文件或调整处理配置。',
  INGESTION_MODEL_WORK_LIMIT: '文档超过模型工作量预算，请联系管理员评估。',
  INGESTION_METADATA_LIMIT: '来源信息超过预算，请联系管理员检查文档结构。',
  INGESTION_MODEL_RESPONSE_INVALID: '模型返回结果不完整，请联系管理员检查模型服务。',
  INGESTION_VECTOR_LIMIT: '向量结果超过预算，请拆分文件或联系管理员。',
  INGESTION_BATCH_LIMIT: '单个片段超过写入预算，请联系管理员调整分块。',
  INGESTION_ADMISSION_CONFIG: '入库调度配置不一致，需要管理员处理。',
  INGESTION_STAGE_DEADLINE: '处理阶段超时，请检查资源与下游服务。',
  RETRY_EXHAUSTED: '自动重试次数已用尽。处理故障后可重试原任务。',
  PREPARED_RESULT_INVALID: '已保存的解析结果校验失败，需要管理员检查存储完整性。',
  PREPARED_RESULT_LIMIT: '已保存的解析结果超过大小限制，需要管理员评估。',
  PREPARED_RESULT_VERSION: '任务处理版本与当前服务不兼容，需要管理员处理。',
  PREPARED_RESULT_UNAVAILABLE: '无法读取已保存的解析结果，请恢复存储服务后重试。',
  LEGACY_PREPARATION_REQUIRED: '升级前的任务需要管理员受控恢复，不能直接重新解析覆盖。',
  INVALID_INPUT_OR_PERMISSION: '输入、文档版本或权限需要人工核对。',
}
export const ingestionErrorHint = (code?: string | null) => code
  ? ERROR_HINTS[code] ?? '处理未完成，请保留错误码与追踪号，联系管理员核对。'
  : '服务端未提供具体原因，请刷新任务或联系管理员。'
export function ingestionRequestError(error: unknown): string {
  const code = apiErrorCode(error)
  if (code && ERROR_HINTS[code]) return ERROR_HINTS[code]
  if (error instanceof ApiError) {
    if (error.status === 409) return '文档版本、提交内容或任务状态冲突。请刷新原任务并核对版本，表单已保留。'
    if (error.status === 404) return '任务不存在、非当前账号提交，或入库服务尚未开放。请核对任务号与登录身份。'
    if (error.status === 400) return '提交参数不符合要求，请核对文件、文档 ID 和版本。'
    if (error.status === 413) return '文件超过服务端大小限制，请拆分后上传。'
    if (error.status === 429) return '服务繁忙，请稍后使用原提交信息重试。'
    if (error.status >= 500) return '服务暂时不可用，结果可能已经受理。请保留原提交信息并确认结果。'
  }
  return humanizeError(error)
}

/** 时间点固定北京时间，不受浏览器本地时区影响。 */
export function knowledgeDate(value?: string | number | null): string {
  if (value == null || value === '') return '未提供'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '时间不可用' : date.toLocaleString('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  })
}
