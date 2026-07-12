<script setup lang="ts">
defineProps<{
  fieldId: string
  placeholder?: string
  maxlength?: number
  invalid?: boolean
  rows?: number
}>()
const model = defineModel<unknown>()
</script>

<template>
  <textarea
    :id="fieldId"
    class="form-control"
    :rows="rows ?? 4"
    :value="(model as string) ?? ''"
    :placeholder="placeholder"
    :maxlength="maxlength"
    :aria-invalid="invalid || undefined"
    :aria-describedby="invalid ? `${fieldId}-error` : undefined"
    @input="model = ($event.target as HTMLTextAreaElement).value"
  />
</template>
