<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useCatalogStore } from '../stores/catalog'
import type { Capability, CapabilityState } from '../types/catalog'
import StatCard from '../components/common/StatCard.vue'

const catalog = useCatalogStore()
const { catalog: cat, modules, allCapabilities, liveTools } = storeToRefs(catalog)

const STATES: { key: CapabilityState; label: string }[] = [
  { key: 'ready', label: '就绪' },
  { key: 'ready-degraded', label: '就绪·降级' },
  { key: 'flag-off', label: '未启用' },
  { key: 'scope-required', label: '需授权' },
  { key: 'display-only', label: '已锁定' },
]

function countStates(caps: Capability[]): Record<CapabilityState, number> {
  const acc: Record<CapabilityState, number> = {
    ready: 0,
    'ready-degraded': 0,
    'flag-off': 0,
    'scope-required': 0,
    'display-only': 0,
  }
  for (const c of caps) acc[c.state] = (acc[c.state] ?? 0) + 1
  return acc
}

function segmentsOf(caps: Capability[]) {
  const counts = countStates(caps)
  const total = caps.length
  return STATES.map((s) => ({
    key: s.key,
    label: s.label,
    count: counts[s.key],
    pct: total ? (counts[s.key] / total) * 100 : 0,
  })).filter((s) => s.count > 0)
}

const totalCaps = computed(() => allCapabilities.value.length)
const globalCounts = computed(() => countStates(allCapabilities.value))
const globalSegments = computed(() => segmentsOf(allCapabilities.value))

/** 核心能力（tags 含 core）→ Hero 下「快速开始」直达。 */
const coreCaps = computed(() =>
  allCapabilities.value.filter((c) => c.tags?.includes('core')).slice(0, 8),
)
</script>

<template>
  <div class="page ov">
    <!-- Hero -->
    <header class="ov__hero">
      <p class="eyebrow">Capability Console</p>
      <h1 class="ov__title">能力展示与试用控制台</h1>
      <p class="ov__lede">
        直连式（direct mode）试用后端已落地的能力：每个能力诚实呈现其
        <strong>方法 / 请求形态 / scope / feature flag / 风险 / 来源</strong>，
        不可执行的能力也会说明原因，绝不制造"看起来能用"的假象。
      </p>
      <div class="ov__meta">
        <span>目录版本 <code>{{ cat?.version ?? '—' }}</code></span>
        <span v-if="cat?.generatedAt">生成于 {{ cat.generatedAt }}</span>
        <span>{{ modules.length }} 模块 · {{ totalCaps }} 能力</span>
        <span v-if="liveTools.length" class="ov__live">
          <span class="ov__live-dot" aria-hidden="true" />
          live discovery {{ liveTools.length }} 项
        </span>
      </div>

      <nav v-if="coreCaps.length" class="ov__quick" aria-label="快速开始">
        <span class="ov__quick-label">快速开始</span>
        <RouterLink
          v-for="c in coreCaps"
          :key="c.id"
          :to="`/m/${c.module}/${c.id}`"
          class="ov__quick-link"
        >
          {{ c.title }}
        </RouterLink>
      </nav>
    </header>

    <!-- 统计卡行 -->
    <section class="ov__stats" aria-label="能力概览统计">
      <StatCard label="模块" :value="modules.length" tone="primary" sub="按领域分组" />
      <StatCard label="能力总数" :value="totalCaps" tone="primary" sub="已收录端点" />
      <StatCard label="就绪" :value="globalCounts.ready" tone="success" sub="可直接执行" />
      <StatCard
        label="需授权"
        :value="globalCounts['scope-required']"
        tone="warning"
        sub="需对应 scope"
      />
      <StatCard
        label="未启用"
        :value="globalCounts['flag-off']"
        tone="neutral"
        sub="需开 feature flag"
      />
    </section>

    <!-- 全局五态分布条 + 图例（不靠纯色，附文字与计数） -->
    <section class="ov__dist-card" aria-label="全局能力状态分布">
      <p class="eyebrow">状态分布</p>
      <div
        v-if="totalCaps"
        class="ov__dist"
        role="img"
        :aria-label="`全局能力状态分布：共 ${totalCaps} 项`"
      >
        <span
          v-for="s in globalSegments"
          :key="s.key"
          class="ov__seg"
          :data-state="s.key"
          :style="{ width: `${s.pct}%` }"
          :title="`${s.label}：${s.count}`"
        />
      </div>
      <ul class="ov__legend">
        <li
          v-for="s in STATES"
          :key="s.key"
          class="ov__legend-item"
          :data-zero="globalCounts[s.key] === 0"
        >
          <span class="ov__swatch" :data-state="s.key" aria-hidden="true" />
          <span class="ov__legend-label">{{ s.label }}</span>
          <span class="ov__legend-count">{{ globalCounts[s.key] }}</span>
        </li>
      </ul>
    </section>

    <!-- 模块卡墙 -->
    <section class="ov__grid">
      <RouterLink
        v-for="(m, i) in modules"
        :key="m.id"
        :to="`/m/${m.id}`"
        class="mod"
        :style="{ '--i': i }"
        :data-empty="(m.capabilities?.length ?? 0) === 0"
      >
        <div class="mod__top">
          <h2 class="mod__title">{{ m.title }}</h2>
          <span class="mod__prio" :data-prio="m.priority">{{ m.priority }}</span>
        </div>
        <p class="mod__desc">{{ m.description }}</p>

        <div v-if="m.capabilities?.length" class="mod__dist" aria-hidden="true">
          <span
            v-for="s in segmentsOf(m.capabilities)"
            :key="s.key"
            class="ov__seg"
            :data-state="s.key"
            :style="{ width: `${s.pct}%` }"
          />
        </div>

        <div class="mod__foot">
          <span class="mod__service">{{ m.service }}</span>
          <span class="mod__count">
            {{ (m.capabilities?.length ?? 0) === 0 ? '占位 · 待补' : `${m.capabilities.length} 能力` }}
          </span>
        </div>
      </RouterLink>
    </section>
  </div>
</template>

<style scoped>
.ov {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

/* ── Hero ── */
.ov__hero {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.ov__title {
  font-size: var(--fs-display);
  font-weight: var(--fw-bold);
  letter-spacing: var(--ls-tight);
  color: var(--text);
  line-height: var(--lh-tight);
}
.ov__lede {
  color: var(--text-muted);
  max-width: 82ch;
  line-height: var(--lh-relaxed);
}
.ov__meta {
  display: flex;
  gap: var(--space-2) var(--space-4);
  flex-wrap: wrap;
  margin-top: var(--space-2);
  font-size: var(--fs-sm);
  color: var(--text-subtle);
}
.ov__meta code {
  font-family: var(--font-mono);
  color: var(--text-muted);
}
.ov__live {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--stream);
}
.ov__live-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--stream-strong);
}
.ov__quick {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-top: var(--space-3);
}
.ov__quick-label {
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--text-subtle);
}
.ov__quick-link {
  padding: 4px 10px;
  font-size: var(--fs-sm);
  color: var(--primary);
  background: var(--primary-soft);
  border: 1px solid var(--primary-border);
  border-radius: var(--radius-pill);
  transition: background var(--dur) var(--ease), color var(--dur) var(--ease),
    transform var(--dur-fast) var(--ease-spring);
}
.ov__quick-link:hover {
  text-decoration: none;
  background: var(--primary);
  color: var(--primary-fg);
  transform: translateY(-1px);
}
/* 触屏无 hover：按压给即时反馈 */
.ov__quick-link:active {
  background: var(--primary);
  color: var(--primary-fg);
}

/* ── 统计卡行 ── */
.ov__stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--space-4);
}

/* ── 全局分布条 ── */
.ov__dist-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--card-pad);
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}
.ov__dist {
  display: flex;
  width: 100%;
  height: 8px;
  border-radius: var(--radius-pill);
  overflow: hidden;
  background: var(--surface-2);
}
.ov__seg {
  height: 100%;
  min-width: 3px;
}
/* 状态配色（分布条 + 图例共用 data-state；图例另附文字/计数，不靠纯色表意） */
.ov__seg[data-state='ready'],
.ov__swatch[data-state='ready'] {
  background: var(--success);
}
.ov__seg[data-state='ready-degraded'],
.ov__swatch[data-state='ready-degraded'] {
  background: linear-gradient(135deg, var(--success), var(--warning));
}
.ov__seg[data-state='flag-off'],
.ov__swatch[data-state='flag-off'] {
  background: var(--neutral);
}
.ov__seg[data-state='scope-required'],
.ov__swatch[data-state='scope-required'] {
  background: var(--warning);
}
.ov__seg[data-state='display-only'],
.ov__swatch[data-state='display-only'] {
  background: var(--danger);
}
.ov__legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-4);
  list-style: none;
  margin: 0;
  padding: 0;
}
.ov__legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  color: var(--text-muted);
}
.ov__legend-item[data-zero='true'] {
  opacity: 0.45;
}
.ov__swatch {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  flex-shrink: 0;
}
.ov__legend-count {
  font-family: var(--font-mono);
  font-weight: var(--fw-semibold);
  color: var(--text);
}

/* ── 模块卡墙 ── */
.ov__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(var(--card-min), 1fr));
  gap: var(--space-4);
}
.mod {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: var(--space-4);
  /* 多卡片网格：纯半透明实底，不叠 backdrop-filter（性能护栏） */
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  color: var(--text);
  box-shadow: var(--shadow-sm);
  transition: border-color var(--dur) var(--ease), box-shadow var(--dur) var(--ease),
    transform var(--dur) var(--ease-out);
  animation: card-in var(--dur-slow) var(--ease-out) backwards;
  animation-delay: calc(var(--i, 0) * 40ms);
}
.mod::after {
  content: '';
  position: absolute;
  inset: 0 0 auto 0;
  height: 1px;
  border-radius: var(--radius-lg) var(--radius-lg) 0 0;
  background: linear-gradient(90deg, transparent, var(--glass-highlight) 50%, transparent);
  pointer-events: none;
}
.mod:hover {
  text-decoration: none;
  border-color: var(--primary-border);
  box-shadow: var(--shadow-lg), var(--glow-primary);
  transform: translateY(-3px);
}
.mod[data-empty='true'] {
  opacity: 0.72;
  border-style: dashed;
}
@keyframes card-in {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
.mod__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.mod__title {
  font-size: var(--fs-md);
  font-weight: var(--fw-bold);
  letter-spacing: var(--ls-tight);
}
.mod__prio {
  font-size: 11px;
  font-weight: var(--fw-bold);
  padding: 1px 7px;
  border-radius: var(--radius-sm);
  color: var(--text-subtle);
  background: var(--surface-2);
  border: 1px solid var(--border);
}
.mod__prio[data-prio='P0'] {
  color: var(--primary);
  background: var(--primary-soft);
  border-color: var(--primary-border);
}
.mod__desc {
  font-size: var(--fs-sm);
  color: var(--text-muted);
  line-height: var(--lh-snug);
  flex: 1;
}
.mod__dist {
  display: flex;
  width: 100%;
  height: 5px;
  border-radius: var(--radius-pill);
  overflow: hidden;
  background: var(--surface-2);
}
.mod__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  font-family: var(--font-mono);
}
</style>
