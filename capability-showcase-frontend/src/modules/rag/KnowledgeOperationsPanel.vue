<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import WorkbenchSection from '../_shared/WorkbenchSection.vue'
import InfoNote from '../_shared/InfoNote.vue'
import './rag-governance.css'
import { useSessionStore } from '../../stores/session'
import { useAuthStore } from '../../stores/auth'
import { ApiError, humanizeError, isAbortError } from '../../api/errors'
import * as api from '../../api/knowledgeOperations'
import type { RunContext } from '../../types/api'
const props = defineProps<{ query?: string }>()
const session = useSessionStore(), auth = useAuthStore()
const busy = ref(false), error = ref(''), notice = ref(''), opened = ref(false)
const question = ref(''), comment = ref(''), documentId = ref(''), reason = ref<api.FeedbackReason>('NO_ANSWER')
const feedbacks = ref<api.Feedback[]>([]), candidates = ref<api.Candidate[]>([])
const selected = ref<api.Candidate | null>(null), history = ref<api.OperationAudit[]>([])
const summary = ref<Partial<Record<api.FeedbackReason, number>> | null>(null)
const title = ref(''), content = ref(''), category = ref(''), feedbackId = ref<string | null>(null)
const checks = ref<api.KnowledgeCheck[]>([{ question: '', requiredText: '' }]), editing = ref(false)
let sequence = 0, controller: AbortController | undefined
// 每个请求意图保留幂等键；未知响应重试不创建第二条记录，输入改变则视为新意图。
const intents = new Map<string, { fingerprint: string; key: string }>()
function key(kind: string, payload: unknown) {
  const fingerprint = JSON.stringify(payload), old = intents.get(kind)
  if (old?.fingerprint === fingerprint) return old.key
  const value = crypto.randomUUID(); intents.set(kind, { fingerprint, key: value }); return value
}
function reset() {
  sequence++; controller?.abort(); busy.value = false; opened.value = false; error.value = ''; notice.value = ''
  feedbacks.value = []; candidates.value = []; selected.value = null; history.value = []; summary.value = null
  question.value = ''; comment.value = ''; documentId.value = ''; reason.value = 'NO_ANSWER'; intents.clear(); clearDraft()
}
watch(() => [session.apiKey, session.edgeBaseUrl, auth.accessToken], reset)
onUnmounted(reset)
async function run<T>(operation: (ctx: RunContext) => Promise<T>, apply: (value: T) => void) {
  if (busy.value) return
  const seq = ++sequence; controller?.abort(); controller = new AbortController()
  busy.value = true; error.value = ''; notice.value = ''
  try { const value = await operation(session.runContext(controller.signal)); if (seq === sequence) apply(value) }
  catch (e) {
    if (seq === sequence && !isAbortError(e)) error.value = e instanceof ApiError && e.status === 409
      ? '版本或请求内容冲突。请刷新详情核对后重试；编辑内容已保留。'
      : e instanceof ApiError && e.status === 403 ? '没有知识运营权限，需要 ingest 权限。反馈仍可单独提交。' : humanizeError(e)
  } finally { if (seq === sequence) busy.value = false }
}
function feedback() {
  const payload = { question: question.value, comment: comment.value, documentId: documentId.value || null, reason: reason.value }
  return run(ctx => api.submitFeedback({ ...payload, idempotencyKey: key('feedback', payload) }, ctx), () => {
    notice.value = '反馈已记录'; comment.value = ''; intents.delete('feedback')
  })
}
function loadFeedback(next = false) {
  const before = next ? feedbacks.value.at(-1)?.id || '~' : '~'
  return run(ctx => api.listFeedback(true, before, ctx), rows => { feedbacks.value = rows; opened.value = true })
}
function loadCandidates(next = false) {
  const before = next ? candidates.value.at(-1)?.id || '~' : '~'
  return run(ctx => api.listCandidates(before, ctx), rows => { candidates.value = rows; opened.value = true })
}
function select(row: api.Candidate) { selected.value = row; history.value = []; summary.value = null; editing.value = false }
function clearDraft() {
  title.value = ''; content.value = ''; category.value = ''; feedbackId.value = null
  checks.value = [{ question: '', requiredText: '' }]; editing.value = false
}
function newDraft(source?: api.Feedback) {
  clearDraft(); selected.value = null; history.value = []; summary.value = null; editing.value = true
  if (source) { feedbackId.value = source.id; checks.value[0]!.question = source.input.question.slice(0, 500) }
}
function edit() {
  if (!selected.value) return
  const d = selected.value.draft; title.value = d.title; content.value = d.content; category.value = d.category || ''
  checks.value = d.checks.map(c => ({ ...c })); feedbackId.value = selected.value.feedbackId; editing.value = true
}
const editable = computed(() => selected.value && ['DRAFT', 'EVALUATED', 'APPROVED'].includes(selected.value.status))
function save() {
  const draft: api.KnowledgeDraft = { title: title.value, content: content.value, category: category.value || null, checks: checks.value.map(c => ({ ...c })) }
  const row = selected.value
  return run(ctx => row ? api.editCandidate(row.id, row.version, draft, ctx)
    : api.createCandidate(key('create', { draft, feedbackId: feedbackId.value }), feedbackId.value, draft, ctx), value => {
    select(value); notice.value = '草稿已保存；需要检查和审核后才能发布。'; intents.delete('create')
  })
}
function act(action: api.CandidateAction) {
  const row = selected.value; if (!row) return
  return run(ctx => api.actOnCandidate(row, action, ctx), value => {
    select(value)
    notice.value = value.status === 'PUBLISHING' ? '入库处理中，请稍后同步状态。' : '已更新，请核对状态和检查结果。'
  })
}
function refresh() {
  const row = selected.value; if (!row) return
  return run(ctx => api.getCandidate(row.id, ctx), value => { selected.value = value; history.value = []; summary.value = null })
}
function rollback() {
  const row = selected.value; if (!row) return
  return run(ctx => api.rollbackCandidate(row.id, key('rollback', row.id), ctx), value => {
    select(value); notice.value = '已创建旧内容草稿，需重新检查、审核和发布；当前线上版本未变。'; intents.delete('rollback')
  })
}
function effect() {
  const row = selected.value; if (!row) return
  return run(ctx => Promise.all([api.candidateHistory(row.id, ctx), api.candidateFeedback(row.id, ctx)]), ([events, counts]) => {
    history.value = events; summary.value = counts
  })
}
const statusNames: Record<api.CandidateStatus, string> = { DRAFT: '草稿', EVALUATED: '内容检查通过', APPROVED: '已审核', PUBLISHING: '发布中', PUBLISH_FAILED: '发布失败', PUBLISHED: '已发布' }
const reasonNames: Record<api.FeedbackReason, string> = { NO_ANSWER: '未找到答案', INCORRECT: '内容不正确', HELPFUL: '有帮助' }
</script>
<template>
  <WorkbenchSection class="rag-governance knowledge-operations" aria-label="知识运营闭环"
    title="知识运营闭环" subtitle="从检索反馈到知识修订，让每次发布都有据可查。">
    <template #actions><span class="governance-tag">反馈 · 修订 · 复查</span></template>
    <div class="ops-entry-grid">
      <form class="ops-feedback" aria-label="检索反馈" @submit.prevent="feedback">
        <div class="governance-section-head">
          <h3>记录检索反馈</h3>
          <button v-if="props.query" type="button" class="btn btn--ghost btn--sm" :disabled="busy" @click="question = props.query || ''">使用当前检索问题</button>
        </div>
        <label>检索问题<input class="form-control" v-model="question" required maxlength="2000" placeholder="输入需要改进的检索问题" /></label>
        <div class="ops-fields">
          <label>反馈类型<select class="form-control" v-model="reason"><option v-for="(name, value) in reasonNames" :key="value" :value="value">{{ name }}</option></select></label>
          <label>关联文档 ID（可选）<input class="form-control" v-model="documentId" maxlength="256" placeholder="填写本次检索关联的文档" /></label>
        </div>
        <label>补充说明<textarea class="form-control" v-model="comment" maxlength="4000" rows="3" placeholder="说明缺失的信息、错误内容或期望结果" /></label>
        <div class="governance-form-footer">
          <span class="governance-muted">反馈将进入收件箱，供后续修订参考。</span>
          <button class="btn btn--primary" :disabled="busy">提交反馈</button>
        </div>
      </form>
      <aside class="ops-manage" aria-label="知识运营入口">
        <div class="ops-manage-item">
          <span class="governance-eyebrow">01 / 收集问题</span>
          <h3>反馈收件箱</h3>
          <p>查看待处理的反馈，从具体问题发起知识修订。</p>
          <button class="btn btn--ghost" :disabled="busy" @click="loadFeedback()">查看反馈收件箱</button>
        </div>
        <div class="ops-manage-item">
          <span class="governance-eyebrow">02 / 修订与发布</span>
          <h3>知识修订</h3>
          <p>检查草稿、审核发布，并复查新版本的检索效果。</p>
          <button class="btn btn--ghost" :disabled="busy" @click="loadCandidates()">查看修订候选</button>
        </div>
        <p class="ops-hint">管理操作需要 ingest 权限。内容检查通过后，仍需人工审核事实准确性。</p>
      </aside>
    </div>
    <div class="governance-feedback" aria-live="polite">
      <InfoNote v-if="busy" tone="neutral" role="status">正在处理…</InfoNote>
      <InfoNote v-if="error" tone="danger" role="alert">{{ error }}</InfoNote>
      <InfoNote v-if="notice" tone="success" role="status">{{ notice }}</InfoNote>
    </div>
    <div v-if="opened" class="governance-block">
      <button class="btn btn--primary" :disabled="busy" @click="newDraft()">新建知识草稿</button>
      <ul v-if="feedbacks.length" class="ops-list"><li v-for="row in feedbacks" :key="row.id"><p>{{ row.input.question }} · {{ reasonNames[row.input.reason] }}</p><p>{{ row.input.comment }}</p><button class="btn btn--ghost" :disabled="busy" @click="newDraft(row)">基于此反馈修订</button></li></ul>
      <button class="btn btn--ghost" v-if="feedbacks.length === 20" :disabled="busy" @click="loadFeedback(true)">下一页反馈</button>
      <ul v-if="candidates.length" class="ops-list"><li v-for="row in candidates" :key="row.id"><button class="btn btn--ghost" :disabled="busy" @click="select(row)">{{ row.draft.title }} · {{ statusNames[row.status] }}</button></li></ul>
      <button class="btn btn--ghost" v-if="candidates.length === 20" :disabled="busy" @click="loadCandidates(true)">下一页候选</button>
      <p v-if="!feedbacks.length && !candidates.length" class="governance-empty">暂无记录，可提交反馈或新建草稿。</p>
    </div>
    <article v-if="selected" class="ops-detail governance-block">
      <h3>{{ selected.draft.title }} · {{ statusNames[selected.status] }}</h3>
      <p class="ops-hint">修订 {{ selected.version }} · 文档 {{ selected.documentId }}</p>
      <p v-if="selected.rollbackOf">旧内容来源：{{ selected.rollbackOf }}；重新发布将生成新的文档版本。</p>
      <p v-if="selected.release">文档版本 {{ selected.release.documentVersion || '待分配' }} · 审核人 {{ selected.release.reviewer }} · 入库任务 {{ selected.release.jobId || '尚未确认' }}</p>
      <p class="ops-content">{{ selected.draft.content }}</p>
      <div class="ops-actions governance-actions">
        <button class="btn btn--ghost" :disabled="busy" @click="refresh">刷新详情</button>
        <button class="btn btn--ghost" v-if="editable" :disabled="busy" @click="edit">编辑草稿</button>
        <button class="btn btn--ghost" v-if="editable" :disabled="busy || editing" @click="act('evaluate')">检查内容契约</button>
        <button class="btn btn--primary" v-if="selected.status === 'EVALUATED'" :disabled="busy || editing" @click="act('approve')">确认审核通过</button>
        <button class="btn btn--primary" v-if="selected.status === 'APPROVED'" :disabled="busy || editing" @click="act('publish')">发布到知识库</button>
        <button class="btn btn--ghost" v-if="['PUBLISHING', 'PUBLISH_FAILED'].includes(selected.status)" :disabled="busy" @click="act('publish')">恢复发布</button>
        <button class="btn btn--ghost" v-if="selected.release?.publisher" :disabled="busy" @click="act('sync')">同步入库状态</button>
        <button class="btn btn--ghost" v-if="selected.status === 'PUBLISHED'" :disabled="busy" @click="act('probe')">复查检索效果</button>
        <button class="btn btn--ghost" v-if="selected.status === 'PUBLISHED'" :disabled="busy" @click="rollback">用此版本创建回滚草稿</button>
        <button class="btn btn--ghost" :disabled="busy" @click="effect">查看反馈与审计</button>
      </div>
      <div v-for="evidence in [selected.evaluation, selected.probe].filter(Boolean)" :key="evidence!.kind" class="ops-evidence">
        <strong>{{ evidence!.kind === 'CONTENT_CONTRACT' ? '内容契约检查' : '检索复查' }}：{{ evidence!.passed }}/{{ evidence!.total }} 通过</strong>
        <ul><li v-for="(check, index) in evidence!.results" :key="index">{{ check.passed ? '通过' : '未通过' }} · {{ check.question }} · {{ check.reason }}</li></ul>
      </div>
      <p v-if="summary">关联文档主动反馈：未找到答案 {{ summary.NO_ANSWER || 0 }} · 内容不正确 {{ summary.INCORRECT || 0 }} · 有帮助 {{ summary.HELPFUL || 0 }}（不等于完整线上质量指标）</p>
      <ol v-if="history.length" class="ops-history"><li v-for="event in history" :key="event.id">{{ event.action }} · {{ event.actor }} · 修订 {{ event.version }} · {{ new Date(event.occurredAt).toLocaleString() }}</li></ol>
    </article>
    <form v-if="editing" class="governance-block" aria-label="知识草稿" @submit.prevent="save">
      <h3>{{ selected ? '编辑草稿' : '新建草稿' }}</h3><p v-if="feedbackId" class="ops-hint">来源反馈：{{ feedbackId }}</p>
      <label>标题<input class="form-control" v-model="title" required maxlength="200" /></label>
      <label>类目（可选）<input class="form-control" v-model="category" maxlength="128" /></label>
      <label>正文<textarea class="form-control" v-model="content" required maxlength="50000" rows="8" /></label>
      <fieldset><legend>内容与检索检查用例（最多 20 项）</legend>
        <div v-for="(check, index) in checks" :key="index" class="ops-check">
          <label>检查问题<input class="form-control" v-model="check.question" required maxlength="500" /></label>
          <label>必须包含的片段<input class="form-control" v-model="check.requiredText" required maxlength="500" /></label>
          <button class="btn btn--ghost" type="button" :disabled="busy || checks.length === 1" @click="checks.splice(index, 1)">移除此项</button>
        </div>
        <button class="btn btn--ghost" type="button" :disabled="busy || checks.length >= 20" @click="checks.push({ question: '', requiredText: '' })">添加检查项</button>
      </fieldset>
      <p class="ops-hint">保存修改后，旧的检查和审核失效。发布中的内容不能直接修改。</p>
      <div class="ops-actions governance-actions"><button class="btn btn--primary" :disabled="busy">保存草稿</button><button class="btn btn--ghost" type="button" :disabled="busy" @click="editing = false">取消编辑</button></div>
    </form>
  </WorkbenchSection>
</template>
