<script setup lang="ts">
defineProps<{
  fieldId: string
  placeholder?: string
  min?: number
  max?: number
  step?: number
  invalid?: boolean
}>()
const model = defineModel<unknown>()

function onInput(e: Event): void {
  const raw = (e.target as HTMLInputElement).value
  model.value = raw === '' ? undefined : Number(raw)
}
</script>

<template>
  <input
    :id="fieldId"
    class="form-control"
    type="number"
    inputmode="decimal"
    :value="model ?? ''"
    :placeholder="placeholder"
    :min="min"
    :max="max"
    :step="step"
    :aria-invalid="invalid || undefined"
    :aria-describedby="invalid ? `${fieldId}-error` : undefined"
    @input="onInput"
  />
</template>
