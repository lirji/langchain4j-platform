<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { useSessionStore } from '../../stores/session'
import { useAuthStore } from '../../stores/auth'
import { ApiError, humanizeError, isAbortError } from '../../api/errors'
import { decideMemory, fetchMemory, fetchMemoryAudit, setMemoryEnabled,
  type MemoryEntry, type MemorySnapshot, type MemoryAudit, type MemoryDecision } from '../../api/memoryGovernance'
import type { RunContext } from '../../types/api'
const session = useSessionStore()
const auth = useAuthStore()
const snapshot = ref<MemorySnapshot | null>(null)
const audit = ref<MemoryAudit[]>([])
const busy = ref(false)
const error = ref('')
const note = ref('')
const editing = ref<MemoryEntry | null>(null)
const draft = ref('')
const expiry = ref('')
const pendingDelete = ref<number | null>(null)
let sequence = 0
let controller: AbortController | undefined
function reset() {
  sequence++; controller?.abort(); busy.value = false; snapshot.value = null
  audit.value = []; editing.value = null; draft.value = ''; expiry.value = ''
  error.value = ''; note.value = ''; pendingDelete.value = null
}
watch(() => [session.apiKey, session.edgeBaseUrl, auth.accessToken], reset)
onUnmounted(reset)
async function run(operation?: (ctx: RunContext) => Promise<unknown>) {
  if (busy.value) return
  const seq = ++sequence
  controller?.abort(); controller = new AbortController()
  const ctx = session.runContext(controller.signal)
  busy.value = true; error.value = ''; note.value = ''
  try {
    if (operation) await operation(ctx)
    const data = await fetchMemory(ctx)
    if (seq !== sequence) return
    snapshot.value = data
    if (operation) { note.value = '已保存'; editing.value = null; pendingDelete.value = null; audit.value = [] }
  } catch (e) {
    if (seq !== sequence || isAbortError(e)) return
    error.value = e instanceof ApiError && e.status === 409
      ? '记忆已发生变化。请刷新后核对，再提交；你的编辑内容已保留。' : humanizeError(e)
  } finally { if (seq === sequence) busy.value = false }
}
function decide(row: MemoryEntry, action: MemoryDecision['action']) {
  return run(ctx => decideMemory(row.recordId, { expectedVersion: row.version, action }, ctx))
}
function edit(row: MemoryEntry) {
  editing.value = row; draft.value = row.text
  expiry.value = row.expiresAtEpochMs ? new Date(row.expiresAtEpochMs).toISOString().slice(0, 16) : ''
}
function save() {
  const row = editing.value
  if (!row || !draft.value.trim() || draft.value.length > 2000) return
  const expiresAtEpochMs = expiry.value ? Date.parse(`${expiry.value}Z`) : null
  if (expiresAtEpochMs !== null && !Number.isFinite(expiresAtEpochMs)) { error.value = '请输入有效时间'; return }
  return run(ctx => decideMemory(row.recordId, { expectedVersion: row.version, action: 'UPDATE',
    text: draft.value.trim(), type: row.type, expiresAtEpochMs }, ctx))
}
async function loadAudit() {
  if (busy.value) return
  const seq = ++sequence
  controller?.abort(); controller = new AbortController(); busy.value = true; error.value = ''
  try {
    const rows = await fetchMemoryAudit(session.runContext(controller.signal))
    if (seq === sequence) audit.value = rows
  } catch (e) { if (seq === sequence && !isAbortError(e)) error.value = humanizeError(e) }
  finally { if (seq === sequence) busy.value = false }
}
const actionNames: Record<string, string> = { PROPOSE: '抽取候选', APPROVE: '确认', REJECT: '拒绝', FORGET: '遗忘',
  UPDATE: '修改', PIN: '固定', UNPIN: '取消固定', CLEAR: '清空', ENABLE: '启用', DISABLE: '停用', ADD: '新增', EVICT: '容量回收' }
</script>
<template>
  <section class="memory-governance" aria-label="记忆治理">
    <button type="button" :disabled="busy" @click="run()">{{ snapshot ? '刷新记忆' : '管理记忆' }}</button>
    <p class="hint">抽取结果需确认后才用于后续对话。可修改、固定或遗忘，仅对当前用户生效。</p>
    <p v-if="busy" role="status">正在处理…</p><p v-if="error" role="alert">{{ error }}</p><p v-if="note" role="status">{{ note }}</p>
    <template v-if="snapshot">
      <div class="actions">
        <span>{{ snapshot.enabled ? '记忆已启用' : '记忆已停用，内容保留且不参与召回' }}</span>
        <button type="button" :disabled="busy" @click="run(ctx => setMemoryEnabled(!snapshot!.enabled, snapshot!.generation, ctx))">{{ snapshot.enabled ? '停用记忆' : '启用记忆' }}</button>
        <button type="button" :disabled="busy" @click="loadAudit">查看操作记录</button>
      </div>
      <p v-if="!snapshot.items.length">暂无记忆。在长期记忆模式对话后，可在此确认新候选。</p>
      <article v-for="row in snapshot.items" :key="row.recordId" class="memory-item">
        <div><strong>{{ row.state === 'PENDING' ? '待确认' : '已生效' }}</strong><span v-if="row.pinned"> · 已固定</span><span v-if="row.expiresAtEpochMs && row.expiresAtEpochMs <= Date.now()"> · 已过期，不参与召回</span></div>
        <p class="memory-text">{{ row.text }}</p>
        <small>来源会话：{{ row.sourceChatId || '未记录' }} · 版本 {{ row.version }}</small>
        <small v-if="row.expiresAtEpochMs"> · 有效期 {{ new Date(row.expiresAtEpochMs).toLocaleString() }}</small>
        <div class="actions">
          <template v-if="row.state === 'PENDING'"><button :disabled="busy" @click="decide(row, 'APPROVE')">确认记忆</button><button :disabled="busy" @click="decide(row, 'REJECT')">拒绝候选</button></template>
          <button :disabled="busy" @click="edit(row)">修改</button>
          <button v-if="row.state === 'ACTIVE'" :disabled="busy" @click="decide(row, row.pinned ? 'UNPIN' : 'PIN')">{{ row.pinned ? '取消固定' : '固定' }}</button>
          <button :disabled="busy" @click="pendingDelete = row.recordId">遗忘</button>
        </div>
        <div v-if="pendingDelete === row.recordId" class="confirm"><p>遗忘将删除这条内容，并阻止先前抽取任务写回。后续新对话仍可能产生新候选。</p><button :disabled="busy" @click="decide(row, 'FORGET')">确认遗忘</button><button :disabled="busy" @click="pendingDelete = null">取消</button></div>
        <form v-if="editing?.recordId === row.recordId" @submit.prevent="save">
          <label>记忆内容<textarea v-model="draft" maxlength="2000" rows="3" required /></label>
          <label>有效期（UTC，可留空）<input v-model="expiry" type="datetime-local" /></label>
          <button :disabled="busy || !draft.trim()">保存修改</button><button type="button" :disabled="busy" @click="editing = null">取消编辑</button>
        </form>
      </article>
      <ol v-if="audit.length" aria-label="操作记录"><li v-for="event in audit" :key="event.auditId">{{ new Date(event.occurredAtEpochMs).toLocaleString() }} · {{ actionNames[event.action] || event.action }} · 记录 {{ event.recordId || '全部' }}</li></ol>
    </template>
  </section>
</template>
<style scoped>
.memory-governance { margin-top: 1rem; border-top: 1px solid var(--border, #d7dce3); padding-top: 1rem; }
.hint, small { color: var(--text-muted, #667085); font-size: .85rem; }
.memory-item { border: 1px solid var(--border, #d7dce3); border-radius: 8px; padding: .8rem; margin: .7rem 0; }
.memory-text { white-space: pre-wrap; overflow-wrap: anywhere; }
.actions { display: flex; gap: .5rem; flex-wrap: wrap; align-items: center; margin-top: .6rem; }
button { min-height: 32px; cursor: pointer; } button:disabled { cursor: wait; opacity: .6; }
label { display: block; margin: .6rem 0; } textarea { display: block; width: 100%; box-sizing: border-box; }
[role='alert'] { color: var(--danger, #b42318); } .confirm { border-left: 3px solid #b42318; padding-left: .7rem; }
</style>
