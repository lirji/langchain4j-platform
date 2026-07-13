<script setup lang="ts">
/**
 * 角色多选器：从 options（真实角色名，取自 adminRoles.roleNames）多选。
 *
 * 硬约束：modelValue 中**不在 options 的未知角色**（列表未加载完、或角色被并发删除）**原样保留、可见**，
 * 单独成"未知角色（保留）"分区，可取消但不因"列表里没有"而静默丢弃。
 */
import { computed } from 'vue'

const props = defineProps<{ modelValue: string[]; options: string[] }>()
const emit = defineEmits<{ 'update:modelValue': [string[]] }>()

const selectedSet = computed(() => new Set(props.modelValue))
function isChecked(role: string): boolean {
  return selectedSet.value.has(role)
}

/** 当前值里、options 未包含的角色——保留展示。 */
const unknownSelected = computed(() => props.modelValue.filter((r) => !props.options.includes(r)))

function toggle(role: string): void {
  const next = selectedSet.value.has(role)
    ? props.modelValue.filter((r) => r !== role)
    : [...props.modelValue, role]
  emit('update:modelValue', next)
}
</script>

<template>
  <div class="rp">
    <fieldset class="rp__group">
      <legend class="rp__legend eyebrow">可选角色</legend>
      <p v-if="!options.length" class="rp__hint">角色列表为空或加载失败，暂不能改角色。</p>
      <label v-for="r in options" :key="r" class="rp__item">
        <input type="checkbox" class="rp__cb" :checked="isChecked(r)" @change="toggle(r)" />
        <span class="rp__name">{{ r }}</span>
      </label>
    </fieldset>

    <fieldset v-if="unknownSelected.length" class="rp__group rp__group--unknown">
      <legend class="rp__legend eyebrow">未知角色（保留）</legend>
      <label v-for="r in unknownSelected" :key="r" class="rp__item">
        <input type="checkbox" class="rp__cb" :checked="true" @change="toggle(r)" />
        <span class="rp__name">{{ r }}</span>
      </label>
    </fieldset>
  </div>
</template>

<style scoped>
.rp {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.rp__group {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: var(--space-3);
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-4);
}
.rp__group--unknown {
  border-color: var(--warning-border);
  background: var(--warning-soft);
}
.rp__legend {
  padding: 0 var(--space-2);
  color: var(--text-subtle);
}
.rp__hint {
  flex-basis: 100%;
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--text-subtle);
}
.rp__item {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  cursor: pointer;
  font-size: var(--fs-sm);
}
.rp__cb {
  width: 15px;
  height: 15px;
  accent-color: var(--primary);
}
.rp__name {
  font-weight: var(--fw-medium);
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  background: var(--primary-soft);
  border: 1px solid var(--primary-border);
  color: var(--primary);
}
</style>
