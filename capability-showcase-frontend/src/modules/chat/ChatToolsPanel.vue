<script setup lang="ts">
/**
 * 对话工作台侧工具：抽取工单 / 长期画像 / 清语义缓存。
 * 留在会话里执行，不再把用户丢进通用 CapabilityRunner。
 */
import { computed, ref, watch } from 'vue'
import { useAbortable } from '../../composables/useAbortable'
import { useSessionStore } from '../../stores/session'
import { runCapability } from '../../api/client'
import { humanizeError, isAbortError } from '../../api/errors'
import { executionGate } from '../../utils/gate'
import { parseTicket } from '../../utils/ticket'
import { validateParams, type FormValues } from '../../utils/validation'
import type { Capability } from '../../types/catalog'
import InfoNote from '../_shared/InfoNote.vue'
import JsonView from '../../components/capability/JsonView.vue'

const props = defineProps<{
  chatId: string
  sessionEpoch?: number
  extractCap?: Capability
  memGetCap?: Capability
  memClearCap?: Capability
  cacheCap?: Capability
  extractSeed?: string
  open?: boolean
  focusExtract?: boolean
  focusTool?: 'extract' | 'profile' | 'cache'
}>()

const session = useSessionStore()
const showTools = ref(!!props.open || !!props.focusExtract || !!props.focusTool)
const showExtract = ref(!!props.focusExtract || props.focusTool === 'extract')
const showMemory = ref(props.focusTool === 'profile')
const showCache = ref(props.focusTool === 'cache')

watch(
  () => props.open,
  (v) => {
    if (v) showTools.value = true
  },
)
watch(
  () => [props.focusExtract, props.focusTool] as const,
  ([extract, tool]) => {
    if (extract || tool === 'extract') {
      showTools.value = true
      showExtract.value = true
    }
    if (tool === 'profile') {
      showTools.value = true
      showMemory.value = true
    }
    if (tool === 'cache') {
      showTools.value = true
      showCache.value = true
    }
  },
)
watch(
  () => props.extractSeed,
  (text) => {
    if (text) {
      extractText.value = text
      showTools.value = true
      showExtract.value = true
    }
  },
)

const extractText = ref('')
const extractType = ref('ticket')
const extractBusy = ref(false)
const extractError = ref<string | null>(null)
const extractRaw = ref<unknown>(null)
const extractAbort = useAbortable()

const extractValues = computed<FormValues>(() => {
  const v: FormValues = { text: extractText.value }
  if (extractType.value.trim()) v.type = extractType.value.trim()
  return v
})
const extractErrors = computed(() =>
  props.extractCap ? validateParams(props.extractCap.params, extractValues.value) : {},
)
const extractGate = computed(() =>
  props.extractCap
    ? executionGate(props.extractCap, { ...session.permissionContext() })
    : { allowed: false, reason: '未找到抽取能力。' },
)
const canExtract = computed(
  () =>
    !!props.extractCap &&
    extractGate.value.allowed &&
    !extractBusy.value &&
    extractText.value.trim().length > 0 &&
    Object.keys(extractErrors.value).length === 0,
)
const ticket = computed(() => parseTicket(extractRaw.value))

async function runExtract(): Promise<void> {
  const cap = props.extractCap
  if (!cap || !canExtract.value) return
  const controller = extractAbort.fresh()
  extractBusy.value = true
  extractError.value = null
  extractRaw.value = null
  try {
    const res = await runCapability(cap, extractValues.value, session.runContext(controller.signal))
    if (controller.signal.aborted) return
    extractRaw.value = res.data ?? null
  } catch (e) {
    if (isAbortError(e) || controller.signal.aborted) return
    extractError.value = humanizeError(e, cap)
  } finally {
    if (!controller.signal.aborted) extractBusy.value = false
  }
}

const memProfile = ref<string | null>(null)
const memError = ref<string | null>(null)
const memBusy = ref(false)
const memAbort = useAbortable()
let memSeq = 0

async function loadProfile(): Promise<void> {
  const cap = props.memGetCap
  if (!cap) return
  const gate = executionGate(cap, { ...session.permissionContext() })
  if (!gate.allowed) {
    memError.value = gate.reason ?? '当前不可执行。'
    return
  }
  const my = ++memSeq
  const controller = memAbort.fresh()
  memBusy.value = true
  memError.value = null
  try {
    const res = await runCapability(
      cap,
      { chatId: props.chatId.trim() || 'default' },
      session.runContext(controller.signal),
    )
    if (my !== memSeq || controller.signal.aborted) return
    memProfile.value = res.data == null ? '（空画像）' : JSON.stringify(res.data, null, 2)
  } catch (e) {
    if (my !== memSeq || isAbortError(e) || controller.signal.aborted) return
    memError.value = humanizeError(e, cap)
  } finally {
    if (my === memSeq) memBusy.value = false
  }
}

async function clearProfile(): Promise<void> {
  const cap = props.memClearCap
  if (!cap) return
  const gate = executionGate(cap, { ...session.permissionContext() })
  if (!gate.allowed) {
    memError.value = gate.reason ?? '当前不可执行。'
    return
  }
  const my = ++memSeq
  const controller = memAbort.fresh()
  memBusy.value = true
  memError.value = null
  try {
    await runCapability(
      cap,
      { chatId: props.chatId.trim() || 'default' },
      session.runContext(controller.signal),
    )
    if (my !== memSeq || controller.signal.aborted) return
    memProfile.value = '（已清除）'
  } catch (e) {
    if (my !== memSeq || isAbortError(e) || controller.signal.aborted) return
    memError.value = humanizeError(e, cap)
  } finally {
    if (my === memSeq) memBusy.value = false
  }
}

const cacheBusy = ref(false)
const cacheError = ref<string | null>(null)
const cacheNote = ref<string | null>(null)
const cacheAbort = useAbortable()
const cachePending = ref(false)

const cacheGate = computed(() =>
  props.cacheCap
    ? executionGate(props.cacheCap, { ...session.permissionContext() })
    : { allowed: false, reason: '未找到清缓存能力。' },
)

async function clearCache(): Promise<void> {
  const cap = props.cacheCap
  if (!cap || !cacheGate.value.allowed) return
  const controller = cacheAbort.fresh()
  cacheBusy.value = true
  cacheError.value = null
  cacheNote.value = null
  try {
    await runCapability(cap, {}, session.runContext(controller.signal))
    if (controller.signal.aborted) return
    cacheNote.value = '已按当前租户清空语义缓存。'
    cachePending.value = false
  } catch (e) {
    if (isAbortError(e) || controller.signal.aborted) return
    cacheError.value = humanizeError(e, cap)
  } finally {
    if (!controller.signal.aborted) cacheBusy.value = false
  }
}

watch(
  () => [props.chatId, props.sessionEpoch] as const,
  () => {
    memSeq += 1
    memAbort.abort()
    extractAbort.abort()
    cacheAbort.abort()
    memProfile.value = null
    memError.value = null
    memBusy.value = false
    extractRaw.value = null
    extractError.value = null
    extractBusy.value = false
    cacheNote.value = null
    cacheError.value = null
    cacheBusy.value = false
  },
)
</script>

<template>
  <aside class="chat__tools" data-chat-section="tools">
    <button
      type="button"
      class="chat__memory-toggle"
      :aria-expanded="showTools"
      @click="showTools = !showTools"
    >
      <span class="chat__chevron" :class="{ 'is-open': showTools }" aria-hidden="true">▸</span>
      会话工具
    </button>

    <div v-show="showTools" class="chat__tools-body">
      <!-- 抽取：产品工作台，不再跳通用表单 -->
      <section v-if="extractCap" class="chat__tool" data-tool="extract">
        <button
          type="button"
          class="chat__memory-toggle"
          :aria-expanded="showExtract"
          @click="showExtract = !showExtract"
        >
          <span class="chat__chevron" :class="{ 'is-open': showExtract }" aria-hidden="true">▸</span>
          结构化抽取
        </button>
        <div v-show="showExtract" class="chat__memory-body">
          <p v-if="!extractGate.allowed && extractGate.reason" class="chat__tool-gate">
            {{ extractGate.reason }}
          </p>
          <label class="chat__tool-field">
            待抽取文本
            <textarea
              v-model="extractText"
              class="form-control"
              rows="3"
              placeholder="从对话气泡点「抽取工单」，或在此粘贴文本"
              aria-label="待抽取文本"
            />
          </label>
          <p v-if="extractErrors.text" class="chat__tool-err" role="alert">{{ extractErrors.text }}</p>
          <div class="chat__memory-actions">
            <button type="button" class="btn btn--sm btn--primary" :disabled="!canExtract" @click="runExtract">
              {{ extractBusy ? '抽取中…' : '抽取工单' }}
            </button>
          </div>
          <InfoNote v-if="extractError" tone="danger" role="alert">{{ extractError }}</InfoNote>
          <div v-if="ticket" class="chat__ticket" data-ticket="true">
            <p v-if="ticket.title" class="chat__ticket-title">{{ ticket.title }}</p>
            <div class="chat__ticket-meta">
              <span v-if="ticket.priority" class="chat__ticket-pri" :data-pri="ticket.priority">{{
                ticket.priority
              }}</span>
              <span v-if="ticket.category" class="chat__ticket-cat">{{ ticket.category }}</span>
            </div>
            <p v-if="ticket.summary" class="chat__ticket-sum">{{ ticket.summary }}</p>
            <ol v-if="ticket.nextSteps?.length" class="chat__ticket-steps">
              <li v-for="(s, i) in ticket.nextSteps" :key="i">{{ s }}</li>
            </ol>
          </div>
          <div v-else-if="extractRaw != null" class="chat__json">
            <JsonView :data="extractRaw" />
          </div>
        </div>
      </section>

      <!-- 画像：保留既有 class / 按钮文案，兼容 interaction 测试 -->
      <section v-if="memGetCap || memClearCap" class="chat__memory" data-tool="profile">
        <button
          type="button"
          class="chat__memory-toggle"
          :aria-expanded="showMemory"
          @click="showMemory = !showMemory"
        >
          <span class="chat__chevron" :class="{ 'is-open': showMemory }" aria-hidden="true">▸</span>
          长期用户画像
        </button>
        <div v-show="showMemory" class="chat__memory-body">
          <InfoNote v-if="memGetCap && memGetCap.state === 'flag-off'" tone="warning">
            画像读写需开启 <strong>{{ memGetCap.featureFlag }}</strong>=true；未启用时以下操作将被闸门拦截。
          </InfoNote>
          <div class="chat__memory-actions">
            <button type="button" class="btn btn--sm" :disabled="memBusy || !memGetCap" @click="loadProfile">
              {{ memBusy ? '读取中…' : '查看画像' }}
            </button>
            <button
              type="button"
              class="btn btn--sm btn--danger"
              :disabled="memBusy || !memClearCap"
              @click="clearProfile"
            >
              清除画像
            </button>
          </div>
          <InfoNote v-if="memError" tone="danger" role="alert" data-mem-error>{{ memError }}</InfoNote>
          <pre v-if="memProfile" class="chat__memory-pre">{{ memProfile }}</pre>
        </div>
      </section>

      <section v-if="cacheCap" class="chat__tool" data-tool="cache">
        <button
          type="button"
          class="chat__memory-toggle"
          :aria-expanded="showCache"
          @click="showCache = !showCache"
        >
          <span class="chat__chevron" :class="{ 'is-open': showCache }" aria-hidden="true">▸</span>
          语义缓存
        </button>
        <div v-show="showCache" class="chat__memory-body">
          <p v-if="!cacheGate.allowed && cacheGate.reason" class="chat__tool-gate">{{ cacheGate.reason }}</p>
          <p class="chat__tool-hint">按租户清空 L1 语义响应缓存。此操作不可恢复。</p>
          <div class="chat__memory-actions">
            <template v-if="!cachePending">
              <button
                type="button"
                class="btn btn--sm btn--danger"
                :disabled="cacheBusy || !cacheGate.allowed"
                @click="cachePending = true"
              >
                清空缓存
              </button>
            </template>
            <template v-else>
              <button type="button" class="btn btn--sm btn--danger" :disabled="cacheBusy" @click="clearCache">
                {{ cacheBusy ? '清除中…' : '确认清空' }}
              </button>
              <button type="button" class="btn btn--sm btn--ghost" :disabled="cacheBusy" @click="cachePending = false">
                取消
              </button>
            </template>
          </div>
          <InfoNote v-if="cacheError" tone="danger" role="alert">{{ cacheError }}</InfoNote>
          <InfoNote v-if="cacheNote" tone="success">{{ cacheNote }}</InfoNote>
        </div>
      </section>
    </div>
  </aside>
</template>

<style scoped>
.chat__tools {
  flex-shrink: 0;
  border-top: 1px solid var(--border);
  padding-top: var(--space-2);
}
.chat__tools-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-2) 0;
}
.chat__tool {
  min-width: 0;
}
.chat__memory-toggle {
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
.chat__chevron {
  color: var(--text-subtle);
  transition: transform var(--dur) var(--ease);
}
.chat__chevron.is-open {
  transform: rotate(90deg);
}
.chat__memory-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-2) 0 0;
}
.chat__memory-actions {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.chat__memory-pre {
  margin: 0;
  padding: var(--space-3);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--code-bg);
  border: 1px solid var(--code-border);
  border-radius: var(--radius);
  max-height: 220px;
  overflow: auto;
}
.chat__tool-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.chat__tool-err,
.chat__tool-gate {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--danger);
}
.chat__tool-hint {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.chat__ticket {
  padding: var(--space-3);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}
.chat__ticket-title {
  margin: 0 0 var(--space-2);
  font-weight: var(--fw-semibold);
}
.chat__ticket-meta {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
  margin-bottom: var(--space-2);
}
.chat__ticket-pri,
.chat__ticket-cat {
  font-size: var(--fs-xs);
  padding: 0 6px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
}
.chat__ticket-pri[data-pri='CRITICAL'],
.chat__ticket-pri[data-pri='HIGH'] {
  color: var(--danger);
  border-color: var(--danger-border);
  background: var(--danger-soft);
}
.chat__ticket-sum {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--text);
}
.chat__ticket-steps {
  margin: var(--space-2) 0 0;
  padding-left: 1.2em;
  font-size: var(--fs-sm);
}
.chat__json {
  max-height: 240px;
  overflow: auto;
}
@media (prefers-reduced-motion: reduce) {
  .chat__chevron {
    transition: none;
  }
}
</style>
