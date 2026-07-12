<script setup lang="ts">
import { ref } from 'vue'

const props = withDefaults(
  defineProps<{ text: string; label?: string; compact?: boolean }>(),
  { label: '复制', compact: false },
)

const copied = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null

async function copy(): Promise<void> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(props.text)
    } else {
      const ta = document.createElement('textarea')
      ta.value = props.text
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    copied.value = true
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => (copied.value = false), 1600)
  } catch {
    /* 剪贴板不可用时静默 */
  }
}
</script>

<template>
  <button
    type="button"
    class="copy-btn"
    :class="{ 'copy-btn--compact': compact, 'copy-btn--done': copied }"
    @click="copy"
  >
    {{ copied ? '已复制 ✓' : label }}
  </button>
</template>

<style scoped>
.copy-btn {
  padding: var(--space-1) var(--space-3);
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--text-muted);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  transition: color var(--dur), background var(--dur);
}
.copy-btn:hover {
  color: var(--text);
  background: var(--surface-3);
}
.copy-btn--compact {
  padding: 2px 8px;
}
.copy-btn--done {
  color: var(--success);
  border-color: var(--success-border);
  background: var(--success-soft);
}
</style>
