<script setup lang="ts">
import type { ParamIn } from '../../types/catalog'

defineProps<{
  fieldId: string
  label: string
  paramIn: ParamIn
  required?: boolean
  help?: string
  error?: string
}>()

const IN_LABEL: Record<ParamIn, string> = {
  body: 'body',
  query: 'query',
  path: 'path',
  'form-data': 'form-data',
  header: 'header',
}
</script>

<template>
  <div class="field" :class="{ 'field--error': !!error }">
    <label :for="fieldId" class="field__label">
      <span class="field__name">
        {{ label }}
        <span v-if="required" class="field__req" aria-hidden="true">*</span>
      </span>
      <span class="field__in" :title="`参数位置：${IN_LABEL[paramIn]}`">{{ IN_LABEL[paramIn] }}</span>
    </label>

    <slot />

    <p v-if="help && !error" class="field__help">{{ help }}</p>
    <p v-if="error" :id="`${fieldId}-error`" class="field__error" role="alert">{{ error }}</p>
  </div>
</template>

<style scoped>
.field {
  margin-bottom: var(--space-4);
}
.field__label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
/* 手机档：label 与参数位置徽章允许换行，避免长参数名挤压 */
@media (max-width: 640px) {
  .field__label {
    flex-wrap: wrap;
  }
}
.field__name {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text);
}
.field__req {
  color: var(--danger);
  margin-left: 2px;
}
.field__in {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-subtle);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 0 6px;
}
.field__help {
  margin-top: 5px;
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.field__error {
  margin-top: 5px;
  font-size: var(--fs-xs);
  color: var(--danger);
  font-weight: 600;
}
</style>
