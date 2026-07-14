<script setup lang="ts">
/**
 * 用户组多选器：从 options（真实组名，取自 adminGroups.groupNames）多选。镜像 RolePicker。
 *
 * 硬约束：modelValue 中**不在 options 的未知组**（列表未加载完、或组被并发删除）**原样保留、可见**，
 * 单独成"未知用户组（保留）"分区，可取消但不因"列表里没有"而静默丢弃。
 */
import { computed } from 'vue'

const props = defineProps<{ modelValue: string[]; options: string[] }>()
const emit = defineEmits<{ 'update:modelValue': [string[]] }>()

const selectedSet = computed(() => new Set(props.modelValue))
function isChecked(group: string): boolean {
  return selectedSet.value.has(group)
}

/** 当前值里、options 未包含的组——保留展示。 */
const unknownSelected = computed(() => props.modelValue.filter((g) => !props.options.includes(g)))

function toggle(group: string): void {
  const next = selectedSet.value.has(group)
    ? props.modelValue.filter((g) => g !== group)
    : [...props.modelValue, group]
  emit('update:modelValue', next)
}
</script>

<template>
  <div class="gp">
    <fieldset class="gp__group">
      <legend class="gp__legend eyebrow">可选用户组</legend>
      <p v-if="!options.length" class="gp__hint">用户组列表为空或加载失败，暂不能改所属组。</p>
      <label v-for="g in options" :key="g" class="gp__item">
        <input type="checkbox" class="gp__cb" :checked="isChecked(g)" @change="toggle(g)" />
        <span class="gp__name">{{ g }}</span>
      </label>
    </fieldset>

    <fieldset v-if="unknownSelected.length" class="gp__group gp__group--unknown">
      <legend class="gp__legend eyebrow">未知用户组（保留）</legend>
      <label v-for="g in unknownSelected" :key="g" class="gp__item">
        <input type="checkbox" class="gp__cb" :checked="true" @change="toggle(g)" />
        <span class="gp__name">{{ g }}</span>
      </label>
    </fieldset>
  </div>
</template>

<style scoped>
.gp {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.gp__group {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: var(--space-3);
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-4);
}
.gp__group--unknown {
  border-color: var(--warning-border);
  background: var(--warning-soft);
}
.gp__legend {
  padding: 0 var(--space-2);
  color: var(--text-subtle);
}
.gp__hint {
  flex-basis: 100%;
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--text-subtle);
}
.gp__item {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  cursor: pointer;
  font-size: var(--fs-sm);
}
.gp__cb {
  width: 15px;
  height: 15px;
  accent-color: var(--primary);
}
.gp__name {
  font-weight: var(--fw-medium);
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  background: var(--primary-soft);
  border: 1px solid var(--primary-border);
  color: var(--primary);
}
</style>
