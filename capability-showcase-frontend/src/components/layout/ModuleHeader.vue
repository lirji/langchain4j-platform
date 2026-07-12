<script setup lang="ts">
import { computed } from 'vue'
import { useCatalogStore } from '../../stores/catalog'
import type { CapabilityState } from '../../types/catalog'

/**
 * 富信息模块头：标题（实色，不用渐变）+ 描述 + 元信息行 + 五态分布条 + 图例。
 * 右上提供 module 级操作槽（slot）。单层玻璃，不叠 backdrop-filter（性能护栏）。
 */
const props = defineProps<{ moduleId: string }>()
const catalog = useCatalogStore()

const mod = computed(() => catalog.moduleById(props.moduleId))
const caps = computed(() => mod.value?.capabilities ?? [])
const total = computed(() => caps.value.length)

const STATES: { key: CapabilityState; label: string }[] = [
  { key: 'ready', label: '就绪' },
  { key: 'ready-degraded', label: '就绪·降级' },
  { key: 'flag-off', label: '未启用' },
  { key: 'scope-required', label: '需授权' },
  { key: 'display-only', label: '已锁定' },
]

const counts = computed<Record<CapabilityState, number>>(() => {
  const acc: Record<CapabilityState, number> = {
    ready: 0,
    'ready-degraded': 0,
    'flag-off': 0,
    'scope-required': 0,
    'display-only': 0,
  }
  for (const c of caps.value) acc[c.state] = (acc[c.state] ?? 0) + 1
  return acc
})

/** 分布条分段（跳过计数为 0 的态）。 */
const segments = computed(() =>
  STATES.map((s) => ({
    key: s.key,
    label: s.label,
    count: counts.value[s.key],
    pct: total.value ? (counts.value[s.key] / total.value) * 100 : 0,
  })).filter((s) => s.count > 0),
)
</script>

<template>
  <header v-if="mod" class="mh">
    <div class="mh__top">
      <div class="mh__intro">
        <h1 class="mh__title">{{ mod.title }}</h1>
        <p class="mh__desc">{{ mod.description }}</p>
        <div class="mh__meta">
          <span class="mh__meta-item"><span aria-hidden="true">⬡</span> {{ mod.service }}</span>
          <span class="mh__meta-sep" aria-hidden="true">·</span>
          <span class="mh__meta-item">{{ total }} 能力</span>
          <span class="mh__meta-sep" aria-hidden="true">·</span>
          <span class="mh__meta-item mh__prio" :data-prio="mod.priority">{{ mod.priority }}</span>
        </div>
      </div>
      <div class="mh__actions">
        <slot name="actions" />
      </div>
    </div>

    <div v-if="total" class="mh__dist" role="img" :aria-label="`能力状态分布：共 ${total} 项`">
      <span
        v-for="s in segments"
        :key="s.key"
        class="mh__seg"
        :data-state="s.key"
        :style="{ width: `${s.pct}%` }"
        :title="`${s.label}：${s.count}`"
      />
    </div>

    <ul v-if="total" class="mh__legend">
      <li v-for="s in STATES" :key="s.key" class="mh__legend-item" :data-zero="counts[s.key] === 0">
        <span class="mh__swatch" :data-state="s.key" aria-hidden="true" />
        <span class="mh__legend-label">{{ s.label }}</span>
        <span class="mh__legend-count">{{ counts[s.key] }}</span>
      </li>
    </ul>
  </header>
</template>

<style scoped>
.mh {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: var(--card-pad);
  /* 单层玻璃，不叠 blur */
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}
.mh__top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
}
.mh__intro {
  min-width: 0;
}
.mh__title {
  font-size: var(--fs-xl);
  font-weight: var(--fw-bold);
  letter-spacing: var(--ls-tight);
  color: var(--text);
}
.mh__desc {
  margin-top: var(--space-2);
  max-width: 82ch;
  color: var(--text-muted);
  line-height: var(--lh-base);
}
.mh__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-top: var(--space-3);
  font-size: var(--fs-sm);
  color: var(--text-subtle);
  font-family: var(--font-mono);
}
.mh__meta-sep {
  opacity: 0.5;
}
.mh__prio {
  font-weight: var(--fw-bold);
  color: var(--text-subtle);
}
.mh__prio[data-prio='P0'] {
  color: var(--primary);
}
.mh__actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

/* 五态分布条 */
.mh__dist {
  display: flex;
  width: 100%;
  height: 6px;
  border-radius: var(--radius-pill);
  overflow: hidden;
  background: var(--surface-2);
}
.mh__seg {
  height: 100%;
  min-width: 3px;
}

/* 状态配色（分布条 + 图例共用 data-state；图例另附文字/计数，不靠纯色） */
.mh__seg[data-state='ready'],
.mh__swatch[data-state='ready'] {
  background: var(--success);
}
.mh__seg[data-state='ready-degraded'],
.mh__swatch[data-state='ready-degraded'] {
  background: linear-gradient(135deg, var(--success), var(--warning));
}
.mh__seg[data-state='flag-off'],
.mh__swatch[data-state='flag-off'] {
  background: var(--neutral);
}
.mh__seg[data-state='scope-required'],
.mh__swatch[data-state='scope-required'] {
  background: var(--warning);
}
.mh__seg[data-state='display-only'],
.mh__swatch[data-state='display-only'] {
  background: var(--danger);
}

.mh__legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-4);
  list-style: none;
  margin: 0;
  padding: 0;
}
.mh__legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--text-muted);
}
.mh__legend-item[data-zero='true'] {
  opacity: 0.45;
}
.mh__swatch {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  flex-shrink: 0;
}
.mh__legend-count {
  font-family: var(--font-mono);
  font-weight: var(--fw-semibold);
  color: var(--text);
}

@media (max-width: 640px) {
  .mh__top {
    flex-direction: column;
  }
}
</style>
