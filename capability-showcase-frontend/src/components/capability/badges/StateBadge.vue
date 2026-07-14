<script setup lang="ts">
import { computed } from 'vue'
import type { CapabilityState } from '../../../types/catalog'
// 五态元数据单一事实源（与侧栏/命令面板 compact 状态点共用，避免 tone 映射重复漂移）。
import { STATE_META } from '../../../config/stateMeta'

const props = defineProps<{ state: CapabilityState }>()

// 徽章不单靠颜色：每态附带独立文字与图标（无障碍）。
const meta = computed(() => STATE_META[props.state])
</script>

<template>
  <span class="state" :data-tone="meta.tone" :title="meta.hint">
    <span class="state__icon" aria-hidden="true">{{ meta.icon }}</span>
    {{ meta.label }}
  </span>
</template>

<style scoped>
.state {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 9px;
  font-size: var(--fs-xs);
  font-weight: 600;
  border-radius: var(--radius-pill);
  border: 1px solid transparent;
  white-space: nowrap;
}
.state__icon {
  font-size: 9px;
}
.state[data-tone='ok'] {
  color: var(--success);
  background: linear-gradient(135deg, var(--success-soft), transparent 86%);
  border-color: var(--success-border);
}
.state[data-tone='ok-warn'] {
  color: var(--success);
  background: linear-gradient(135deg, var(--success-soft), transparent 86%);
  border-color: var(--warning-border);
}
.state[data-tone='off'] {
  color: var(--neutral);
  background: linear-gradient(135deg, var(--neutral-soft), transparent 86%);
  border-color: var(--neutral-border);
}
.state[data-tone='warn'] {
  color: var(--warning);
  background: linear-gradient(135deg, var(--warning-soft), transparent 86%);
  border-color: var(--warning-border);
}
.state[data-tone='danger'] {
  color: var(--danger);
  background: linear-gradient(135deg, var(--danger-soft), transparent 86%);
  border-color: var(--danger-border);
}
</style>
