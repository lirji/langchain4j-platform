<script setup lang="ts">
import { useRouter } from 'vue-router'
import type { NavModuleVM } from '../../../navigation/navigationModel'
import NavModuleIcon from './NavModuleIcon.vue'
import NavCapabilityRow from './NavCapabilityRow.vue'

/**
 * 模块行（主导航）：色块图标 chip + 中文名/英文副名 + 计数 + 折叠 chevron。
 * 整行点击「双动作」：既 toggle 能力列表的展开/收起，又跳转到该模块工作台落地页（/m/:id，
 * 如知识库的文档库/共享库/检索台）；工作台落地页也可继续从总览卡片/面包屑进入。
 * 强调色 --g/--g-soft/--g-text 由祖先分组注入（此处也回落 data-accent 便于独立测试）。
 * navigate 事件仍向上透传：来自内部能力行 NavCapabilityRow（移动端点叶子后收起抽屉）。
 */
const props = defineProps<{ mod: NavModuleVM; open: boolean }>()
const emit = defineEmits<{ (e: 'toggle'): void; (e: 'navigate'): void }>()
const router = useRouter()

/** 点模块行：展开/收起子菜单 + 跳到模块工作台落地页 /m/:id（两件事都做，保留展开能力列表）。 */
function onModuleClick(): void {
  emit('toggle')
  void router.push(`/m/${props.mod.id}`)
}
</script>

<template>
  <li class="mod" :data-accent="mod.accent" :class="{ 'mod--current': mod.current, 'mod--open': open }">
    <div class="mod__head">
      <button
        type="button"
        class="mod__link"
        :aria-expanded="mod.hasCaps ? open : undefined"
        :aria-label="mod.hasCaps ? `进入 ${mod.name} 工作台并展开/收起` : `进入 ${mod.name}`"
        @click="onModuleClick"
      >
        <span class="mod__chip"><NavModuleIcon :name="mod.id" /></span>
        <span class="mod__text">
          <span class="mod__name">{{ mod.name }}</span>
          <span v-if="mod.en" class="mod__en">{{ mod.en }}</span>
        </span>
        <span class="mod__count">{{ mod.count }}</span>
        <span v-if="mod.hasCaps" class="mod__caret" aria-hidden="true">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6" /></svg>
        </span>
      </button>
    </div>

    <ul v-if="open && mod.capabilities.length" class="mod__caps">
      <li v-for="c in mod.capabilities" :key="c.id">
        <NavCapabilityRow :cap="c" @navigate="emit('navigate')" />
      </li>
    </ul>
    <p v-else-if="open && mod.matchedByTitleOnly" class="mod__hint">模块名匹配 · 无匹配能力</p>
    <p v-else-if="open && !mod.hasCaps" class="mod__hint">能力待补（占位）</p>
  </li>
</template>

<style scoped>
.mod {
  margin-bottom: 2px;
  list-style: none;
}
.mod__head {
  display: flex;
  align-items: center;
  gap: 2px;
  border-radius: var(--radius);
}
.mod__link {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
  width: 100%;
  padding: 6px 8px;
  color: var(--text);
  border-radius: var(--radius);
  transition: background var(--dur) var(--ease);
  /* 由 RouterLink 改为整行 toggle 按钮：重置 button 默认外观。 */
  background: transparent;
  border: none;
  font-family: inherit;
  font-size: var(--nav-fs);
  text-align: left;
  cursor: pointer;
}
.mod__link:hover {
  background: var(--surface-2);
  text-decoration: none;
}
.mod__chip {
  width: var(--nav-chip);
  height: var(--nav-chip);
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: var(--g-soft);
  color: var(--g);
}
.mod__text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
  line-height: 1.15;
}
.mod__name {
  font-size: var(--nav-fs);
  font-weight: var(--fw-semibold);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mod__en {
  font-size: var(--nav-fs);
  color: var(--text-subtle);
  margin-top: 1px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mod__count {
  flex: 0 0 auto;
  font-family: var(--font-mono);
  font-size: var(--nav-fs);
  font-weight: var(--fw-medium);
  color: var(--text-subtle);
  font-variant-numeric: tabular-nums;
  padding-left: 6px;
}
/* chevron 现为整行按钮内的装饰性指示（不单独可点）；展开态旋转 90°。 */
.mod__caret {
  width: 18px;
  height: 18px;
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--text-subtle);
  transition: transform var(--dur) var(--ease), color var(--dur) var(--ease);
}
.mod--open .mod__caret {
  transform: rotate(90deg);
  color: var(--g);
}
/* 当前模块：分组强调色软底 + 主色名/图标 chip 反白。 */
.mod--current .mod__link {
  background: var(--g-soft);
}
.mod--current .mod__name {
  color: var(--g-text);
}
.mod--current .mod__chip {
  background: var(--surface);
  box-shadow: inset 0 0 0 1px var(--g-line);
}

/* 能力列表：分组强调色左连接轨 + 缩进。 */
.mod__caps {
  list-style: none;
  margin: 2px 0 6px var(--nav-indent);
  padding-left: 12px;
  border-left: 1.5px solid var(--g-line);
}
.mod__hint {
  margin: 2px 0 6px calc(var(--nav-indent) + 12px);
  font-size: var(--nav-fs);
  color: var(--text-subtle);
}

/* 移动端：加大触控目标。 */
@media (max-width: 1023px) {
  .mod__link {
    min-height: var(--nav-touch-min);
  }
}
</style>
