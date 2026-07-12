<script setup lang="ts">
/**
 * Multimodal Console —— 图像 + 语音工作台（module=multimodal）。
 *
 * 顶部统一 ModuleHeader + 诚实横幅（多数能力 flag-off）。图像 / 语音各一个「能力选择器 + 单运行器」：
 *  - 选择器 chips 切换分区内能力（AgentLab 同款范式）；
 *  - 运行器复用通用 CapabilityRunner —— 文件字段经增强 FileField 提供拖拽区 + 图像缩略图 / 音频播放器预览；
 *    caption 结果与原图在运行器左右两栏并排；voice.chat.stream 为 multipart-sse，运行器自动切 SseConsole；
 *  - flag-off 经 executionGate 诚实锁定（运行器内展示原因）。首期仅文件上传，不做浏览器录音。
 *
 * 深链（capId 存在）沿用通用 CapabilityRunner。执行统一经运行器内的 executionGate + runCapability/streamCapability。
 */
import { computed, ref, watch } from 'vue'
import { useCatalogStore } from '../../stores/catalog'
import { isStreamingKind } from '../../api/client'
import type { Capability } from '../../types/catalog'
import CapabilityRunner from '../../components/capability/CapabilityRunner.vue'
import EmptyState from '../../components/common/EmptyState.vue'
import ModuleHeader from '../../components/layout/ModuleHeader.vue'
import WorkbenchSection from '../_shared/WorkbenchSection.vue'
import InfoNote from '../_shared/InfoNote.vue'

const props = defineProps<{ moduleId: string; capId?: string }>()
const catalog = useCatalogStore()

const module = computed(() => catalog.moduleById(props.moduleId))
const focusedCap = computed(() =>
  props.capId ? (module.value?.capabilities ?? []).find((c) => c.id === props.capId) : undefined,
)

const IMAGE_IDS = [
  'vision.caption.file',
  'vision.caption.json',
  'chat.vision',
  'rag.image.ingest',
  'rag.image.search',
]
const VOICE_IDS = ['voice.transcribe', 'voice.chat', 'voice.chat.stream']

function pick(ids: string[]): Capability[] {
  return ids.map((id) => catalog.capabilityById(id)).filter((c): c is Capability => !!c)
}
const imageCaps = computed(() => pick(IMAGE_IDS))
const voiceCaps = computed(() => pick(VOICE_IDS))

// 分区内当前选中的能力（默认首个可用）。
const selectedImageId = ref<string>('')
const selectedVoiceId = ref<string>('')
const selectedImageCap = computed(
  () => imageCaps.value.find((c) => c.id === selectedImageId.value) ?? imageCaps.value[0],
)
const selectedVoiceCap = computed(
  () => voiceCaps.value.find((c) => c.id === selectedVoiceId.value) ?? voiceCaps.value[0],
)
// 目录就绪后把选中初始化到首个可用能力。
watch(
  imageCaps,
  (caps) => {
    if (!selectedImageId.value && caps.length) selectedImageId.value = caps[0].id
  },
  { immediate: true },
)
watch(
  voiceCaps,
  (caps) => {
    if (!selectedVoiceId.value && caps.length) selectedVoiceId.value = caps[0].id
  },
  { immediate: true },
)

// 深链聚焦语音流式能力时，额外提示 multipart-sse 语义。
const focusedIsStream = computed(
  () => !!focusedCap.value && isStreamingKind(focusedCap.value.requestKind),
)
</script>

<template>
  <EmptyState
    v-if="!module"
    variant="error"
    title="模块不存在"
    :description="`未找到模块「${moduleId}」。`"
  />

  <!-- 深链：单能力聚焦（通用运行器；multipart-sse 自动走流式控制台） -->
  <template v-else-if="capId && focusedCap">
    <div class="mm mm--focus">
      <InfoNote v-if="focusedIsStream" tone="info" class="mm__focus-note">
        该能力为 <strong>multipart-sse</strong>（文件上传 + SSE 流式响应），执行后逐句返回。
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

  <!-- 工作台着陆：图像 / 语音分区（选择器 + 单运行器） -->
  <div v-else class="mm">
    <ModuleHeader :module-id="moduleId" />

    <InfoNote tone="warning" class="mm__banner">
      多数能力默认未注册：需开启对应开关（<code>app.vision.enabled</code>、<code>app.voice.enabled</code>、
      <code>app.conversation.vision.enabled</code>、<code>app.rag.multimodal-embedding.enabled</code>）后端才会挂载。
      未启用时可预览请求 / 复制 curl，但不可执行（运行器会标注具体 flag）。首期仅支持文件上传，暂不做浏览器录音采集。
    </InfoNote>

    <WorkbenchSection
      v-if="imageCaps.length"
      title="图像"
      subtitle="图像描述（文件 / base64）、图文对话、图片入库与检索。选择能力后上传图片可见缩略图预览，结果与原图左右并排。"
    >
      <div class="mm__selector" role="tablist" aria-label="图像能力">
        <button
          v-for="c in imageCaps"
          :key="c.id"
          type="button"
          role="tab"
          class="mm__opt"
          :data-cap="c.id"
          :data-active="c.id === selectedImageCap?.id"
          :aria-selected="c.id === selectedImageCap?.id"
          :title="c.state === 'flag-off' ? `未启用：需开启 ${c.featureFlag}` : c.description"
          @click="selectedImageId = c.id"
        >
          {{ c.title }}
          <span v-if="isStreamingKind(c.requestKind)" class="mm__opt-badge">SSE</span>
          <span v-if="c.state === 'flag-off'" class="mm__opt-flag">未启用</span>
        </button>
      </div>
      <CapabilityRunner
        v-if="selectedImageCap"
        :key="selectedImageCap.id"
        :cap="selectedImageCap"
      />
    </WorkbenchSection>

    <WorkbenchSection
      v-if="voiceCaps.length"
      title="语音"
      subtitle="语音转写（ASR）、语音对话，以及 multipart-sse 半流式分句 TTS。上传音频后可就地播放；流式能力逐句返回。"
    >
      <div class="mm__selector" role="tablist" aria-label="语音能力">
        <button
          v-for="c in voiceCaps"
          :key="c.id"
          type="button"
          role="tab"
          class="mm__opt"
          :data-cap="c.id"
          :data-active="c.id === selectedVoiceCap?.id"
          :aria-selected="c.id === selectedVoiceCap?.id"
          :title="c.state === 'flag-off' ? `未启用：需开启 ${c.featureFlag}` : c.description"
          @click="selectedVoiceId = c.id"
        >
          {{ c.title }}
          <span v-if="isStreamingKind(c.requestKind)" class="mm__opt-badge">SSE</span>
          <span v-if="c.state === 'flag-off'" class="mm__opt-flag">未启用</span>
        </button>
      </div>
      <CapabilityRunner
        v-if="selectedVoiceCap"
        :key="selectedVoiceCap.id"
        :cap="selectedVoiceCap"
      />
    </WorkbenchSection>

    <EmptyState
      v-if="!imageCaps.length && !voiceCaps.length"
      variant="empty"
      icon="⏳"
      title="能力待补"
      description="未在目录中找到多模态能力。"
    />
  </div>
</template>

<style scoped>
.mm {
  max-width: var(--content-max);
  margin: 0 auto;
  padding: var(--space-6) var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.mm--focus {
  max-width: none;
  padding: 0;
}
.mm__focus-note {
  margin: var(--space-4) var(--space-5) 0;
}
.mm__banner {
  margin: 0;
}

/* 能力选择器 chips */
.mm__selector {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}
.mm__opt {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  padding: 6px 12px;
  color: var(--text-muted);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  transition: color var(--dur) var(--ease), background var(--dur) var(--ease),
    border-color var(--dur) var(--ease);
}
.mm__opt:hover {
  color: var(--text);
  background: var(--surface-2);
}
.mm__opt:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--primary-border);
}
.mm__opt[data-active='true'] {
  color: var(--primary);
  background: var(--primary-soft);
  border-color: var(--primary-border);
}
.mm__opt-badge {
  font-size: 10px;
  font-weight: var(--fw-bold);
  padding: 0 5px;
  border-radius: var(--radius-sm);
  color: var(--stream);
  background: var(--stream-soft);
  border: 1px solid var(--stream-border);
}
.mm__opt-flag {
  font-size: 10px;
  font-weight: var(--fw-bold);
  padding: 0 5px;
  border-radius: var(--radius-sm);
  color: var(--neutral);
  background: var(--neutral-soft);
  border: 1px solid var(--neutral-border);
}
</style>
