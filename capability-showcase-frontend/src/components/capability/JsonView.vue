<script setup lang="ts">
import { computed, ref } from 'vue'
import { classifyJson } from '../../utils/json'

const props = withDefaults(
  defineProps<{
    data: unknown
    keyName?: string
    depth?: number
    isLast?: boolean
  }>(),
  { depth: 0, isLast: true },
)

const kind = computed(() => classifyJson(props.data))
const isBranch = computed(() => kind.value === 'object' || kind.value === 'array')
const open = ref(props.depth < 2)

const entries = computed<[string, unknown][]>(() => {
  if (kind.value === 'array') return (props.data as unknown[]).map((v, i) => [String(i), v])
  if (kind.value === 'object') return Object.entries(props.data as Record<string, unknown>)
  return []
})

const count = computed(() => entries.value.length)
const bracket = computed(() => (kind.value === 'array' ? ['[', ']'] : ['{', '}']))

function display(v: unknown): string {
  const k = classifyJson(v)
  if (k === 'string') return `"${v as string}"`
  if (k === 'null') return 'null'
  return String(v)
}
</script>

<template>
  <div class="jv" :style="{ '--depth': depth }">
    <!-- 分支节点（对象/数组） -->
    <template v-if="isBranch">
      <button type="button" class="jv__row jv__toggle" :aria-expanded="open" @click="open = !open">
        <span class="jv__caret" aria-hidden="true">{{ open ? '▾' : '▸' }}</span>
        <span v-if="keyName !== undefined" class="jv__key">{{ keyName }}:</span>
        <span class="jv__punc">{{ bracket[0] }}</span>
        <span v-if="!open" class="jv__summary">
          {{ count }} {{ kind === 'array' ? '项' : '键' }} {{ bracket[1] }}{{ isLast ? '' : ',' }}
        </span>
      </button>
      <div v-show="open" class="jv__children">
        <JsonView
          v-for="([k, v], i) in entries"
          :key="k"
          :data="v"
          :key-name="kind === 'array' ? undefined : k"
          :depth="depth + 1"
          :is-last="i === entries.length - 1"
        />
      </div>
      <div v-show="open" class="jv__row jv__close">
        <span class="jv__punc">{{ bracket[1] }}</span>{{ isLast ? '' : ',' }}
      </div>
    </template>

    <!-- 叶子节点 -->
    <div v-else class="jv__row">
      <span v-if="keyName !== undefined" class="jv__key">{{ keyName }}:</span>
      <span class="jv__val" :data-kind="kind">{{ display(data) }}</span>
      <span v-if="!isLast" class="jv__punc">,</span>
    </div>
  </div>
</template>

<style scoped>
.jv {
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
  line-height: 1.6;
}
.jv__row {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding-left: calc(var(--depth) * 14px);
  border-radius: var(--radius-sm);
  transition: background var(--dur-fast) var(--ease);
}
/* 行 hover 全宽高亮（分支/叶子一致） */
.jv__row:hover {
  background: var(--surface-2);
}
.jv__toggle {
  width: 100%;
  text-align: left;
  background: transparent;
  border: none;
  color: inherit;
  font: inherit;
  border-radius: var(--radius-sm);
}
.jv__caret {
  color: var(--text-subtle);
  width: 10px;
  flex-shrink: 0;
}
/* 子层左侧引导线（随缩进对齐父级 caret） */
.jv__children {
  display: block;
  position: relative;
}
.jv__children::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: calc(var(--depth) * 14px + 6px);
  width: 1px;
  background: var(--border);
  opacity: 0.7;
  pointer-events: none;
}
.jv__close {
  padding-left: calc(var(--depth) * 14px + 16px);
}
.jv__key {
  color: var(--info);
}
.jv__summary {
  color: var(--text-subtle);
}
.jv__punc {
  color: var(--text-subtle);
}
.jv__val[data-kind='string'] {
  color: var(--success);
  word-break: break-word;
}
.jv__val[data-kind='number'] {
  color: var(--warning);
}
.jv__val[data-kind='boolean'] {
  color: var(--primary);
}
.jv__val[data-kind='null'] {
  color: var(--danger);
  font-style: italic;
}
</style>
