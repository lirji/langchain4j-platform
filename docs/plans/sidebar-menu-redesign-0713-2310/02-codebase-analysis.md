# 左侧菜单重设计：代码库分析

## 1. 技术范围

`capability-showcase-frontend` 是独立 Vue 3 + TypeScript + Vite SPA，不属于根 Maven 构建。菜单不调用业务 API；它消费目录、路由、收藏、权限和 UI 状态。因此默认方案无需改后端、数据库、消息、部署或 Maven 配置。

## 2. 实际调用链

1. `src/main.ts::bootstrap()` 创建 Vue/Pinia，完成 `auth.bootstrap()` 与 `auth.loadPublicConfig()`，注册 router 后挂载。
2. `src/App.vue` 在 `onMounted()` 调用 `ui.applyTheme()`、`ui.applyDensity()`、`catalog.load()`；非 public 路由渲染 AppHeader、SideNav 和主区。
3. `src/stores/catalog.ts::load()` 调用 `src/api/catalog.ts::fetchCatalog()`，`tagCatalogSource()` 后将 catalog 置为 ready；有凭证时异步 `refreshLive()`/`mergeLive()`，仅改变能力 source。
4. `stores/catalog.ts::modules` 按 `Module.order` 排序；`SideNav.vue::navModules` 搜索过滤，`navGroups` 通过 `moduleGroups.ts::groupIdForModule()` 聚合。
5. SideNav 同时读取 `useUiStore()`、`useFavoritesStore()`、`usePermission().canAdmin` 与 `useRoute()`。
6. 模块/能力链接分别进入 `/m/:moduleId`、`/m/:moduleId/:capId`，由 `modules/ModuleHost.vue::SPECIALIZED` 选择 9 个现有专用视图，未知模块回退 `GenericModuleView`。
7. `SideNav.vue::onNavigate()` 关闭移动抽屉；`AppHeader.vue::toggleNav()` 在窄屏切 `sidebarOpen`，宽屏切 `navCollapsed`。
8. admin 可见链为 `auth.isAdmin && session.credentialMode === 'bearer' && RBAC_CONSOLE_ENABLED`；router 的 `resolveRouteAccess()` 仍做最终前端门禁。

## 3. 数据、配置与状态

- `src/types/catalog.ts::Module` 已有 `icon?`，但当前 9 个模块都未提供；SideNav 使用本地 `MODULE_ICON` emoji 映射。
- `GROUP_ORDER` 和 `GROUP_OF` 在 `src/config/moduleGroups.ts`；未知模块落 `OTHER_GROUP_ID='other'`。
- `SideNav.vue::groupCollapsed` 写 `showcase.navGroups`；true=收起。反序列化只检查 object，数组和非 boolean 值也可能被接受。
- `manualExpand` 只在组件内存中；默认当前模块展开。组持久化、模块不持久化。
- `stores/ui.ts` 持久化 `showcase.theme`、`showcase.density`、`showcase.navCollapsed`；`sidebarOpen` 和 `filter` 仅内存。
- `stores/favorites.ts` 只持久化 capability id；失效 id 在 `favCaps` 中忽略。
- `styles/tokens.css` 提供浅深主题、`--sidenav-w:288px`、密度和动效 token；`base.css` 提供 focus-visible、reduced-motion。

## 4. 现存问题及证据

### 信息层级

- `SideNav.vue` 约 620 行，把数据建模、搜索、权限、本地存储、模板和 CSS 混在一个组件。
- 三级折叠树叠加总览/管理/收藏；82 项能力造成扫描和滚动负担。
- 288px 中模块行同时容纳 emoji、长双语标题、计数和 24px chevron；能力行还容纳最小 52px `MethodBadge`、状态点和缩进。
- Header 与 SideNav 都有“搜索能力”入口，但 `CommandPalette.vue` 用 fuzzy 且不含 description，SideNav 用 substring 且含 description。

### 当前项与展开

- `isGroupOpen()` 不因当前路由展开所属组；深链可能被持久折叠隐藏。
- `isModuleOpen()` 先读 `manualExpand`；用户可手动隐藏当前能力。
- 总览以 `!activeModuleId` 判断 active，admin/forbidden 无 moduleId，导致误亮总览。
- 能力 active 只比较 capId；收藏与原模块可同时出现同强度 active。

### 响应式与可访问性

- `AppHeader.vue` 的 `.header__menu` 只在 ≤1023px 显示，但桌面 nav 可被 `showcase.navCollapsed=1` 隐藏，形成无可见恢复入口。
- 移动按钮 ARIA 绑定 `navCollapsed`，点击却改变 `sidebarOpen`。
- transform/width 隐藏的导航仍在 DOM，未设置 inert/正确 aria-hidden，可能 Tab 到屏外。
- 移动抽屉无 Esc、初始焦点、focus trap、关闭焦点归还；可复用 `useFocusTrap.ts`。
- 裸 `/` 总是找 `#sidenav-filter`，即使导航不可见。
- 能力状态点 `aria-hidden=true` 且仅靠颜色；`StateBadge.vue` 已有文字/图形语义可复用。
- 菜单 padding 硬编码，DensityToggle 对菜单密度影响有限；移动 chevron 小于建议触控目标。

### 搜索与健壮性

- 模块标题命中而能力不命中时 `nm.caps=[]`，展开后无说明；完全无匹配也无空态。
- 搜索强制展开目前不改偏好，这是应保留的正确行为。
- 当前规模 O(82) 过滤无需虚拟滚动；复杂动画、重复过滤和多层 blur 才是潜在性能问题。

## 5. 可复用资产

- `stores/catalog.ts::{modules,allCapabilities,moduleById,capabilityById}`
- `config/moduleGroups.ts::{GROUP_ORDER,groupIdForModule,groupLabel}`
- `usePermission.ts::canAdmin`
- `stores/favorites.ts`、`stores/ui.ts`
- `useGlobalShortcuts.ts`、`useFocusTrap.ts`
- `styles/tokens.css`、`styles/base.css`
- `MethodBadge.vue`、`StateBadge.vue`、`EmptyState.vue`
- `App.vue` 的壳层与 scrim、`AppHeader.vue` 的触发器

## 6. 影响文件分级

### 最终推荐方案必改

- `src/components/layout/SideNav.vue`
- `src/App.vue`
- `src/components/layout/AppHeader.vue`
- `src/stores/ui.ts`
- `src/config/moduleGroups.ts`
- `src/components/common/CommandPalette.vue`
- `src/composables/useGlobalShortcuts.ts`
- `src/components/capability/badges/StateBadge.vue`
- `src/components/capability/badges/MethodBadge.vue`
- `src/styles/tokens.css`
- `README.md`（交互/快捷键与真实能力统计文档一致性）

### 推荐拟新增

- `src/navigation/navigationModel.ts` 及 `.test.ts`
- `src/components/layout/navigation/NavGroupSection.vue`
- `src/components/layout/navigation/NavModuleRow.vue`
- `src/components/layout/navigation/NavCapabilityRow.vue`
- `src/components/layout/navigation/NavEmptyState.vue`
- `src/components/layout/SideNav.test.ts`
- `src/components/layout/AppHeader.test.ts`
- `src/stores/ui.test.ts`
- `src/config/moduleGroups.test.ts`

新增文件名是规划名称，当前不存在；实施 Agent 如调整命名，必须保持职责边界。

### 默认只回归、不改

- `src/router/index.ts`、`src/types/catalog.ts`、`capabilities.yml`、`public/catalog.json`
- `src/stores/catalog.ts`、`src/stores/favorites.ts`
- `src/modules/ModuleHost.vue`、`GenericModuleView.vue`、各专用模块视图
- `src/components/layout/Breadcrumb.vue`、`DensityToggle.vue`
- `package.json`、Vite/Vitest、Docker/nginx/compose 和所有后端模块

## 7. 测试现状

现有 `stores/catalog.test.ts`、`router/guard.test.ts`、`usePermission.test.ts` 等保护目录和权限；没有 SideNav、AppHeader、ui store、moduleGroups 或菜单快捷键的专项测试，也没有 E2E/视觉基线。Vitest 使用 jsdom 且 `css:false`，无法证明媒体查询、布局、层叠、触控尺寸、颜色对比或焦点视觉，必须补真实浏览器回归。
