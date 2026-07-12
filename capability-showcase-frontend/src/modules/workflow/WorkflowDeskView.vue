<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useCatalogStore } from '../../stores/catalog'
import { useSessionStore } from '../../stores/session'
import { runCapability } from '../../api/client'
import { humanizeError } from '../../api/errors'
import { executionGate } from '../../utils/gate'
import type { Capability } from '../../types/catalog'
import type { FormValues } from '../../utils/validation'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import ModuleHeader from '../../components/layout/ModuleHeader.vue'
import WorkbenchSection from '../_shared/WorkbenchSection.vue'
import InfoNote from '../_shared/InfoNote.vue'

const props = defineProps<{ moduleId: string; capId?: string }>()
const catalog = useCatalogStore()
const session = useSessionStore()

const module = computed(() => catalog.moduleById(props.moduleId))
const focusedCap = computed(() =>
  props.capId ? (module.value?.capabilities ?? []).find((c) => c.id === props.capId) : undefined,
)

// 各分区能力（catalog 为事实源；缺失则优雅跳过对应区块）。
const startCap = computed(() => catalog.capabilityById('workflow.refund.start'))
const tasksListCap = computed(() => catalog.capabilityById('workflow.tasks.list'))
const claimCap = computed(() => catalog.capabilityById('workflow.tasks.claim'))
const unclaimCap = computed(() => catalog.capabilityById('workflow.tasks.unclaim'))
const completeCap = computed(() => catalog.capabilityById('workflow.tasks.complete'))
const instancesGetCap = computed(() => catalog.capabilityById('workflow.instances.get'))
const purgeCap = computed(() => catalog.capabilityById('workflow.data.purge'))

// ── 本会话串联状态（不落库，刷新即清空）──
interface StartedItem {
  instanceId?: string
  taskId?: string
  status?: string
  reply?: string
  deduplicated: boolean
  at: string
}
interface InboxTask {
  taskId: string
  name?: string
  instanceId?: string
  priority?: string
  summary?: string
  assignee?: string
}

const startedItems = ref<StartedItem[]>([])
const inboxTasks = ref<InboxTask[]>([])
const inboxLoaded = ref(false)
const selectedTaskId = ref<string | null>(null)
const actionError = ref<string | null>(null)
const actionNote = ref<string | null>(null)
const busyKey = ref<string | null>(null)
const comments = reactive<Record<string, string>>({})

const selectedTask = computed<InboxTask | null>(
  () => inboxTasks.value.find((t) => t.taskId === selectedTaskId.value) ?? null,
)

function asStr(v: unknown): string | undefined {
  if (v == null) return undefined
  if (typeof v === 'string') return v.trim() ? v : undefined
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  return undefined
}

/** 统一优先级徽章：P0=danger / P1=warning / P2=neutral（取字符串中的数字位；无则 neutral）。 */
function prioDigit(p?: string): number | null {
  if (!p) return null
  const m = /\d/.exec(p)
  return m ? Number(m[0]) : null
}
function prioLabel(p?: string): string {
  const d = prioDigit(p)
  return d == null ? String(p) : `P${d}`
}
function prioTone(p?: string): 'danger' | 'warning' | 'neutral' {
  const d = prioDigit(p)
  if (d === 0) return 'danger'
  if (d === 1) return 'warning'
  return 'neutral'
}

/** 复用 executionGate + runCapability 驱动一次动作（不手写 fetch，不绕过安全闸门）。 */
async function exec(cap: Capability | undefined, values: FormValues, key: string): Promise<unknown> {
  if (!cap) return undefined
  const gate = executionGate(cap, { hasApiKey: session.hasCredential, confirmed: false })
  if (!gate.allowed) {
    actionError.value = gate.reason ?? '当前不可执行。'
    return undefined
  }
  busyKey.value = key
  actionError.value = null
  actionNote.value = null
  try {
    const res = await runCapability(cap, values, session.runContext())
    return res.data
  } catch (e) {
    actionError.value = humanizeError(e, cap)
    return undefined
  } finally {
    busyKey.value = null
  }
}

/** CapabilityRunner 成功回调：把 StartResult 记入本地列表，便于后续认领/审批。 */
function onStartResult(payload: { cap: Capability; data: unknown; status: number | null }): void {
  const d = payload.data
  if (!d || typeof d !== 'object') return
  const o = d as Record<string, unknown>
  startedItems.value.unshift({
    instanceId: asStr(o.instanceId),
    taskId: asStr(o.taskId),
    status: asStr(o.status),
    reply: asStr(o.reply),
    deduplicated: o.deduplicated === true,
    at: new Date().toLocaleTimeString(),
  })
}

function parseInbox(data: unknown): InboxTask[] {
  const arr = Array.isArray(data) ? data : []
  return arr
    .filter((x): x is Record<string, unknown> => !!x && typeof x === 'object')
    .map((o) => ({
      taskId: asStr(o.taskId) ?? '',
      name: asStr(o.name),
      instanceId: asStr(o.instanceId),
      priority: asStr(o.priority),
      summary: asStr(o.summary),
      assignee: asStr(o.assignee),
    }))
    .filter((t) => t.taskId)
}

async function refreshInbox(): Promise<void> {
  const data = await exec(tasksListCap.value, {}, 'list')
  if (data !== undefined) {
    inboxTasks.value = parseInbox(data)
    inboxLoaded.value = true
    // 保持选中；若选中项已不在列表则回退到首项，便于连续审批。
    if (!inboxTasks.value.some((t) => t.taskId === selectedTaskId.value)) {
      selectedTaskId.value = inboxTasks.value[0]?.taskId ?? null
    }
    if (!inboxTasks.value.length) actionNote.value = '当前没有待办审批任务。'
  }
}

function selectTask(taskId: string): void {
  selectedTaskId.value = taskId
}

async function claim(taskId: string): Promise<void> {
  await exec(claimCap.value, { taskId }, `claim:${taskId}`)
  await refreshInbox()
}
async function unclaim(taskId: string): Promise<void> {
  await exec(unclaimCap.value, { taskId }, `unclaim:${taskId}`)
  await refreshInbox()
}
async function complete(taskId: string, approved: boolean): Promise<void> {
  const comment = comments[taskId]?.trim()
  const values: FormValues = { taskId, approved }
  if (comment) values.comment = comment
  const data = await exec(completeCap.value, values, `complete:${taskId}`)
  if (data !== undefined) {
    actionNote.value = `任务 ${taskId} 已${approved ? '通过' : '驳回'}。`
    comments[taskId] = ''
  }
  await refreshInbox()
}

const canAct = computed(() => session.hasCredential && busyKey.value === null)
const approveScopeHint = computed(() => {
  const scopes = tasksListCap.value?.requiredScopes ?? []
  return scopes.length ? scopes.join(' / ') : 'approve'
})
</script>

<template>
  <EmptyState
    v-if="!module"
    variant="error"
    title="模块不存在"
    :description="`未找到模块「${moduleId}」。`"
  />

  <!-- 深链：单能力聚焦（沿用通用运行器，含 gate/curl/危险确认） -->
  <CapabilityRunner v-else-if="capId && focusedCap" :key="focusedCap.id" :cap="focusedCap" />

  <EmptyState
    v-else-if="capId && !focusedCap"
    variant="error"
    title="能力不存在"
    :description="`模块「${module.title}」下未找到能力「${capId}」。`"
  />

  <!-- 工作台着陆：发起 → 待办工作台(主从) → 实例 → 危险区 -->
  <div v-else class="wf">
    <ModuleHeader :module-id="moduleId" />

    <!-- ① 发起退款流程（可折叠） -->
    <WorkbenchSection
      v-if="startCap"
      title="① 发起退款流程"
      subtitle="启动退款审批 BPMN；成功后返回的实例 / 任务会记入下方「本会话」列表，便于直接认领与审批。"
      collapsible
    >
      <CapabilityRunner :cap="startCap" @result="onStartResult" />

      <div v-if="startedItems.length" class="wf__started" aria-live="polite">
        <h3 class="wf__sub">本会话发起的实例 / 任务</h3>
        <ul class="wf__list">
          <li v-for="(it, i) in startedItems" :key="i" class="wf__item">
            <div class="wf__item-main">
              <span v-if="it.status" class="wf__status">{{ it.status }}</span>
              <span v-if="it.deduplicated" class="wf__dedupe" title="幂等命中：未重复发起">去重命中</span>
              <code v-if="it.instanceId" class="wf__id">instance: {{ it.instanceId }}</code>
              <code v-if="it.taskId" class="wf__id">task: {{ it.taskId }}</code>
              <span class="wf__at">{{ it.at }}</span>
            </div>
            <p v-if="it.reply" class="wf__reply">{{ it.reply }}</p>
            <div v-if="it.taskId && completeCap" class="wf__row-actions">
              <input
                v-model="comments[it.taskId]"
                class="form-control wf__comment"
                type="text"
                placeholder="审批意见(可选)"
                :aria-label="`任务 ${it.taskId} 审批意见`"
              />
              <button
                v-if="claimCap"
                type="button"
                class="btn wf__btn"
                :disabled="!canAct"
                @click="claim(it.taskId!)"
              >
                认领
              </button>
              <button
                type="button"
                class="btn btn--primary wf__btn"
                :disabled="!canAct"
                @click="complete(it.taskId!, true)"
              >
                通过
              </button>
              <button
                type="button"
                class="btn btn--danger wf__btn"
                :disabled="!canAct"
                @click="complete(it.taskId!, false)"
              >
                驳回
              </button>
            </div>
          </li>
        </ul>
      </div>
    </WorkbenchSection>

    <!-- ② 待办清单（主从工作台：左列表 / 右详情+审批动作） -->
    <WorkbenchSection
      v-if="tasksListCap"
      title="② 待办清单"
      subtitle="拉取待办后在左侧选择任务，右侧填写审批意见并认领 / 通过 / 驳回；taskId 自动带入，无需手填。"
    >
      <template #actions>
        <button type="button" class="btn btn--primary" :disabled="!canAct" @click="refreshInbox">
          {{ busyKey === 'list' ? '刷新中…' : '刷新待办' }}
        </button>
      </template>
      <template #notice>
        <InfoNote tone="warning">
          审批相关能力需 <strong>{{ approveScopeHint }}</strong> scope；若当前 API Key 不具备，将返回 403（已翻译为人话提示）。
        </InfoNote>
        <InfoNote v-if="actionError" tone="danger" role="alert">{{ actionError }}</InfoNote>
        <InfoNote v-else-if="actionNote" tone="success">{{ actionNote }}</InfoNote>
      </template>

      <p v-if="!session.hasCredential" class="wf__nokey">请先登录 才能拉取待办。</p>

      <div class="wf__md">
        <!-- 主：待办清单 -->
        <div class="wf__master">
          <ul v-if="inboxTasks.length" class="wf__tasklist" aria-live="polite" role="listbox">
            <li v-for="t in inboxTasks" :key="t.taskId">
              <button
                type="button"
                class="wf__task"
                :class="{ 'is-selected': t.taskId === selectedTaskId }"
                role="option"
                :aria-selected="t.taskId === selectedTaskId"
                @click="selectTask(t.taskId)"
              >
                <span class="wf__task-top">
                  <span v-if="t.priority" class="wf__prio" :data-tone="prioTone(t.priority)">
                    {{ prioLabel(t.priority) }}
                  </span>
                  <strong class="wf__task-name">{{ t.name ?? '审批任务' }}</strong>
                </span>
                <code class="wf__id wf__id--block">task: {{ t.taskId }}</code>
                <span v-if="t.assignee" class="wf__assignee">已认领 @{{ t.assignee }}</span>
                <span v-else class="wf__unassigned">未认领</span>
              </button>
            </li>
          </ul>
          <EmptyState
            v-else-if="inboxLoaded"
            variant="empty"
            icon="✅"
            title="没有待办任务"
            description="当前租户下暂无待认领 / 待处理的审批任务。"
          />
          <EmptyState
            v-else
            variant="empty"
            icon="📥"
            title="尚未拉取待办"
            description="点击右上「刷新待办」拉取当前租户下的审批任务。"
          />
        </div>

        <!-- 从：任务详情 + 审批动作（桌面吸顶） -->
        <div class="wf__detail">
          <div v-if="selectedTask" class="wf__detail-card">
            <div class="wf__detail-head">
              <span
                v-if="selectedTask.priority"
                class="wf__prio"
                :data-tone="prioTone(selectedTask.priority)"
              >
                {{ prioLabel(selectedTask.priority) }}
              </span>
              <h3 class="wf__detail-title">{{ selectedTask.name ?? '审批任务' }}</h3>
            </div>
            <dl class="wf__kv">
              <div class="wf__kv-row">
                <dt>任务</dt>
                <dd><code class="wf__id">{{ selectedTask.taskId }}</code></dd>
              </div>
              <div v-if="selectedTask.instanceId" class="wf__kv-row">
                <dt>实例</dt>
                <dd><code class="wf__id">{{ selectedTask.instanceId }}</code></dd>
              </div>
              <div class="wf__kv-row">
                <dt>认领</dt>
                <dd>
                  <span v-if="selectedTask.assignee" class="wf__assignee">@{{ selectedTask.assignee }}</span>
                  <span v-else class="wf__unassigned">未认领</span>
                </dd>
              </div>
            </dl>
            <p v-if="selectedTask.summary" class="wf__reply">{{ selectedTask.summary }}</p>

            <label class="wf__comment-label">
              审批意见（可选）
              <textarea
                v-model="comments[selectedTask.taskId]"
                class="form-control wf__comment-area"
                rows="2"
                placeholder="填写审批意见，将随「通过 / 驳回」一并提交…"
                :aria-label="`任务 ${selectedTask.taskId} 审批意见`"
              />
            </label>

            <div class="wf__detail-actions">
              <button
                v-if="claimCap"
                type="button"
                class="btn wf__btn"
                :disabled="!canAct"
                @click="claim(selectedTask.taskId)"
              >
                {{ busyKey === `claim:${selectedTask.taskId}` ? '认领中…' : '认领' }}
              </button>
              <button
                v-if="unclaimCap"
                type="button"
                class="btn wf__btn"
                :disabled="!canAct"
                @click="unclaim(selectedTask.taskId)"
              >
                {{ busyKey === `unclaim:${selectedTask.taskId}` ? '处理中…' : '取消认领' }}
              </button>
              <span class="wf__actions-spacer" />
              <button
                v-if="completeCap"
                type="button"
                class="btn btn--primary wf__btn"
                :disabled="!canAct"
                @click="complete(selectedTask.taskId, true)"
              >
                {{ busyKey === `complete:${selectedTask.taskId}` ? '提交中…' : '✓ 通过' }}
              </button>
              <button
                v-if="completeCap"
                type="button"
                class="btn btn--danger wf__btn"
                :disabled="!canAct"
                @click="complete(selectedTask.taskId, false)"
              >
                ✕ 驳回
              </button>
            </div>
          </div>

          <EmptyState
            v-else
            variant="empty"
            icon="👈"
            title="选择一个待办任务"
            description="从左侧待办清单选中任务后，在此查看详情并执行审批动作。"
          />
        </div>
      </div>
    </WorkbenchSection>

    <!-- ③ 实例查询 -->
    <WorkbenchSection
      v-if="instancesGetCap"
      title="③ 实例查询"
      subtitle="用上方列表中的 instanceId 查询流程实例状态与 reply。"
    >
      <CapabilityRunner :cap="instancesGetCap" />
    </WorkbenchSection>

    <!-- ④ 危险区（保持锁定） -->
    <WorkbenchSection
      v-if="purgeCap"
      title="④ 危险区"
      subtitle="合规删除会不可逆地清理工作流数据。"
    >
      <template #notice>
        <InfoNote tone="danger">
          <strong>破坏性操作，默认锁定。</strong> 仅可预览 / 复制 curl；如确需执行必须显式二次确认。需
          <strong>{{ (purgeCap.requiredScopes[0] ?? 'approve') }}</strong> scope。
        </InfoNote>
      </template>
      <CapabilityRunner :cap="purgeCap" />
    </WorkbenchSection>
  </div>
</template>

<style scoped>
.wf {
  max-width: var(--content-max);
  margin: 0 auto;
  padding: var(--space-6) var(--space-5);
}
.wf__sub {
  font-size: var(--fs-sm);
  font-weight: 700;
  color: var(--text-muted);
  margin-bottom: var(--space-2);
}
.wf__started {
  margin-top: var(--space-4);
}
.wf__list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.wf__item {
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
}
.wf__item-main {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.wf__status {
  font-size: var(--fs-xs);
  font-weight: 700;
  padding: 1px 7px;
  border-radius: var(--radius-sm);
  color: var(--primary);
  background: var(--primary-soft);
  border: 1px solid var(--primary-border);
}
.wf__dedupe {
  font-size: var(--fs-xs);
  font-weight: 700;
  padding: 1px 7px;
  border-radius: var(--radius-sm);
  color: var(--warning);
  background: var(--warning-soft);
  border: 1px solid var(--warning-border);
}
/* 统一优先级徽章：P0=danger / P1=warning / P2=neutral */
.wf__prio {
  font-size: var(--fs-xs);
  font-weight: 700;
  padding: 1px 7px;
  border-radius: var(--radius-sm);
  color: var(--neutral);
  background: var(--neutral-soft);
  border: 1px solid var(--neutral-border);
}
.wf__prio[data-tone='danger'] {
  color: var(--danger);
  background: var(--danger-soft);
  border-color: var(--danger-border);
}
.wf__prio[data-tone='warning'] {
  color: var(--warning);
  background: var(--warning-soft);
  border-color: var(--warning-border);
}
.wf__id {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  background: var(--surface-2);
  padding: 1px 6px;
  border-radius: var(--radius-sm);
}
.wf__id--block {
  display: inline-block;
  width: fit-content;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wf__at {
  margin-left: auto;
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.wf__assignee {
  font-size: var(--fs-xs);
  color: var(--stream);
}
.wf__unassigned {
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.wf__reply {
  margin-top: var(--space-2);
  font-size: var(--fs-sm);
  color: var(--text-muted);
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
.wf__row-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  margin-top: var(--space-3);
}
.wf__comment {
  width: auto;
  flex: 1;
  min-width: 160px;
}
.wf__btn {
  padding: 6px 12px;
}
.wf__nokey {
  font-size: var(--fs-sm);
  color: var(--warning);
  margin-bottom: var(--space-3);
}

/* ── 主从工作台布局 ── */
.wf__md {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.25fr);
  gap: var(--space-4);
  align-items: start;
}
.wf__master {
  min-width: 0;
}
.wf__tasklist {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
/* 列表项：实底玻璃卡（不叠 blur），选中态 inset primary 高亮 */
.wf__task {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  text-align: left;
  padding: var(--space-3);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius);
  background: var(--glass-bg);
  cursor: pointer;
  transition: border-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease),
    background var(--dur) var(--ease);
}
.wf__task:hover {
  border-color: var(--primary-border);
  background: var(--surface);
}
.wf__task.is-selected {
  border-color: var(--primary);
  box-shadow: inset 0 0 0 1px var(--primary);
  background: var(--primary-soft);
}
.wf__task:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--primary-border);
}
.wf__task-top {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.wf__task-name {
  font-size: var(--fs-sm);
  color: var(--text);
}

/* 详情面：桌面吸顶；玻璃强面（少数大面允许 blur） */
.wf__detail {
  min-width: 0;
}
.wf__detail-card {
  padding: var(--space-4);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  background: var(--glass-bg-strong);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.4);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.4);
  box-shadow: var(--shadow-glass);
}
.wf__detail-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}
.wf__detail-title {
  font-size: var(--fs-lg);
  font-weight: 700;
}
.wf__kv {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}
.wf__kv-row {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
}
.wf__kv-row dt {
  flex-shrink: 0;
  width: 3.5em;
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.wf__kv-row dd {
  min-width: 0;
  word-break: break-all;
}
.wf__comment-label {
  display: block;
  margin-top: var(--space-3);
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.wf__comment-area {
  margin-top: 6px;
  width: 100%;
  resize: vertical;
}
.wf__detail-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  margin-top: var(--space-4);
}
.wf__actions-spacer {
  flex: 1;
  min-width: var(--space-4);
}

@media (min-width: 1024px) {
  .wf__detail {
    position: sticky;
    top: var(--space-4);
  }
}
@media (max-width: 1023px) {
  .wf__md {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
