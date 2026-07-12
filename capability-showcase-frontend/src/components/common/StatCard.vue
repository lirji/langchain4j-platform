<script setup lang="ts">
/**
 * 统计卡：eyebrow 标签 + 实色数值（带语义点）+ 辅助行 + 可选趋势箭头。
 * 玻璃卡，不叠 backdrop-filter（性能护栏）。供批2 Overview 使用。
 */
withDefaults(
  defineProps<{
    label: string
    value: string | number
    sub?: string
    tone?: 'primary' | 'success' | 'warning' | 'danger' | 'neutral' | 'stream'
    trend?: 'up' | 'down' | 'flat'
  }>(),
  { tone: 'primary' },
)

const TREND_ICON: Record<'up' | 'down' | 'flat', string> = {
  up: '▲',
  down: '▼',
  flat: '▬',
}
</script>

<template>
  <div class="stat" :data-tone="tone">
    <p class="stat__label eyebrow">{{ label }}</p>
    <div class="stat__value-row">
      <span class="stat__dot" aria-hidden="true" />
      <span class="stat__value">{{ value }}</span>
      <span v-if="trend" class="stat__trend" :data-trend="trend" aria-hidden="true">
        {{ TREND_ICON[trend] }}
      </span>
    </div>
    <p v-if="sub" class="stat__sub">{{ sub }}</p>
  </div>
</template>

<style scoped>
.stat {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--card-pad);
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}
.stat__value-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.stat__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  background: var(--primary);
}
.stat[data-tone='success'] .stat__dot {
  background: var(--success);
}
.stat[data-tone='warning'] .stat__dot {
  background: var(--warning);
}
.stat[data-tone='danger'] .stat__dot {
  background: var(--danger);
}
.stat[data-tone='neutral'] .stat__dot {
  background: var(--neutral);
}
.stat[data-tone='stream'] .stat__dot {
  background: var(--stream);
}
.stat__value {
  font-size: var(--fs-2xl);
  font-weight: var(--fw-bold);
  letter-spacing: var(--ls-tight);
  color: var(--text);
  line-height: var(--lh-tight);
}
.stat__trend {
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
}
.stat__trend[data-trend='up'] {
  color: var(--success);
}
.stat__trend[data-trend='down'] {
  color: var(--danger);
}
.stat__trend[data-trend='flat'] {
  color: var(--text-subtle);
}
.stat__sub {
  font-size: var(--fs-sm);
  color: var(--text-subtle);
}
</style>
