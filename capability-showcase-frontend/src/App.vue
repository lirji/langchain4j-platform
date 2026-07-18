<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch, type ComponentPublicInstance } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute } from 'vue-router'
import AppHeader from './components/layout/AppHeader.vue'
import SideNav from './components/layout/SideNav.vue'
import EmptyState from './components/common/EmptyState.vue'
import CommandPalette from './components/common/CommandPalette.vue'
import HistoryDrawer from './components/common/HistoryDrawer.vue'
import ShortcutsDialog from './components/common/ShortcutsDialog.vue'
import SessionExpiredDialog from './components/common/SessionExpiredDialog.vue'
import { useCatalogStore } from './stores/catalog'
import { useUiStore } from './stores/ui'
import { useGlobalShortcuts } from './composables/useGlobalShortcuts'
import { useIsDesktop } from './composables/useIsDesktop'
import { useFocusTrap } from './composables/useFocusTrap'

const catalog = useCatalogStore()
const ui = useUiStore()
const route = useRoute()
const { status, error } = storeToRefs(catalog)
const { isDesktop } = useIsDesktop()

const navRef = ref<ComponentPublicInstance | null>(null)
const navContainer = computed<HTMLElement | null>(
  () => (navRef.value?.$el as HTMLElement | undefined) ?? null,
)

// 公开页（登录）全屏渲染，不套 header/侧栏/目录门禁与全局浮层。
const isAuthRoute = computed(() => route.meta.public === true)
// 是否需要能力目录：register/callback 等 bypassCatalog 路由不依赖 catalog，catalog 失败也应能打开。
const needsCatalog = computed(() => !isAuthRoute.value && route.meta.bypassCatalog !== true)

/**
 * 侧栏是否处于隐藏态（桌面折叠 或 移动抽屉关闭）——隐藏时对其设 inert + aria-hidden，
 * 使屏外/收起的导航不可 Tab、不被读屏，修复"隐藏导航仍可聚焦"。
 */
const navHidden = computed(() =>
  isDesktop.value ? ui.navCollapsed : !ui.sidebarOpen,
)

// 断点切换清理：进入桌面时用 set 关闭移动抽屉（避免遗留 scrim/开态）。
watch(isDesktop, (desktop) => {
  if (desktop) ui.setSidebarOpen(false)
})

/**
 * 移动抽屉焦点隔离：仅移动端抽屉打开、且无其它全局浮层时激活 —— 与命令面板/历史/快捷键互斥，
 * 避免多层焦点陷阱相互抢占。Esc 关闭并由 useFocusTrap 归还焦点给打开前元素。
 */
useFocusTrap({
  active: () =>
    !isDesktop.value &&
    ui.sidebarOpen &&
    !ui.cmdkOpen &&
    !ui.historyOpen &&
    !ui.shortcutsOpen &&
    !ui.authModalOpen,
  container: navContainer,
  onEscape: () => ui.closeSidebar(),
})

useGlobalShortcuts()

const shellEl = ref<HTMLElement | null>(null)

/**
 * iOS Safari 视口校正：壳层（app-shell，overflow:hidden）设计上不滚动，但 iOS 弹键盘
 * “滚动露出输入框”会连 overflow:hidden 的祖先一起滚，键盘收起后偶发不归零——
 * 顶栏（含 ☰）被顶出屏幕上沿，表现为"菜单栏消失、刷新才恢复"。
 * 键盘收起（可视高≈布局视口高；不用 innerHeight——iOS 上它随键盘变）且有残留时全部归零。
 */
function restoreViewportAfterKeyboard(): void {
  const vv = window.visualViewport
  if (vv && vv.height < document.documentElement.clientHeight - 80) return // 键盘仍展开，放行
  if (window.scrollY > 0) window.scrollTo(0, 0)
  for (const el of [document.documentElement, document.body, shellEl.value]) {
    if (el && el.scrollTop > 0) el.scrollTop = 0
  }
}

onMounted(() => {
  ui.applyTheme()
  ui.applyDensity()
  void catalog.load()
  window.visualViewport?.addEventListener('resize', restoreViewportAfterKeyboard)
  // 壳层被滚动本身就是异常信号（键盘收起时）：即时纠偏，兜住 resize 之后才发生的滞留
  shellEl.value?.addEventListener('scroll', restoreViewportAfterKeyboard)
})
onUnmounted(() => {
  window.visualViewport?.removeEventListener('resize', restoreViewportAfterKeyboard)
  shellEl.value?.removeEventListener('scroll', restoreViewportAfterKeyboard)
})
</script>

<template>
  <div ref="shellEl" class="app-shell">
    <!-- 公开页（登录）：全屏，无壳层 -->
    <RouterView v-if="isAuthRoute" />

    <!-- 主控制台：header + 侧栏 + 目录门禁 -->
    <template v-else>
      <a class="skip-link" href="#main-content">跳到主内容</a>
      <AppHeader class="app-header" />

      <div class="app-body">
        <SideNav
          ref="navRef"
          class="app-nav"
          :class="{ 'app-nav--open': ui.sidebarOpen, 'app-nav--collapsed': ui.navCollapsed }"
          :inert="navHidden"
          :aria-hidden="navHidden ? 'true' : undefined"
        />
        <div
          v-if="ui.sidebarOpen"
          class="app-scrim"
          aria-hidden="true"
          @click="ui.closeSidebar()"
        />

        <main class="app-main" id="main-content">
          <!-- 仅依赖能力目录的路由才受 catalog 状态门禁；register/callback（bypassCatalog）直接渲染。 -->
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
      <SessionExpiredDialog />
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
  /* iOS Safari 对 fixed 背景支持差且伤滚动性能，移动端降为默认 */
  .app-shell {
    background-attachment: scroll;
  }
  .app-nav {
    position: fixed;
    top: var(--header-h);
    bottom: 0;
    left: 0;
    z-index: 40;
    transform: translateX(-100%);
    transition: transform var(--dur) var(--ease);
    box-shadow: var(--shadow-lg);
    /* 底部 home 条安全区，防抽屉页脚被遮 */
    padding-bottom: var(--safe-bottom);
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
