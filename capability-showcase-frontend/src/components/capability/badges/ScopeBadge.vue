<script setup lang="ts">
defineProps<{ scopes: string[] }>()
</script>

<template>
  <span
    v-if="scopes.length"
    class="scope"
    :title="`需要 scope：${scopes.join(', ')}`"
  >
    <span class="scope__lock" aria-hidden="true">🔑</span>
    scope: {{ scopes.join(' / ') }}
  </span>
</template>

<style scoped>
.scope {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--warning);
  background: linear-gradient(135deg, var(--warning-soft), transparent 86%);
  border: 1px solid var(--warning-border);
  border-radius: var(--radius-sm);
}
.scope__lock {
  font-size: 10px;
}
</style>
