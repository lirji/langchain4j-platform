<script setup lang="ts">
defineProps<{
  fieldId: string
  placeholder?: string
  maxlength?: number
  invalid?: boolean
}>()
const model = defineModel<unknown>()
</script>

<template>
  <input
    :id="fieldId"
    class="form-control"
    type="text"
    :value="(model as string) ?? ''"
    :placeholder="placeholder"
    :maxlength="maxlength"
    :aria-invalid="invalid || undefined"
    :aria-describedby="invalid ? `${fieldId}-error` : undefined"
    @input="model = ($event.target as HTMLInputElement).value"
  />
</template>
