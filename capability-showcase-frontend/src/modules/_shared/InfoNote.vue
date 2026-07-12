<script setup lang="ts">
/**
 * 领域工作台通用「提示条」——统一各专用视图的 flag/scope/锁定/422 说明文案样式。
 * 纯展示；配色复用设计 token，与 CapabilityHeader 的 notice 视觉一致。
 */
defineProps<{ tone?: 'info' | 'warning' | 'danger' | 'neutral' | 'success' }>()
</script>

<template>
  <div class="note" :data-tone="tone ?? 'info'" role="note">
    <slot />
  </div>
</template>

<style scoped>
.note {
  position: relative;
  overflow: hidden;
  padding: var(--space-2) var(--space-3) var(--space-2) var(--space-4);
  font-size: var(--fs-sm);
  line-height: 1.55;
  border-radius: var(--radius);
  border: 1px solid var(--glass-border);
  background: var(--glass-bg-strong);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.4);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.4);
}
/* 左侧 3px 语义强调竖条（跟随 tone 文字色） */
.note::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: currentColor;
  opacity: 0.55;
}
.note :deep(code) {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  padding: 1px 5px;
  border-radius: var(--radius-sm);
  background: rgba(127, 127, 127, 0.14);
}
.note :deep(strong) {
  font-weight: 700;
}
.note[data-tone='info'] {
  color: var(--info);
  border-color: var(--primary-border);
}
.note[data-tone='warning'] {
  color: var(--warning);
  border-color: var(--warning-border);
}
.note[data-tone='danger'] {
  color: var(--danger);
  border-color: var(--danger-border);
}
.note[data-tone='neutral'] {
  color: var(--neutral);
  border-color: var(--neutral-border);
}
.note[data-tone='success'] {
  color: var(--success);
  border-color: var(--success-border);
}
</style>
