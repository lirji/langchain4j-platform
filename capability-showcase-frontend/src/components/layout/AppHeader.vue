<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import AuthControl from './AuthControl.vue'
import ThemeToggle from './ThemeToggle.vue'
import Breadcrumb from './Breadcrumb.vue'
import DensityToggle from './DensityToggle.vue'
import { useUiStore } from '../../stores/ui'
import { useHistoryStore } from '../../stores/history'
import { usePermission } from '../../composables/usePermission'
import { CATALOG_URL } from '../../config'

const ui = useUiStore()
const history = useHistoryStore()
// 管理入口可见性：与侧栏/命令面板同一 permission 源（Bearer role-admin + 构建开关）。
const { canAdmin } = usePermission()

// 中屏以下的溢出菜单（⋯）：收纳次要项。
const moreOpen = ref(false)
const moreWrap = ref<HTMLElement | null>(null)

function toggleMore(): void {
  moreOpen.value = !moreOpen.value
}
function closeMore(): void {
  moreOpen.value = false
}
function onDocClick(e: MouseEvent): void {
  if (moreOpen.value && moreWrap.value && !moreWrap.value.contains(e.target as Node)) closeMore()
}
onMounted(() => document.addEventListener('click', onDocClick))
onUnmounted(() => document.removeEventListener('click', onDocClick))

/** ☰ 菜单按钮：窄屏开合移动抽屉，宽屏折叠/展开桌面侧栏（重复点击收起菜单栏）。 */
function toggleNav(): void {
  if (typeof window !== 'undefined' && window.innerWidth <= 1023) {
    ui.toggleSidebar()
  } else {
    ui.toggleNavCollapsed()
  }
}
</script>

<template>
  <header class="header">
    <button
      type="button"
      class="header__menu"
      :aria-label="ui.navCollapsed ? '展开导航菜单' : '收起导航菜单'"
      :aria-expanded="!ui.navCollapsed"
      title="开合菜单栏"
      @click="toggleNav()"
    >
      ☰
    </button>

    <RouterLink to="/" class="header__brand">
      <span class="header__logo" aria-hidden="true">◆</span>
      <span class="header__title">能力展示与试用控制台</span>
    </RouterLink>

    <Breadcrumb class="header__crumbs" />

    <span class="header__spacer" />

    <button
      type="button"
      class="header__search"
      aria-label="打开命令面板搜索能力"
      @click="ui.openCmdk()"
    >
      <span class="header__search-icon" aria-hidden="true">⌕</span>
      <span class="header__search-text">搜索能力…</span>
      <kbd class="header__search-kbd">⌘K</kbd>
    </button>

    <div class="header__inline">
      <RouterLink
        v-if="canAdmin"
        :to="{ name: 'admin-users' }"
        class="header__admin"
        title="平台管理中心（用户 / 角色）"
      >
        <span aria-hidden="true">⚙</span>
        <span class="header__admin-text">管理中心</span>
      </RouterLink>
      <button
        type="button"
        class="header__icon"
        aria-label="请求历史"
        title="请求历史（⌘J）"
        @click="ui.openHistory()"
      >
        <span aria-hidden="true">🕘</span>
        <span v-if="history.entries.length" class="header__badge">{{ history.entries.length }}</span>
      </button>
      <DensityToggle />
      <a class="header__docs" :href="CATALOG_URL" target="_blank" rel="noopener">catalog</a>
      <button
        type="button"
        class="header__icon"
        aria-label="快捷键帮助"
        title="快捷键帮助（⌘/）"
        @click="ui.openShortcuts()"
      >
        ?
      </button>
    </div>

    <AuthControl />
    <ThemeToggle />

    <div ref="moreWrap" class="header__morewrap">
      <button
        type="button"
        class="header__icon header__more-btn"
        aria-label="更多选项"
        aria-haspopup="menu"
        :aria-expanded="moreOpen"
        @click.stop="toggleMore"
      >
        ⋯
      </button>
      <div v-if="moreOpen" class="header__pop" role="menu">
        <RouterLink
          v-if="canAdmin"
          role="menuitem"
          class="header__pop-item"
          :to="{ name: 'admin-users' }"
          @click="closeMore()"
        >
          ⚙ 管理中心
        </RouterLink>
        <button
          type="button"
          role="menuitem"
          class="header__pop-item"
          @click="ui.openHistory(); closeMore()"
        >
          🕘 请求历史
          <span v-if="history.entries.length" class="header__pop-count">{{ history.entries.length }}</span>
        </button>
        <div class="header__pop-item header__pop-item--static">
          <span>密度</span>
          <DensityToggle />
        </div>
        <a
          role="menuitem"
          class="header__pop-item"
          href="/showcase/api/catalog"
          target="_blank"
          rel="noopener"
          @click="closeMore()"
        >
          catalog
        </a>
        <button
          type="button"
          role="menuitem"
          class="header__pop-item"
          @click="ui.openShortcuts(); closeMore()"
        >
          ? 快捷键帮助
        </button>
      </div>
    </div>
  </header>
</template>

<style scoped>
.header {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  height: var(--header-h);
  padding: 0 var(--space-4);
  background: var(--glass-bg-strong);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(1.4);
  backdrop-filter: blur(var(--glass-blur)) saturate(1.4);
  border-bottom: 1px solid var(--glass-border);
  box-shadow: var(--shadow-sm);
  position: sticky;
  top: 0;
  z-index: var(--z-header);
  flex-shrink: 0;
}
.header__menu {
  display: none;
  padding: 6px 10px;
  font-size: 18px;
  color: var(--text-muted);
  background: transparent;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.header__brand {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--text);
  font-weight: var(--fw-bold);
  flex-shrink: 0;
}
.header__brand:hover {
  text-decoration: none;
}
.header__logo {
  color: var(--primary);
  font-size: 18px;
  text-shadow: 0 0 12px var(--aurora-1), 0 0 4px var(--aurora-2);
}
.header__title {
  font-size: var(--fs-md);
  color: var(--primary);
  white-space: nowrap;
}
/* 品牌标题渐变文字：带实色 fallback + @supports 守卫 */
@supports ((background-clip: text) or (-webkit-background-clip: text)) {
  .header__title {
    background: var(--gradient-text);
    -webkit-background-clip: text;
    background-clip: text;
    -webkit-text-fill-color: transparent;
    color: transparent;
  }
}
.header__crumbs {
  min-width: 0;
  overflow: hidden;
}
.header__spacer {
  flex: 1;
  min-width: var(--space-2);
}

/* 命令面板触发（搜索框样式，非渐变） */
.header__search {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  height: var(--control-h);
  min-width: 200px;
  padding: 0 var(--space-2) 0 var(--space-3);
  color: var(--text-subtle);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  transition: border-color var(--dur) var(--ease), background var(--dur) var(--ease);
}
.header__search:hover {
  border-color: var(--border-strong);
  background: var(--surface-3);
}
.header__search-icon {
  font-size: 15px;
}
.header__search-text {
  flex: 1;
  text-align: left;
  font-size: var(--fs-sm);
}
.header__search-kbd {
  flex-shrink: 0;
}

.header__inline {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
}
/* 管理中心入口：主色描边药丸，仅 role-admin 出现（canAdmin 同源门禁） */
.header__admin {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: var(--control-h);
  padding: 0 12px;
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  color: var(--primary);
  background: var(--primary-soft);
  border: 1px solid var(--primary-border);
  border-radius: var(--radius);
}
.header__admin:hover {
  text-decoration: none;
  background: var(--surface-3);
}
.header__admin.router-link-active {
  box-shadow: inset 0 0 0 1px var(--primary);
}
.header__icon {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: var(--control-h);
  height: var(--control-h);
  padding: 0 8px;
  color: var(--text-muted);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  font-size: var(--fs-sm);
}
.header__icon:hover {
  color: var(--text);
  background: var(--surface-3);
}
.header__badge {
  position: absolute;
  top: -6px;
  right: -6px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  font-size: 10px;
  font-weight: var(--fw-bold);
  line-height: 16px;
  text-align: center;
  color: var(--primary-fg);
  background: var(--primary);
  border-radius: var(--radius-pill);
}
.header__docs {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text-subtle);
  padding: 4px 8px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}

/* 溢出菜单 */
.header__morewrap {
  position: relative;
  display: none;
}
.header__pop {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  z-index: var(--z-popover);
  min-width: 200px;
  padding: var(--space-1);
  background: var(--surface);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
}
.header__pop-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  width: 100%;
  padding: var(--row-py) var(--space-3);
  font-size: var(--fs-sm);
  color: var(--text-muted);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  text-align: left;
}
button.header__pop-item:hover,
a.header__pop-item:hover {
  color: var(--text);
  background: var(--surface-2);
  text-decoration: none;
}
.header__pop-item--static {
  cursor: default;
}
.header__pop-count {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text-subtle);
}

/* 中屏以下：次要项收进 ⋯ */
@media (max-width: 1023px) {
  .header__menu {
    display: inline-block;
  }
  .header__title {
    display: none;
  }
  .header__inline {
    display: none;
  }
  .header__morewrap {
    display: inline-flex;
  }
}
@media (max-width: 640px) {
  .header__search-text,
  .header__search-kbd {
    display: none;
  }
  .header__search {
    min-width: 0;
  }
}
</style>
