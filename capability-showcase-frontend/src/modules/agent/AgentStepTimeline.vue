<script setup lang="ts">
/**
 * ReAct 步骤时间线 —— 把响应中的 steps 数组渲染为纵向编号时间线。
 * 探测常见 ReAct 键（思考/动作/输入/观察）；无法识别的步骤以 JsonView 兜底。不臆造字段。
 */
import { computed } from 'vue'
import JsonView from '../../components/capability/JsonView.vue'

const props = defineProps<{ steps: unknown[] }>()

interface KnownField {
  keys: string[]
  label: string
}
const KNOWN: KnownField[] = [
  { keys: ['thought', 'reasoning'], label: '思考' },
  { keys: ['action', 'tool', 'toolName'], label: '动作' },
  { keys: ['actionInput', 'input', 'arguments', 'args'], label: '输入' },
  { keys: ['observation', 'output', 'result'], label: '观察' },
]

function asString(v: unknown): string {
  if (v == null) return ''
  if (typeof v === 'string') return v
  try {
    return JSON.stringify(v)
  } catch {
    return String(v)
  }
}

interface Rendered {
  isString: boolean
  text?: string
  header?: string
  fields: { label: string; value: string }[]
  raw: unknown
  hasKnown: boolean
}

const rendered = computed<Rendered[]>(() =>
  props.steps.map((step) => {
    if (typeof step === 'string') {
      return { isString: true, text: step, fields: [], raw: step, hasKnown: false }
    }
    if (step && typeof step === 'object') {
      const o = step as Record<string, unknown>
      const header =
        (typeof o.name === 'string' && o.name) ||
        (typeof o.type === 'string' && o.type) ||
        (typeof o.step === 'string' && o.step) ||
        (typeof o.title === 'string' && o.title) ||
        undefined
      const fields: { label: string; value: string }[] = []
      for (const f of KNOWN) {
        for (const k of f.keys) {
          if (o[k] != null && o[k] !== '') {
            fields.push({ label: f.label, value: asString(o[k]) })
            break
          }
        }
      }
      return {
        isString: false,
        header: header || undefined,
        fields,
        raw: step,
        hasKnown: fields.length > 0,
      }
    }
    return { isString: true, text: String(step), fields: [], raw: step, hasKnown: false }
  }),
)
</script>

<template>
  <ol class="st">
    <li v-for="(r, i) in rendered" :key="i" class="st__item">
      <span class="st__node" aria-hidden="true">{{ i + 1 }}</span>
      <div class="st__body">
        <span v-if="r.header" class="st__header">{{ r.header }}</span>

        <p v-if="r.isString" class="st__text">{{ r.text }}</p>

        <dl v-else-if="r.hasKnown" class="st__fields">
          <div v-for="(f, fi) in r.fields" :key="fi" class="st__field">
            <dt class="st__field-label">{{ f.label }}</dt>
            <dd class="st__field-value">{{ f.value }}</dd>
          </div>
        </dl>

        <div v-else class="st__json">
          <JsonView :data="r.raw" />
        </div>
      </div>
    </li>
  </ol>
</template>

<style scoped>
.st {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}
.st__item {
  position: relative;
  display: flex;
  gap: var(--space-3);
  padding-left: 2px;
}
/* 连接竖线（编号之间） */
.st__item:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 13px;
  top: 26px;
  bottom: calc(-1 * var(--space-3));
  width: 2px;
  background: var(--border);
}
.st__node {
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  color: var(--primary);
  background: var(--primary-soft);
  border: 1px solid var(--primary-border);
  border-radius: var(--radius-pill);
  z-index: 1;
}
.st__body {
  flex: 1;
  min-width: 0;
  padding-bottom: var(--space-1);
}
.st__header {
  display: inline-block;
  margin-bottom: 6px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--text-muted);
}
.st__text {
  font-size: var(--fs-sm);
  color: var(--text);
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
.st__fields {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.st__field {
  display: flex;
  gap: var(--space-2);
  align-items: baseline;
}
.st__field-label {
  flex-shrink: 0;
  width: 3em;
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}
.st__field-value {
  min-width: 0;
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--text);
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
.st__json {
  padding: var(--space-2) var(--space-3);
  background: var(--code-bg);
  border: 1px solid var(--code-border);
  border-radius: var(--radius);
  overflow: auto;
}
</style>
