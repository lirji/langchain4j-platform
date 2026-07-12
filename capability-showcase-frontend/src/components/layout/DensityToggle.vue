<script setup lang="ts">
import { useUiStore } from '../../stores/ui'

/** 密度切换分段控件（紧凑 | 舒适），aria-pressed 表达当前态。 */
const ui = useUiStore()
</script>

<template>
  <div class="density" role="group" aria-label="界面密度">
    <button
      type="button"
      class="density__btn"
      :class="{ 'is-active': ui.density === 'compact' }"
      :aria-pressed="ui.density === 'compact'"
      title="紧凑密度"
      @click="ui.setDensity('compact')"
    >
      紧凑
    </button>
    <button
      type="button"
      class="density__btn"
      :class="{ 'is-active': ui.density === 'comfortable' }"
      :aria-pressed="ui.density === 'comfortable'"
      title="舒适密度"
      @click="ui.setDensity('comfortable')"
    >
      舒适
    </button>
  </div>
</template>

<style scoped>
.density {
  display: inline-flex;
  padding: 2px;
  gap: 2px;
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}
.density__btn {
  padding: 0 10px;
  height: var(--control-h-sm);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--text-subtle);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  transition: color var(--dur) var(--ease), background var(--dur) var(--ease),
    box-shadow var(--dur) var(--ease);
}
.density__btn:hover {
  color: var(--text);
}
.density__btn.is-active {
  color: var(--primary);
  background: var(--surface);
  box-shadow: var(--shadow-sm), inset 0 0 0 1px var(--primary-border);
}
</style>
