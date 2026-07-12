<script setup lang="ts">
import { computed } from 'vue'
import { useUiStore } from '../../stores/ui'

const ui = useUiStore()
const icon = computed(() => (ui.theme === 'system' ? '🖥' : ui.theme === 'dark' ? '🌙' : '☀'))
const label = computed(() =>
  ui.theme === 'system' ? '跟随系统' : ui.theme === 'dark' ? '深色' : '浅色',
)
</script>

<template>
  <button
    type="button"
    class="theme-toggle"
    :title="`主题：${label}（点击切换）`"
    :aria-label="`当前主题 ${label}，点击切换`"
    @click="ui.cycleTheme()"
  >
    <span aria-hidden="true">{{ icon }}</span>
    <span class="theme-toggle__text">{{ label }}</span>
  </button>
</template>

<style scoped>
.theme-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  color: var(--text-muted);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  font-size: var(--fs-sm);
}
.theme-toggle:hover {
  color: var(--text);
  background: var(--surface-3);
}
@media (max-width: 640px) {
  .theme-toggle__text {
    display: none;
  }
}
</style>
