<script setup lang="ts">
/**
 * Interop & Eval Tools —— 互操作与评测工作台（module=interop-eval）。
 *
 * 顶部统一 ModuleHeader。分区：
 *  ① MCP 工具：列表 → 详情 → 调用 三步串联（选中工具自动带入调用表单的 tool 名）。
 *  ② Agent Card / A2A：能力名片与 A2A JSON-RPC（卡片深链；params 待核验）。
 *  ③ 评测 Eval：检索评测（Recall@k/MRR/Hit@k）以指标卡 + ResultTable 可视化（按真实响应探测，缺则 ResponseViewer 兜底）；
 *     其余端到端 / 套件 / 双跑 / 门禁经卡片深链到通用运行器；eval.gate 的 422 业务化说明保留。
 *
 * 深链沿用通用 CapabilityRunner。执行统一经 executionGate + runCapability（不手写路径 / 不绕闸门 / 不臆造字段）。
 */
import { computed, onScopeDispose, ref, watch } from 'vue'
import { useCatalogStore } from '../../stores/catalog'
import { useSessionStore } from '../../stores/session'
import { runCapability } from '../../api/client'
import { humanizeError } from '../../api/errors'
import { executionGate } from '../../utils/gate'
import type { Capability } from '../../types/catalog'
import type { FormValues } from '../../utils/validation'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import CapabilityCard from '../../components/capability/CapabilityCard.vue'
import ResponseViewer from '../../components/capability/ResponseViewer.vue'
import JsonView from '../../components/capability/JsonView.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import StatCard from '../../components/common/StatCard.vue'
import ModuleHeader from '../../components/layout/ModuleHeader.vue'
import WorkbenchSection from '../_shared/WorkbenchSection.vue'
import InfoNote from '../_shared/InfoNote.vue'
import ResultTable from '../_shared/ResultTable.vue'

const props = defineProps<{ moduleId: string; capId?: string }>()
const catalog = useCatalogStore()
const session = useSessionStore()

const module = computed(() => catalog.moduleById(props.moduleId))
const focusedCap = computed(() =>
  props.capId ? (module.value?.capabilities ?? []).find((c) => c.id === props.capId) : undefined,
)

const AGENT_CARD_IDS = ['interop.agent-card', 'interop.a2a.agent-card', 'interop.a2a.call']
// 评测：非检索类经卡片深链（含发现 / 端到端 / 套件 / 双跑 / 门禁）。
const EVAL_CARD_IDS = ['eval.capabilities', 'eval.run', 'eval.suite.run', 'eval.dual-run', 'eval.gate']

function pick(ids: string[]): Capability[] {
  return ids.map((id) => catalog.capabilityById(id)).filter((c): c is Capability => !!c)
}
const agentCardCaps = computed(() => pick(AGENT_CARD_IDS))
const evalCardCaps = computed(() => pick(EVAL_CARD_IDS))

const toolsCap = computed(() => catalog.capabilityById('interop.mcp.tools'))
const toolDetailCap = computed(() => catalog.capabilityById('interop.mcp.tool'))
const callCapability = computed(() => catalog.capabilityById('interop.mcp.call'))
const retrievalCap = computed(() => catalog.capabilityById('eval.retrieval'))

const focusedIsGate = computed(() => focusedCap.value?.id === 'eval.gate')

// ── 探测辅助（不臆造字段）──
function asStr(v: unknown): string | undefined {
  if (v == null) return undefined
  if (typeof v === 'string') return v.trim() ? v : undefined
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  return undefined
}
function numOf(v: unknown): number | undefined {
  if (typeof v === 'number' && Number.isFinite(v)) return v
  if (typeof v === 'string' && v.trim() && Number.isFinite(Number(v))) return Number(v)
  return undefined
}
/** 多键 envelope 探测：取首个**非空**数组（空数组不得遮蔽后置有效数组）；全空则回落首个空数组。 */
function firstArray(data: unknown, keys: string[]): unknown[] | null {
  if (Array.isArray(data)) return data
  if (data && typeof data === 'object') {
    const o = data as Record<string, unknown>
    let firstEmpty: unknown[] | null = null
    for (const k of keys) {
      const v = o[k]
      if (Array.isArray(v)) {
        if (v.length) return v
        if (!firstEmpty) firstEmpty = v
      }
    }
    return firstEmpty
  }
  return null
}

// ── 请求生命周期治理：AbortController 池 + 代号 ──
// 代号（generation）由「新一轮动作 / 凭证切换」递增；旧一轮的异步完成回调凭代号失效，不回写状态。
// 卸载或凭证切换时 abort 所有 pending 请求。
const activeControllers = new Set<AbortController>()
let mcpGeneration = 0
let retrievalGeneration = 0
function abortAll(): void {
  for (const c of activeControllers) c.abort()
  activeControllers.clear()
}
onScopeDispose(abortAll)

/** 复用 executionGate + runCapability 驱动一次一次性调用。 */
async function callCap(
  cap: Capability | undefined,
  values: FormValues,
  signal?: AbortSignal,
): Promise<{ data?: unknown; error?: string }> {
  if (!cap) return { error: '能力不在目录中。' }
  const gate = executionGate(cap, { ...session.permissionContext(), confirmed: false })
  if (!gate.allowed) return { error: gate.reason ?? '当前不可执行。' }
  try {
    const res = await runCapability(cap, values, session.runContext(signal))
    return { data: res.data }
  } catch (e) {
    return { error: humanizeError(e, cap) }
  }
}

// ── MCP 三步串联：列表 → 详情 → 调用 ──
interface ToolItem {
  name: string
  description?: string
}
const tools = ref<ToolItem[] | null>(null)
const toolsRaw = ref<unknown>(null)
const toolsBusy = ref(false)
const toolsError = ref<string | null>(null)
const toolsLoaded = ref(false)

const selectedTool = ref<string | null>(null)
const detailData = ref<unknown>(null)
const detailBusy = ref(false)
const detailError = ref<string | null>(null)

const callArgs = ref('{}')
const callBusy = ref(false)
const callError = ref<string | null>(null)
const callResult = ref<unknown>(null)
const called = ref(false)

function parseTools(data: unknown): ToolItem[] | null {
  const arr = firstArray(data, ['tools', 'items', 'data', 'results'])
  if (!arr) return null
  // 过滤 null/无标识项（不产生可点击的伪工具），重名去重保首个。
  const out: ToolItem[] = []
  const seen = new Set<string>()
  for (const item of arr) {
    if (item == null) continue
    let name: string | undefined
    let description: string | undefined
    if (typeof item === 'object') {
      const o = item as Record<string, unknown>
      name = asStr(o.name) ?? asStr(o.tool) ?? asStr(o.id)
      description = asStr(o.description) ?? asStr(o.summary) ?? asStr(o.title)
    } else {
      name = asStr(item)
    }
    if (!name || seen.has(name)) continue
    seen.add(name)
    out.push({ name, description })
  }
  return out
}
const toolsFallback = computed(() => toolsRaw.value != null && tools.value === null)
// 成功但空响应体：独立终态，不与「尚未请求」混用。
const toolsEmptyBody = computed(
  () => toolsLoaded.value && !toolsBusy.value && !toolsError.value && toolsRaw.value == null,
)

/** 重置 MCP 选中/详情/调用派生状态（重新列出、凭证切换时调用）。 */
function resetMcpSelection(): void {
  selectedTool.value = null
  detailData.value = null
  detailError.value = null
  detailBusy.value = false
  callError.value = null
  callResult.value = null
  called.value = false
  callBusy.value = false
}

async function loadTools(): Promise<void> {
  if (toolsBusy.value) return
  const gen = ++mcpGeneration
  const controller = new AbortController()
  activeControllers.add(controller)
  toolsBusy.value = true
  toolsError.value = null
  toolsRaw.value = null
  tools.value = null
  // 重新列出即重置旧选中/详情/调用状态（旧工具可能已不存在）。
  resetMcpSelection()
  const { data, error } = await callCap(toolsCap.value, {}, controller.signal)
  activeControllers.delete(controller)
  if (gen !== mcpGeneration) return // 旧代号：已被新一轮 / 凭证切换失效
  toolsBusy.value = false
  toolsLoaded.value = true
  if (error) {
    toolsError.value = error
    return
  }
  toolsRaw.value = data ?? null
  tools.value = parseTools(data)
}

async function selectTool(name: string): Promise<void> {
  // 新选择使旧详情与旧调用一并失效（详情/调用共用代号，防乱序覆盖与结果错挂）。
  const gen = ++mcpGeneration
  selectedTool.value = name
  detailData.value = null
  detailError.value = null
  callError.value = null
  callResult.value = null
  called.value = false
  callBusy.value = false
  // 选中即把工具名带入调用表单（三步串联）。
  detailBusy.value = true
  const controller = new AbortController()
  activeControllers.add(controller)
  const { data, error } = await callCap(toolDetailCap.value, { toolName: name }, controller.signal)
  activeControllers.delete(controller)
  if (gen !== mcpGeneration) return
  detailBusy.value = false
  if (error) {
    detailError.value = error
    return
  }
  detailData.value = data ?? null
}

async function callTool(): Promise<void> {
  if (callBusy.value) return
  const tool = selectedTool.value
  if (!tool) return
  const argsStr = callArgs.value.trim() || '{}'
  let parsedArgs: unknown
  try {
    parsedArgs = JSON.parse(argsStr)
  } catch {
    callError.value = 'arguments 不是合法 JSON。'
    return
  }
  // 后端 McpToolCallRequest.arguments 是 Map：数组/标量在前端即阻断。
  if (!parsedArgs || typeof parsedArgs !== 'object' || Array.isArray(parsedArgs)) {
    callError.value = 'arguments 必须是 JSON 对象。'
    return
  }
  const gen = mcpGeneration // 快照代号：期间切换工具/重列则丢弃本次结果，不错挂到新工具名下
  callBusy.value = true
  callError.value = null
  callResult.value = null
  const controller = new AbortController()
  activeControllers.add(controller)
  const { data, error } = await callCap(
    callCapability.value,
    { tool, arguments: argsStr },
    controller.signal,
  )
  activeControllers.delete(controller)
  if (gen !== mcpGeneration) return
  callBusy.value = false
  called.value = true
  if (error) {
    callError.value = error
    return
  }
  callResult.value = data ?? null
}

// ── 检索评测可视化：指标卡 + 每用例 ResultTable ──
const METRIC_RE = /recall|mrr|hit|precision|ndcg|f1|map(?![a-z])/i
function metricTone(key: string): 'primary' | 'success' | 'warning' | 'stream' | 'neutral' {
  const k = key.toLowerCase()
  if (k.includes('recall')) return 'primary'
  if (k.includes('mrr')) return 'stream'
  if (k.includes('hit')) return 'success'
  if (k.includes('precision')) return 'warning'
  return 'neutral'
}
function fmtMetric(n: number): string {
  return n >= 0 && n <= 1 ? n.toFixed(3) : String(n)
}
interface Metric {
  label: string
  value: string
  tone: 'primary' | 'success' | 'warning' | 'stream' | 'neutral'
}
function extractMetrics(data: unknown): Metric[] | null {
  if (!data || typeof data !== 'object') return null
  const root = data as Record<string, unknown>
  const scopes: Record<string, unknown>[] = [root]
  for (const k of ['metrics', 'summary', 'aggregate', 'overall', 'result', 'scores']) {
    const v = root[k]
    if (v && typeof v === 'object' && !Array.isArray(v)) scopes.push(v as Record<string, unknown>)
  }
  const out: Metric[] = []
  const seen = new Set<string>()
  for (const obj of scopes) {
    for (const [key, val] of Object.entries(obj)) {
      // 去重按规范化（小写）key：`Recall` 与嵌套 `recall` 语义相同，只保留先出现的一个。
      const norm = key.toLowerCase()
      if (!METRIC_RE.test(key) || seen.has(norm)) continue
      const n = numOf(val)
      if (n === undefined) continue
      seen.add(norm)
      out.push({ label: key, value: fmtMetric(n), tone: metricTone(key) })
    }
  }
  return out.length ? out : null
}
function extractCaseRows(data: unknown): Record<string, unknown>[] | null {
  const arr = firstArray(data, ['cases', 'perCase', 'caseResults', 'results', 'details', 'items'])
  if (!arr) return null
  const rows = arr.filter((x): x is Record<string, unknown> => !!x && typeof x === 'object')
  return rows.length ? rows : null
}

// 检索评测表单（cases 预填 param 示例）。
const retrievalCases = ref<string>(retrievalCap.value?.params.find((p) => p.name === 'cases')?.example ?? '[]')
const retrievalTopK = ref<number | null>(5)
const retrievalCategory = ref('')
const retrievalBusy = ref(false)
const retrievalError = ref<string | null>(null)
const retrievalRaw = ref<unknown>(null)
const retrievalRan = ref(false)

const retrievalMetrics = computed(() =>
  retrievalRaw.value != null ? extractMetrics(retrievalRaw.value) : null,
)
const retrievalRows = computed(() =>
  retrievalRaw.value != null ? extractCaseRows(retrievalRaw.value) : null,
)
// 兜底：既无指标又无用例行时展示原始响应。
const retrievalFallback = computed(
  () => retrievalRaw.value != null && !retrievalMetrics.value && !retrievalRows.value,
)

const retrievalGate = computed(() =>
  retrievalCap.value
    ? executionGate(retrievalCap.value, { ...session.permissionContext() })
    : { allowed: false, reason: '未找到检索评测能力。' },
)

/** 本地校验失败：给出错误并清掉上一轮结果，避免旧指标被误读为本轮结果。 */
function retrievalReject(message: string): void {
  retrievalError.value = message
  retrievalRaw.value = null
}

async function runRetrieval(): Promise<void> {
  if (retrievalBusy.value) return
  if (!retrievalCap.value) return
  const casesStr = retrievalCases.value.trim() || '[]'
  let parsedCases: unknown
  try {
    parsedCases = JSON.parse(casesStr)
  } catch {
    retrievalReject('cases 不是合法 JSON 数组。')
    return
  }
  // 结构校验（不止语法）：必须是非空数组，且每个元素为用例对象（字段以后端 RetrievalCase 为准）。
  if (
    !Array.isArray(parsedCases) ||
    parsedCases.length === 0 ||
    parsedCases.some((c) => !c || typeof c !== 'object' || Array.isArray(c))
  ) {
    retrievalReject('cases 必须是非空 JSON 数组，且每个元素为用例对象。')
    return
  }
  // topK：清空视为省略；非空必须是 1..50 的整数（HTML min/max 不拦程序性提交；.number 解析失败可能给字符串）。
  const rawTopK: unknown = retrievalTopK.value
  const hasTopK = rawTopK !== null && rawTopK !== undefined && rawTopK !== ''
  if (
    hasTopK &&
    (typeof rawTopK !== 'number' || !Number.isInteger(rawTopK) || rawTopK < 1 || rawTopK > 50)
  ) {
    retrievalReject('TopK 必须是 1..50 的整数。')
    return
  }
  const gen = ++retrievalGeneration
  retrievalBusy.value = true
  retrievalError.value = null
  retrievalRaw.value = null
  const values: FormValues = { cases: casesStr }
  if (hasTopK) values.topK = rawTopK as number
  if (retrievalCategory.value.trim()) values.category = retrievalCategory.value.trim()
  const controller = new AbortController()
  activeControllers.add(controller)
  const { data, error } = await callCap(retrievalCap.value, values, controller.signal)
  activeControllers.delete(controller)
  if (gen !== retrievalGeneration) return
  retrievalBusy.value = false
  retrievalRan.value = true
  if (error) {
    retrievalError.value = error
    return
  }
  retrievalRaw.value = data ?? null
}

// ── 凭证切换：清空两个分区的页面数据并使 pending 请求失效（租户不串味）──
watch(
  () => [session.apiKey, session.credentialMode] as const,
  () => {
    mcpGeneration += 1
    retrievalGeneration += 1
    abortAll()
    tools.value = null
    toolsRaw.value = null
    toolsError.value = null
    toolsLoaded.value = false
    toolsBusy.value = false
    resetMcpSelection()
    retrievalRaw.value = null
    retrievalError.value = null
    retrievalRan.value = false
    retrievalBusy.value = false
  },
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
  <template v-else-if="capId && focusedCap">
    <div class="ie ie--focus">
      <InfoNote v-if="focusedIsGate" tone="info" class="ie__focus-note">
        <strong>422 = 检出回归，属正常门禁结果</strong>（非网络错误）。响应体即门禁明细；供 CI 判定 fail。
      </InfoNote>
      <CapabilityRunner :key="focusedCap.id" :cap="focusedCap" />
    </div>
  </template>

  <EmptyState
    v-else-if="capId && !focusedCap"
    variant="error"
    title="能力不存在"
    :description="`模块「${module.title}」下未找到能力「${capId}」。`"
  />

  <!-- 工作台着陆：MCP / Agent Card / 评测 -->
  <div v-else class="ie">
    <ModuleHeader :module-id="moduleId" />

    <!-- ① MCP 工具：列表 → 详情 → 调用 三步串联 -->
    <WorkbenchSection
      v-if="toolsCap"
      title="MCP 工具"
      subtitle="列表 → 详情 → 调用 三步串联：列出工具后选中即拉取详情，并把 tool 名带入调用表单。"
    >
      <template #actions>
        <button
          type="button"
          class="btn btn--primary btn--sm"
          :disabled="toolsBusy || !session.hasCredential"
          @click="loadTools"
        >
          {{ toolsBusy ? '列出中…' : toolsLoaded ? '重新列出' : '① 列出工具' }}
        </button>
      </template>
      <template #notice>
        <InfoNote v-if="!session.hasCredential" tone="warning">
          请先登录 才能列出 MCP 工具。
        </InfoNote>
        <InfoNote v-if="toolsError" tone="danger" role="alert">{{ toolsError }}</InfoNote>
      </template>

      <div class="ie__mcp">
        <!-- 主：工具列表 -->
        <div class="ie__mcp-master">
          <ul v-if="tools && tools.length" class="ie__toollist" role="listbox">
            <li v-for="t in tools" :key="t.name">
              <button
                type="button"
                class="ie__tool"
                :class="{ 'is-selected': t.name === selectedTool }"
                role="option"
                :aria-selected="t.name === selectedTool"
                @click="selectTool(t.name)"
              >
                <code class="ie__tool-name">{{ t.name }}</code>
                <span v-if="t.description" class="ie__tool-desc">{{ t.description }}</span>
              </button>
            </li>
          </ul>
          <EmptyState
            v-else-if="tools && !tools.length"
            variant="empty"
            icon="∅"
            title="没有工具"
            description="MCP surface 未暴露任何工具。"
          />
          <div v-else-if="toolsFallback" class="ie__fallback">
            <ResponseViewer phase="success" :data="toolsRaw" />
          </div>
          <EmptyState
            v-else-if="toolsEmptyBody"
            variant="empty"
            icon="∅"
            title="成功，但响应体为空"
            description="请求成功，但服务未返回任何数据。"
          />
          <EmptyState
            v-else-if="!toolsLoaded"
            variant="empty"
            icon="🧰"
            title="尚未列出工具"
            description="点击右上「① 列出工具」拉取平台暴露的 MCP 工具。"
          />
        </div>

        <!-- 从：详情 + 调用 -->
        <div class="ie__mcp-detail">
          <template v-if="selectedTool">
            <h3 class="ie__step">② 工具详情 · <code>{{ selectedTool }}</code></h3>
            <p v-if="detailBusy" class="ie__muted">加载中…</p>
            <InfoNote v-else-if="detailError" tone="danger" role="alert">{{ detailError }}</InfoNote>
            <div v-else-if="detailData != null" class="ie__json">
              <JsonView :data="detailData" />
            </div>
            <p v-else class="ie__muted">无详情返回。</p>

            <h3 class="ie__step">③ 调用工具</h3>
            <label class="ie__field">
              arguments（JSON 对象）
              <textarea
                v-model="callArgs"
                class="form-control ie__args"
                rows="3"
                placeholder="{}"
                aria-label="MCP 调用参数 JSON"
              />
            </label>
            <div class="ie__call-actions">
              <span class="ie__call-target">tool: <code>{{ selectedTool }}</code></span>
              <button
                type="button"
                class="btn btn--primary btn--sm"
                :disabled="callBusy || !callCapability || !session.hasCredential"
                @click="callTool"
              >
                {{ callBusy ? '调用中…' : '调用' }}
              </button>
            </div>
            <InfoNote v-if="callError" tone="danger" role="alert">{{ callError }}</InfoNote>
            <div v-if="called && !callError" class="ie__json ie__json--result">
              <JsonView v-if="callResult != null" :data="callResult" />
              <p v-else class="ie__muted">调用成功，无响应体。</p>
            </div>
          </template>
          <EmptyState
            v-else
            variant="empty"
            icon="👈"
            title="选择一个工具"
            description="从左侧列表选中工具后，在此查看详情并调用。"
          />
        </div>
      </div>
    </WorkbenchSection>

    <!-- ② Agent Card / A2A -->
    <WorkbenchSection
      v-if="agentCardCaps.length"
      title="Agent Card / A2A"
      subtitle="平台能力名片与 A2A JSON-RPC 互操作调用。"
    >
      <template #notice>
        <InfoNote tone="neutral">
          A2A <code>message/stream</code> 的 <code>params</code> 字段以后端 A2A record 为准，目录中为占位模板（标注「待核验」），请勿据此当作稳定契约。
        </InfoNote>
      </template>
      <div class="ie__grid">
        <CapabilityCard
          v-for="(c, i) in agentCardCaps"
          :key="c.id"
          :cap="c"
          :module-id="module.id"
          :style="{ '--i': i }"
        />
      </div>
    </WorkbenchSection>

    <!-- ③ 评测 Eval -->
    <WorkbenchSection
      title="评测 Eval"
      subtitle="检索评测（Recall@k / MRR / Hit@k）指标可视化；端到端 / 套件 / 双跑 / 门禁经卡片深链到运行器。"
    >
      <template #notice>
        <InfoNote tone="warning">
          <strong>CI 门禁</strong>（<code>eval.gate</code>）检出回归时返回 <strong>HTTP 422</strong>：这是业务门禁结果，
          <strong>非网络错误</strong>，响应体为门禁明细（供 CI fail）。<code>eval.dual-run</code> 则恒 200，仅作信息化对比。
        </InfoNote>
      </template>

      <!-- 检索评测：自定义指标可视化工具 -->
      <div v-if="retrievalCap" class="ie__retrieval">
        <h3 class="ie__step">检索评测（指标卡 + 每用例明细）</h3>
        <div class="ie__ret-form">
          <label class="ie__field ie__field--wide">
            cases（JSON 数组）
            <textarea
              v-model="retrievalCases"
              class="form-control ie__args"
              rows="3"
              placeholder='[{"id":"c1","question":"退款","relevantDocIds":["d1"]}]'
              aria-label="检索评测用例 JSON"
            />
          </label>
          <div class="ie__ret-params">
            <label class="ie__field">
              TopK
              <input v-model.number="retrievalTopK" class="form-control" type="number" min="1" max="50" />
            </label>
            <label class="ie__field">
              类目
              <input v-model="retrievalCategory" class="form-control" type="text" placeholder="可选" />
            </label>
          </div>
          <div class="ie__ret-actions">
            <button
              type="button"
              class="btn btn--primary"
              :disabled="retrievalBusy || !retrievalGate.allowed"
              @click="runRetrieval"
            >
              {{ retrievalBusy ? '评测中…' : '运行检索评测' }}
            </button>
          </div>
          <InfoNote v-if="!retrievalGate.allowed && retrievalGate.reason" tone="warning">
            {{ retrievalGate.reason }}
          </InfoNote>
          <InfoNote v-if="retrievalError" tone="danger" role="alert">{{ retrievalError }}</InfoNote>
        </div>

        <!-- 指标卡（按真实响应探测 recall/mrr/hit/... 数值字段） -->
        <div v-if="retrievalMetrics && retrievalMetrics.length" class="ie__metrics">
          <StatCard
            v-for="m in retrievalMetrics"
            :key="m.label"
            :label="m.label"
            :value="m.value"
            :tone="m.tone"
          />
        </div>
        <!-- 每用例明细表 -->
        <ResultTable
          v-if="retrievalRows && retrievalRows.length"
          :rows="retrievalRows"
          caption="每用例检索评测明细"
        />
        <!-- 兜底：无法探测出指标 / 用例行时展示原始响应，绝不臆造字段 -->
        <div v-else-if="retrievalFallback" class="ie__fallback">
          <ResponseViewer phase="success" :data="retrievalRaw" />
        </div>
        <EmptyState
          v-else-if="retrievalRan && !retrievalBusy && !retrievalError && retrievalRaw == null"
          variant="empty"
          icon="∅"
          title="成功，但响应体为空"
          description="评测请求成功，但服务未返回任何数据。"
        />
        <EmptyState
          v-else-if="!retrievalRan && !retrievalMetrics"
          variant="empty"
          icon="📈"
          title="尚未评测"
          description="填写用例并点击「运行检索评测」，Recall@k / MRR / Hit@k 将以指标卡呈现。"
        />
      </div>

      <!-- 其余评测能力：卡片深链到通用运行器（结构化 ResponseViewer 展示） -->
      <div v-if="evalCardCaps.length" class="ie__grid ie__grid--eval">
        <CapabilityCard
          v-for="(c, i) in evalCardCaps"
          :key="c.id"
          :cap="c"
          :module-id="module.id"
          :style="{ '--i': i }"
        />
      </div>
    </WorkbenchSection>

    <EmptyState
      v-if="!toolsCap && !agentCardCaps.length && !retrievalCap && !evalCardCaps.length"
      variant="empty"
      icon="⏳"
      title="能力待补"
      description="未在目录中找到互操作 / 评测能力。"
    />
  </div>
</template>

<style scoped>
.ie {
  max-width: var(--content-max);
  margin: 0 auto;
  padding: var(--space-6) var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.ie--focus {
  max-width: none;
  padding: 0;
}
.ie__focus-note {
  margin: var(--space-4) var(--space-5) 0;
}
.ie__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(var(--card-min), 1fr));
  gap: var(--space-4);
}
.ie__grid--eval {
  margin-top: var(--space-5);
}
.ie__muted {
  font-size: var(--fs-sm);
  color: var(--text-subtle);
}
.ie__step {
  font-size: var(--fs-sm);
  font-weight: var(--fw-bold);
  color: var(--text-muted);
  margin-bottom: var(--space-2);
}
.ie__step + .ie__step {
  margin-top: var(--space-4);
}
.ie__step code {
  font-family: var(--font-mono);
  color: var(--primary);
}

/* MCP 主从 */
.ie__mcp {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.3fr);
  gap: var(--space-4);
  align-items: start;
}
.ie__mcp-master,
.ie__mcp-detail {
  min-width: 0;
}
.ie__toollist {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.ie__tool {
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
  transition: border-color var(--dur) var(--ease), background var(--dur) var(--ease),
    box-shadow var(--dur) var(--ease);
}
.ie__tool:hover {
  border-color: var(--primary-border);
  background: var(--surface);
}
.ie__tool.is-selected {
  border-color: var(--primary);
  box-shadow: inset 0 0 0 1px var(--primary);
  background: var(--primary-soft);
}
.ie__tool:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--primary-border);
}
.ie__tool-name {
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  color: var(--text);
}
.ie__tool-desc {
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.ie__json {
  padding: var(--space-3);
  background: var(--code-bg);
  border: 1px solid var(--code-border);
  border-radius: var(--radius);
  overflow: auto;
  max-height: 280px;
}
.ie__json--result {
  margin-top: var(--space-2);
}
.ie__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.ie__field--wide {
  width: 100%;
}
.ie__args {
  font-family: var(--font-mono);
  resize: vertical;
}
.ie__call-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  margin-top: var(--space-2);
  flex-wrap: wrap;
}
.ie__call-target {
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.ie__call-target code {
  font-family: var(--font-mono);
  color: var(--primary);
}
.ie__fallback {
  min-height: 180px;
  border: 1px solid var(--code-border);
  border-radius: var(--radius);
  background: var(--code-bg);
  overflow: hidden;
}

/* 检索评测 */
.ie__retrieval {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.ie__ret-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.ie__ret-params {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3);
  max-width: 360px;
}
.ie__ret-actions {
  display: flex;
  gap: var(--space-2);
}
.ie__metrics {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: var(--space-3);
}

@media (max-width: 1023px) {
  .ie__mcp {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
