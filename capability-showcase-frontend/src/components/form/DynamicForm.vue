<script setup lang="ts">
import { ref, watch } from 'vue'
import type { ParamSpec } from '../../types/catalog'
import { validateParams, type FormValues } from '../../utils/validation'
import FieldWrapper from './FieldWrapper.vue'
import StringField from './fields/StringField.vue'
import TextField from './fields/TextField.vue'
import NumberField from './fields/NumberField.vue'
import BooleanField from './fields/BooleanField.vue'
import SelectField from './fields/SelectField.vue'
import FileField from './fields/FileField.vue'
import JsonField from './fields/JsonField.vue'

const props = defineProps<{ params: ParamSpec[]; idPrefix?: string }>()
const model = defineModel<FormValues>({ default: () => ({}) })

const errors = ref<Record<string, string>>({})

function defaultFor(p: ParamSpec): unknown {
  if (p.type === 'file') return null
  if (p.defaultValue !== undefined && p.defaultValue !== null) return p.defaultValue
  if (p.type === 'boolean') return false
  return ''
}

function initDefaults(): void {
  const next: FormValues = {}
  for (const p of props.params) next[p.name] = defaultFor(p)
  model.value = next
  errors.value = {}
}

watch(() => props.params, initDefaults, { immediate: true })

function setField(name: string, value: unknown): void {
  model.value = { ...model.value, [name]: value }
  if (errors.value[name]) {
    const rest = { ...errors.value }
    delete rest[name]
    errors.value = rest
  }
}

function fieldId(p: ParamSpec): string {
  return `${props.idPrefix ?? 'f'}-${p.name}`
}

/** 供父组件在提交前调用；返回错误映射（空则通过）。 */
function validate(): Record<string, string> {
  errors.value = validateParams(props.params, model.value)
  return errors.value
}

defineExpose({ validate })
</script>

<template>
  <form class="dynform" novalidate @submit.prevent>
    <p v-if="!params.length" class="dynform__none">该能力无需参数，可直接执行。</p>

    <FieldWrapper
      v-for="p in params"
      :key="p.name"
      :field-id="fieldId(p)"
      :label="p.label || p.name"
      :param-in="p.in"
      :required="p.required"
      :help="p.help"
      :error="errors[p.name]"
    >
      <TextField
        v-if="p.type === 'text'"
        :field-id="fieldId(p)"
        :placeholder="p.placeholder"
        :maxlength="p.maxLength"
        :invalid="!!errors[p.name]"
        :model-value="model[p.name]"
        @update:model-value="setField(p.name, $event)"
      />
      <NumberField
        v-else-if="p.type === 'number' || p.type === 'integer'"
        :field-id="fieldId(p)"
        :placeholder="p.placeholder"
        :min="p.min"
        :max="p.max"
        :step="p.type === 'integer' ? 1 : undefined"
        :invalid="!!errors[p.name]"
        :model-value="model[p.name]"
        @update:model-value="setField(p.name, $event)"
      />
      <BooleanField
        v-else-if="p.type === 'boolean'"
        :field-id="fieldId(p)"
        :model-value="model[p.name]"
        @update:model-value="setField(p.name, $event)"
      />
      <SelectField
        v-else-if="p.type === 'select'"
        :field-id="fieldId(p)"
        :options="p.enumValues ?? []"
        :required="p.required"
        :invalid="!!errors[p.name]"
        :model-value="model[p.name]"
        @update:model-value="setField(p.name, $event)"
      />
      <FileField
        v-else-if="p.type === 'file'"
        :field-id="fieldId(p)"
        :accept="p.accept"
        :invalid="!!errors[p.name]"
        :model-value="model[p.name]"
        @update:model-value="setField(p.name, $event)"
      />
      <JsonField
        v-else-if="p.type === 'json' || p.type === 'array' || p.type === 'object'"
        :field-id="fieldId(p)"
        :placeholder="p.placeholder"
        :invalid="!!errors[p.name]"
        :model-value="model[p.name]"
        @update:model-value="setField(p.name, $event)"
      />
      <StringField
        v-else
        :field-id="fieldId(p)"
        :placeholder="p.placeholder"
        :maxlength="p.maxLength"
        :invalid="!!errors[p.name]"
        :model-value="model[p.name]"
        @update:model-value="setField(p.name, $event)"
      />
    </FieldWrapper>
  </form>
</template>

<style scoped>
/* 整表单统一限宽：所有字段随之收敛到同一宽度、靠左对齐，页页一致（不随字段类型/数量变化）。 */
.dynform {
  max-width: var(--form-max);
}
.dynform__none {
  padding: var(--space-3);
  font-size: var(--fs-sm);
  color: var(--text-subtle);
  background: var(--surface-2);
  border-radius: var(--radius);
}
</style>
