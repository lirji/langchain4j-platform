<script setup lang="ts">
import { ref } from 'vue'
import { useUiStore } from '../../stores/ui'
import { useFocusTrap } from '../../composables/useFocusTrap'

/** 快捷键帮助弹窗（⌘/）：复用命令面板的 scrim / 焦点陷阱。 */
const ui = useUiStore()
const panelEl = ref<HTMLElement | null>(null)
const closeBtn = ref<HTMLButtonElement | null>(null)

interface Shortcut {
  keys: string[]
  desc: string
}
const SHORTCUTS: Shortcut[] = [
  { keys: ['⌘', 'K'], desc: '打开命令面板' },
  { keys: ['/'], desc: '聚焦侧栏筛选' },
  { keys: ['⌘', 'J'], desc: '打开请求历史' },
  { keys: ['⌘', '/'], desc: '快捷键帮助' },
  { keys: ['⌘', '⇧', 'L'], desc: '切换主题' },
  { keys: ['⌘', '⇧', 'D'], desc: '切换密度' },
  { keys: ['⌘', '↵'], desc: '运行当前能力' },
  { keys: ['Esc'], desc: '取消运行 / 关闭浮层' },
]

useFocusTrap({
  active: () => ui.shortcutsOpen,
  container: panelEl,
  onEscape: () => ui.closeShortcuts(),
  initialFocus: () => closeBtn.value,
})
</script>

<template>
  <Teleport to="body">
    <div v-if="ui.shortcutsOpen" class="sc" role="presentation">
      <div class="sc__scrim" aria-hidden="true" @click="ui.closeShortcuts()" />
      <div ref="panelEl" class="sc__panel" role="dialog" aria-modal="true" aria-label="快捷键">
        <header class="sc__head">
          <h2 class="sc__title">键盘快捷键</h2>
          <button
            ref="closeBtn"
            type="button"
            class="sc__close"
            aria-label="关闭"
            @click="ui.closeShortcuts()"
          >
            ✕
          </button>
        </header>
        <dl class="sc__list">
          <div v-for="(s, i) in SHORTCUTS" :key="i" class="sc__item">
            <dt class="sc__keys">
              <kbd v-for="(k, j) in s.keys" :key="j">{{ k }}</kbd>
            </dt>
            <dd class="sc__desc">{{ s.desc }}</dd>
          </div>
        </dl>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.sc {
  position: fixed;
  inset: 0;
  z-index: var(--z-cmdk);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  /* dvh 防移动地址栏跳动；手机档顶距收窄 */
  padding: 10dvh var(--space-4) var(--space-4);
}
@media (max-width: 640px) {
  .sc {
    padding-top: var(--space-4);
  }
}
.sc__scrim {
  position: absolute;
  inset: 0;
  background: rgba(2, 6, 23, 0.5);
}
.sc__panel {
  position: relative;
  width: 100%;
  max-width: 440px;
  background: var(--glass-bg-strong);
  -webkit-backdrop-filter: blur(var(--glass-blur-strong)) saturate(1.4);
  backdrop-filter: blur(var(--glass-blur-strong)) saturate(1.4);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
  animation: cmdk-in var(--dur-base) var(--ease-out);
}
@keyframes cmdk-in {
  from {
    opacity: 0;
    transform: translateY(-8px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}
.sc__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--glass-border);
}
.sc__title {
  font-size: var(--fs-md);
  font-weight: var(--fw-bold);
}
.sc__close {
  width: var(--control-h-sm);
  height: var(--control-h-sm);
  color: var(--text-muted);
  background: transparent;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.sc__close:hover {
  color: var(--text);
  background: var(--surface-2);
}
.sc__list {
  margin: 0;
  padding: var(--space-2) var(--space-4) var(--space-4);
}
.sc__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--row-py) 0;
  border-bottom: 1px solid var(--glass-border);
}
.sc__item:last-child {
  border-bottom: none;
}
.sc__keys {
  display: inline-flex;
  gap: 4px;
  margin: 0;
  flex-shrink: 0;
}
.sc__desc {
  margin: 0;
  color: var(--text-muted);
  font-size: var(--fs-sm);
  text-align: right;
}
</style>
