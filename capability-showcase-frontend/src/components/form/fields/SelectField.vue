<script setup lang="ts">
defineProps<{
  fieldId: string
  options: string[]
  required?: boolean
  invalid?: boolean
}>()
const model = defineModel<unknown>()
</script>

<template>
  <select
    :id="fieldId"
    class="form-control"
    :value="(model as string) ?? ''"
    :aria-invalid="invalid || undefined"
    @change="model = ($event.target as HTMLSelectElement).value"
  >
    <option v-if="!required" value="">（不设置）</option>
    <option v-for="opt in options" :key="opt" :value="opt">{{ opt }}</option>
  </select>
</template>
