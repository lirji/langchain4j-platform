<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ method: string }>()
const m = computed(() => props.method.toUpperCase())
</script>

<template>
  <span class="method" :data-method="m" :title="`HTTP ${m}`">{{ m }}</span>
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
</style>
