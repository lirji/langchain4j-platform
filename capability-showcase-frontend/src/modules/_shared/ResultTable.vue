<script setup lang="ts">
/**
 * 通用「行集 → 表格」展示组件（领域工作台复用）。
 * 输入为一组行对象；列由行键并集推导（或显式传入）。纯展示，横向可滚动、AA 语义化表格。
 * 值为对象/数组时以紧凑 JSON 呈现；null/undefined 显示为「—」。不臆造任何字段。
 */
import { computed } from 'vue'

const props = defineProps<{
  rows: Record<string, unknown>[]
  columns?: string[]
  caption?: string
}>()

const cols = computed<string[]>(() => {
  if (props.columns?.length) return props.columns
  const seen: string[] = []
  for (const row of props.rows) {
    for (const k of Object.keys(row)) if (!seen.includes(k)) seen.push(k)
  }
  return seen
})

function fmt(v: unknown): string {
  if (v === null || v === undefined) return '—'
  if (typeof v === 'object') {
    try {
      return JSON.stringify(v)
    } catch {
      return String(v)
    }
  }
  return String(v)
}
</script>

<template>
  <div class="rt" role="region" :aria-label="caption ?? '结果表格'" tabindex="0">
    <table class="rt__table">
      <caption v-if="caption" class="rt__caption">{{ caption }}</caption>
      <thead>
        <tr>
          <th v-for="c in cols" :key="c" scope="col" class="rt__th">{{ c }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, i) in rows" :key="i" class="rt__tr">
          <td v-for="c in cols" :key="c" class="rt__td" :title="fmt(row[c])">{{ fmt(row[c]) }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.rt {
  width: 100%;
  overflow-x: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  /* 横滚可见性提示：右缘渐隐示意还有内容（纯视觉，不改交互） */
  -webkit-mask-image: linear-gradient(to right, #000 calc(100% - 20px), rgba(0, 0, 0, 0.35));
  mask-image: linear-gradient(to right, #000 calc(100% - 20px), rgba(0, 0, 0, 0.35));
}
.rt:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--primary-border);
}
.rt__table {
  border-collapse: collapse;
  width: 100%;
  font-size: var(--fs-sm);
}
.rt__caption {
  padding: var(--space-2) var(--space-3);
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  text-align: left;
}
.rt__th {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: var(--space-2) var(--space-3);
  text-align: left;
  font-weight: var(--fw-semibold);
  color: var(--text-muted);
  background: var(--surface-2);
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}
.rt__td {
  padding: var(--space-2) var(--space-3);
  color: var(--text);
  border-bottom: 1px solid var(--border);
  max-width: 32ch;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
}
.rt__tr:hover .rt__td {
  background: var(--surface-2);
}
.rt__tr:last-child .rt__td {
  border-bottom: none;
}
/* 手机档：单元格截断收紧，减少横滚距离 */
@media (max-width: 640px) {
  .rt__td {
    max-width: 24ch;
  }
}
</style>
