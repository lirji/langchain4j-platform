<script setup lang="ts">
/**
 * 按域分组的 scope 勾选器（无障碍 fieldset/legend）。
 *
 * 硬约束（对齐 scopeCatalog 契约）：**未知 scope**（不在 scopeCatalog 字典）在 UI 上一律**原样保留、
 * 照实回写**，绝不丢弃——落入"其它 / 未知"分组，可取消勾选但不可新增未知项。
 * readonly=true 时仅按组展示已选 scope（chip），不渲染任何交互输入（用于用户 directScopes 只读展示）。
 */
import { computed } from 'vue'
import {
  SCOPE_GROUPS,
  describeScope,
  groupScopes,
  UNKNOWN_GROUP_ID,
  UNKNOWN_GROUP_LABEL,
} from '../../config/scopeCatalog'

const props = withDefaults(
  defineProps<{ modelValue: string[]; readonly?: boolean }>(),
  { readonly: false },
)
const emit = defineEmits<{ 'update:modelValue': [string[]] }>()

/** 高权 scope：授予需谨慎（放权后果显著）。仅视觉标记，不阻止勾选。 */
const HIGH_RISK = new Set(['role-admin', 'public-ingest'])

/** 当前值里的未知 scope（字典未收录）——原样保留，殿后单独成组。 */
const unknownSelected = computed(() => props.modelValue.filter((s) => !describeScope(s)))

const selectedSet = computed(() => new Set(props.modelValue))
function isChecked(scope: string): boolean {
  return selectedSet.value.has(scope)
}

/** 只读展示：按组归拢已选 scope（含未知项，绝不丢弃）。 */
const readonlyGroups = computed(() => groupScopes(props.modelValue))

function toggle(scope: string): void {
  if (props.readonly) return
  const next = selectedSet.value.has(scope)
    ? props.modelValue.filter((s) => s !== scope)
    : [...props.modelValue, scope]
  emit('update:modelValue', next)
}
</script>

<template>
  <!-- 只读：仅展示已选 scope（分组 chip），无交互输入 -->
  <div v-if="readonly" class="sp sp--readonly">
    <p v-if="!modelValue.length" class="sp__empty">（无）</p>
    <div v-for="g in readonlyGroups" :key="g.id" class="sp__ro-group">
      <span class="sp__ro-legend eyebrow">{{ g.label }}</span>
      <span class="sp__chips">
        <span
          v-for="it in g.items"
          :key="it.scope"
          class="sp__chip"
          :class="{ 'sp__chip--unknown': !it.known, 'sp__chip--risk': HIGH_RISK.has(it.scope) }"
          :title="it.desc"
        >
          <span v-if="HIGH_RISK.has(it.scope)" aria-hidden="true">⚠ </span>{{ it.label }}
        </span>
      </span>
    </div>
  </div>

  <!-- 可编辑：按域分组的复选框 -->
  <div v-else class="sp">
    <fieldset v-for="g in SCOPE_GROUPS" :key="g.id" class="sp__group">
      <legend class="sp__legend eyebrow">{{ g.label }}</legend>
      <label
        v-for="s in g.scopes"
        :key="s.scope"
        class="sp__item"
        :class="{ 'sp__item--risk': HIGH_RISK.has(s.scope) }"
        :title="s.desc"
      >
        <input
          type="checkbox"
          class="sp__cb"
          :checked="isChecked(s.scope)"
          @change="toggle(s.scope)"
        />
        <span class="sp__label">
          <span v-if="HIGH_RISK.has(s.scope)" class="sp__risk" aria-hidden="true">⚠</span>
          {{ s.label }}
          <code class="sp__token">{{ s.scope }}</code>
        </span>
      </label>
    </fieldset>

    <!-- 未识别（保留）：字典未收录、当前值携带的 scope，默认勾选，可取消但不丢弃 -->
    <fieldset v-if="unknownSelected.length" class="sp__group sp__group--unknown">
      <legend class="sp__legend eyebrow">{{ UNKNOWN_GROUP_LABEL }}</legend>
      <p class="sp__hint">前端字典未收录的 scope，原样保留回写（可取消勾选以移除）。</p>
      <label v-for="s in unknownSelected" :key="s" class="sp__item">
        <input type="checkbox" class="sp__cb" :checked="true" @change="toggle(s)" />
        <span class="sp__label"><code class="sp__token">{{ s }}</code></span>
      </label>
    </fieldset>
    <span class="sr-only" :data-unknown-group="UNKNOWN_GROUP_ID" />
  </div>
</template>

<style scoped>
.sp {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.sp__group {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: var(--space-3);
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-4);
}
.sp__group--unknown {
  border-color: var(--warning-border);
  background: var(--warning-soft);
}
.sp__legend {
  padding: 0 var(--space-2);
  color: var(--text-subtle);
}
.sp__hint {
  flex-basis: 100%;
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--warning);
}
.sp__item {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  cursor: pointer;
  font-size: var(--fs-sm);
  color: var(--text);
}
.sp__item--risk .sp__label {
  color: var(--warning);
  font-weight: var(--fw-semibold);
}
.sp__cb {
  width: 15px;
  height: 15px;
  accent-color: var(--primary);
  flex-shrink: 0;
}
.sp__label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.sp__risk {
  color: var(--warning);
}
.sp__token {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  background: var(--surface-2);
  padding: 1px 6px;
  border-radius: var(--radius-sm);
}
/* 只读展示 */
.sp--readonly {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.sp__empty {
  margin: 0;
  color: var(--text-subtle);
  font-size: var(--fs-sm);
}
.sp__ro-group {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.sp__ro-legend {
  color: var(--text-subtle);
  flex-shrink: 0;
}
.sp__chips {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 6px;
}
.sp__chip {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text-muted);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius-pill);
  padding: 2px 10px;
}
.sp__chip--unknown {
  color: var(--warning);
  background: var(--warning-soft);
  border-color: var(--warning-border);
}
.sp__chip--risk {
  color: var(--warning);
  border-color: var(--warning-border);
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}
</style>
