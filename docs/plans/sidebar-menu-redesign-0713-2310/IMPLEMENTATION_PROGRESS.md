# 实施进度 · 左侧菜单重设计（方案③ 工作台/彩色编码）

> 依据 `FINAL_PLAN.md`（B 骨架 + A 交互 + 方案③视觉基线）。每阶段结束：编译 + 跑相关测试 + `git diff` 自检 + 更新本文件。

分支：`feat/rbac-shared-kb`（当前）。目录：`capability-showcase-frontend/`。

## 阶段清单

- [x] **阶段 1｜数据结构与领域模型**：`config/moduleGroups.ts`(加分组强调色) + 新 `navigation/navigationModel.ts` 纯模型 + `navigationModel.test.ts` + `moduleGroups.test.ts` ✅
- [x] **阶段 2｜核心业务逻辑**：`stores/ui.ts` 显式 desktop/mobile action + 严格 storage 校验 + `ui.test.ts` ✅
- [x] **阶段 3｜接口与适配层（含视觉落地）**：4 个导航子组件 + SideNav 改编排 + App/AppHeader inert/ARIA/恢复入口 + tokens 分组色/尺度 + StateBadge/MethodBadge compact + CommandPalette 复用元数据 + shortcuts 裸`/` ✅
- [x] **阶段 4｜测试（自动化部分）**：SideNav(11)/AppHeader(2) 测试 + 全量 `npm test` **274 passed/43 files** + `type-check` **0** + `build` **ok** ✅；⚠️ 三浏览器人工视觉回归待用户执行（jsdom 无法证明布局/配色/触控）
- [x] **阶段 5｜文档与最终检查**：README 导航与快捷键新章 + 能力数 80→82；catalog.json/dist 为 gitignore 生成物（未手改）；diff 仅授权范围 ✅

---

## 进度日志

### 阶段 1 ✅（完成 2026-07-14）
- 改动：`config/moduleGroups.ts`(追加 `AccentKey`/`accent`/`groupAccent()`) / 新 `src/navigation/navigationModel.ts`(纯模型 `buildNavigationModel` + `splitModuleTitle`/`capabilityMatches`/`moduleMatches`) / `navigationModel.test.ts`(22) / `moduleGroups.test.ts`(5)
- 测试：`vitest run` 两文件 **27 passed**；`vue-tsc --noEmit` **exit 0**
- 验收：不修改入参 ✓；真实 9/82 守恒 ✓；唯一 active + 祖先可见 ✓；未知归 Other/slate ✓；失效收藏忽略/去重 ✓；搜索命中·仅模块名命中·零结果 ✓；无 catalog/契约变更 ✓
- diff 仅在授权范围（moduleGroups.ts + 新 navigation/ + 两测试）

### 阶段 2 ✅（完成 2026-07-14）
- 改动：`stores/ui.ts` 新增 `setSidebarOpen/openSidebar/setNavCollapsed`（幂等、storage 抛错安全降级），旧 `toggleSidebar/closeSidebar/toggleNavCollapsed` 保留为委托出口（兼容）；新 `stores/ui.test.ts`(11)
- 测试：`ui.test.ts` **11 passed**；**全量 261 passed / 41 files**（无回归）；`type-check` 待阶段末统一
- 验收：合法/畸形/异常存储值均安全（严格 `==='1'`）✓；重复 set 幂等 ✓；移动抽屉不持久化 ✓

### 阶段 3 ✅（完成 2026-07-14）
- 新增：`config/stateMeta.ts`（五态元数据单一事实源）、`composables/useIsDesktop.ts`（断点）、`components/layout/navigation/`（`NavModuleIcon`/`NavCapabilityRow`/`NavModuleRow`/`NavGroupSection`/`NavEmptyState`）
- 改：`SideNav.vue`→编排容器（方案③彩色编码，接 navigationModel，唯一 active 用 route.name/params/路径前缀）；`App.vue`（隐藏侧栏 inert+aria-hidden、断点切换 setSidebarOpen(false)、移动抽屉 useFocusTrap+Esc+焦点归还，与浮层互斥）；`AppHeader.vue`（☰ 桌面常驻可恢复、toggleNav 显式 action、aria 按断点反映）；`tokens.css`（--sidenav-w 280、[data-accent] 6 色 ×light/dark/首屏、nav 尺度令牌；纯追加不改旧值）；`StateBadge.vue`/`CommandPalette.vue` 复用 stateMeta 去重；`MethodBadge.vue` +compact；`useGlobalShortcuts.ts` 裸`/`先展开再聚焦否则退回命令面板
- 决策：CommandPalette 的 `fuzzy()` 子序列匹配**保持不变**（与 SideNav substring 有意不同），只共享五态元数据——避免搜索语义漂移（符合计划"不改命令面板搜索行为"）
- 校验：`type-check` 0；`build` ok

### 阶段 4 ✅（完成 2026-07-14）
- 新 `SideNav.test.ts`(11)：结构渲染(9模块/4组/图例)、管理门禁 canAdmin 真假、唯一 active(总览/模块深链/管理深链不双亮)、当前模块自动展开、收藏、搜索零结果+清除、命中过滤、分组折叠持久化；新 `AppHeader.test.ts`(2)：☰ 桌面切 navCollapsed + aria 同步、桌面常驻可见
- 全量 **274 passed / 43 files**、`type-check` **0**、`build` **ok**
- ⚠️ 待用户：三浏览器(Chrome/Firefox/Safari) × viewport(320/768/1023/1024/1440) × light/dark × 两密度 × 200% zoom 人工视觉/触控/焦点回归（`npm run dev` 即可查看方案③实机效果）

### 阶段 5 ✅（完成 2026-07-14）
- README 新增「左侧导航与快捷键」章（分组彩色编码/五态双编码/筛选/折叠/桌面折叠/移动抽屉 inert+Esc/收藏/管理/密度/快捷键）；能力数 80→82（注明以 catalog.json 为准）
- 生成物：`public/catalog.json`、`dist/` 均 gitignore（未提交、未手改）；改动集 = 计划授权的 10 改 + 计划新增 + 3 合理新增(stateMeta/useIsDesktop/NavModuleIcon)，未碰 router/catalog store/模块页/后端/部署

## 遗留 / 待办
- 三浏览器人工视觉回归（阶段4 人工部分）——需真实浏览器，本环境无 Playwright，留给用户执行
- 提交：尚未 commit（等用户确认视觉效果后再提交）
