<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useCatalogStore } from '../../stores/catalog'

/**
 * 面包屑：首页 / [模块] / [能力]。数据来自 route.params + catalog 反查。
 * 当前段 aria-current 不可点；md 以下仅显示末级；每段超长省略。
 */
const route = useRoute()
const catalog = useCatalogStore()

interface Crumb {
  label: string
  to?: string
  current: boolean
}

const crumbs = computed<Crumb[]>(() => {
  const moduleId = String(route.params.moduleId ?? '')
  const capId = String(route.params.capId ?? '')
  const atHome = !moduleId
  const list: Crumb[] = [{ label: '首页', to: atHome ? undefined : '/', current: atHome }]

  if (moduleId) {
    const m = catalog.moduleById(moduleId)
    const isCurrent = !capId
    list.push({
      label: m?.title ?? moduleId,
      to: isCurrent ? undefined : `/m/${moduleId}`,
      current: isCurrent,
    })
    if (capId) {
      const c = catalog.capabilityById(capId)
      list.push({ label: c?.title ?? capId, current: true })
    }
  }
  return list
})
</script>

<template>
  <nav class="crumbs" aria-label="面包屑">
    <ol class="crumbs__list">
      <li v-for="(c, i) in crumbs" :key="i" class="crumbs__li" :data-last="i === crumbs.length - 1">
        <RouterLink v-if="c.to" :to="c.to" class="crumbs__item">{{ c.label }}</RouterLink>
        <span v-else class="crumbs__item crumbs__item--current" aria-current="page">{{ c.label }}</span>
        <span v-if="i < crumbs.length - 1" class="crumbs__sep" aria-hidden="true">⟩</span>
      </li>
    </ol>
  </nav>
</template>

<style scoped>
.crumbs {
  min-width: 0;
}
.crumbs__list {
  display: flex;
  align-items: center;
  gap: 2px;
  list-style: none;
  margin: 0;
  padding: 0;
  min-width: 0;
}
.crumbs__li {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  min-width: 0;
}
.crumbs__item {
  display: inline-block;
  max-width: 22ch;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  font-size: var(--fs-sm);
  color: var(--text-subtle);
  transition: color var(--dur) var(--ease), background var(--dur) var(--ease);
}
a.crumbs__item:hover {
  color: var(--text);
  background: var(--surface-2);
  text-decoration: none;
}
.crumbs__item--current {
  color: var(--text);
  font-weight: var(--fw-semibold);
}
.crumbs__sep {
  color: var(--text-subtle);
  font-size: var(--fs-xs);
  opacity: 0.7;
  flex-shrink: 0;
}

/* 平板及以下（canonical 1023，与抽屉断点对齐）：仅保留末级 */
@media (max-width: 1023px) {
  .crumbs__li:not([data-last='true']) {
    display: none;
  }
}
</style>
