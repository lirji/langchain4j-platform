<script setup lang="ts">
import { knowledgeDate } from './knowledgePresentation'
import { ref, watch, onUnmounted } from 'vue'
import WorkbenchSection from '../_shared/WorkbenchSection.vue'
import InfoNote from '../_shared/InfoNote.vue'
import './rag-governance.css'
import { useSessionStore } from '../../stores/session'
import { useAuthStore } from '../../stores/auth'
import { ApiError, humanizeError, isAbortError } from '../../api/errors'
import { listFeedback, type Feedback } from '../../api/knowledgeOperations'
import * as api from '../../api/retrievalQuality'
import type { RunContext } from '../../types/api'
const session = useSessionStore(), auth = useAuthStore()
const busy = ref(false), error = ref(''), notice = ref(''), loaded = ref(false), editing = ref(false), confirmDiscard = ref(false)
const datasets = ref<api.Dataset[]>([]), selected = ref<api.Dataset | null>(null), runs = ref<api.QualityRun[]>([]), result = ref<api.QualityRun | null>(null)
const feedbacks = ref<Feedback[]>([]), label = ref(''), draft = ref<api.Definition>(empty())
let sequence = 0, controller: AbortController | undefined
const intents = new Map<string, { fingerprint: string; key: string }>()
function emptyCase(): api.QualityCase { return { id: crypto.randomUUID(), question: '', relevant: [{ visibility: 'tenant', docId: '' }], feedbackId: null } }
function empty(): api.Definition { return { name: '', cases: [emptyCase()], settings: { topK: 5, minScore: 0, category: null, allowedDrop: 0, allowedP95IncreaseMs: 1000 } } }
function intent(kind: string, value: unknown) {
  const fingerprint = JSON.stringify(value), old = intents.get(kind)
  if (old?.fingerprint === fingerprint) return old.key
  const key = crypto.randomUUID(); intents.set(kind, { fingerprint, key }); return key
}
function reset() {
  sequence++; controller?.abort(); busy.value = false; error.value = ''; notice.value = ''; loaded.value = false; editing.value = false; confirmDiscard.value = false
  datasets.value = []; selected.value = null; runs.value = []; result.value = null; feedbacks.value = []; draft.value = empty(); label.value = ''; intents.clear()
}
watch(() => [session.apiKey, session.edgeBaseUrl, auth.accessToken], reset)
onUnmounted(reset)
async function request<T>(work: (ctx: RunContext) => Promise<T>, apply: (value: T) => void) {
  if (busy.value) return
  const seq = ++sequence; controller?.abort(); controller = new AbortController(); busy.value = true; error.value = ''; notice.value = ''
  try { const value = await work(session.runContext(controller.signal)); if (seq === sequence) apply(value) }
  catch (e) { if (seq === sequence && !isAbortError(e)) error.value = e instanceof ApiError && e.status === 409
    ? '版本、基线或运行状态冲突。请刷新核对；未保存内容已保留。基线须来自同一评测定义和权限上下文。'
    : e instanceof ApiError && e.status === 403 ? '需要 ingest 权限才能管理和运行评测。'
    : `${humanizeError(e)}。执行结果可能已保存，可刷新运行记录或重试原请求。` }
  finally { if (seq === sequence) busy.value = false }
}
function load(next = false) { return request(ctx => api.listDatasets(next ? datasets.value.at(-1)?.id || '~' : '~', ctx), rows => { datasets.value = rows; loaded.value = true }) }
function select(row: api.Dataset) { selected.value = row; result.value = null; runs.value = []; label.value = ''; notice.value = ''; error.value = '' }
function edit(row: api.Dataset | null) {
  selected.value = row; draft.value = row ? JSON.parse(JSON.stringify(row.definition)) : empty(); editing.value = true; confirmDiscard.value = false; result.value = null; runs.value = []
}
function source(sample: api.QualityCase, id: string) {
  const row = feedbacks.value.find(item => item.id === id); sample.feedbackId = row?.id || null
  if (row) sample.question = row.input.question
}
function save() {
  const definition: api.Definition = JSON.parse(JSON.stringify(draft.value)); definition.settings.category ||= null
  const row = selected.value
  return request(ctx => row ? api.editDataset(row, definition, ctx) : api.createDataset(intent('create', definition), definition, ctx), value => {
    selected.value = value; editing.value = false; confirmDiscard.value = false; intents.delete('create'); notice.value = '评测集已保存。定义变更后需重新选择基线。'
    datasets.value = [value, ...datasets.value.filter(item => item.id !== value.id)].slice(0, 20)
  })
}
function refresh() { const row = selected.value; if (row) return request(ctx => api.getDataset(row.id, ctx), value => { selected.value = value }) }
function history(next = false) { const row = selected.value; if (row) return request(ctx => api.listRuns(row.id, next ? runs.value.at(-1)?.id || '~' : '~', ctx), rows => { runs.value = rows }) }
function execute() {
  const row = selected.value; if (!row) return
  return request(ctx => api.execute(row, intent('run', { id: row.id, revision: row.revision, label: label.value }), label.value, ctx), value => {
    result.value = value; runs.value = [value, ...runs.value.filter(item => item.id !== value.id)].slice(0, 20)
    if (value.status !== 'RUNNING') intents.delete('run')
  })
}
function baseline() {
  const row = selected.value, run = result.value; if (!row || !run) return
  return request(ctx => api.selectBaseline(row, run.id, ctx), value => { selected.value = value; notice.value = '已将此运行设为基线。下一次运行将按固定门槛比较。' })
}
function refreshRun(abandon = false) {
  const row = result.value; if (!row) return
  return request(ctx => abandon ? api.abandonRun(row.id, ctx) : api.getRun(row.id, ctx), value => { result.value = value; runs.value = runs.value.map(item => item.id === value.id ? value : item) })
}
const verdict: Record<string, string> = { BASELINE_REQUIRED: '尚无基线', PASS: '通过', REGRESSION: '发现退化', FAILED: '执行失败' }
function status(run: api.QualityRun) { return run.status === 'RUNNING' ? '执行中' : verdict[run.gate?.verdict || 'FAILED'] }
function percent(value: number) { return `${(value * 100).toFixed(1)}%` }
function delta(value: number | null) { return value === null ? '未比较' : `${value > 0 ? '+' : ''}${(value * 100).toFixed(1)} 个百分点` }
function reason(code: string) {
  const names: Record<string, string> = { recall_regression: '召回率下降', precision_regression: '精确率下降', mrr_regression: '首个相关文档排名下降', hit_rate_regression: '问题命中率下降', p95_regression: 'P95 延迟增加', incomplete_evaluation: '评测未完整执行', baseline_required: '先确认一次完整运行作为基线' }
  if (code.startsWith('case_recall:')) return `用例召回下降：${result.value?.snapshot.cases.find(c => c.id === code.slice(12))?.question || code.slice(12)}`
  return names[code] || code
}
</script>
<template>
  <WorkbenchSection class="rag-governance quality-panel" aria-label="检索质量评测"
    title="检索质量评测" subtitle="把真实反馈整理成评测集，持续比较知识与检索调整前后的效果。">
    <template #actions>
      <button class="btn btn--ghost" :disabled="busy || editing" @click="load()">查看评测集</button>
      <button class="btn btn--primary" :disabled="busy || editing" @click="edit(null)">新建评测集</button>
    </template>
    <p class="governance-muted">验证解析优化时，请先等待新文档版本显示“已可检索”，再使用相同评测集和检索设置比较。升级代码不会自动重建历史文档；此处不评估 OCR 准确率。</p>
    <ol v-if="!loaded && !editing && !selected" class="quality-workflow" aria-label="评测流程">
      <li><span class="quality-step" aria-hidden="true">01</span><div><h3>整理评测问题</h3><p>从反馈选题，标注应该命中的文档。</p></div></li>
      <li><span class="quality-step" aria-hidden="true">02</span><div><h3>建立检索基线</h3><p>运行评测，确认结果后设为基线。</p></div></li>
      <li><span class="quality-step" aria-hidden="true">03</span><div><h3>验证改进效果</h3><p>比较召回、排名与延迟，定位退化。</p></div></li>
    </ol>
    <p class="governance-scope">评测范围：文档检索质量与延迟。回答质量和模型费用不在本次评测内。</p>
    <div class="governance-feedback" aria-live="polite">
      <InfoNote v-if="busy" tone="neutral" role="status">正在处理，请稍候。运行请求中断后可查看历史确认结果。</InfoNote>
      <InfoNote v-if="error" tone="danger" role="alert">{{ error }}</InfoNote>
      <InfoNote v-if="notice" tone="success" role="status">{{ notice }}</InfoNote>
    </div>
    <ul v-if="loaded" class="quality-datasets">
      <li v-for="row in datasets" :key="row.id" :class="{ 'is-selected': selected?.id === row.id }">
        <button class="btn btn--ghost quality-dataset-name" :aria-pressed="selected?.id === row.id" :disabled="busy || editing" @click="select(row)">{{ row.definition.name }}</button>
        <div class="quality-dataset-meta"><span>{{ row.definition.cases.length }} 题</span><span class="governance-tag" :data-tone="row.baselineRunId ? 'success' : 'neutral'">{{ row.baselineRunId ? '已设基线' : '尚无基线' }}</span></div>
      </li>
    </ul>
    <p v-if="loaded && !datasets.length" class="governance-empty">暂无评测集，可从反馈开始建立。</p>
    <button class="btn btn--ghost" v-if="datasets.length === 20" :disabled="busy || editing" @click="load(true)">下一页评测集</button>
    <form v-if="editing" class="quality-editor governance-block" aria-label="评测集编辑" @submit.prevent="save">
      <fieldset :disabled="busy">
        <legend>{{ selected ? '编辑评测集（保存后清除当前基线）' : '新建评测集' }}</legend>
        <label class="quality-name">名称<input class="form-control" v-model="draft.name" required maxlength="128" placeholder="为这组检索问题起一个容易识别的名称" /></label>
        <h3 class="quality-group-title">检索设置与退化门槛</h3>
        <div class="fields quality-settings">
          <label>检索条数 K<input class="form-control" v-model.number="draft.settings.topK" type="number" min="1" max="20" required /></label>
          <label>最低相似度<input class="form-control" v-model.number="draft.settings.minScore" type="number" min="0" max="1" step="0.01" required /></label>
          <label>分类（可选）<input class="form-control" v-model="draft.settings.category" maxlength="128" /></label>
          <label>允许质量下降（0～1）<input class="form-control" v-model.number="draft.settings.allowedDrop" type="number" min="0" max="1" step="0.01" required /></label>
          <label>允许 P95 增加（毫秒）<input class="form-control" v-model.number="draft.settings.allowedP95IncreaseMs" type="number" min="0" max="120000" required /></label>
        </div>
        <button class="btn btn--ghost btn--sm" type="button" @click="request(ctx => listFeedback(true, '~', ctx), rows => { feedbacks = rows })">加载近期反馈</button>
        <button class="btn btn--ghost" v-if="feedbacks.length === 20" type="button" @click="request(ctx => listFeedback(true, feedbacks.at(-1)!.id, ctx), rows => { feedbacks = rows })">下一页反馈来源</button>
        <article v-for="(sample, i) in draft.cases" :key="sample.id" class="sample">
          <div class="governance-section-head"><h3>用例 {{ i + 1 }}</h3><span class="governance-tag">{{ sample.feedbackId ? '来源于反馈' : '手动标注' }}</span></div>
          <label>反馈来源<select class="form-control" :value="sample.feedbackId || ''" @change="source(sample, ($event.target as HTMLSelectElement).value)"><option value="">手动标注</option><option v-if="sample.feedbackId && !feedbacks.some(f => f.id === sample.feedbackId)" :value="sample.feedbackId">已关联反馈</option><option v-for="row in feedbacks" :key="row.id" :value="row.id">{{ row.input.question }}</option></select></label>
          <label>问题<textarea class="form-control" v-model="sample.question" :readonly="!!sample.feedbackId" required maxlength="2000" rows="2" /></label>
          <p class="governance-muted">请人工确认相关文档 ID；反馈中提到的文档不一定是正确结果。</p>
          <div v-for="(target, j) in sample.relevant" :key="j" class="fields quality-target">
            <label>知识范围<select class="form-control" v-model="target.visibility"><option value="tenant">本租户</option><option value="public">共享库</option></select></label>
            <label>相关文档 ID<input class="form-control" v-model="target.docId" required maxlength="256" /></label>
            <button class="btn btn--ghost" v-if="sample.relevant.length > 1" type="button" @click="sample.relevant.splice(j, 1)">移除文档</button>
          </div>
          <div class="actions governance-actions"><button class="btn btn--ghost" type="button" :disabled="sample.relevant.length >= 20" @click="sample.relevant.push({ visibility: 'tenant', docId: '' })">添加相关文档</button><button class="btn btn--ghost" type="button" :disabled="draft.cases.length === 1" @click="draft.cases.splice(i, 1)">移除此用例</button></div>
        </article>
        <div class="actions governance-actions"><button class="btn btn--ghost" type="button" :disabled="draft.cases.length >= 20" @click="draft.cases.push(emptyCase())">添加用例（最多20题）</button><button class="btn btn--primary" type="submit">保存评测集</button><button class="btn btn--ghost" type="button" @click="confirmDiscard = true">取消编辑</button></div>
        <div v-if="confirmDiscard" class="quality-discard" role="alert"><p>放弃本次未保存的编辑？</p><button class="btn btn--ghost" type="button" @click="editing = false; confirmDiscard = false">放弃编辑</button><button class="btn btn--ghost" type="button" @click="confirmDiscard = false">继续编辑</button></div>
      </fieldset>
    </form>
    <article v-if="selected" class="quality-detail governance-block">
      <h3>{{ selected.definition.name }} · {{ selected.definition.cases.length }} 题</h3>
      <p class="governance-muted">修订 {{ selected.revision }} · {{ selected.baselineRunId ? `基线 ${selected.baselineRunId}` : '尚无基线，首次运行不会判为通过。' }}</p>
      <div class="actions governance-actions"><button class="btn btn--ghost" :disabled="busy" @click="refresh">刷新评测集</button><button class="btn btn--ghost" :disabled="busy || editing" @click="edit(selected)">编辑定义</button><button class="btn btn--ghost" :disabled="busy || editing" @click="history()">查看运行历史</button></div>
      <form v-if="!editing" class="quality-execute" aria-label="执行检索评测" @submit.prevent="execute">
        <label>本次运行说明<input class="form-control" v-model="label" required maxlength="128" placeholder="例如：调整分块后的验证" :disabled="busy" /></label><button class="btn btn--primary" :disabled="busy">执行检索评测</button>
      </form>
      <ul class="quality-history"><li v-for="run in runs" :key="run.id"><button class="btn btn--ghost" :disabled="busy || editing" @click="result = run">{{ run.label }} · {{ status(run) }}</button> · {{ knowledgeDate(run.startedAt) }}（北京时间）</li></ul>
      <button class="btn btn--ghost" v-if="runs.length === 20" :disabled="busy || editing" @click="history(true)">下一页运行</button>
    </article>
    <article v-if="result && !editing" class="result governance-block" :data-verdict="result.gate?.verdict || result.status">
      <h3>{{ result.label }}：{{ status(result) }}</h3>
      <p class="governance-muted">固定评测集修订 {{ result.datasetRevision }} · K={{ result.snapshot.settings.topK }} · 基线 {{ result.baselineRunId || '无' }}</p>
      <div class="actions governance-actions"><button class="btn btn--ghost" :disabled="busy" @click="refreshRun()">刷新运行结果</button><button class="btn btn--primary" v-if="result.status === 'SUCCEEDED'" :disabled="busy" @click="baseline">将此运行设为基线</button><button class="btn btn--ghost" v-if="result.status === 'RUNNING'" :disabled="busy" @click="refreshRun(true)">终止过期运行</button></div>
      <p v-if="result.status === 'RUNNING'">运行尚未结束。服务端两分钟期限到达后可终止过期记录；终止不会撤销已发生的检索调用。</p>
      <p v-if="result.status === 'FAILED'" class="quality-failure" role="alert">本次执行未完成，不能作为通过证据或基线。请检查检索服务后发起新的运行。</p>
      <dl v-if="result.metrics" class="quality-metrics">
        <div><dt>召回率</dt><dd>{{ percent(result.metrics.recall) }}</dd></div>
        <div><dt>精确率</dt><dd>{{ percent(result.metrics.precision) }}</dd></div>
        <div><dt>MRR</dt><dd>{{ result.metrics.mrr.toFixed(3) }}</dd></div>
        <div><dt>命中率</dt><dd>{{ percent(result.metrics.hitRate) }}</dd></div>
        <div><dt>P50 延迟</dt><dd>{{ result.metrics.p50Ms }} <small>ms</small></dd></div>
        <div><dt>P95 延迟</dt><dd>{{ result.metrics.p95Ms }} <small>ms</small></dd></div>
      </dl>
      <p v-if="result.gate && result.gate.recallDelta !== null">相对基线：召回 {{ delta(result.gate.recallDelta) }}；精确率 {{ delta(result.gate.precisionDelta) }}；MRR {{ result.gate.mrrDelta?.toFixed(3) }}；命中率 {{ delta(result.gate.hitRateDelta) }}；P95 {{ result.gate.p95DeltaMs }} ms。</p>
      <ul v-if="result.gate"><li v-for="item in result.gate.reasons" :key="item">{{ reason(item) }}</li></ul>
      <div class="table-scroll"><table><caption>逐题结果（相关文档按范围和ID匹配，重复片段不重复计数）</caption><thead><tr><th>问题</th><th>相关文档</th><th>命中文档 / 版本</th><th>召回率</th><th>耗时</th></tr></thead><tbody><tr v-for="item in result.results" :key="item.caseId"><td>{{ result.snapshot.cases.find(c => c.id === item.caseId)?.question }}</td><td><div v-for="target in result.snapshot.cases.find(c => c.id === item.caseId)?.relevant" :key="`${target.visibility}:${target.docId}`">{{ target.visibility === 'public' ? '共享' : '租户' }} / {{ target.docId }}</div></td><td><div v-for="hit in item.retrieved" :key="`${hit.document.visibility}:${hit.document.docId}`">{{ hit.document.visibility === 'public' ? '共享' : '租户' }} / {{ hit.document.docId }} / v{{ hit.version || '未知' }}</div><span v-if="!item.retrieved.length">未命中文档</span></td><td>{{ percent(item.recall) }}</td><td>{{ item.latencyMs }} ms</td></tr></tbody></table></div>
      <p class="governance-muted">精确率以实际返回的不同文档数为分母；小样本延迟仅用于本次比较。运行说明是人工备注，不代表模型或索引版本已固定。</p>
    </article>
  </WorkbenchSection>
</template>
