<script setup lang="ts">
/**
 * Async Monitor —— 通用任务中心工作台（module=tasks）。
 *
 * 顶部统一 ModuleHeader。常驻「会话任务时间线」贯穿深链 / 着陆两态（观测范式）：
 *   创建 / 查询任务后自动追踪；SSE 订阅实时状态（状态节点着色 + 重连 / 续订检查点指示）；含状态过滤。
 * 着陆分区：① 任务能力网格（创建 / lease / 更新 / 取消 / 流，排除死信）；
 *   ② webhook 死信 —— 从一张卡提升为常驻可观测区块（live 拉取，探测数组→ResultTable，兜底 ResponseViewer）。
 *
 * 深链沿用通用 CapabilityRunner，结果回流时间线。执行统一经 executionGate + runCapability / streamCapability。
 */
import { computed, onUnmounted, ref } from 'vue'
import { useCatalogStore } from '../../stores/catalog'
import { useSessionStore } from '../../stores/session'
import { runCapability } from '../../api/client'
import { streamCapability } from '../../api/sse'
import { humanizeError } from '../../api/errors'
import { executionGate } from '../../utils/gate'
import { tryParseJson } from '../../utils/json'
import type { Capability } from '../../types/catalog'
import type { FormValues } from '../../utils/validation'
import type { TrackedTask } from './types'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import CapabilityCard from '../../components/capability/CapabilityCard.vue'
import ResponseViewer from '../../components/capability/ResponseViewer.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import ModuleHeader from '../../components/layout/ModuleHeader.vue'
import WorkbenchSection from '../_shared/WorkbenchSection.vue'
import InfoNote from '../_shared/InfoNote.vue'
import ResultTable from '../_shared/ResultTable.vue'
import AsyncTaskTimeline from './AsyncTaskTimeline.vue'

const props = defineProps<{ moduleId: string; capId?: string }>()
const catalog = useCatalogStore()
const session = useSessionStore()

const module = computed(() => catalog.moduleById('tasks'))
const cap = computed(() =>
  props.capId ? (module.value?.capabilities ?? []).find((c) => c.id === props.capId) : undefined,
)

// 任务能力网格（排除死信 —— 已提升为独立常驻区块）。
const deadletterCap = computed(() => catalog.capabilityById('async.deadletter'))
const gridCaps = computed(() =>
  (module.value?.capabilities ?? []).filter((c) => c.id !== 'async.deadletter'),
)

const tasks = ref<TrackedTask[]>([])
const streamHandles = new Map<string, { abort: () => void }>()

function nowStr(): string {
  return new Date().toLocaleTimeString()
}

function upsert(id: string, patch: Partial<TrackedTask>): TrackedTask {
  let t = tasks.value.find((x) => x.taskId === id)
  if (!t) {
    t = { taskId: id, status: 'PENDING', updatedAt: nowStr(), events: [] }
    tasks.value.unshift(t)
  }
  if (patch.status) t.status = patch.status
  if (patch.kind) t.kind = patch.kind
  if (patch.error !== undefined) t.error = patch.error
  if (patch.streaming !== undefined) t.streaming = patch.streaming
  t.updatedAt = nowStr()
  return t
}

function ingest(data: unknown): void {
  const record = (d: unknown): void => {
    if (d && typeof d === 'object') {
      const o = d as Record<string, unknown>
      if (typeof o.taskId === 'string') {
        upsert(o.taskId, {
          status: typeof o.status === 'string' ? o.status : undefined,
          kind: typeof o.kind === 'string' ? o.kind : undefined,
        })
      }
    }
  }
  if (Array.isArray(data)) data.forEach(record)
  else record(data)
}

function onRunnerResult(payload: { cap: Capability; data: unknown; status: number | null }): void {
  ingest(payload.data)
}

function findCap(id: string): Capability | undefined {
  return catalog.capabilityById(id)
}

async function refreshTask(id: string): Promise<void> {
  const getCap = findCap('async.get')
  if (!getCap || !session.hasCredential) return
  try {
    const res = await runCapability(getCap, { taskId: id }, session.runContext())
    ingest(res.data)
  } catch (e) {
    upsert(id, { error: humanizeError(e, getCap) })
  }
}

async function cancelTask(id: string): Promise<void> {
  const cancelCap = findCap('async.cancel')
  if (!cancelCap || !session.hasCredential) return
  try {
    const res = await runCapability(cancelCap, { taskId: id }, session.runContext())
    ingest(res.data)
    upsert(id, { status: 'CANCELLED' })
  } catch (e) {
    upsert(id, { error: humanizeError(e, cancelCap) })
  }
}

function streamTask(id: string): void {
  const streamCap = findCap('async.stream')
  if (!streamCap || !session.hasCredential) return
  streamHandles.get(id)?.abort()
  const t = upsert(id, { streaming: true, error: null })
  // 记录订阅次数：第 2 次起视为重连（重新订阅）。lastEventId 即 Last-Event-ID 续订检查点。
  t.subscribes = (t.subscribes ?? 0) + 1
  const handle = streamCapability(streamCap, { taskId: id }, session.runContext(), {
    onEvent: (ev) => {
      t.events.push(ev)
      if (ev.id) t.lastEventId = ev.id
      const parsed = tryParseJson(ev.data)
      if (parsed && typeof parsed === 'object' && 'status' in parsed) {
        const s = (parsed as Record<string, unknown>).status
        if (typeof s === 'string') upsert(id, { status: s })
      }
    },
    onNamed: (name, data) => {
      if (name === 'error') upsert(id, { error: data || '流式错误' })
    },
    onError: (e) => upsert(id, { error: humanizeError(e, streamCap) }),
    onDone: () => upsert(id, { streaming: false }),
  })
  streamHandles.set(id, handle)
}

onUnmounted(() => {
  streamHandles.forEach((h) => h.abort())
  streamHandles.clear()
})

// ── webhook 死信：常驻可观测区块（live 拉取）──
function firstArray(data: unknown, keys: string[]): unknown[] | null {
  if (Array.isArray(data)) return data
  if (data && typeof data === 'object') {
    const o = data as Record<string, unknown>
    for (const k of keys) if (Array.isArray(o[k])) return o[k] as unknown[]
  }
  return null
}

const deadRows = ref<Record<string, unknown>[] | null>(null)
const deadRaw = ref<unknown>(null)
const deadBusy = ref(false)
const deadError = ref<string | null>(null)
const deadLoaded = ref(false)

function parseDeadRows(data: unknown): Record<string, unknown>[] | null {
  const arr = firstArray(data, ['dead', 'deadLetters', 'entries', 'items', 'data', 'results', 'records'])
  if (!arr) return null
  return arr.filter((x): x is Record<string, unknown> => !!x && typeof x === 'object')
}
const deadFallback = computed(() => deadRaw.value != null && deadRows.value === null)

async function loadDeadletter(): Promise<void> {
  const capDl = deadletterCap.value
  if (!capDl) return
  const gate = executionGate(capDl, { hasApiKey: session.hasCredential, confirmed: false })
  if (!gate.allowed) {
    deadError.value = gate.reason ?? '当前不可执行。'
    return
  }
  deadBusy.value = true
  deadError.value = null
  deadRaw.value = null
  deadRows.value = null
  try {
    const res = await runCapability(capDl, {} as FormValues, session.runContext())
    deadRaw.value = res.data ?? null
    deadRows.value = parseDeadRows(res.data)
  } catch (e) {
    deadError.value = humanizeError(e, capDl)
  } finally {
    deadBusy.value = false
    deadLoaded.value = true
  }
}
</script>

<template>
  <EmptyState
    v-if="!module"
    variant="error"
    title="模块不存在"
    description="未找到 tasks 模块。"
  />

  <div v-else class="mon">
    <!-- 选中能力：通用运行器（结果回流到时间线） -->
    <template v-if="capId && cap">
      <CapabilityRunner :key="cap.id" :cap="cap" @result="onRunnerResult" />
    </template>

    <EmptyState
      v-else-if="capId && !cap"
      variant="error"
      title="能力不存在"
      :description="`未找到能力「${capId}」。`"
    />

    <!-- 模块着陆：模块头 + 任务能力网格 + 死信区块 -->
    <div v-else class="mon__land">
      <ModuleHeader :module-id="moduleId" />

      <WorkbenchSection
        title="任务能力"
        subtitle="创建 / lease / 状态更新 / 取消 / 实时流。执行结果自动汇入下方「会话任务时间线」。"
      >
        <div class="mon__grid">
          <CapabilityCard
            v-for="(c, i) in gridCaps"
            :key="c.id"
            :cap="c"
            :module-id="module.id"
            :style="{ '--i': i }"
          />
        </div>
      </WorkbenchSection>

      <WorkbenchSection
        v-if="deadletterCap"
        title="webhook 死信"
        subtitle="webhook 重试彻底失败后进入死信队列。此区块用于排障可观测（只读，无副作用）。"
      >
        <template #actions>
          <button
            type="button"
            class="btn btn--primary btn--sm"
            :disabled="deadBusy || !session.hasCredential"
            @click="loadDeadletter"
          >
            {{ deadBusy ? '加载中…' : deadLoaded ? '刷新死信' : '加载死信' }}
          </button>
        </template>
        <template #notice>
          <InfoNote v-if="!session.hasCredential" tone="warning">
            请先登录 才能拉取死信队列。
          </InfoNote>
          <InfoNote v-if="deadError" tone="danger" role="alert">{{ deadError }}</InfoNote>
        </template>

        <ResultTable
          v-if="deadRows && deadRows.length"
          :rows="deadRows"
          caption="webhook 死信条目"
        />
        <EmptyState
          v-else-if="deadRows && !deadRows.length"
          variant="empty"
          icon="✅"
          title="死信队列为空"
          description="当前租户下没有进入死信的 webhook 投递。"
        />
        <!-- 兜底：无法解析为条目数组时展示原始响应，绝不臆造字段。 -->
        <div v-else-if="deadFallback" class="mon__fallback">
          <ResponseViewer phase="success" :data="deadRaw" />
        </div>
        <EmptyState
          v-else-if="!deadLoaded"
          variant="empty"
          icon="📮"
          title="尚未加载死信"
          description="点击右上「加载死信」拉取当前租户的 webhook 死信队列。"
        />
      </WorkbenchSection>
    </div>

    <!-- 常驻任务时间线（深链 / 着陆两态皆在） -->
    <section class="mon__timeline" aria-label="任务时间线">
      <div class="mon__timeline-head">
        <h2 class="mon__timeline-title">会话任务时间线</h2>
        <span class="mon__timeline-hint">
          创建 / 查询任务后自动追踪；SSE 订阅实时状态（重新订阅会记为重连，最近事件 id 即 Last-Event-ID 续订检查点）。
        </span>
      </div>
      <AsyncTaskTimeline
        :tasks="tasks"
        :disabled="!session.hasCredential"
        @stream="streamTask"
        @refresh="refreshTask"
        @cancel="cancelTask"
      />
    </section>
  </div>
</template>

<style scoped>
.mon {
  display: flex;
  flex-direction: column;
}
.mon__land {
  max-width: var(--content-max);
  margin: 0 auto;
  width: 100%;
  padding: var(--space-6) var(--space-5) var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.mon__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(var(--card-min), 1fr));
  gap: var(--space-4);
}
.mon__fallback {
  min-height: 200px;
  border: 1px solid var(--code-border);
  border-radius: var(--radius);
  background: var(--code-bg);
  overflow: hidden;
}
.mon__timeline {
  max-width: var(--content-max);
  margin: 0 auto;
  width: 100%;
  padding: var(--space-3) var(--space-5) var(--space-6);
}
.mon__timeline-head {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  flex-wrap: wrap;
  margin-bottom: var(--space-3);
}
.mon__timeline-title {
  font-size: var(--fs-lg);
  font-weight: 700;
}
.mon__timeline-hint {
  font-size: var(--fs-sm);
  color: var(--text-subtle);
}
</style>
