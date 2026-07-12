<script setup lang="ts">
import { computed } from 'vue'
import type { CapabilityState } from '../../../types/catalog'

const props = defineProps<{ state: CapabilityState }>()

// 徽章不单靠颜色：每态附带独立文字与图标（无障碍）。
const META: Record<CapabilityState, { label: string; icon: string; tone: string; hint: string }> = {
  ready: { label: '就绪', icon: '●', tone: 'ok', hint: '默认可执行。' },
  'ready-degraded': {
    label: '就绪·降级',
    icon: '◐',
    tone: 'ok-warn',
    hint: '可执行，但为内存/确定性降级实现（非真实语义/生产依赖）。',
  },
  'flag-off': {
    label: '未启用',
    icon: '○',
    tone: 'off',
    hint: '需开启对应 feature flag 才注册，默认不可执行。',
  },
  'scope-required': {
    label: '需授权',
    icon: '◆',
    tone: 'warn',
    hint: '需要特定 scope（如 ingest/approve），否则返回 403。',
  },
  'display-only': {
    label: '已锁定',
    icon: '■',
    tone: 'danger',
    hint: '破坏性写/删，默认仅展示不执行。',
  },
}

const meta = computed(() => META[props.state])
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
