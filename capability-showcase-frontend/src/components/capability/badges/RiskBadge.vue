<script setup lang="ts">
import { computed } from 'vue'
import type { RiskLevel } from '../../../types/catalog'

const props = defineProps<{ risk: RiskLevel }>()
const META: Record<RiskLevel, { label: string; icon: string }> = {
  safe: { label: '安全', icon: '✓' },
  caution: { label: '谨慎', icon: '!' },
  destructive: { label: '破坏性', icon: '✕' },
}
const meta = computed(() => META[props.risk])
</script>

<template>
  <span class="risk" :data-risk="risk" :title="`风险等级：${meta.label}`">
    <span aria-hidden="true">{{ meta.icon }}</span> {{ meta.label }}
  </span>
</template>

<style scoped>
.risk {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  font-size: var(--fs-xs);
  font-weight: 600;
  border-radius: var(--radius-sm);
  border: 1px solid transparent;
}
.risk[data-risk='safe'] {
  color: var(--success);
  background: linear-gradient(135deg, var(--success-soft), transparent 86%);
  border-color: var(--success-border);
}
.risk[data-risk='caution'] {
  color: var(--warning);
  background: linear-gradient(135deg, var(--warning-soft), transparent 86%);
  border-color: var(--warning-border);
}
.risk[data-risk='destructive'] {
  color: var(--danger);
  background: linear-gradient(135deg, var(--danger-soft), transparent 86%);
  border-color: var(--danger-border);
  box-shadow: var(--glow-danger);
}
</style>
