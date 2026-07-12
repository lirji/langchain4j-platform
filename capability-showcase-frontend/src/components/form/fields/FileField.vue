<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { formatBytes } from '../../../utils/json'

defineProps<{ fieldId: string; accept?: string; invalid?: boolean }>()
const model = defineModel<unknown>()
const dragging = ref(false)
const inputEl = ref<HTMLInputElement | null>(null)

// 防御性判断：非 DOM 环境（SSR / 测试）可能没有 File 全局，避免 `instanceof` 抛错。
const isFile = computed(() => typeof File !== 'undefined' && model.value instanceof File)
const mimeType = computed(() => (isFile.value ? (model.value as File).type : ''))
const isImage = computed(() => mimeType.value.startsWith('image/'))
const isAudio = computed(() => mimeType.value.startsWith('audio/'))

// 图像缩略图 / 音频播放器预览：为可预览类型创建 object URL，切换 / 卸载时回收避免泄漏。
const previewUrl = ref<string | null>(null)
function revoke(): void {
  if (previewUrl.value) {
    try {
      URL.revokeObjectURL(previewUrl.value)
    } catch {
      /* noop */
    }
    previewUrl.value = null
  }
}
watch(model, (v) => {
  revoke()
  if (
    typeof File !== 'undefined' &&
    v instanceof File &&
    (v.type.startsWith('image/') || v.type.startsWith('audio/')) &&
    typeof URL !== 'undefined' &&
    typeof URL.createObjectURL === 'function'
  ) {
    try {
      previewUrl.value = URL.createObjectURL(v)
    } catch {
      previewUrl.value = null
    }
  }
})
onBeforeUnmount(revoke)

function pick(files: FileList | null): void {
  model.value = files && files.length ? files[0] : null
}
function onDrop(e: DragEvent): void {
  dragging.value = false
  pick(e.dataTransfer?.files ?? null)
}
function clear(): void {
  model.value = null
  if (inputEl.value) inputEl.value.value = ''
}
</script>

<template>
  <div>
    <div
      class="dropzone"
      :class="{ 'dropzone--drag': dragging, 'dropzone--has': !!model }"
      :data-invalid="invalid || undefined"
      role="button"
      tabindex="0"
      @click="inputEl?.click()"
      @keydown.enter.prevent="inputEl?.click()"
      @keydown.space.prevent="inputEl?.click()"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop.prevent="onDrop"
    >
      <input
        :id="fieldId"
        ref="inputEl"
        type="file"
        class="dropzone__input"
        :accept="accept"
        @change="pick(($event.target as HTMLInputElement).files)"
      />
      <template v-if="isFile">
        <span class="dropzone__file">📄 {{ (model as File).name }}</span>
        <span class="dropzone__meta">{{ formatBytes((model as File).size) }}</span>
      </template>
      <template v-else>
        <span class="dropzone__hint">点击或拖拽文件到此处上传</span>
        <span v-if="accept" class="dropzone__meta">接受：{{ accept }}</span>
      </template>
    </div>
    <!-- 缩略图 / 音频播放器预览（首期仅文件上传，不做录音采集） -->
    <div v-if="previewUrl && isImage" class="filepreview">
      <img :src="previewUrl" :alt="(model as File).name" class="filepreview__thumb" />
    </div>
    <audio
      v-else-if="previewUrl && isAudio"
      class="filepreview__audio"
      :src="previewUrl"
      controls
      preload="metadata"
    />

    <button v-if="isFile" type="button" class="dropzone__clear" @click="clear">
      移除文件
    </button>
  </div>
</template>

<style scoped>
.dropzone {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: var(--space-5);
  text-align: center;
  border: 1.5px dashed var(--border-strong);
  border-radius: var(--radius);
  background: var(--surface-2);
  color: var(--text-muted);
  transition: border-color var(--dur), background var(--dur);
}
.dropzone--drag {
  border-color: var(--primary);
  background: var(--primary-soft);
}
.dropzone--has {
  border-style: solid;
  border-color: var(--success-border);
  background: var(--success-soft);
  color: var(--success);
}
.dropzone[data-invalid='true'] {
  border-color: var(--danger);
}
.dropzone__input {
  display: none;
}
.dropzone__file {
  font-weight: 600;
}
.dropzone__meta {
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.dropzone__clear {
  margin-top: 6px;
  font-size: var(--fs-xs);
  color: var(--danger);
  background: transparent;
  border: none;
  text-decoration: underline;
}
.filepreview {
  margin-top: var(--space-2);
}
.filepreview__thumb {
  display: block;
  max-width: 100%;
  max-height: 200px;
  border-radius: var(--radius);
  border: 1px solid var(--border);
  object-fit: contain;
  background: var(--surface-2);
}
.filepreview__audio {
  margin-top: var(--space-2);
  width: 100%;
}
</style>
