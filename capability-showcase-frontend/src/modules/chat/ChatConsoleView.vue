<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { useCatalogStore } from '../../stores/catalog'
import { useSessionStore } from '../../stores/session'
import { runCapability } from '../../api/client'
import { humanizeError } from '../../api/errors'
import { executionGate } from '../../utils/gate'
import { renderMarkdown } from '../../utils/markdown'
import { downloadText } from '../../utils/download'
import { useCapabilityRun, type RunPhase } from '../../composables/useCapabilityRun'
import type { Capability } from '../../types/catalog'
import type { FormValues } from '../../utils/validation'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import ModuleHeader from '../../components/layout/ModuleHeader.vue'
import InfoNote from '../_shared/InfoNote.vue'
import CopyButton from '../../components/common/CopyButton.vue'

const props = defineProps<{ moduleId: string; capId?: string }>()
const catalog = useCatalogStore()
const session = useSessionStore()

// ── 统一模式：chat 模块的对话类能力（经 capabilityById 取，缺失自动跳过）──
interface ModeMeta {
  id: string
  label: string
  icon: string
}
const MODES: ModeMeta[] = [
  { id: 'chat.sync', label: '同步', icon: '⚡' },
  { id: 'chat.stream', label: '流式', icon: '🌊' },
  { id: 'chat.auto', label: '意图路由', icon: '🧭' },
  { id: 'chat.cascade', label: '级联降级', icon: '🪜' },
  { id: 'chat.mcp', label: 'MCP 工具', icon: '🧰' },
  { id: 'chat.memory', label: '长期记忆', icon: '🧠' },
]
const CHAT_MODE_IDS = MODES.map((m) => m.id)

const availableModes = computed(() =>
  MODES.map((m) => ({ ...m, cap: catalog.capabilityById(m.id) })).filter(
    (m): m is ModeMeta & { cap: Capability } => !!m.cap,
  ),
)
function modeLabelFor(id: string): string {
  return MODES.find((m) => m.id === id)?.label ?? id
}

// 非会话能力（结构化抽取 / 画像读写 / 清缓存等深链）→ 交给通用运行器。
const selectedCap = computed(() =>
  props.capId ? catalog.capabilityById(props.capId) : undefined,
)
const delegateToRunner = computed(
  () => !!selectedCap.value && !CHAT_MODE_IDS.includes(selectedCap.value.id),
)

function pickInitialMode(): string {
  if (props.capId && CHAT_MODE_IDS.includes(props.capId)) return props.capId
  if (catalog.capabilityById('chat.stream')) return 'chat.stream'
  if (catalog.capabilityById('chat.sync')) return 'chat.sync'
  return availableModes.value[0]?.id ?? 'chat.stream'
}
const modeId = ref<string>(pickInitialMode())
// 深链到某对话模式时同步选中（不导航，复用同一对话流）。
watch(
  () => props.capId,
  (id) => {
    if (id && CHAT_MODE_IDS.includes(id)) modeId.value = id
  },
)

const activeCap = computed(() => catalog.capabilityById(modeId.value))
// activeCap 恒为对话模式能力；无对话能力时下方 EmptyState 兜底，run.* 不会被访问。
const run = useCapabilityRun(() => activeCap.value as Capability)

const activeGate = computed(() =>
  activeCap.value
    ? executionGate(activeCap.value, { ...session.permissionContext() })
    : { allowed: false, reason: '当前无可用对话能力。' },
)

// ── 对话流 ──
interface ChatMsg {
  id: number
  role: 'user' | 'assistant' | 'system'
  text: string
  html?: string
  streaming?: boolean
  error?: boolean
  aborted?: boolean
  mode?: string
  elapsedMs?: number
  traceId?: string | null
  src?: FormValues
}
const messages = ref<ChatMsg[]>([])
const activeAsstId = ref<number | null>(null)
let seq = 0

const message = ref('')
// 每个浏览器会话生成唯一 chatId：避免多标签/多次会话共享服务端对话记忆（写死 'default' 会串上下文）。
// 用户仍可在「参数」里手动改写以复用某会话；「清空对话」会换成新的 chatId 开启全新会话。
function newChatId(): string {
  const rand =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID().slice(0, 8)
      : Math.random().toString(36).slice(2, 10)
  return `web-${rand}`
}
const chatId = ref(newChatId())
const category = ref('')
const showParams = ref(false)

const transcriptEl = ref<HTMLElement | null>(null)

/** 自动滚动守卫：用户上滑离底超过此阈值即暂停跟随（防流式/软键盘弹出时强拽回底部）。 */
const AUTO_SCROLL_SLACK = 80
function isNearBottom(el: HTMLElement): boolean {
  return el.scrollHeight - el.scrollTop - el.clientHeight <= AUTO_SCROLL_SLACK
}
function scrollToBottom(force = false): void {
  // 判定须在 nextTick 前取当前滚动位（新内容渲染后 scrollHeight 已变大、判定必假）
  const el = transcriptEl.value
  if (el && !force && !isNearBottom(el)) return
  void nextTick(() => {
    const target = transcriptEl.value
    if (target) target.scrollTop = target.scrollHeight
  })
}
function patch(id: number, fn: (m: ChatMsg) => void): void {
  const m = messages.value.find((x) => x.id === id)
  if (m) fn(m)
}
function pushSystem(text: string): void {
  messages.value.push({ id: ++seq, role: 'system', text })
  scrollToBottom()
}

// 逐 token 更新当前助手气泡（流式）。
watch(
  () => run.sse.tokens,
  (t) => {
    const id = activeAsstId.value
    if (id != null && run.isSse.value) {
      patch(id, (m) => {
        m.text = t
      })
      scrollToBottom()
    }
  },
)
// 护栏 / 接地校验旁路提示 → 系统消息条。
watch(
  () => run.sse.note,
  (note) => {
    if (note) pushSystem(note)
  },
)
// 终态：定稿助手气泡（成功渲染 markdown / 错误 / 中断）。
watch(
  () => run.phase.value,
  (phase) => finalizeIfTerminal(phase),
)

function finalizeIfTerminal(phase: RunPhase): void {
  const id = activeAsstId.value
  if (id == null) return
  const sse = run.isSse.value
  const terminal = sse
    ? phase === 'done' || phase === 'aborted' || phase === 'error'
    : phase === 'success' || phase === 'error' || phase === 'aborted'
  if (!terminal) return

  patch(id, (m) => {
    m.streaming = false
    m.elapsedMs = run.elapsedMs.value || undefined
    m.traceId = run.traceId.value
    if (phase === 'error') {
      if (sse && run.sse.tokens) {
        // 流中途出错但已收到部分内容：保留正文（渲染 markdown），错误另起系统条提示，不抹掉已收 token。
        m.text = run.sse.tokens
        m.html = renderMarkdown(m.text)
        pushSystem(`流错误：${run.errorMessage.value ?? '未知错误'}`)
      } else {
        m.error = true
        m.text = run.errorMessage.value ?? m.text ?? '请求失败。'
      }
    } else if (phase === 'aborted') {
      m.aborted = true
      if (sse) m.text = run.sse.tokens
      m.text = `${m.text ? `${m.text}\n\n` : ''}（已中断）`
    } else {
      // success / done
      if (sse) {
        m.text = run.sse.tokens
      } else {
        const data = run.result.value?.data as Record<string, unknown> | undefined
        const reply =
          (data && typeof data.reply === 'string' && data.reply) ||
          run.result.value?.text ||
          (data ? JSON.stringify(data, null, 2) : '')
        m.text = String(reply)
      }
      m.html = renderMarkdown(m.text)
    }
  })
  activeAsstId.value = null
  scrollToBottom()
}

function buildValues(cap: Capability, text: string): FormValues {
  const names = new Set(cap.params.map((p) => p.name))
  const v: FormValues = {}
  if (names.has('message')) v.message = text
  if (names.has('chatId')) v.chatId = chatId.value.trim() || 'default'
  if (names.has('category') && category.value.trim()) v.category = category.value.trim()
  return v
}

function startAssistant(cap: Capability, values: FormValues): void {
  const asstId = ++seq
  messages.value.push({
    id: asstId,
    role: 'assistant',
    text: '',
    streaming: run.isSse.value,
    mode: modeLabelFor(cap.id),
    src: { ...values },
  })
  activeAsstId.value = asstId
  // 用户刚发送：无条件滚到底（不受上滑守卫影响）
  scrollToBottom(true)
}

const canSend = computed(
  () => activeGate.value.allowed && !run.running.value && message.value.trim().length > 0,
)

async function send(): Promise<void> {
  const cap = activeCap.value
  const text = message.value.trim()
  if (!cap || !text || run.running.value || !activeGate.value.allowed) return
  const values = buildValues(cap, text)
  messages.value.push({ id: ++seq, role: 'user', text })
  message.value = ''
  startAssistant(cap, values)
  await run.run(values)
}

async function regenerate(src: ChatMsg): Promise<void> {
  const cap = activeCap.value
  if (!cap || run.running.value || !src.src || !activeGate.value.allowed) return
  startAssistant(cap, src.src)
  await run.run(src.src)
}

function stop(): void {
  run.abort()
}
function clearAll(): void {
  run.abort()
  messages.value = []
  activeAsstId.value = null
  // 清空即开新会话：换 chatId，服务端对话记忆随之隔离（旧会话不再被带入）。
  chatId.value = newChatId()
  // 画像属于旧 chatId：一并清理，并使在途画像请求失效（晚到不得写入新会话的抽屉）。
  memSeq += 1
  memProfile.value = null
  memError.value = null
  memBusy.value = false
}
function onComposerKey(e: KeyboardEvent): void {
  if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
    e.preventDefault()
    void send()
  }
}

// ── 导出对话 ──
function exportMarkdown(): void {
  if (!messages.value.length) return
  const blocks = messages.value.map((m) => {
    const who =
      m.role === 'user'
        ? '**你**'
        : m.role === 'assistant'
          ? `**助手**${m.mode ? ` · ${m.mode}` : ''}`
          : '**系统**'
    return `${who}\n\n${m.text}`
  })
  downloadText(`chat-${Date.now()}.md`, blocks.join('\n\n---\n\n'), 'text/markdown')
}
function exportJson(): void {
  if (!messages.value.length) return
  const payload = messages.value.map((m) => ({
    role: m.role,
    mode: m.mode,
    text: m.text,
    elapsedMs: m.elapsedMs,
    traceId: m.traceId,
  }))
  downloadText(`chat-${Date.now()}.json`, JSON.stringify(payload, null, 2), 'application/json')
}

// ── 长期记忆画像（仅记忆模式；经 executionGate 诚实处理 flag/scope）──
const memGetCap = computed(() => catalog.capabilityById('memory.profile.get'))
const memClearCap = computed(() => catalog.capabilityById('memory.profile.clear'))
const showMemory = ref(false)
const memProfile = ref<string | null>(null)
const memError = ref<string | null>(null)
const memBusy = ref(false)
const isMemoryMode = computed(() => modeId.value === 'chat.memory')
// 画像请求序号：连点/清空会话（换 chatId）后，旧请求晚到不得覆盖新状态。
let memSeq = 0

async function loadProfile(): Promise<void> {
  const cap = memGetCap.value
  if (!cap) return
  const gate = executionGate(cap, { ...session.permissionContext() })
  if (!gate.allowed) {
    memError.value = gate.reason ?? '当前不可执行。'
    return
  }
  const my = ++memSeq
  memBusy.value = true
  memError.value = null
  try {
    const res = await runCapability(
      cap,
      { chatId: chatId.value.trim() || 'default' },
      session.runContext(),
    )
    if (my !== memSeq) return
    memProfile.value =
      res.data == null ? '（空画像）' : JSON.stringify(res.data, null, 2)
  } catch (e) {
    if (my !== memSeq) return
    memError.value = humanizeError(e, cap)
  } finally {
    if (my === memSeq) memBusy.value = false
  }
}
async function clearProfile(): Promise<void> {
  const cap = memClearCap.value
  if (!cap) return
  const gate = executionGate(cap, { ...session.permissionContext() })
  if (!gate.allowed) {
    memError.value = gate.reason ?? '当前不可执行。'
    return
  }
  const my = ++memSeq
  memBusy.value = true
  memError.value = null
  try {
    await runCapability(cap, { chatId: chatId.value.trim() || 'default' }, session.runContext())
    if (my !== memSeq) return
    memProfile.value = '（已清除）'
  } catch (e) {
    if (my !== memSeq) return
    memError.value = humanizeError(e, cap)
  } finally {
    if (my === memSeq) memBusy.value = false
  }
}

// 状态条：取最近一条已定稿助手消息的耗时 / traceId（无则不显示——诚实）。
const lastAssistant = computed(() =>
  [...messages.value].reverse().find((m) => m.role === 'assistant' && !m.streaming),
)

onUnmounted(() => run.abort())
</script>

<template>
  <!-- 非会话能力（如结构化抽取 / 画像读写深链）→ 通用运行器 -->
  <CapabilityRunner
    v-if="delegateToRunner && selectedCap"
    :key="selectedCap.id"
    :cap="selectedCap"
  />

  <EmptyState
    v-else-if="capId && !selectedCap"
    variant="error"
    title="能力不存在"
    :description="`对话模块下未找到能力「${capId}」。`"
  />

  <EmptyState
    v-else-if="!activeCap"
    variant="error"
    title="对话能力不可用"
    description="未在目录中找到任何 chat.* 对话能力。"
  />

  <div v-else class="chat">
    <div class="chat__top">
      <ModuleHeader :module-id="moduleId">
        <template #actions>
          <button
            type="button"
            class="btn btn--ghost btn--sm"
            :disabled="!messages.length"
            title="导出为 Markdown"
            @click="exportMarkdown"
          >
            导出 md
          </button>
          <button
            type="button"
            class="btn btn--ghost btn--sm"
            :disabled="!messages.length"
            title="导出为 JSON"
            @click="exportJson"
          >
            导出 json
          </button>
          <button
            type="button"
            class="btn btn--ghost btn--sm"
            :disabled="!messages.length"
            @click="clearAll"
          >
            清空
          </button>
        </template>
      </ModuleHeader>

      <!-- 统一模式选择器（分段） -->
      <div class="chat__rail" role="tablist" aria-label="对话模式">
        <button
          v-for="m in availableModes"
          :key="m.id"
          type="button"
          role="tab"
          class="chat__mode"
          :class="{ active: m.id === modeId, 'is-off': m.cap.state === 'flag-off' }"
          :aria-selected="m.id === modeId"
          :disabled="run.running.value"
          :title="m.cap.state === 'flag-off' ? `未启用：需开启 ${m.cap.featureFlag}` : m.cap.description"
          @click="modeId = m.id"
        >
          <span class="chat__mode-icon" aria-hidden="true">{{ m.icon }}</span>
          <span class="chat__mode-label">{{ m.label }}</span>
          <span v-if="m.cap.state === 'flag-off'" class="chat__mode-flag">未启用</span>
        </button>
      </div>

      <p v-if="activeCap" class="chat__hint">
        <code>{{ activeCap.method }} {{ activeCap.path }}</code>
        <span class="chat__hint-desc">{{ activeCap.description }}</span>
      </p>
    </div>

    <div class="chat__transcript" ref="transcriptEl" aria-live="polite">
      <EmptyState
        v-if="!messages.length"
        variant="empty"
        icon="💬"
        title="开始对话"
        description="输入消息并按 ⌘/Ctrl+Enter 发送。切换上方模式复用同一对话流。"
      />

      <div
        v-for="m in messages"
        :key="m.id"
        class="msg"
        :class="[`msg--${m.role}`, { 'msg--error': m.error, 'msg--streaming': m.streaming }]"
      >
        <span class="msg__role" :data-role="m.role">
          <template v-if="m.role === 'assistant'">
            <span class="msg__avatar" aria-hidden="true">🤖</span> 助手
            <span v-if="m.mode" class="msg__mode">{{ m.mode }}</span>
          </template>
          <template v-else-if="m.role === 'user'">你</template>
          <template v-else>系统</template>
        </span>

        <div class="msg__bubble">
          <!-- 助手：定稿后渲染 markdown（已消毒）；流式 / 未定稿显示纯文本 -->
          <div
            v-if="m.role === 'assistant' && m.html && !m.streaming"
            class="msg__md"
            v-html="m.html"
          />
          <template v-else>
            <span v-if="m.text" class="msg__text">{{ m.text }}</span>
            <span v-else-if="m.streaming" class="msg__typing">等待响应…</span>
          </template>
          <span
            v-if="m.streaming && m.text"
            class="msg__cursor"
            aria-hidden="true"
          >▋</span>
        </div>

        <!-- 助手气泡消息级操作（hover 显现） -->
        <div v-if="m.role === 'assistant' && !m.streaming && m.text" class="msg__actions">
          <CopyButton :text="m.text" compact label="复制" />
          <button
            type="button"
            class="msg__act"
            :disabled="run.running.value || !activeGate.allowed || !m.src"
            title="用当前入参重新生成"
            @click="regenerate(m)"
          >
            重新生成
          </button>
        </div>
      </div>
    </div>

    <!-- 记忆画像抽屉（仅记忆模式） -->
    <aside v-if="isMemoryMode" class="chat__memory">
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
          <button
            type="button"
            class="btn btn--sm"
            :disabled="memBusy || !memGetCap"
            @click="loadProfile"
          >
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
        <InfoNote v-if="memError" tone="danger" role="alert">{{ memError }}</InfoNote>
        <pre v-if="memProfile" class="chat__memory-pre">{{ memProfile }}</pre>
      </div>
    </aside>

    <!-- Composer：sticky 底部玻璃 -->
    <footer class="chat__composer">
      <p v-if="!activeGate.allowed && activeGate.reason" class="chat__gate" role="status">
        {{ activeGate.reason }}
      </p>
      <p v-else-if="activeGate.hint" class="chat__gate chat__gate--hint" role="status">
        {{ activeGate.hint }}
      </p>

      <button
        type="button"
        class="chat__params-toggle"
        :aria-expanded="showParams"
        @click="showParams = !showParams"
      >
        <span class="chat__chevron" :class="{ 'is-open': showParams }" aria-hidden="true">▸</span>
        参数（chatId · 类目）
      </button>
      <div v-show="showParams" class="chat__params">
        <label>
          chatId
          <input
            v-model="chatId"
            class="chat__param-input"
            type="text"
            placeholder="default"
            title="本会话的 chatId（对话记忆按此隔离）。默认每个浏览器会话唯一；手动改写可复用/切换会话，「清空对话」会自动换新。"
          />
        </label>
        <label>
          类目(可选)
          <input v-model="category" class="chat__param-input" type="text" placeholder="policy" />
        </label>
      </div>

      <div class="chat__input-row">
        <textarea
          v-model="message"
          class="form-control chat__textarea"
          rows="2"
          placeholder="输入消息，⌘/Ctrl+Enter 发送…"
          @keydown="onComposerKey"
        />
        <div class="chat__btns">
          <button v-if="run.running.value" type="button" class="btn btn--danger" @click="stop">
            停止
          </button>
          <button
            v-else
            type="button"
            class="btn btn--primary chat__send"
            :disabled="!canSend"
            @click="send"
          >
            发送 ⌘⏎
          </button>
        </div>
      </div>

      <div v-if="lastAssistant && (lastAssistant.elapsedMs || lastAssistant.traceId)" class="chat__status">
        <span v-if="lastAssistant.elapsedMs" class="chat__status-item">{{ lastAssistant.elapsedMs }} ms</span>
        <span
          v-if="lastAssistant.traceId"
          class="chat__status-item"
          :title="`X-Trace-Id: ${lastAssistant.traceId}`"
        >
          trace {{ lastAssistant.traceId.slice(0, 8) }}…
        </span>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.chat {
  display: flex;
  flex-direction: column;
  height: 100%;
  max-width: var(--content-max);
  margin: 0 auto;
  width: 100%;
  padding: var(--space-5) var(--space-5) 0;
}
.chat__top {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

/* 模式分段选择器 */
.chat__rail {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}
.chat__mode {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
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
.chat__mode:hover:not(:disabled) {
  color: var(--text);
  border-color: var(--primary-border);
}
.chat__mode.active {
  color: var(--primary);
  background: var(--primary-soft);
  border-color: var(--primary);
  box-shadow: var(--glow-primary);
}
.chat__mode:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.chat__mode:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--primary-border);
}
.chat__mode-flag {
  font-size: 11px;
  font-weight: var(--fw-bold);
  padding: 0 5px;
  border-radius: var(--radius-sm);
  color: var(--neutral);
  background: var(--neutral-soft);
  border: 1px solid var(--neutral-border);
}
.chat__mode.is-off .chat__mode-label {
  color: var(--text-subtle);
}

.chat__hint {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  flex-wrap: wrap;
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
.chat__hint code {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  background: var(--surface-2);
  padding: 1px 6px;
  border-radius: var(--radius-sm);
}
.chat__hint-desc {
  min-width: 0;
}

/* 转录区（唯一滚动区） */
.chat__transcript {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: var(--space-4) 0;
}
.msg {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: 82%;
}
.msg--user {
  align-self: flex-end;
  align-items: flex-end;
}
.msg--assistant {
  align-self: flex-start;
}
.msg--system {
  align-self: center;
  max-width: 92%;
  align-items: center;
}
.msg__role {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.msg__avatar {
  font-size: var(--fs-sm);
}
.msg__mode {
  font-size: 11px;
  padding: 0 6px;
  border-radius: var(--radius-pill);
  color: var(--stream);
  background: var(--stream-soft);
  border: 1px solid var(--stream-border);
}
.msg__bubble {
  position: relative;
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-lg);
  font-size: var(--fs-base);
  line-height: var(--lh-relaxed);
  word-break: break-word;
}
.msg__text {
  white-space: pre-wrap;
}
/* 用户气泡：主渐变实底 + 白字 + 光晕 */
.msg--user .msg__bubble {
  background: var(--gradient-primary);
  color: var(--primary-fg);
  border-bottom-right-radius: var(--radius-sm);
  box-shadow: var(--glow-primary);
}
/* 助手气泡：玻璃强底 + 边框（不叠 blur，性能护栏） */
.msg--assistant .msg__bubble {
  background: var(--glass-bg-strong);
  border: 1px solid var(--glass-border);
  color: var(--text);
  border-bottom-left-radius: var(--radius-sm);
  box-shadow: var(--shadow-sm);
}
/* 流式中：青色 inset 描边（复用 SseConsole 语汇） */
.msg--assistant.msg--streaming .msg__bubble {
  box-shadow: inset 0 0 0 1px var(--stream-border);
}
/* 系统 / 护栏：warning 玻璃条 */
.msg--system .msg__bubble {
  background: var(--warning-soft);
  color: var(--warning);
  border: 1px solid var(--warning-border);
  font-size: var(--fs-sm);
  text-align: center;
}
.msg--error .msg__bubble {
  background: var(--danger-soft);
  color: var(--danger);
  border: 1px solid var(--danger-border);
}
.msg__typing {
  color: var(--text-subtle);
}
.msg__cursor {
  color: var(--stream-strong);
  text-shadow: 0 0 8px var(--stream-strong);
  animation: chat-blink 1s step-end infinite;
}
@keyframes chat-blink {
  50% {
    opacity: 0;
  }
}

/* markdown 渲染（已消毒 HTML） */
.msg__md :deep(p) {
  margin: 0 0 var(--space-2);
}
.msg__md :deep(p:last-child) {
  margin-bottom: 0;
}
.msg__md :deep(ul),
.msg__md :deep(ol) {
  margin: var(--space-2) 0;
  padding-left: 1.4em;
}
.msg__md :deep(li) {
  margin: 2px 0;
}
.msg__md :deep(code) {
  font-family: var(--font-mono);
  font-size: 0.9em;
  padding: 1px 5px;
  border-radius: var(--radius-sm);
  background: rgba(127, 127, 127, 0.16);
}
.msg__md :deep(pre) {
  margin: var(--space-2) 0;
  padding: var(--space-3);
  overflow-x: auto;
  border-radius: var(--radius);
  background: var(--code-bg);
  border: 1px solid var(--code-border);
}
.msg__md :deep(pre code) {
  padding: 0;
  background: none;
  font-size: var(--fs-xs);
  line-height: 1.6;
}
.msg__md :deep(table) {
  border-collapse: collapse;
  margin: var(--space-2) 0;
  font-size: var(--fs-sm);
  display: block;
  overflow-x: auto;
}
.msg__md :deep(th),
.msg__md :deep(td) {
  border: 1px solid var(--border);
  padding: 4px 10px;
  text-align: left;
}
.msg__md :deep(th) {
  background: var(--surface-2);
  font-weight: var(--fw-semibold);
}
.msg__md :deep(a) {
  color: var(--primary);
  text-decoration: underline;
}
.msg__md :deep(blockquote) {
  margin: var(--space-2) 0;
  padding-left: var(--space-3);
  border-left: 3px solid var(--border-strong);
  color: var(--text-muted);
}

/* 消息级操作：hover / focus-within 才显现 */
.msg__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  opacity: 0;
  transition: opacity var(--dur) var(--ease);
}
.msg:hover .msg__actions,
.msg:focus-within .msg__actions {
  opacity: 1;
}
/* 触屏无 hover：复制/重新生成常显，否则手机上不可达 */
@media (hover: none) {
  .msg__actions {
    opacity: 1;
  }
}
.msg__act {
  padding: 2px 8px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.msg__act:hover:not(:disabled) {
  color: var(--text);
  background: var(--surface-3);
}
.msg__act:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 记忆画像抽屉 */
.chat__memory {
  flex-shrink: 0;
  border-top: 1px solid var(--border);
  padding-top: var(--space-2);
}
.chat__memory-toggle,
.chat__params-toggle {
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
  padding: var(--space-2) 0;
}
.chat__memory-actions {
  display: flex;
  gap: var(--space-2);
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

/* Composer：sticky 底部玻璃（大面，允许 blur）；底衬 home 条安全区 */
.chat__composer {
  position: sticky;
  bottom: 0;
  flex-shrink: 0;
  margin-top: var(--space-2);
  padding: var(--space-3) var(--space-4) calc(var(--space-4) + var(--safe-bottom));
  border: 1px solid var(--glass-border);
  border-bottom: none;
  border-top-left-radius: var(--radius-lg);
  border-top-right-radius: var(--radius-lg);
  background: var(--glass-bg-strong);
  -webkit-backdrop-filter: blur(var(--glass-blur-strong)) saturate(1.4);
  backdrop-filter: blur(var(--glass-blur-strong)) saturate(1.4);
  box-shadow: var(--shadow-glass);
}
.chat__gate {
  margin-bottom: var(--space-2);
  font-size: var(--fs-sm);
  color: var(--warning);
}
.chat__gate--hint {
  color: var(--text-muted);
}
.chat__params {
  display: flex;
  gap: var(--space-4);
  flex-wrap: wrap;
  margin-bottom: var(--space-2);
  font-size: var(--fs-xs);
  color: var(--text-muted);
}
.chat__params label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.chat__param-input {
  width: 140px;
  padding: 4px 8px;
  font-size: var(--fs-xs);
  color: var(--text);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.chat__input-row {
  display: flex;
  gap: var(--space-3);
  align-items: flex-end;
}
.chat__textarea {
  flex: 1;
  resize: vertical;
}
.chat__btns {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.chat__send {
  white-space: nowrap;
}
.chat__status {
  display: flex;
  gap: var(--space-3);
  margin-top: var(--space-2);
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  font-family: var(--font-mono);
}

/* 手机档：模式 chips 单行横滚、气泡放宽、参数输入弹性化 */
@media (max-width: 640px) {
  .chat__rail {
    flex-wrap: nowrap;
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
    padding-bottom: 2px;
  }
  .chat__mode {
    flex-shrink: 0;
  }
  .msg {
    max-width: 92%;
  }
  .chat__param-input {
    width: auto;
    flex: 1 1 120px;
  }
  .chat__params label {
    flex: 1 1 auto;
  }
}
/* 触屏：消息操作按钮触控目标抬升；参数输入 16px 防 iOS 聚焦缩放 */
@media (pointer: coarse) {
  .msg__act {
    min-height: 32px;
    padding: 4px 10px;
  }
  .chat__param-input {
    font-size: 16px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .chat__mode,
  .chat__chevron,
  .msg__actions {
    transition: none;
  }
  .msg__cursor {
    animation: none;
  }
}
</style>
