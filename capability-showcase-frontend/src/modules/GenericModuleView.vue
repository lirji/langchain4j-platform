<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useCatalogStore } from '../stores/catalog'
import type { Capability, CapabilityState } from '../types/catalog'
import ModuleHeader from '../components/layout/ModuleHeader.vue'
import CapabilityRunner from '../components/capability/CapabilityRunner.vue'
import CapabilityCard from '../components/capability/CapabilityCard.vue'
import EmptyState from '../components/common/EmptyState.vue'

const props = defineProps<{ moduleId: string; capId?: string }>()
const catalog = useCatalogStore()

const module = computed(() => catalog.moduleById(props.moduleId))
const cap = computed(() =>
  props.capId ? (module.value?.capabilities ?? []).find((c) => c.id === props.capId) : undefined,
)

const allCaps = computed<Capability[]>(() => module.value?.capabilities ?? [])

// 能力较多时才显示筛选（纯前端过滤，不改数据）。
const SHOW_FILTER_THRESHOLD = 8
const showFilters = computed(() => allCaps.value.length >= SHOW_FILTER_THRESHOLD)

type StateFilter = 'all' | 'ready' | 'scope-required' | 'flag-off'
type MethodFilter = 'all' | 'GET' | 'POST'
const stateFilter = ref<StateFilter>('all')
const methodFilter = ref<MethodFilter>('all')

// 模块切换时重置筛选。
watch(
  () => props.moduleId,
  () => {
    stateFilter.value = 'all'
    methodFilter.value = 'all'
  },
)

function stateCount(s: CapabilityState): number {
  return allCaps.value.filter((c) => c.state === s).length
}

const STATE_CHIPS: { key: StateFilter; label: string }[] = [
  { key: 'all', label: '全部' },
  { key: 'ready', label: '就绪' },
  { key: 'scope-required', label: '需授权' },
  { key: 'flag-off', label: '未启用' },
]
const METHOD_CHIPS: MethodFilter[] = ['all', 'GET', 'POST']

const filteredCaps = computed<Capability[]>(() => {
  let list = allCaps.value
  if (stateFilter.value !== 'all') list = list.filter((c) => c.state === stateFilter.value)
  if (methodFilter.value !== 'all') {
    list = list.filter((c) => c.method.toUpperCase() === methodFilter.value)
  }
  return list
})

function resetFilters(): void {
  stateFilter.value = 'all'
  methodFilter.value = 'all'
}
</script>

<template>
  <EmptyState
    v-if="!module"
    variant="error"
    title="模块不存在"
    :description="`未找到模块「${moduleId}」。`"
  />

  <!-- 选中具体能力 → 通用运行器 -->
  <CapabilityRunner v-else-if="capId && cap" :key="cap.id" :cap="cap" />

  <EmptyState
    v-else-if="capId && !cap"
    variant="error"
    title="能力不存在"
    :description="`模块「${module.title}」下未找到能力「${capId}」。`"
  />

  <!-- 模块着陆页：富模块头 + 能力卡片列表 -->
  <div v-else class="page mod-land">
    <ModuleHeader :module-id="module.id" />

    <div v-if="showFilters" class="mod-land__filters" role="group" aria-label="筛选能力">
      <div class="mod-land__chips" role="group" aria-label="按状态筛选">
        <button
          v-for="s in STATE_CHIPS"
          :key="s.key"
          type="button"
          class="mod-land__chip"
          :class="{ 'is-active': stateFilter === s.key }"
          :aria-pressed="stateFilter === s.key"
          @click="stateFilter = s.key"
        >
          {{ s.label }}
          <span v-if="s.key !== 'all'" class="mod-land__chip-n">{{ stateCount(s.key) }}</span>
        </button>
      </div>
      <div class="mod-land__chips" role="group" aria-label="按方法筛选">
        <button
          v-for="m in METHOD_CHIPS"
          :key="m"
          type="button"
          class="mod-land__chip"
          :class="{ 'is-active': methodFilter === m }"
          :aria-pressed="methodFilter === m"
          @click="methodFilter = m"
        >
          {{ m === 'all' ? '全部方法' : m }}
        </button>
      </div>
    </div>

    <div v-if="filteredCaps.length" class="mod-land__grid">
      <CapabilityCard
        v-for="(c, i) in filteredCaps"
        :key="c.id"
        :cap="c"
        :module-id="module.id"
        :style="{ '--i': i }"
      />
    </div>

    <EmptyState
      v-else-if="!allCaps.length"
      variant="empty"
      icon="⏳"
      title="能力待补（占位模块）"
      :description="`该模块（${module.priority}）已规划，交互表单将在后续阶段补齐。`"
    />
    <EmptyState
      v-else
      variant="empty"
      icon="⌕"
      title="无匹配能力"
      description="当前筛选下没有能力，试试放宽条件。"
      action-label="重置筛选"
      @action="resetFilters"
    />
  </div>
</template>

<style scoped>
.mod-land {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}
.mod-land__filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3) var(--space-4);
}
.mod-land__chips {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}
.mod-land__chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: var(--control-h-sm);
  padding: 0 10px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  color: var(--text-subtle);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  transition: color var(--dur) var(--ease), background var(--dur) var(--ease);
}
.mod-land__chip:hover {
  color: var(--text);
}
.mod-land__chip.is-active {
  color: var(--primary);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}
.mod-land__chip-n {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-subtle);
}
.mod-land__chip.is-active .mod-land__chip-n {
  color: var(--primary);
}
.mod-land__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(var(--card-min), 1fr));
  gap: var(--space-4);
}
</style>
