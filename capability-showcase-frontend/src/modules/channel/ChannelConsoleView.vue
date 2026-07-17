<script setup lang="ts">
/**
 * Channel Console —— 出站投递 + 回调桥工作台（module=channel）。
 *
 * 顶部统一 ModuleHeader。分区：
 *  ① 能力发现：live 拉取 channel.capabilities → 「已配置渠道」列表（探测数组，兜底 ResponseViewer）。
 *  ② 出站投递：channel.messages.send（display-only，真实外部副作用，默认锁定）。
 *  ③ 回调桥：channel.callbacks.async-task / workflow 内联运行器 —— 表单里的 header 字段即「header 构建器」，
 *     curl 预览可视化注入结果；X-Api-Key 由网关注入不被覆盖。
 *  ④ 入站事件：channel.inbound（可选 X-Channel-Signature header）。
 *
 * 深链（capId 存在）沿用通用 CapabilityRunner。执行统一经 executionGate + runCapability（不手写路径/不绕闸门）。
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

const discoveryCap = computed(() => catalog.capabilityById('channel.capabilities'))
const outboundCap = computed(() => catalog.capabilityById('channel.messages.send'))
const inboundCap = computed(() => catalog.capabilityById('channel.inbound'))

const CALLBACK_IDS = ['channel.callbacks.async-task', 'channel.callbacks.workflow']
const callbackCaps = computed(() =>
  CALLBACK_IDS.map((id) => catalog.capabilityById(id)).filter((c): c is Capability => !!c),
)

const focusedIsOutbound = computed(() => focusedCap.value?.id === 'channel.messages.send')

// ── 探测辅助（不臆造字段）──
function asStr(v: unknown): string | undefined {
  if (v == null) return undefined
  if (typeof v === 'string') return v.trim() ? v : undefined
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  return undefined
}
function firstArray(data: unknown, keys: string[]): unknown[] | null {
  if (Array.isArray(data)) return data
  if (data && typeof data === 'object') {
    const o = data as Record<string, unknown>
    for (const k of keys) if (Array.isArray(o[k])) return o[k] as unknown[]
  }
  return null
}

// ── 能力发现：live 拉取「已配置渠道」──
interface ChannelInfo {
  label: string
  detail?: string
}
const channels = ref<ChannelInfo[] | null>(null)
const discoveryRaw = ref<unknown>(null)
const discoveryBusy = ref(false)
const discoveryError = ref<string | null>(null)
const discovered = ref(false)

function parseChannels(data: unknown): ChannelInfo[] | null {
  const arr = firstArray(data, ['channels', 'capabilities', 'configured', 'data', 'items', 'results'])
  if (!arr) return null
  // 过滤 null/无标识项，不产生伪渠道条目。
  const out: ChannelInfo[] = []
  for (const item of arr) {
    if (item == null) continue
    if (typeof item === 'object') {
      const o = item as Record<string, unknown>
      const label =
        asStr(o.channel) ?? asStr(o.name) ?? asStr(o.id) ?? asStr(o.type) ?? asStr(o.provider)
      if (!label) continue
      const bits: string[] = []
      for (const k of ['type', 'provider', 'enabled', 'target', 'description']) {
        const v = asStr(o[k])
        if (v && v !== label) bits.push(`${k}: ${v}`)
      }
      out.push({ label, detail: bits.length ? bits.join(' · ') : undefined })
    } else {
      const label = asStr(item)
      if (label) out.push({ label })
    }
  }
  return out
}
// 兜底：无法解析为渠道列表时展示原始响应。
const discoveryFallback = computed(() => discoveryRaw.value != null && channels.value === null)
// 成功但空响应体：独立终态，不与「尚未发现」混用。
const discoveryEmptyBody = computed(
  () => discovered.value && !discoveryBusy.value && !discoveryError.value && discoveryRaw.value == null,
)

// ── 请求生命周期治理：AbortController 池 + 代号（卸载 / 凭证切换时使 pending 失效）──
const activeControllers = new Set<AbortController>()
let discoverGeneration = 0
function abortAll(): void {
  for (const c of activeControllers) c.abort()
  activeControllers.clear()
}
onScopeDispose(abortAll)

async function discover(): Promise<void> {
  if (discoveryBusy.value) return
  const cap = discoveryCap.value
  if (!cap) return
  const gate = executionGate(cap, { ...session.permissionContext(), confirmed: false })
  if (!gate.allowed) {
    discoveryError.value = gate.reason ?? '当前不可执行。'
    return
  }
  const gen = ++discoverGeneration
  const controller = new AbortController()
  activeControllers.add(controller)
  discoveryBusy.value = true
  discoveryError.value = null
  discoveryRaw.value = null
  channels.value = null
  try {
    const res = await runCapability(cap, {} as FormValues, session.runContext(controller.signal))
    if (gen !== discoverGeneration) return // 旧代号：已被新一轮 / 凭证切换失效
    discoveryRaw.value = res.data ?? null
    channels.value = parseChannels(res.data)
  } catch (e) {
    if (gen !== discoverGeneration) return
    discoveryError.value = humanizeError(e, cap)
  } finally {
    activeControllers.delete(controller)
    if (gen === discoverGeneration) {
      discoveryBusy.value = false
      discovered.value = true
    }
  }
}

// ── 凭证切换：清空已展示的渠道数据并使 pending 请求失效（租户不串味）──
watch(
  () => [session.apiKey, session.credentialMode] as const,
  () => {
    discoverGeneration += 1
    abortAll()
    channels.value = null
    discoveryRaw.value = null
    discoveryError.value = null
    discovered.value = false
    discoveryBusy.value = false
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
    <div class="ch ch--focus">
      <InfoNote v-if="focusedIsOutbound" tone="danger" class="ch__focus-note">
        <strong>真实外部投递，默认锁定。</strong> 会向真实渠道（如钉钉 / 飞书）发送消息，误触成本高；
        仅建议预览 / 复制 curl。如确需执行须显式二次确认。
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

  <!-- 工作台着陆：发现 / 出站 / 回调 / 入站 -->
  <div v-else class="ch">
    <ModuleHeader :module-id="moduleId" />

    <WorkbenchSection
      v-if="discoveryCap"
      title="能力发现"
      subtitle="拉取并展示已配置的出站渠道（只读，无副作用）。"
    >
      <template #actions>
        <button
          type="button"
          class="btn btn--primary btn--sm"
          :disabled="discoveryBusy || !session.hasCredential"
          @click="discover"
        >
          {{ discoveryBusy ? '发现中…' : discovered ? '重新发现' : '发现渠道' }}
        </button>
      </template>
      <template #notice>
        <InfoNote v-if="!session.hasCredential" tone="warning">
          请先登录 才能拉取已配置渠道。
        </InfoNote>
        <InfoNote v-if="discoveryError" tone="danger" role="alert">{{ discoveryError }}</InfoNote>
      </template>

      <ul v-if="channels && channels.length" class="ch__channels" aria-live="polite">
        <li v-for="(c, i) in channels" :key="i" class="ch__channel">
          <span class="ch__channel-dot" aria-hidden="true" />
          <div class="ch__channel-main">
            <strong class="ch__channel-name">{{ c.label }}</strong>
            <span v-if="c.detail" class="ch__channel-detail">{{ c.detail }}</span>
          </div>
        </li>
      </ul>
      <EmptyState
        v-else-if="channels && !channels.length"
        variant="empty"
        icon="∅"
        title="没有已配置渠道"
        description="当前租户下未发现已启用的出站渠道。"
      />
      <!-- 兜底：无法解析为渠道列表时展示原始响应，绝不臆造字段。 -->
      <div v-else-if="discoveryFallback" class="ch__fallback">
        <ResponseViewer phase="success" :data="discoveryRaw" />
      </div>
      <EmptyState
        v-else-if="discoveryEmptyBody"
        variant="empty"
        icon="∅"
        title="成功，但响应体为空"
        description="发现请求成功，但服务未返回任何数据。"
      />
      <EmptyState
        v-else-if="!discovered"
        variant="empty"
        icon="🛰"
        title="尚未发现"
        description="点击右上「发现渠道」拉取当前租户下已配置的出站渠道。"
      />
    </WorkbenchSection>

    <WorkbenchSection
      v-if="outboundCap"
      title="出站投递"
      subtitle="向真实渠道投递消息。"
    >
      <template #notice>
        <InfoNote tone="danger">
          <strong>真实外部副作用，默认锁定。</strong> 打开能力后仅预览 / 复制 curl；如确需真投递须在运行器内显式二次确认。
        </InfoNote>
      </template>
      <div class="ch__grid">
        <CapabilityCard :cap="outboundCap" :module-id="module.id" />
      </div>
    </WorkbenchSection>

    <WorkbenchSection
      v-if="callbackCaps.length"
      title="回调桥"
      subtitle="演示 header 注入：任务 / 实例状态经业务 header 传入（body 为任意 payload）。"
    >
      <template #notice>
        <InfoNote tone="info">
          <strong>header 构建器：</strong>在下方表单填写业务 header（如 <code>X-Async-Task-Id</code>、
          <code>X-Workflow-Status</code>），点「预览 curl」即可看到 <code>-H</code> 注入结果。这些 header
          <strong>不会覆盖</strong>网关鉴权用的 <code>X-Api-Key</code>（由会话统一注入）。
        </InfoNote>
      </template>
      <div class="ch__runners">
        <CapabilityRunner v-for="c in callbackCaps" :key="c.id" :cap="c" />
      </div>
    </WorkbenchSection>

    <WorkbenchSection
      v-if="inboundCap"
      title="入站事件"
      subtitle="接收渠道入站事件；可选携带签名 header。"
    >
      <template #notice>
        <InfoNote tone="neutral">
          <code>X-Channel-Signature</code> 为可选 header，仅当后端 <code>inboundSignatureEnabled=true</code>（默认关闭）时才校验。
        </InfoNote>
      </template>
      <div class="ch__grid">
        <CapabilityCard :cap="inboundCap" :module-id="module.id" />
      </div>
    </WorkbenchSection>

    <EmptyState
      v-if="!discoveryCap && !outboundCap && !callbackCaps.length && !inboundCap"
      variant="empty"
      icon="⏳"
      title="能力待补"
      description="未在目录中找到渠道能力。"
    />
  </div>
</template>

<style scoped>
.ch {
  max-width: var(--content-max);
  margin: 0 auto;
  padding: var(--space-6) var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.ch--focus {
  max-width: none;
  padding: 0;
}
.ch__focus-note {
  margin: var(--space-4) var(--space-5) 0;
}
.ch__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(var(--card-min), 1fr));
  gap: var(--space-4);
}
/* 回调运行器：纵向堆叠（复用 RAG 入库分区范式） */
.ch__runners {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

/* 已配置渠道列表 */
.ch__channels {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.ch__channel {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
}
.ch__channel-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  background: var(--success);
}
.ch__channel-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.ch__channel-name {
  font-size: var(--fs-sm);
  color: var(--text);
}
.ch__channel-detail {
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  font-family: var(--font-mono);
}
.ch__fallback {
  min-height: 180px;
  border: 1px solid var(--code-border);
  border-radius: var(--radius);
  background: var(--code-bg);
  overflow: hidden;
}
</style>
