<script setup lang="ts">
import { computed } from 'vue'
import type { NavCapabilityVM } from '../../../navigation/navigationModel'
import { STATE_META } from '../../../config/stateMeta'
import MethodBadge from '../../capability/badges/MethodBadge.vue'

/**
 * 能力行（次级）：方法徽章(compact) + 能力名 + 五态状态点（颜色+形状双编码）。
 * flat=true 用于收藏虚拟分组（无模块连接轨，选中态弱化，避免与模块树同强度）。
 * 选中态使用祖先注入的分组强调色（--g / --g-soft / --g-text）。
 */
const props = defineProps<{ cap: NavCapabilityVM; flat?: boolean }>()
const emit = defineEmits<{ (e: 'navigate'): void }>()
const meta = computed(() => STATE_META[props.cap.state])
</script>

<template>
  <RouterLink
    :to="`/m/${cap.moduleId}/${cap.id}`"
    class="cap"
    :class="{ 'cap--active': cap.active, 'cap--flat': flat }"
    :aria-current="cap.active ? 'page' : undefined"
    @click="emit('navigate')"
  >
    <MethodBadge :method="cap.method" compact />
    <span class="cap__title">{{ cap.title }}</span>
    <span class="cap__dot" :data-state="cap.state" :title="meta.hint" aria-hidden="true" />
    <span class="cap__sr">{{ meta.label }}</span>
  </RouterLink>
</template>

<style scoped>
.cap {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
  border-radius: var(--radius-sm);
  color: var(--text-muted);
  text-decoration: none;
  transition: background var(--dur) var(--ease), color var(--dur) var(--ease);
}
.cap:hover {
  background: var(--surface-2);
  color: var(--text);
  text-decoration: none;
}
.cap__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--nav-fs);
}
/* 选中：分组强调色软底 pill + 左强调条 + 同色文字（AA 达标 --g-text）。 */
.cap--active {
  background: var(--g-soft);
  color: var(--g-text);
}
.cap--active .cap__title {
  color: var(--g-text);
  font-weight: var(--fw-semibold);
}
.cap--active::before {
  content: '';
  position: absolute;
  left: -13px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 17px;
  border-radius: var(--radius-pill);
  background: var(--g);
}
/* 收藏（flat）：无连接轨，选中态弱化——不画左强调条，仅弱软底 + 同色文字。 */
.cap--flat.cap--active::before {
  content: none;
}
.cap--flat.cap--active {
  background: color-mix(in srgb, var(--g-soft) 60%, transparent);
}

/* 五态状态点：颜色 + 形状双编码（不单靠颜色）。 */
.cap__dot {
  flex: 0 0 auto;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--neutral);
}
.cap__dot[data-state='ready'] {
  background: var(--success);
}
.cap__dot[data-state='ready-degraded'] {
  background: var(--success);
  box-shadow: 0 0 0 1.5px var(--surface), 0 0 0 3px var(--warning);
}
.cap__dot[data-state='flag-off'] {
  background: transparent;
  box-shadow: inset 0 0 0 1.6px var(--neutral);
}
.cap__dot[data-state='scope-required'] {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  transform: rotate(45deg);
  background: var(--warning);
}
.cap__dot[data-state='display-only'] {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  background: var(--danger);
}

/* 屏读补充：状态文字（视觉隐藏）。 */
.cap__sr {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}
</style>
