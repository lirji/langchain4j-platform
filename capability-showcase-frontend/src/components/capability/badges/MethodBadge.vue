<script setup lang="ts">
import { computed } from 'vue'
// compact：侧栏/命令面板等密集列表用的窄形态（去最小宽度与描边），默认宽形态其它页面沿用。
const props = defineProps<{ method: string; compact?: boolean }>()
const m = computed(() => props.method.toUpperCase())
</script>

<template>
  <span class="method" :class="{ 'method--compact': compact }" :data-method="m" :title="`HTTP ${m}`">{{ m }}</span>
</template>

<style scoped>
.method {
  display: inline-block;
  min-width: 52px;
  text-align: center;
  padding: 2px 8px;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: 700;
  letter-spacing: 0.02em;
  border-radius: var(--radius-sm);
  border: 1px solid transparent;
}
.method[data-method='GET'] {
  color: var(--primary);
  background: linear-gradient(135deg, var(--primary-soft), transparent 88%);
  border-color: var(--primary-border);
}
.method[data-method='POST'] {
  color: var(--success);
  background: linear-gradient(135deg, var(--success-soft), transparent 88%);
  border-color: var(--success-border);
}
.method[data-method='PATCH'],
.method[data-method='PUT'] {
  color: var(--warning);
  background: linear-gradient(135deg, var(--warning-soft), transparent 88%);
  border-color: var(--warning-border);
}
.method[data-method='DELETE'] {
  color: var(--danger);
  background: linear-gradient(135deg, var(--danger-soft), transparent 88%);
  border-color: var(--danger-border);
}
/* compact：密集导航列表专用——固定 40px、更小字、无描边渐变，仅纯色底色块，方便与能力名对齐。 */
.method--compact {
  min-width: 40px;
  padding: 2px 4px;
  font-size: 9px;
  border-color: transparent;
  background: var(--surface-3);
  color: var(--text-muted);
}
.method--compact[data-method='GET'] {
  color: var(--primary);
  background: var(--primary-soft);
}
.method--compact[data-method='POST'] {
  color: var(--success);
  background: var(--success-soft);
}
.method--compact[data-method='PATCH'],
.method--compact[data-method='PUT'] {
  color: var(--warning);
  background: var(--warning-soft);
}
.method--compact[data-method='DELETE'] {
  color: var(--danger);
  background: var(--danger-soft);
}
</style>
