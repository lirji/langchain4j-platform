<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useCatalogStore } from '../../stores/catalog'
import { useSessionStore } from '../../stores/session'
import { getIngestionJob, retryIngestion, submitIngestion } from '../../api/knowledge'
import { ApiError, isAbortError } from '../../api/errors'
import { IngestionStatus, type IngestionJobView } from '../../types/knowledge'
import { executionGate } from '../../utils/gate'
import FileField from '../../components/form/fields/FileField.vue'
import WorkbenchSection from '../_shared/WorkbenchSection.vue'
import InfoNote from '../_shared/InfoNote.vue'
import { useKnowledgeScope } from './useKnowledgeScope'
import { useIngestionState } from './ingestionState'
import { DOCUMENT_ACCEPT, DOCUMENT_FORMAT_HINT, OCR_HINT, INGESTION_PREPARATION_HINT, POLL_INTERVAL_MS, POLL_MAX_ROUNDS,
  ingestionErrorHint, ingestionRequestError, isRejectedSubmission, jobLabel, jobTone, knowledgeDate, mayRetry,
  shouldPoll, sinkLabel, stageLabel, TRACKED_JOB_LIMIT } from './knowledgePresentation'

withDefaults(defineProps<{ showUpload?: boolean }>(), { showUpload: true })
const emit = defineEmits<{ ready: [job: IngestionJobView]; openDocument: [job: IngestionJobView] }>()
const session = useSessionStore(), catalog = useCatalogStore(), state = useIngestionState()
const scope = useKnowledgeScope()
const jobIdInput = ref(''), taskError = ref(''), taskNote = ref('')
const errorJobId = ref('')
const busyIds = ref<string[]>([])
const selected = computed(() => state.jobs.find(job => job.jobId === state.selectedId))
const uploadCap = computed(() => catalog.capabilityById('rag.upload.file'))
const gate = computed(() => uploadCap.value ? executionGate(uploadCap.value, session.permissionContext())
  : { allowed: false, reason: '上传入口尚未加载。' })
let disposed = false, timer: ReturnType<typeof setTimeout> | undefined, rounds = 0
const controllers = new Map<string, AbortController>()
const paused = ref(false)
const readySeen = new Set<string>()
const monitoring = computed(() => !paused.value && state.jobs.some(shouldPoll))

function accept(job: IngestionJobView) {
  state.upsert(job)
  if (job.status === IngestionStatus.Ready && !readySeen.has(job.jobId)) {
    readySeen.add(job.jobId)
    emit('ready', job)
  }
}
function startRequest(key: string) {
  const controller = new AbortController()
  controllers.set(key, controller)
  busyIds.value = [...busyIds.value, key]
  return { controller, identity: scope.value }
}
function current(request: ReturnType<typeof startRequest>) {
  return !disposed && !request.controller.signal.aborted && request.identity === scope.value
}
function finish(key: string, controller: AbortController) {
  if (controllers.get(key) !== controller) return
  controllers.delete(key)
  busyIds.value = busyIds.value.filter(id => id !== key)
}
async function refresh(jobId: string) {
  if (!jobId || busyIds.value.includes(jobId) || !session.hasCredential) return
  const req = startRequest(jobId)
  try {
    const job = await getIngestionJob(jobId, session.runContext(req.controller.signal))
    if (!current(req)) return
    if (!job?.status || job.jobId !== jobId) throw new ApiError(502, '任务回执不完整')
    accept(job)
    state.uncertainRetries = state.uncertainRetries.filter(id => id !== jobId)
    if (errorJobId.value === jobId) { taskError.value = ''; errorJobId.value = '' }
  } catch (error) {
    if (!current(req) || isAbortError(error)) return
    errorJobId.value = jobId
    taskError.value = `任务 ${jobId}：${ingestionRequestError(error)} 以下保留上次查询结果，请刷新确认。`
    // 查询失败保留最后已知状态，但停止自动循环，避免持续刷失败请求。
    paused.value = true
  } finally { finish(jobId, req.controller) }
}
async function track() {
  taskNote.value = ''
  const id = jobIdInput.value.trim()
  if (!id) return
  await refresh(id)
  if (state.jobs.some(job => job.jobId === id)) state.selectedId = id
  if (!taskError.value) resume()
}
function schedule() {
  clearTimeout(timer)
  if (!monitoring.value || disposed || !session.hasCredential) return
  timer = setTimeout(async () => {
    const identity = scope.value
    const jobs = state.jobs.filter(shouldPoll)
    await Promise.allSettled(jobs.map(job => refresh(job.jobId)))
    if (disposed || identity !== scope.value) return
    rounds += 1
    if (rounds >= POLL_MAX_ROUNDS) paused.value = true
    else schedule()
  }, POLL_INTERVAL_MS)
}
function resume() { rounds = 0; paused.value = false; schedule() }

async function submit() {
  if (!gate.value.allowed || state.phase === 'submitting' || state.phase === 'accepted') return
  state.submitError = ''
  if (!(state.file instanceof File) || !state.file.size) { state.submitError = '请选择有内容的文件。'; return }
  if (!state.documentId.trim() || !Number.isSafeInteger(Number(state.documentVersion)) || state.documentVersion < 1) {
    state.submitError = '请填写文档 ID，版本必须是大于零的安全整数。'; return
  }
  const input = state.command()
  const req = startRequest('submit')
  state.phase = 'submitting'
  try {
    const job = await submitIngestion(input, session.runContext(req.controller.signal))
    if (!current(req)) return
    state.phase = 'accepted'
    accept(job)
    state.selectedId = job.jobId
    resume()
  } catch (error) {
    if (!current(req)) return
    // 只有明确的客户端拒绝可修改请求；断网/服务故障必须保留原文件和幂等身份。
    state.phase = isRejectedSubmission(error) ? 'rejected' : 'uncertain'
    state.submitError = ingestionRequestError(error)
  } finally { finish('submit', req.controller) }
}
/** 只停止浏览器等待，服务端是否已受理仍需用原幂等身份确认。 */
function stopWaiting() {
  controllers.get('submit')?.abort()
  state.phase = 'uncertain'
  state.submitError = '已停止等待响应，服务端仍可能处理中。请用原提交信息确认结果。'
}
async function retry(job: IngestionJobView) {
  if (!gate.value.allowed || !mayRetry(job) || busyIds.value.includes(job.jobId) || state.uncertainRetries.includes(job.jobId)) return
  const req = startRequest(job.jobId)
  state.uncertainRetries = [...state.uncertainRetries, job.jobId]
  taskError.value = ''; taskNote.value = ''; errorJobId.value = ''
  let conflict = false
  try {
    const result = await retryIngestion(job.jobId, session.runContext(req.controller.signal))
    if (!current(req)) return
    if (result?.jobId !== job.jobId || !result.status) throw new ApiError(502, '任务回执不完整')
    accept(result)
    state.uncertainRetries = state.uncertainRetries.filter(id => id !== job.jobId)
    taskNote.value = '重试已受理，完成状态以任务刷新结果为准。'
    resume()
  } catch (error) {
    if (!current(req)) return
    errorJobId.value = job.jobId
    taskError.value = ingestionRequestError(error)
    if (isRejectedSubmission(error)) {
      state.uncertainRetries = state.uncertainRetries.filter(id => id !== job.jobId)
    }
    conflict = error instanceof ApiError && error.status === 409
  } finally { finish(job.jobId, req.controller) }
  if (conflict) {
    const note = taskError.value
    await refresh(job.jobId)
    if (current(req)) taskNote.value = note
  }
}
function stopRequests() {
  clearTimeout(timer)
  controllers.forEach(controller => controller.abort())
  controllers.clear(); busyIds.value = []
}
function leave(event: BeforeUnloadEvent) {
  if (state.phase !== 'submitting' && state.phase !== 'uncertain') return
  event.preventDefault(); event.returnValue = ''
}
watch(() => state.jobs.map(job => `${job.jobId}:${job.status}:${job.nextRetryAt}`).join('|'), schedule)
watch(scope, () => { stopRequests(); jobIdInput.value = ''; taskError.value = ''; taskNote.value = ''; errorJobId.value = ''; readySeen.clear(); rounds = 0; paused.value = false }, { flush: 'sync' })
onMounted(() => { window.addEventListener('beforeunload', leave); schedule() })
onBeforeUnmount(() => {
  disposed = true; stopRequests(); window.removeEventListener('beforeunload', leave)
  if (state.phase === 'submitting') state.phase = 'uncertain'
})
</script>

<template>
  <WorkbenchSection v-if="showUpload" title="文档入库" subtitle="上传到当前租户知识库。先接收文件，再在后台解析与索引；任务显示“已可检索”后才完成。">
    <InfoNote v-if="!gate.allowed" tone="warning">{{ gate.reason }}</InfoNote>
    <p class="ki-hint">{{ DOCUMENT_FORMAT_HINT }}</p>
    <p class="ki-hint">{{ OCR_HINT }}</p>
    <form class="ki-form" @submit.prevent="submit">
      <fieldset :disabled="state.locked" class="ki-fields">
        <div class="ki-field ki-wide"><label for="ingestion-file">文件（必填）</label><FileField field-id="ingestion-file" v-model="state.file" :accept="DOCUMENT_ACCEPT" /></div>
        <div class="ki-field"><label for="ingestion-name">文档名称</label><input id="ingestion-name" v-model="state.displayName" class="form-control" aria-describedby="ingestion-name-tip" /><small id="ingestion-name-tip">留空时使用原文件名。</small></div>
        <div class="ki-field"><label for="ingestion-category">类目</label><input id="ingestion-category" v-model="state.category" class="form-control" aria-describedby="ingestion-category-tip" /><small id="ingestion-category-tip">可选，用于知识检索时过滤。</small></div>
        <div class="ki-field"><label for="ingestion-id">文档 ID（必填）</label><input id="ingestion-id" v-model="state.documentId" required class="form-control" aria-describedby="ingestion-id-tip" /><small id="ingestion-id-tip">新文件已自动生成；更新文档请沿用原 ID。</small></div>
        <div class="ki-field"><label for="ingestion-version">文档版本（必填）</label><input id="ingestion-version" v-model.number="state.documentVersion" required type="number" min="1" step="1" class="form-control" aria-describedby="ingestion-version-tip" /><small id="ingestion-version-tip">新文档从 1 开始；更新须为当前已发布版本 + 1。</small></div>
        <details class="ki-wide ki-receipt"><summary>恢复没有收到回执的提交</summary><div class="ki-field"><label for="ingestion-recovery">原提交编号</label><input id="ingestion-recovery" v-model="state.recoveryKey" class="form-control" aria-describedby="ingestion-recovery-tip" /><small id="ingestion-recovery-tip">新上传请留空。恢复时填入原提交编号，并选择完全相同的文件、文档 ID、版本、名称与类目；服务端会核对并返回原任务。</small></div></details>
      </fieldset>
      <InfoNote v-if="state.submitError" tone="danger" role="alert">{{ state.submitError }}</InfoNote>
      <InfoNote v-if="state.phase === 'uncertain'" tone="warning">结果尚未确认。原文件与提交编号已保留，请点击“确认原提交结果”。不要关闭或刷新本页。</InfoNote>
      <InfoNote v-if="state.phase === 'accepted'" tone="success" role="status">已收到入库回执，请在下方查看任务。已受理不代表已可检索。</InfoNote>
      <div class="ki-actions">
        <button v-if="state.phase !== 'accepted'" type="submit" class="btn btn--primary" :disabled="!gate.allowed || state.phase === 'submitting'">{{ state.phase === 'submitting' ? '正在提交…' : state.phase === 'uncertain' ? '确认原提交结果' : state.phase === 'rejected' ? '重新提交' : '提交入库' }}</button>
        <button v-if="state.phase === 'submitting'" type="button" class="btn btn--ghost" @click="stopWaiting">停止等待</button>
        <button v-if="state.phase === 'accepted'" type="button" class="btn btn--primary" @click="state.newDocument()">继续上传新文档</button>
      </div>
      <details v-if="state.attempt" class="ki-receipt"><summary>提交凭据与恢复说明</summary><dl class="ki-facts"><dt>提交编号</dt><dd>{{ state.attempt.idempotencyKey }}</dd><dt>文档 ID</dt><dd>{{ state.attempt.documentId }}</dd><dt>文档版本</dt><dd>{{ state.attempt.documentVersion }}</dd></dl><p class="ki-hint">同一提交编号绑定相同文件和版本。当前会话保留原提交；刷新页面不会保存文件。需要离开时请先记录任务号或提交编号。</p></details>
    </form>
  </WorkbenchSection>

  <WorkbenchSection title="入库任务" subtitle="查看本会话跟踪的任务，或用原提交账号粘贴任务号查询。仅展示最近跟踪的任务，不代表全部历史。">
    <InfoNote v-if="!session.hasCredential" tone="warning">请先登录，再使用原提交账号查询任务。</InfoNote>
    <form class="ki-track" @submit.prevent="track"><label for="ingestion-job">任务 ID<input id="ingestion-job" v-model="jobIdInput" class="form-control" aria-label="入库任务 ID" /></label><button type="submit" class="btn btn--ghost" :disabled="!session.hasCredential || !jobIdInput.trim() || busyIds.includes(jobIdInput.trim())">跟踪任务</button></form>
    <InfoNote v-if="taskError" tone="danger" role="alert">{{ taskError }}</InfoNote>
    <InfoNote v-if="taskNote" tone="neutral" role="status">{{ taskNote }}</InfoNote>
    <p v-if="!state.jobs.length" class="ki-hint">当前会话尚未跟踪任何入库任务。上传文件后会自动跟踪，也可输入已有任务号。</p>
    <template v-else>
      <div class="ki-actions"><p class="ki-hint">本会话最多保留 {{ TRACKED_JOB_LIMIT }} 个任务。{{ monitoring ? '正在自动刷新；达到本轮跟踪上限后将暂停。' : '自动刷新已停止，可手动刷新或继续跟踪。' }}</p><button v-if="paused" class="btn btn--ghost btn--sm" @click="resume">继续自动跟踪</button></div>
      <div class="ki-table" tabindex="0" aria-label="入库任务列表"><table><thead><tr><th>任务 ID</th><th>文档 ID</th><th>版本</th><th>状态</th><th>自动重试次数</th><th>下次重试（北京时间）</th><th>操作</th></tr></thead><tbody><tr v-for="job in state.jobs" :key="job.jobId"><td>{{ job.jobId }}</td><td>{{ job.documentId }}</td><td>{{ job.documentVersion }}</td><td><span class="ki-badge" :data-tone="jobTone(job.status)">{{ jobLabel(job.status) }}</span></td><td>{{ job.retryCount ?? '未提供' }}</td><td>{{ job.nextRetryAt ? knowledgeDate(job.nextRetryAt) : '未安排' }}</td><td><div class="ki-actions"><button class="btn btn--ghost btn--sm" :aria-pressed="state.selectedId === job.jobId" @click="state.selectedId = job.jobId">任务详情</button><button class="btn btn--ghost btn--sm" :disabled="busyIds.includes(job.jobId)" @click="refresh(job.jobId)">刷新</button></div></td></tr></tbody></table></div>
    </template>
    <article v-if="selected" class="ki-detail" aria-label="入库任务详情">
      <h3>{{ jobLabel(selected.status) }}</h3>
      <dl class="ki-facts"><dt>任务 ID</dt><dd>{{ selected.jobId }}</dd><dt>文档 ID</dt><dd>{{ selected.documentId }}</dd><dt>文档版本</dt><dd>{{ selected.documentVersion }}</dd><dt v-if="selected.failedStage">失败阶段</dt><dd v-if="selected.failedStage">{{ stageLabel(selected.failedStage) }}</dd></dl>
      <InfoNote v-if="selected.errorCode" tone="warning"><p>{{ ingestionErrorHint(selected.errorCode) }}</p><p>错误码：{{ selected.errorCode }}</p></InfoNote>
      <p v-if="selected.status === IngestionStatus.Ready" class="ki-hint">该版本已发布，可以查询文档并测试检索。</p>
      <p v-else class="ki-hint">此任务尚未确认可检索。更新期间，之前已发布的版本继续可查。</p>
      <p v-if="selected.status === IngestionStatus.Processing" class="ki-hint">{{ INGESTION_PREPARATION_HINT }}</p>
      <div v-if="Object.keys(selected.sinks || {}).length" class="ki-table"><table><caption>索引与文档写入进度</caption><thead><tr><th>步骤</th><th>状态</th></tr></thead><tbody><tr v-for="(value, key) in selected.sinks" :key="key"><td>{{ stageLabel(key) }}</td><td>{{ sinkLabel(value) }}</td></tr></tbody></table></div>
      <template v-if="mayRetry(selected)"><p class="ki-hint">请先处理上述原因。重试会沿用原文件与已完成结果；需要更换原文件时，请联系管理员核对该文档版本，不能用重试替换文件。</p><InfoNote v-if="state.uncertainRetries.includes(selected.jobId)" tone="warning">重试结果尚未确认，请先刷新任务状态。</InfoNote><button class="btn btn--primary" :disabled="!gate.allowed || busyIds.includes(selected.jobId) || state.uncertainRetries.includes(selected.jobId)" @click="retry(selected)">已处理原因，重试原任务</button><p v-if="!gate.allowed" class="ki-hint">{{ gate.reason }}</p></template>
      <button v-if="selected.status === IngestionStatus.Ready" class="btn btn--primary" @click="emit('openDocument', selected)">查看已入库文档</button>
      <details v-if="selected.traceId || selected.contentHash" class="ki-receipt"><summary>排障信息</summary><dl class="ki-facts"><dt v-if="selected.traceId">追踪号</dt><dd v-if="selected.traceId">{{ selected.traceId }}</dd><dt v-if="selected.contentHash">内容摘要</dt><dd v-if="selected.contentHash">{{ selected.contentHash }}</dd></dl></details>
    </article>
  </WorkbenchSection>
</template>

<style scoped>
.ki-form,.ki-detail { display:grid; gap:var(--space-4); margin-top:var(--space-4); }
.ki-fields { border:0; padding:0; margin:0; display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:var(--space-4); min-width:0; }
.ki-field { display:grid; gap:var(--space-2); align-content:start; min-width:0; }
.ki-wide { grid-column:1/-1; }
.ki-field label,.ki-track label { font-weight:600; font-size:var(--fs-sm); }
.ki-field small,.ki-hint { color:var(--text-muted); font-size:var(--fs-sm); line-height:1.6; overflow-wrap:anywhere; }
.ki-hint { margin:var(--space-2) 0; }
.ki-actions,.ki-track { display:flex; align-items:center; flex-wrap:wrap; gap:var(--space-3); }
.ki-track { align-items:flex-end; margin-bottom:var(--space-4); }
.ki-track label { display:grid; gap:var(--space-2); flex:1; min-width:180px; }
.ki-table { overflow:auto; max-width:100%; margin:var(--space-3) 0; }
table { border-collapse:collapse; width:100%; font-size:var(--fs-sm); }
th,td { text-align:left; vertical-align:top; padding:var(--space-3); border-bottom:1px solid var(--border); min-width:90px; max-width:250px; overflow-wrap:anywhere; }
th { color:var(--text-muted); background:var(--surface-2); white-space:nowrap; }
td:last-child,th:last-child { position:sticky; right:0; background:var(--surface); }
caption { text-align:left; padding-bottom:var(--space-2); color:var(--text-muted); }
.ki-badge { display:inline-block; padding:4px 8px; border-radius:var(--radius); background:var(--surface-2); }
.ki-badge[data-tone='success'] { background:var(--success-soft); color:var(--success); }
.ki-badge[data-tone='warning'] { background:var(--warning-soft); color:var(--warning); }
.ki-detail { padding:var(--space-4); border:1px solid var(--border); border-radius:var(--radius); }
.ki-detail h3 { margin:0; font-size:var(--fs-base); }
.ki-detail > .btn { justify-self:start; }
.ki-detail td:last-child,.ki-detail th:last-child { position:static; background:transparent; }
.ki-facts { display:grid; grid-template-columns:minmax(100px,auto) 1fr; gap:var(--space-2) var(--space-4); font-size:var(--fs-sm); }
.ki-facts dt { color:var(--text-muted); }.ki-facts dd { margin:0; overflow-wrap:anywhere; }
.ki-receipt { font-size:var(--fs-sm); }.ki-receipt summary { cursor:pointer; color:var(--text-muted); }
fieldset:disabled { opacity:.8; pointer-events:none; }
@media(max-width:600px) { .ki-fields { grid-template-columns:1fr; }.ki-facts { grid-template-columns:1fr; gap:var(--space-2); }.ki-track { align-items:stretch; flex-direction:column; } }
</style>
