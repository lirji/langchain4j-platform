<script setup lang="ts">
/**
 * 领域工作台的「分区」容器——标题 + 说明 + 可选 notice 槽 + 内容槽。
 * 仅负责布局；专用视图用它把相关能力组织成语义分区。
 *
 * collapsible=true 时标题变为可折叠开关（内容 v-show，不销毁 DOM，保留内部组件状态）。
 */
import { ref } from 'vue'

const props = withDefaults(
  defineProps<{ title: string; subtitle?: string; collapsible?: boolean; defaultOpen?: boolean }>(),
  { collapsible: false, defaultOpen: true },
)

const open = ref(props.defaultOpen)
function toggle(): void {
  if (props.collapsible) open.value = !open.value
}
</script>

<template>
  <section class="wb-sec" :class="{ 'wb-sec--collapsed': collapsible && !open }">
    <header class="wb-sec__head">
      <button
        v-if="collapsible"
        type="button"
        class="wb-sec__toggle"
        :aria-expanded="open"
        @click="toggle"
      >
        <span class="wb-sec__chevron" :class="{ 'is-open': open }" aria-hidden="true">▸</span>
        <span class="wb-sec__heading">
          <span class="wb-sec__title">{{ title }}</span>
          <span v-if="subtitle" class="wb-sec__sub">{{ subtitle }}</span>
        </span>
      </button>
      <div v-else class="wb-sec__heading">
        <h2 class="wb-sec__title">{{ title }}</h2>
        <p v-if="subtitle" class="wb-sec__sub">{{ subtitle }}</p>
      </div>
      <div v-if="$slots.actions" class="wb-sec__actions"><slot name="actions" /></div>
    </header>
    <div v-if="$slots.notice" v-show="open" class="wb-sec__notice"><slot name="notice" /></div>
    <div v-show="open" class="wb-sec__body"><slot /></div>
  </section>
</template>

<style scoped>
.wb-sec {
  position: relative;
  padding: var(--space-5) 0;
}
/* 分区分隔：渐变发丝线（左浓右淡） */
.wb-sec::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 1px;
  background: linear-gradient(90deg, var(--border), transparent 70%);
}
.wb-sec:first-child {
  padding-top: var(--space-3);
}
.wb-sec:first-child::before {
  display: none;
}
.wb-sec__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
  flex-wrap: wrap;
}
/* 折叠开关：整块标题可点，视觉与静态标题一致 */
.wb-sec__toggle {
  display: flex;
  align-items: flex-start;
  gap: var(--space-2);
  padding: 0;
  background: none;
  border: none;
  text-align: left;
  cursor: pointer;
  color: inherit;
}
.wb-sec__chevron {
  margin-top: 2px;
  color: var(--text-subtle);
  transition: transform var(--dur) var(--ease);
}
.wb-sec__chevron.is-open {
  transform: rotate(90deg);
}
.wb-sec__title {
  font-size: var(--fs-lg);
  font-weight: 700;
}
.wb-sec__sub {
  display: block;
  margin-top: 4px;
  font-size: var(--fs-sm);
  color: var(--text-muted);
  max-width: 82ch;
  line-height: 1.55;
}
.wb-sec__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}
.wb-sec__notice {
  margin-top: var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.wb-sec__body {
  margin-top: var(--space-4);
}
@media (prefers-reduced-motion: reduce) {
  .wb-sec__chevron {
    transition: none;
  }
}
</style>
