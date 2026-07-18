<script setup lang="ts">
defineProps<{ fieldId: string; text?: string }>()
const model = defineModel<unknown>()
</script>

<template>
  <label class="bool" :for="fieldId">
    <input
      :id="fieldId"
      type="checkbox"
      class="bool__input"
      :checked="!!model"
      @change="model = ($event.target as HTMLInputElement).checked"
    />
    <span class="bool__text">{{ text ?? (model ? '是 / true' : '否 / false') }}</span>
  </label>
</template>

<style scoped>
.bool {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
}
.bool__input {
  width: 18px;
  height: 18px;
  accent-color: var(--primary);
}
.bool__text {
  font-size: var(--fs-sm);
  color: var(--text-muted);
}
/* 触屏：不走 .form-control 的勾选目标单独抬升 */
@media (pointer: coarse) {
  .bool {
    min-height: 32px;
  }
  .bool__input {
    width: 22px;
    height: 22px;
  }
}
</style>
