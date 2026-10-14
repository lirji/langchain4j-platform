<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import WorkbenchSection from '../_shared/WorkbenchSection.vue'
import InfoNote from '../_shared/InfoNote.vue'
import { useSessionStore } from '../../stores/session'
import { useAuthStore } from '../../stores/auth'
import { ApiError, humanizeError, isAbortError } from '../../api/errors'
import type { RunContext } from '../../types/api'
import * as api from '../../api/knowledgeSync'
import './rag-governance.css'
const session = useSessionStore(), auth = useAuthStore()
const busy = ref(false), loaded = ref(false), error = ref(''), notice = ref(''), historyLoaded = ref(false)
const sources = ref<api.SourceStatus[]>([]), selected = ref<api.SourceStatus | null>(null)
const runs = ref<api.SyncRun[]>([]), detail = ref<api.SyncRun | null>(null), nextCursor = ref<string | null>(null)
let sequence = 0, controller: AbortController | undefined
function reset() {
  sequence++; controller?.abort(); busy.value = false; loaded.value = false; error.value = ''; notice.value = ''
  sources.value = []; selected.value = null; clearHistory()
}
function clearHistory() { runs.value = []; detail.value = null; historyLoaded.value = false; nextCursor.value = null }
watch(() => [session.apiKey, session.edgeBaseUrl, auth.accessToken], reset)
onUnmounted(reset)
async function request<T>(work: (ctx: RunContext) => Promise<T>, apply: (value: T) => void) {
  if (busy.value) return
  const seq = ++sequence; controller?.abort(); controller = new AbortController(); busy.value = true; error.value = ''; notice.value = ''
  try { const value = await work(session.runContext(controller.signal)); if (seq === sequence) apply(value) }
  catch (e) {
    if (seq !== sequence || isAbortError(e)) return
    error.value = e instanceof ApiError && e.status === 409 ? '来源状态已变化或已暂停。请刷新来源，核对后重新操作。'
      : e instanceof ApiError && e.status === 403 ? '需要 ingest 权限及该来源的运营授权。'
      : e instanceof ApiError && e.status === 404 ? '数据接入未启用、后端尚未升级，或当前来源已不可访问。请联系管理员确认。'
      : `${humanizeError(e)}。操作可能已受理，请刷新来源和运行记录确认结果。`
  } finally { if (seq === sequence) busy.value = false }
}
function load() { return request(api.listSources, rows => {
  sources.value = rows; loaded.value = true
  if (selected.value) { selected.value = rows.find(r => r.sourceId === selected.value?.sourceId) || null; if (!selected.value) clearHistory() }
}) }
function select(row: api.SourceStatus) {
  sequence++; controller?.abort(); busy.value = false; selected.value = row; error.value = ''; notice.value = ''; clearHistory()
}
function applySource(row: api.SourceStatus) { selected.value = row; sources.value = sources.value.map(r => r.sourceId === row.sourceId ? row : r) }
function refresh() { const row = selected.value; if (row) return request(ctx => api.getSource(row.sourceId, ctx), applySource) }
function action(kind: 'pause' | 'resume' | 'poll' | 'retry') {
  const row = selected.value; if (!row) return
  return request(ctx => api.control(row, kind, ctx), value => {
    applySource(value)
    notice.value = kind === 'pause' ? '来源已暂停。已领取的步骤和入库任务仍可能完成，检查点不会由旧执行器推进。'
      : kind === 'resume' ? '来源已恢复，将从原检查点继续。请刷新运行记录查看进度。'
      : '请求已受理。是否完成以来源状态和运行记录为准；请刷新确认。'
    clearHistory()
  })
}
function history(next = false) {
  const row = selected.value; if (!row) return
  return request(ctx => api.listRuns(row.sourceId, next ? nextCursor.value : null, ctx), page => {
    runs.value = page.items; nextCursor.value = page.nextCursor; historyLoaded.value = true; detail.value = null
  })
}
function show(run: api.SyncRun) { const row = selected.value; if (row) return request(ctx => api.getRun(row.sourceId, run.id, ctx), value => { detail.value = value; runs.value = runs.value.map(r => r.id === value.id ? value : r) }) }
const names: Record<string, string> = { NOT_STARTED: '尚未开始', IDLE: '等待下次同步', SYNCING: '同步中', RUNNING: '执行中', WAITING: '等待入库', BLOCKED: '需要处理', PAUSED: '已暂停', SUCCEEDED: '已完成', FAILED: '失败', CREATED: '新增', UPDATED: '更新', DELETED: '删除', SKIPPED: '跳过', LEGACY_CONFIRMED: '历史已确认' }
function label(value: string) { return names[value] || value }
function date(value: number | null) { return value ? new Date(value).toLocaleString() : '暂无' }
function reason(code: string | null) {
  if (!code) return ''
  const messages: Record<string, string> = {
    INGESTION_REQUIRES_ATTENTION: '入库任务失败。处理原因后可重试当前任务。', SOURCE_HTTP_ERROR: '上游接口请求失败，请检查来源服务。',
    SOURCE_CONFIG_CHANGED: '来源身份或地址已变更，需管理员使用新来源 ID。', SOURCE_REVISION_REUSED: '来源对同一版本返回不同内容，需修复上游版本。',
    DOCUMENT_VERSION_CHANGED: '知识文档版本已变化，需核对来源与文档的更新权限。', INGESTION_FORBIDDEN: '执行身份缺少入库或文档编辑权限。',
    SYNC_UNAVAILABLE: '同步依赖暂不可用，请检查服务后重新触发。', NO_RETRYABLE_JOB: '当前没有可重试的入库任务。',
  }
  return messages[code] || `同步遇到问题（${code}），请联系管理员排查。`
}
</script>

<template>
  <WorkbenchSection title="数据接入" subtitle="同步产品说明与客服 FAQ，追踪每一页数据的处理结果。" class="rag-governance sync-panel">
    <template #actions><button class="btn btn--ghost btn--sm" :disabled="busy" @click="load">{{ loaded ? '刷新数据源' : '查看数据源' }}</button></template>
    <p class="governance-muted">选择一个已配置的 HTTP 来源，查看同步进度、暂停采集或处理失败记录。来源地址与凭据由管理员维护。</p>
    <div class="governance-feedback" aria-live="polite">
      <InfoNote v-if="busy" tone="neutral" role="status">正在处理，请稍候。</InfoNote>
      <InfoNote v-if="error" tone="danger" role="alert">{{ error }}</InfoNote>
      <InfoNote v-if="notice" tone="success" role="status">{{ notice }}</InfoNote>
    </div>
    <p v-if="loaded && !sources.length" class="governance-empty">暂无可管理的数据源。请确认来源配置、当前租户与运营授权。</p>
    <div v-if="sources.length" class="sync-layout">
      <nav class="sync-sources" aria-label="HTTP 数据源">
        <button v-for="row in sources" :key="row.sourceId" class="sync-source" :aria-pressed="selected?.sourceId === row.sourceId" @click="select(row)">
          <strong>{{ row.sourceId }}</strong><span>{{ label(row.status) }}</span><small>最后成功：{{ date(row.lastSuccessAt) }}</small>
        </button>
      </nav>
      <article v-if="selected" class="sync-detail" aria-label="来源详情">
        <div class="governance-section-head"><h3>{{ selected.sourceId }}</h3><span class="governance-tag">{{ label(selected.status) }}</span></div>
        <dl class="sync-facts"><div><dt>执行身份</dt><dd>{{ selected.executionUserId }}</dd></div><div><dt>待处理记录</dt><dd>{{ selected.remaining }}</dd></div><div><dt>最后整页成功</dt><dd>{{ date(selected.lastSuccessAt) }}</dd></div></dl>
        <InfoNote v-if="selected.errorCode" tone="danger">{{ reason(selected.errorCode) }}</InfoNote>
        <div class="governance-actions">
          <button class="btn btn--primary" :disabled="busy || selected.paused" @click="action('poll')">立即同步</button>
          <button class="btn btn--ghost" :disabled="busy" @click="action(selected.paused ? 'resume' : 'pause')">{{ selected.paused ? '恢复来源' : '暂停来源' }}</button>
          <button v-if="selected.pendingJobId && selected.errorCode" class="btn btn--ghost" :disabled="busy || selected.paused" @click="action('retry')">重试失败任务</button>
          <button class="btn btn--ghost" :disabled="busy" @click="refresh">刷新来源</button>
        </div>
        <p class="governance-muted">暂停阻止后续协调；已领取的步骤和入库任务仍可能完成。恢复沿用原检查点，不会跳过失败数据。</p>
        <div class="governance-section-head"><h3>运行记录</h3><button class="btn btn--ghost btn--sm" :disabled="busy" @click="history()">{{ historyLoaded ? '刷新最新记录' : '查看运行记录' }}</button></div>
        <p v-if="historyLoaded && !runs.length" class="governance-empty">尚无运行记录。</p>
        <ul class="sync-runs">
          <li v-for="run in runs" :key="run.id">
            <button class="btn btn--ghost" :disabled="busy" :aria-pressed="detail?.id === run.id" @click="show(run)">{{ date(run.startedAt) }} · {{ label(run.status) }}</button>
            <span>新增 {{ run.created }} / 更新 {{ run.updated }} / 删除 {{ run.deleted }} / 跳过 {{ run.skipped }} / 失败 {{ run.failed }}</span>
          </li>
        </ul>
        <button v-if="nextCursor" class="btn btn--ghost" :disabled="busy" @click="history(true)">下一页运行记录</button>
      </article>
      <p v-else class="governance-empty">选择数据源查看详情。</p>
    </div>
    <article v-if="detail" class="sync-run-detail governance-block" aria-label="运行详情">
      <div class="governance-section-head"><h3>运行结果 · {{ label(detail.status) }}</h3><button class="btn btn--ghost btn--sm" :disabled="busy" @click="show(detail)">刷新此运行</button></div>
      <p class="governance-muted">{{ detail.id }} · 首次触发：{{ detail.triggeredBy === 'scheduler' ? '自动调度' : detail.triggeredBy }} · 执行身份：{{ detail.executionUserId }}</p>
      <dl class="sync-facts"><div><dt>本页记录</dt><dd>{{ detail.fetchedCount ?? '尚未取得有效页' }}</dd></div><div><dt>尚待处理</dt><dd>{{ detail.remaining }}</dd></div><div><dt>最近更新</dt><dd>{{ date(detail.updatedAt) }}</dd></div><div><dt>完成时间</dt><dd>{{ date(detail.finishedAt) }}</dd></div></dl>
      <InfoNote v-if="detail.errorCode" tone="danger">{{ reason(detail.errorCode) }}</InfoNote>
      <p class="governance-muted">等待和重试沿用本次运行；统计按每条记录的最新结果计算。</p>
      <p v-if="detail.legacyConfirmed" class="governance-muted">包含 {{ detail.legacyConfirmed }} 条升级前已确认的记录，原操作分类未保存，不计入新增或更新数量。</p>
      <ul class="sync-records"><li v-for="record in detail.records" :key="record.externalId">
        <div class="governance-section-head"><strong>{{ record.externalId }} · 版本 {{ record.sourceRevision }}</strong><span>{{ label(record.action) }} · {{ label(record.status) }}</span></div>
        <p v-if="record.errorCode">{{ reason(record.errorCode) }}</p>
        <p class="governance-muted">文档：{{ record.documentId }}<span v-if="record.jobId"> · 入库任务：{{ record.jobId }}</span></p>
      </li></ul>
      <p v-if="!detail.records.length" class="governance-muted">暂未产生逐条处理结果；抓取或校验失败请查看本次运行错误。</p>
    </article>
  </WorkbenchSection>
</template>

<style scoped>
:deep(.wb-sec__heading) { flex: 1 1 240px; min-width: 0; max-width: 100%; }
:deep(.wb-sec__sub) { white-space: normal; overflow-wrap: anywhere; }
.sync-layout { display: grid; grid-template-columns: minmax(180px, 1fr) minmax(0, 2.5fr); gap: var(--space-5); margin-top: var(--space-5); }
.sync-sources, .sync-detail, .sync-run-detail { display: grid; gap: var(--space-4); align-content: start; min-width: 0; }
.sync-source { display: grid; gap: var(--space-2); width: 100%; text-align: left; padding: var(--space-4); border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface-2); color: var(--text); cursor: pointer; overflow-wrap: anywhere; }
.sync-source[aria-pressed='true'] { border-color: var(--primary); background: var(--primary-soft); }
.sync-source:focus-visible { outline: 2px solid var(--primary); outline-offset: 3px; }
.sync-source span, .sync-source small { color: var(--text-muted); font-size: var(--fs-sm); }
.sync-detail { padding-left: var(--space-5); border-left: 1px solid var(--border); }
.sync-facts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--space-4); margin: 0; padding: var(--space-4); background: var(--surface-2); border-radius: var(--radius); }
.sync-facts dt { color: var(--text-muted); font-size: var(--fs-sm); margin-bottom: var(--space-2); }
.sync-facts dd { margin: 0; overflow-wrap: anywhere; }
.sync-runs, .sync-records { list-style: none; padding: 0; margin: 0; display: grid; gap: var(--space-3); }
.sync-runs li, .sync-records li { display: grid; gap: var(--space-2); padding: var(--space-3); border: 1px solid var(--border); border-radius: var(--radius); overflow-wrap: anywhere; }
.sync-runs .btn { justify-content: start; text-align: left; }
.sync-runs span { color: var(--text-muted); font-size: var(--fs-sm); }
@container (max-width: 680px) {
  .sync-layout { grid-template-columns: 1fr; }
  .sync-detail { border-left: 0; padding-left: 0; padding-top: var(--space-4); border-top: 1px solid var(--border); }
  .sync-facts { grid-template-columns: 1fr 1fr; }
}
</style>
