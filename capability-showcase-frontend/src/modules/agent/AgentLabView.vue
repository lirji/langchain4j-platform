<script setup lang="ts">
/**
 * Agent Lab —— 目标驱动台面（module=agent）。
 *
 * 顶部「目标 composer」+ 执行模式选择器：同步 ReAct / 异步 / DAG(+自动规划+异步) / 链 / 投票 / 反思(+流式)
 *   / 数据分析智能体 / 业务流程（flag-off 诚实）。经 capabilityById 取所选能力，用 useCapabilityRun 驱动。
 * 结果区：流式(reflexive.stream) → SSE 控制台；异步(*.async) → 已提交 taskId 提示 + 深链观测；
 *   同步 → 若响应含 steps 做步骤时间线，否则 ResponseViewer 兜底（不臆造字段）。
 * 「更多能力」：任务管理类能力以 CapabilityCard 网格深链到通用运行器。
 *
 * 深链（capId 存在）沿用通用 CapabilityRunner。执行统一经 useCapabilityRun（内部走 executionGate + runCapability）。
 */
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { useCatalogStore } from '../../stores/catalog'
import { useSessionStore } from '../../stores/session'
import { executionGate } from '../../utils/gate'
import { useCapabilityRun } from '../../composables/useCapabilityRun'
import type { Capability } from '../../types/catalog'
import type { FormValues } from '../../utils/validation'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import CapabilityCard from '../../components/capability/CapabilityCard.vue'
import ResponseViewer from '../../components/capability/ResponseViewer.vue'
import JsonView from '../../components/capability/JsonView.vue'
import SseConsole from '../../components/capability/SseConsole.vue'
import SseStageConsole from '../../components/capability/SseStageConsole.vue'
import SseEventTimeline from '../../components/capability/SseEventTimeline.vue'
import CopyButton from '../../components/common/CopyButton.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import ModuleHeader from '../../components/layout/ModuleHeader.vue'
import WorkbenchSection from '../_shared/WorkbenchSection.vue'
import InfoNote from '../_shared/InfoNote.vue'
import AgentStepTimeline from './AgentStepTimeline.vue'

const props = defineProps<{ moduleId: string; capId?: string }>()
const catalog = useCatalogStore()
const session = useSessionStore()

const module = computed(() => catalog.moduleById(props.moduleId))
const focusedCap = computed(() =>
  props.capId ? (module.value?.capabilities ?? []).find((c) => c.id === props.capId) : undefined,
)

// ── 执行模式（catalog 为事实源；缺失自动跳过）──
interface Mode {
  id: string
  label: string
  group: string
}
const MODES: Mode[] = [
  { id: 'agent.run', label: '同步 ReAct', group: '单 Agent' },
  { id: 'agent.run.async', label: '异步', group: '单 Agent' },
  { id: 'agent.dag.run', label: 'DAG', group: 'DAG 编排' },
  { id: 'agent.dag.plan-run', label: '自动规划', group: 'DAG 编排' },
  { id: 'agent.dag.run.async', label: 'DAG 异步', group: 'DAG 编排' },
  { id: 'agent.dag.plan-run.async', label: '规划异步', group: 'DAG 编排' },
  { id: 'agent.chain', label: '链式', group: '轻量编排' },
  { id: 'agent.vote', label: '投票', group: '轻量编排' },
  { id: 'agent.reflexive', label: '反思', group: '轻量编排' },
  { id: 'agent.reflexive.stream', label: '反思流式', group: '轻量编排' },
  { id: 'agent.analyst.run', label: '数据分析', group: '智能体' },
  { id: 'agent.analyst.run.async', label: '数据分析·异步', group: '智能体' },
  { id: 'agent.process.run', label: '业务流程', group: '智能体' },
  { id: 'agent.process.run.async', label: '业务流程·异步', group: '智能体' },
]
const MODE_IDS = MODES.map((m) => m.id)

type ModeWithCap = Mode & { cap: Capability }
const availableModes = computed<ModeWithCap[]>(() =>
  MODES.map((m) => ({ ...m, cap: catalog.capabilityById(m.id) })).filter(
    (m): m is ModeWithCap => !!m.cap,
  ),
)
const modeGroups = computed(() => {
  const groups: { name: string; modes: ModeWithCap[] }[] = []
  for (const m of availableModes.value) {
    let g = groups.find((x) => x.name === m.group)
    if (!g) {
      g = { name: m.group, modes: [] }
      groups.push(g)
    }
    g.modes.push(m)
  }
  return groups
})

function pickInitial(): string {
  if (props.capId && MODE_IDS.includes(props.capId)) return props.capId
  if (catalog.capabilityById('agent.run')) return 'agent.run'
  return availableModes.value[0]?.id ?? 'agent.run'
}
const modeId = ref<string>(pickInitial())
watch(
  () => props.capId,
  (id) => {
    if (id && MODE_IDS.includes(id)) modeId.value = id
  },
)

const activeCap = computed(() => catalog.capabilityById(modeId.value))
const isAsyncMode = computed(() => modeId.value.endsWith('.async'))

// useCapabilityRun 恒需一个 Capability；activeCap 缺失时下方 EmptyState 兜底、run.* 不被访问。
const run = useCapabilityRun(() => activeCap.value as Capability)
const activeGate = computed(() =>
  activeCap.value
    ? executionGate(activeCap.value, { ...session.permissionContext() })
    : { allowed: false, reason: '当前无可用 Agent 能力。' },
)

// ── 目标 composer ──
const PRIMARY_ORDER = ['goal', 'question', 'input', 'message']
const primaryParam = computed(() =>
  activeCap.value?.params.find((p) => PRIMARY_ORDER.includes(p.name)),
)
const primaryLabel = computed(() => primaryParam.value?.label ?? '目标')
const primaryPlaceholder = computed(() => primaryParam.value?.placeholder ?? '输入目标…')

function hasParam(name: string): boolean {
  return activeCap.value?.params.some((p) => p.name === name) ?? false
}
const showWebhook = computed(() => hasParam('webhookUrl'))
const showTasks = computed(() => hasParam('tasks'))
const showVoteN = computed(() => hasParam('n'))

const goalText = ref('')
const webhookUrl = ref('')
const tasksText = ref('')
const voteN = ref<number | null>(3)

/** 任务 DAG 字段错误：填了但不是合法 JSON 数组（issue-07）。 */
const tasksError = computed(() => {
  if (!showTasks.value || !tasksText.value.trim()) return null
  try {
    return Array.isArray(JSON.parse(tasksText.value)) ? null : '任务 DAG 不是合法的 JSON 数组。'
  } catch {
    return '任务 DAG 不是合法的 JSON 数组。'
  }
})
/** 投票采样路数字段错误：越界/非整数（issue-08）。 */
const voteNError = computed(() => {
  if (!showVoteN.value || voteN.value == null) return null
  return Number.isInteger(voteN.value) && voteN.value >= 1 && voteN.value <= 9
    ? null
    : '采样路数 n 需为 1..9 的整数。'
})

const canSend = computed(() => {
  const cap = activeCap.value
  if (!cap || !activeGate.value.allowed || run.running.value) return false
  if (tasksError.value || voteNError.value) return false
  // 按目录 required 声明逐项校验（issue-07：DAG 的 goal 与 tasks 都是 required，缺一不可）。
  for (const p of cap.params) {
    if (!p.required) continue
    if (p.name === primaryParam.value?.name && !goalText.value.trim()) return false
    if (p.name === 'tasks' && !tasksText.value.trim()) return false
    if (p.name === 'n' && voteN.value == null) return false
    if (p.name === 'webhookUrl' && !webhookUrl.value.trim()) return false
  }
  // 保持既有底线：至少填了主输入或任务 DAG，不允许全空提交。
  return goalText.value.trim().length > 0 || (showTasks.value && tasksText.value.trim().length > 0)
})

function buildValues(cap: Capability): FormValues {
  const names = new Set(cap.params.map((p) => p.name))
  const v: FormValues = {}
  if (primaryParam.value && goalText.value.trim()) {
    v[primaryParam.value.name] = goalText.value.trim()
  }
  if (names.has('webhookUrl') && webhookUrl.value.trim()) v.webhookUrl = webhookUrl.value.trim()
  if (names.has('tasks') && tasksText.value.trim()) v.tasks = tasksText.value.trim()
  if (names.has('n') && voteN.value != null) v.n = voteN.value
  return v
}

async function send(): Promise<void> {
  const cap = activeCap.value
  if (!cap || !canSend.value) return
  await run.run(buildValues(cap))
}
function stop(): void {
  run.abort()
}
function onComposerKey(e: KeyboardEvent): void {
  if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
    e.preventDefault()
    void send()
  }
}

// ── 结果解析（不臆造字段：探测常见键，缺失即 ResponseViewer 兜底）──
function asStr(v: unknown): string | undefined {
  if (typeof v === 'string') return v.trim() ? v : undefined
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  return undefined
}
function extractSteps(data: unknown): unknown[] | null {
  if (data && typeof data === 'object') {
    const o = data as Record<string, unknown>
    for (const k of ['steps', 'trace', 'actions', 'history', 'iterations']) {
      if (Array.isArray(o[k]) && o[k].length) return o[k] as unknown[]
    }
  }
  return null
}
function extractFinal(data: unknown): string | null {
  if (data && typeof data === 'object') {
    const o = data as Record<string, unknown>
    for (const k of ['answer', 'finalAnswer', 'reply', 'output', 'summary', 'result', 'content']) {
      const v = o[k]
      if (typeof v === 'string' && v.trim()) return v
    }
  }
  return null
}
function extractTaskId(data: unknown): string | null {
  if (data && typeof data === 'object') {
    const o = data as Record<string, unknown>
    return asStr(o.taskId) ?? asStr(o.id) ?? null
  }
  return null
}

const resultData = computed(() => run.result.value?.data)
const steps = computed(() => (run.phase.value === 'success' ? extractSteps(resultData.value) : null))
const finalAnswer = computed(() =>
  run.phase.value === 'success' ? extractFinal(resultData.value) : null,
)
const asyncTaskId = computed(() =>
  run.phase.value === 'success' ? extractTaskId(resultData.value) : null,
)

const showAsyncPanel = computed(
  () => !run.isSse.value && isAsyncMode.value && run.phase.value === 'success' && !!asyncTaskId.value,
)
const showSteps = computed(
  () => !run.isSse.value && !showAsyncPanel.value && run.phase.value === 'success' && !!steps.value,
)
const showRunnerFallback = computed(
  () => !run.isSse.value && !showAsyncPanel.value && !showSteps.value,
)
const showRawUnder = ref(false)

// 切换执行模式即重置结果区（issue-09）：旧结果不得按新模式重新解释
// （如把旧同步响应的 id 当作新异步模式的 taskId 展示「已提交」）。模式按钮在 running 时已禁用，reset 不会打断执行中的请求。
watch(modeId, () => {
  run.reset()
  showRawUnder.value = false
})

const streamCap = computed(() => catalog.capabilityById('agent.tasks.stream'))

// ── 更多能力（任务管理类）→ CapabilityCard 深链 ──
const MORE_IDS = ['agent.tasks.list', 'agent.tasks.get', 'agent.tasks.stream', 'agent.tasks.cancel']
const moreCaps = computed<Capability[]>(() =>
  MORE_IDS.map((id) => catalog.capabilityById(id)).filter((c): c is Capability => !!c),
)
</script>

<template>
  <EmptyState
    v-if="!module"
    variant="error"
    title="模块不存在"
    :description="`未找到模块「${moduleId}」。`"
  />

  <!-- 深链：单能力聚焦 -->
  <CapabilityRunner v-else-if="capId && focusedCap" :key="focusedCap.id" :cap="focusedCap" />

  <EmptyState
    v-else-if="capId && !focusedCap"
    variant="error"
    title="能力不存在"
    :description="`模块「${module.title}」下未找到能力「${capId}」。`"
  />

  <EmptyState
    v-else-if="!availableModes.length"
    variant="error"
    title="Agent 能力不可用"
    description="未在目录中找到任何 agent.* 执行能力。"
  />

  <!-- 工作台着陆：目标 composer + 模式 + 结果 + 更多能力 -->
  <div v-else class="ag">
    <ModuleHeader :module-id="moduleId" />

    <div class="ag__lab">
      <!-- 目标 composer + 模式选择器 -->
      <section class="ag__composer" aria-label="目标与执行模式">
        <div class="ag__modes">
          <div v-for="g in modeGroups" :key="g.name" class="ag__modegroup">
            <span class="ag__modegroup-label">{{ g.name }}</span>
            <div class="ag__modebtns" role="tablist" :aria-label="`${g.name} 执行模式`">
              <button
                v-for="m in g.modes"
                :key="m.id"
                type="button"
                role="tab"
                class="ag__mode"
                :class="{ active: m.id === modeId, 'is-off': m.cap.state === 'flag-off' }"
                :aria-selected="m.id === modeId"
                :disabled="run.running.value"
                :title="
                  m.cap.state === 'flag-off'
                    ? `未启用：需开启 ${m.cap.featureFlag}`
                    : m.cap.description
                "
                @click="modeId = m.id"
              >
                {{ m.label }}
                <span v-if="m.cap.requestKind === 'sse'" class="ag__mode-sse">SSE</span>
                <span v-else-if="m.id.endsWith('.async')" class="ag__mode-async">async</span>
                <span v-if="m.cap.state === 'flag-off'" class="ag__mode-flag">未启用</span>
              </button>
            </div>
          </div>
        </div>

        <p v-if="activeCap" class="ag__endpoint">
          <code>{{ activeCap.method }} {{ activeCap.path }}</code>
          <span class="ag__endpoint-desc">{{ activeCap.description }}</span>
        </p>

        <label class="ag__goal-label">
          {{ primaryLabel }}
          <textarea
            v-model="goalText"
            class="form-control ag__goal"
            rows="3"
            :placeholder="primaryPlaceholder"
            aria-label="目标输入"
            @keydown="onComposerKey"
          />
        </label>

        <!-- 高级参数（仅当所选能力声明时显示，不臆造字段） -->
        <div v-if="showWebhook || showTasks || showVoteN" class="ag__advanced">
          <label v-if="showVoteN" class="ag__adv-field">
            采样路数 n
            <input v-model.number="voteN" class="form-control" type="number" min="1" max="9" />
          </label>
          <label v-if="showWebhook" class="ag__adv-field ag__adv-field--wide">
            回调 URL（可选）
            <input
              v-model="webhookUrl"
              class="form-control"
              type="text"
              placeholder="https://example.com/webhook"
            />
          </label>
          <label v-if="showTasks" class="ag__adv-field ag__adv-field--wide">
            任务 DAG（JSON 数组，可选）
            <textarea
              v-model="tasksText"
              class="form-control ag__tasks"
              rows="3"
              placeholder='[{"id":"t1","goal":"..."}]'
            />
          </label>
        </div>

        <p v-if="tasksError" class="ag__gate" role="alert">{{ tasksError }}</p>
        <p v-if="voteNError" class="ag__gate" role="alert">{{ voteNError }}</p>

        <p v-if="!activeGate.allowed && activeGate.reason" class="ag__gate" role="status">
          {{ activeGate.reason }}
        </p>
        <p v-else-if="activeGate.hint" class="ag__gate ag__gate--hint" role="status">
          {{ activeGate.hint }}
        </p>

        <div class="ag__composer-actions">
          <button v-if="run.running.value" type="button" class="btn btn--danger" @click="stop">
            停止 (Esc)
          </button>
          <button
            v-else
            type="button"
            class="btn"
            :class="run.isSse.value ? 'btn--stream' : 'btn--primary'"
            :disabled="!canSend"
            @click="send"
          >
            {{ run.isSse.value ? '开始流式 ⌘⏎' : '执行 ⌘⏎' }}
          </button>
        </div>
      </section>

      <!-- 结果区 -->
      <section class="ag__result" aria-label="执行结果">
        <h2 class="ag__result-h">结果</h2>
        <div class="ag__result-body">
          <!-- 流式三态：token 流→拼接视图；反思→轮次视图；其它命名事件流（任务状态流）→通用事件时间线。均不卡「等待首个 token」 -->
          <SseConsole
            v-if="run.isSse.value && run.streamShape.value === 'token'"
            :tokens="run.sse.tokens"
            :events="run.sse.events"
            :status="run.sse.status"
            :note="run.sse.note"
            :error="run.errorMessage.value"
            :elapsed-ms="run.elapsedMs.value"
            :trace-id="run.traceId.value"
          />
          <SseStageConsole
            v-else-if="run.isSse.value && run.isReflexion.value"
            :events="run.sse.events"
            :status="run.sse.status"
            :note="run.sse.note"
            :error="run.errorMessage.value"
            :elapsed-ms="run.elapsedMs.value"
            :trace-id="run.traceId.value"
          />
          <SseEventTimeline
            v-else-if="run.isSse.value"
            :events="run.sse.events"
            :status="run.sse.status"
            :note="run.sse.note"
            :error="run.errorMessage.value"
            :elapsed-ms="run.elapsedMs.value"
            :trace-id="run.traceId.value"
          />

          <!-- 异步：已提交 taskId -->
          <div v-else-if="showAsyncPanel" class="ag__async">
            <div class="ag__async-badge">已提交</div>
            <p class="ag__async-line">
              任务已异步提交，<strong>taskId=</strong>
              <code class="ag__async-id">{{ asyncTaskId }}</code>
              <CopyButton :text="asyncTaskId ?? ''" compact />
            </p>
            <p class="ag__async-hint">
              可在 Agent 任务流（SSE）中观测执行步骤，或用任务详情轮询状态。
            </p>
            <RouterLink v-if="streamCap" class="btn btn--sm" to="/m/agent/agent.tasks.stream">
              去异步观测（SSE）
            </RouterLink>
            <div class="ag__async-raw">
              <JsonView :data="resultData" />
            </div>
          </div>

          <!-- 同步：ReAct 步骤时间线 -->
          <div v-else-if="showSteps" class="ag__steps">
            <div v-if="finalAnswer" class="ag__final">
              <span class="ag__final-tag">最终答案</span>
              <p class="ag__final-text">{{ finalAnswer }}</p>
            </div>
            <h3 class="ag__steps-title">执行步骤（{{ steps!.length }}）</h3>
            <AgentStepTimeline :steps="steps!" />
            <div class="ag__raw">
              <button
                type="button"
                class="ag__raw-toggle"
                :aria-expanded="showRawUnder"
                @click="showRawUnder = !showRawUnder"
              >
                <span class="ag__chevron" :class="{ 'is-open': showRawUnder }" aria-hidden="true">▸</span>
                原始响应
              </button>
              <div v-show="showRawUnder" class="ag__raw-body">
                <JsonView :data="resultData" />
              </div>
            </div>
          </div>

          <!-- 兜底：同步无 steps / 错误 / 未执行 → ResponseViewer -->
          <ResponseViewer
            v-else-if="showRunnerFallback"
            :phase="run.phase.value"
            :data="run.result.value?.data"
            :text="run.result.value?.text"
            :http-status="run.httpStatus.value"
            :trace-id="run.traceId.value"
            :elapsed-ms="run.elapsedMs.value"
            :error="run.errorMessage.value"
          />
        </div>
      </section>
    </div>

    <!-- 更多能力：任务管理 → 深链通用运行器 -->
    <WorkbenchSection
      v-if="moreCaps.length"
      title="更多能力：任务管理"
      subtitle="列出 / 查看 / 流式观测 / 取消 Agent 任务。点击卡片进入通用运行器。"
    >
      <div class="ag__grid">
        <CapabilityCard
          v-for="(c, i) in moreCaps"
          :key="c.id"
          :cap="c"
          :module-id="module.id"
          :style="{ '--i': i }"
        />
      </div>
    </WorkbenchSection>
  </div>
</template>

<style scoped>
.ag {
  max-width: var(--content-max);
  margin: 0 auto;
  padding: var(--space-6) var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.ag__lab {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--space-4);
  align-items: start;
}

/* Composer */
.ag__composer {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-4);
  background: var(--glass-bg-strong);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.4);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.4);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-glass);
}
.ag__modes {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.ag__modegroup {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ag__modegroup-label {
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--text-subtle);
}
.ag__modebtns {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.ag__mode {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 10px;
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  color: var(--text-muted);
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-pill);
  cursor: pointer;
  transition: color var(--dur) var(--ease), background var(--dur) var(--ease),
    border-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease);
}
.ag__mode:hover:not(:disabled) {
  color: var(--text);
  border-color: var(--primary-border);
}
.ag__mode.active {
  color: var(--primary);
  background: var(--primary-soft);
  border-color: var(--primary);
  box-shadow: var(--glow-primary);
}
.ag__mode:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.ag__mode:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--primary-border);
}
.ag__mode-sse,
.ag__mode-async,
.ag__mode-flag {
  font-size: 10px;
  font-weight: var(--fw-bold);
  padding: 0 5px;
  border-radius: var(--radius-sm);
}
.ag__mode-sse {
  color: var(--stream);
  background: var(--stream-soft);
  border: 1px solid var(--stream-border);
}
.ag__mode-async {
  color: var(--text-subtle);
  background: var(--surface-2);
  border: 1px solid var(--border);
}
.ag__mode-flag {
  color: var(--neutral);
  background: var(--neutral-soft);
  border: 1px solid var(--neutral-border);
}
.ag__endpoint {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  flex-wrap: wrap;
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.ag__endpoint code {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  background: var(--surface-2);
  padding: 1px 6px;
  border-radius: var(--radius-sm);
}
.ag__endpoint-desc {
  min-width: 0;
}
.ag__goal-label {
  display: block;
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.ag__goal {
  margin-top: 6px;
}
.ag__advanced {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
}
.ag__adv-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.ag__adv-field--wide {
  flex: 1;
  min-width: 220px;
}
.ag__tasks {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
}
.ag__gate {
  font-size: var(--fs-sm);
  color: var(--warning);
}
.ag__gate--hint {
  color: var(--text-muted);
}
.ag__composer-actions {
  display: flex;
  gap: var(--space-2);
}

/* 结果区 */
.ag__result {
  display: flex;
  flex-direction: column;
  padding: var(--space-4);
  background: var(--glass-bg-strong);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.4);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.4);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-glass);
}
.ag__result-h {
  font-size: var(--fs-xs);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--text-subtle);
  margin-bottom: var(--space-3);
}
.ag__result-body {
  flex: 1;
  min-height: 320px;
  border: 1px solid var(--code-border);
  border-radius: var(--radius);
  background: var(--code-bg);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

/* 异步面板 */
.ag__async {
  padding: var(--space-4);
  overflow: auto;
}
.ag__async-badge {
  display: inline-block;
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  color: var(--success);
  background: var(--success-soft);
  border: 1px solid var(--success-border);
  border-radius: var(--radius-sm);
  padding: 2px 8px;
  margin-bottom: var(--space-3);
}
.ag__async-line {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  font-size: var(--fs-sm);
  color: var(--text);
}
.ag__async-id {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--primary);
  background: var(--primary-soft);
  border: 1px solid var(--primary-border);
  padding: 1px 6px;
  border-radius: var(--radius-sm);
}
.ag__async-hint {
  margin: var(--space-2) 0 var(--space-3);
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.ag__async-raw {
  margin-top: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border);
}

/* 步骤时间线 */
.ag__steps {
  padding: var(--space-4);
  overflow: auto;
}
.ag__final {
  margin-bottom: var(--space-4);
  padding: var(--space-3);
  border: 1px solid var(--success-border);
  border-radius: var(--radius);
  background: var(--success-soft);
}
.ag__final-tag {
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--success);
}
.ag__final-text {
  margin-top: 6px;
  font-size: var(--fs-sm);
  color: var(--text);
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
.ag__steps-title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
  margin-bottom: var(--space-3);
}
.ag__raw {
  margin-top: var(--space-3);
  padding-top: var(--space-2);
  border-top: 1px solid var(--border);
}
.ag__raw-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 0;
  font-size: var(--fs-sm);
  color: var(--text-muted);
  background: none;
  border: none;
  cursor: pointer;
}
.ag__chevron {
  color: var(--text-subtle);
  transition: transform var(--dur) var(--ease);
}
.ag__chevron.is-open {
  transform: rotate(90deg);
}
.ag__raw-body {
  margin-top: var(--space-2);
}

/* 更多能力网格 */
.ag__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(var(--card-min), 1fr));
  gap: var(--space-4);
}

@media (max-width: 1023px) {
  .ag__lab {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (prefers-reduced-motion: reduce) {
  .ag__mode,
  .ag__chevron {
    transition: none;
  }
}
</style>
