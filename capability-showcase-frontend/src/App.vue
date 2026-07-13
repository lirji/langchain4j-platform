<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute } from 'vue-router'
import AppHeader from './components/layout/AppHeader.vue'
import SideNav from './components/layout/SideNav.vue'
import EmptyState from './components/common/EmptyState.vue'
import CommandPalette from './components/common/CommandPalette.vue'
import HistoryDrawer from './components/common/HistoryDrawer.vue'
import ShortcutsDialog from './components/common/ShortcutsDialog.vue'
import { useCatalogStore } from './stores/catalog'
import { useUiStore } from './stores/ui'
import { useGlobalShortcuts } from './composables/useGlobalShortcuts'

const catalog = useCatalogStore()
const ui = useUiStore()
const route = useRoute()
const { status, error } = storeToRefs(catalog)

// 公开页（登录）全屏渲染，不套 header/侧栏/目录门禁与全局浮层。
const isAuthRoute = computed(() => route.meta.public === true)
// 是否需要能力目录：admin/forbidden 等 bypassCatalog 路由不依赖 catalog，catalog 失败也应能打开。
const needsCatalog = computed(() => !isAuthRoute.value && route.meta.bypassCatalog !== true)

useGlobalShortcuts()

onMounted(() => {
  ui.applyTheme()
  ui.applyDensity()
  void catalog.load()
})
</script>

<template>
  <div class="app-shell">
    <!-- 公开页（登录）：全屏，无壳层 -->
    <RouterView v-if="isAuthRoute" />

    <!-- 主控制台：header + 侧栏 + 目录门禁 -->
    <template v-else>
      <a class="skip-link" href="#main-content">跳到主内容</a>
      <AppHeader class="app-header" />

      <div class="app-body">
        <SideNav
          class="app-nav"
          :class="{ 'app-nav--open': ui.sidebarOpen, 'app-nav--collapsed': ui.navCollapsed }"
        />
        <div
          v-if="ui.sidebarOpen"
          class="app-scrim"
          aria-hidden="true"
          @click="ui.closeSidebar()"
        />

        <main class="app-main" id="main-content">
          <!-- 仅依赖能力目录的路由才受 catalog 状态门禁；admin/forbidden（bypassCatalog）直接渲染。 -->
          <EmptyState
            v-if="needsCatalog && status === 'loading'"
            variant="loading"
            title="正在加载能力目录…"
          />
          <EmptyState
            v-else-if="needsCatalog && status === 'error'"
            variant="error"
            title="能力目录加载失败"
            :description="error ?? undefined"
            action-label="重试"
            @action="catalog.load()"
          />
          <RouterView v-else v-slot="{ Component }">
            <Transition name="route">
              <component :is="Component" />
            </Transition>
          </RouterView>
        </main>
      </div>

      <CommandPalette />
      <HistoryDrawer />
      <ShortcutsDialog />
    </template>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  height: 100vh;
  height: 100dvh;
  position: relative;
  isolation: isolate;
  background: var(--gradient-app);
  background-attachment: fixed;
  overflow: hidden;
}
/* 极光光晕层：固定在背景、不拦截交互，缓慢漂移（仅 transform 动画） */
.app-shell::before {
  content: '';
  position: absolute;
  inset: -10%;
  z-index: 0;
  pointer-events: none;
  background:
    radial-gradient(660px 460px at 16% 10%, var(--aurora-1) 0%, transparent 70%),
    radial-gradient(720px 520px at 86% 84%, var(--aurora-2) 0%, transparent 72%);
  will-change: transform;
  animation: aurora-drift 26s var(--ease-in-out) infinite alternate;
}
@keyframes aurora-drift {
  from {
    transform: translate3d(-2%, -1%, 0) scale(1);
  }
  to {
    transform: translate3d(3%, 2%, 0) scale(1.08);
  }
}

.skip-link {
  position: absolute;
  left: 8px;
  top: -48px;
  z-index: 100;
  padding: 8px 14px;
  background: var(--primary);
  color: var(--primary-fg);
  border-radius: var(--radius);
  transition: top var(--dur) var(--ease);
}
.skip-link:focus {
  top: 8px;
  text-decoration: none;
}

.app-header {
  position: relative;
  z-index: 1;
}

.app-body {
  flex: 1;
  display: flex;
  min-height: 0;
  position: relative;
  z-index: 1;
}

.app-nav {
  width: var(--sidenav-w);
  flex-shrink: 0;
  border-right: 1px solid var(--glass-border);
  overflow-y: auto;
}

/* 桌面端折叠：宽屏时点顶栏 ☰ 收起整条菜单栏（平滑过渡）。移动端由下方抽屉规则接管，不受此影响。 */
@media (min-width: 1024px) {
  .app-nav {
    transition: width var(--dur-base) var(--ease), border-color var(--dur-base) var(--ease);
  }
  .app-nav--collapsed {
    width: 0;
    overflow: hidden;
    border-right-color: transparent;
  }
}

.app-main {
  position: relative;
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  background: transparent;
}

.app-scrim {
  display: none;
}

/*
 * 路由切换过渡：淡入 + 轻微上移（仅 opacity/transform）。
 * 不用 mode="out-in"——它与 RouterView 的动态 <component :is> 组合在真实浏览器里
 * 会死锁（离场组件移除后入场组件永不挂载，表现为"点总览页面空白"）。
 * 改用同时进出的交叉淡入淡出；离场页绝对定位覆盖于上层淡出，不占文档流高度，
 * 从而避免两页纵向堆叠导致的高度跳动。
 */
.route-enter-active {
  transition: opacity var(--dur-base) var(--ease-out), transform var(--dur-base) var(--ease-out);
}
.route-leave-active {
  position: absolute;
  inset: 0;
  transition: opacity var(--dur-fast) var(--ease-out), transform var(--dur-fast) var(--ease-out);
}
.route-enter-from {
  opacity: 0;
  transform: translateY(6px);
}
.route-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

@media (max-width: 1023px) {
  .app-nav {
    position: fixed;
    top: var(--header-h);
    bottom: 0;
    left: 0;
    z-index: 40;
    transform: translateX(-100%);
    transition: transform var(--dur) var(--ease);
    box-shadow: var(--shadow-lg);
  }
  .app-nav--open {
    transform: translateX(0);
  }
  .app-scrim {
    display: block;
    position: fixed;
    inset: var(--header-h) 0 0 0;
    z-index: 30;
    background: rgba(2, 6, 23, 0.5);
  }
}
</style>
