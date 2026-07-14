<script setup lang="ts">
import type { NavGroupVM } from '../../../navigation/navigationModel'
import NavModuleRow from './NavModuleRow.vue'

/**
 * 语义分组区：组头（强调色条 + 标签 + 模块数 + 折叠 chevron）+ 模块行列表。
 * 分组折叠态与模块展开态由 SideNav 统一持有并下传（openModuleIds），
 * 本组件只发事件（toggle-group / toggle-module / navigate），不直接读 store/route。
 * data-accent 注入该组强调色，后代继承 --g/--g-soft/--g-text/--g-line。
 */
defineProps<{ group: NavGroupVM; open: boolean; openModuleIds: string[] }>()
const emit = defineEmits<{
  (e: 'toggle-group'): void
  (e: 'toggle-module', id: string): void
  (e: 'navigate'): void
}>()
</script>

<template>
  <section class="grp" :data-accent="group.accent">
    <button
      type="button"
      class="grp__head"
      :aria-expanded="open"
      @click="emit('toggle-group')"
    >
      <span class="grp__bar" aria-hidden="true" />
      <span class="grp__label">{{ group.label }}</span>
      <span class="grp__count">{{ group.modules.length }} 模块</span>
      <svg
        class="grp__chev"
        :class="{ 'is-open': open }"
        width="12"
        height="12"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="M6 9l6 6 6-6" />
      </svg>
    </button>

    <ul v-if="open" class="grp__mods">
      <NavModuleRow
        v-for="m in group.modules"
        :key="m.id"
        :mod="m"
        :open="openModuleIds.includes(m.id)"
        @toggle="emit('toggle-module', m.id)"
        @navigate="emit('navigate')"
      />
    </ul>
  </section>
</template>

<style scoped>
.grp {
  margin-top: var(--space-3);
}
.grp__head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 8px 4px;
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  text-align: left;
  cursor: pointer;
}
.grp__head:hover {
  background: var(--surface-2);
}
.grp__bar {
  width: 3px;
  height: 13px;
  flex: 0 0 auto;
  border-radius: var(--radius-pill);
  background: var(--g);
}
.grp__label {
  flex: 1;
  min-width: 0;
  font-size: var(--nav-fs);
  font-weight: var(--fw-bold);
  letter-spacing: 0.02em;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.grp__count {
  flex: 0 0 auto;
  font-size: var(--nav-fs);
  color: var(--text-subtle);
  font-variant-numeric: tabular-nums;
}
.grp__chev {
  flex: 0 0 auto;
  color: var(--text-subtle);
  transition: transform var(--dur) var(--ease);
}
.grp__chev.is-open {
  transform: rotate(0deg);
}
.grp__chev:not(.is-open) {
  transform: rotate(-90deg);
}
.grp__mods {
  list-style: none;
  margin: 4px 0 0;
  padding: 0;
}
</style>
