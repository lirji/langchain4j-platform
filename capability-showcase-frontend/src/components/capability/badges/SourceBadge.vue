<script setup lang="ts">
import { computed } from 'vue'
import type { CapabilitySource } from '../../../types/catalog'

const props = withDefaults(defineProps<{ source?: CapabilitySource }>(), { source: 'manifest' })
const isLive = computed(() => props.source === 'live')
</script>

<template>
  <span
    class="source"
    :data-live="isLive"
    :title="isLive ? '由运行时 live discovery 确认存在' : '来自静态能力清单'"
  >
    {{ isLive ? '实时' : '清单' }}
  </span>
</template>

<style scoped>
.source {
  display: inline-block;
  padding: 1px 7px;
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--text-subtle);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.source[data-live='true'] {
  color: var(--stream);
  background: linear-gradient(135deg, var(--stream-soft), transparent 86%);
  border-color: var(--stream-border);
  box-shadow: var(--glow-stream);
}
</style>
