<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  fieldId: string
  placeholder?: string
  invalid?: boolean
}>()
const model = defineModel<unknown>()

const text = computed(() => (typeof model.value === 'string' ? model.value : ''))

const parseState = computed<'empty' | 'ok' | 'bad'>(() => {
  const t = text.value.trim()
  if (t === '') return 'empty'
  try {
    JSON.parse(t)
    return 'ok'
  } catch {
    return 'bad'
  }
})

function format(): void {
  try {
    model.value = JSON.stringify(JSON.parse(text.value), null, 2)
  } catch {
    /* 无法格式化时保持原样 */
  }
}
</script>

<template>
  <div class="jsonf">
    <textarea
      :id="fieldId"
      class="form-control form-control--mono jsonf__area"
      rows="5"
      :value="text"
      :placeholder="placeholder ?? '{ }'"
      spellcheck="false"
      :aria-invalid="invalid || parseState === 'bad' || undefined"
      @input="model = ($event.target as HTMLTextAreaElement).value"
    />
    <div class="jsonf__bar">
      <span class="jsonf__status" :data-state="parseState">
        {{ parseState === 'ok' ? 'JSON 合法' : parseState === 'bad' ? 'JSON 格式错误' : '空' }}
      </span>
      <button
        type="button"
        class="jsonf__format"
        :disabled="parseState !== 'ok'"
        @click="format"
      >
        格式化
      </button>
    </div>
  </div>
</template>

<style scoped>
.jsonf__area {
  border-bottom-left-radius: 0;
  border-bottom-right-radius: 0;
}
.jsonf__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 8px;
  border: 1px solid var(--border-strong);
  border-top: none;
  border-bottom-left-radius: var(--radius);
  border-bottom-right-radius: var(--radius);
  background: var(--surface-2);
}
.jsonf__status {
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.jsonf__status[data-state='ok'] {
  color: var(--success);
}
.jsonf__status[data-state='bad'] {
  color: var(--danger);
}
.jsonf__format {
  font-size: var(--fs-xs);
  color: var(--primary);
  background: transparent;
  border: none;
}
.jsonf__format:disabled {
  color: var(--text-subtle);
  cursor: not-allowed;
}
</style>
