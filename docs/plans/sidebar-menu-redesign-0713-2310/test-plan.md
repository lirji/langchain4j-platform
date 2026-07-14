# 左侧菜单重设计测试计划

## 1. 测试基线

仓库已有 Vitest 2.1.8、Vue Test Utils 2.4.6、jsdom 25；`vitest.config.ts` 为 `css:false`。当前没有 SideNav 测试，且未安装 Playwright/axe。因此自动测试覆盖逻辑/DOM/ARIA，媒体查询和视觉必须真实浏览器验证。

## 2. 自动测试（拟新增）

### `src/navigation/navigationModel.test.ts`

表驱动覆盖：Module.order、GROUP_ORDER、未知模块 Other；SideNav 的 title/id/path/description/tags substring 搜索、trim/大小写；模块命中/能力命中/零结果；收藏有效/失效；唯一 current/ancestor；收藏副本不得 `aria-current`；五态；输入不被原地排序/修改；空 catalog/空模块。另为 CommandPalette 既有 fuzzy/字段集合保留特征测试，首发不统一两个 matcher。

### `src/components/layout/SideNav.test.ts`

memory router + Pinia 挂载真实 Catalog 形状，覆盖：唯一 `aria-current`；当前祖先强制展开；非当前折叠；搜索清空恢复；无结果/清除；链接关闭移动抽屉；合法/坏 JSON/错误类型/storage 抛错；状态非纯颜色语义；长标题完整名称可获得。

### 子组件测试

为 NavGroupSection/NavModuleRow/NavCapabilityRow/NavEmptyState 覆盖 props、emits、稳定 key、展开按钮 `aria-expanded`、链接可访问名称、compact 方法/状态标识。最终文件名以实现为准。

### 壳层与权限集成

拟新增 `src/App.test.ts`、`src/components/layout/AppHeader.test.ts`、`src/stores/ui.test.ts`：

- public 无壳，catalog 门禁与 bypassCatalog。
- 1023/1024 模拟下分别改变 sidebarOpen/navCollapsed，按钮 ARIA 正确。
- scrim、Esc、导航关闭抽屉并归还焦点；隐藏区不可聚焦。
- `showcase.navGroups`/`showcase.navCollapsed` 初始化、畸形/读写异常、显式 set 幂等、resize 不使用 toggle；首发不新增 v1 key。
- 移动抽屉与 CommandPalette/History/Shortcuts 互斥，任一全局浮层打开前关闭抽屉，不出现双 focus trap。
- none、普通 Bearer、admin Bearer、admin+API Key 四态，同时断言 SideNav/AppHeader/CommandPalette；仅 admin Bearer 显示管理入口。

### 现有回归

重点运行 `router/guard.test.ts`、`usePermission.test.ts`、`AuthControl.test.ts`、catalog 和各模块 View 测试，确认授权、目录、API、工作台不变。

## 3. 数据矩阵

- Catalog：空、无能力、多组、未知模块、重复/超长标题、真实 9/82、模拟 100+。
- Route：总览、模块、能力、admin users/roles、forbidden、未知参数。
- 身份：none、普通 Bearer、admin Bearer、admin+API Key、RBAC 关闭。
- 收藏：空、有效、失效、当前能力重复。
- Storage：缺失、合法、坏 JSON、数组/错类型、异常、旧 key。
- 五种 CapabilityState。
- 320/768/1023/1024/1440；light/dark × comfortable/compact。

## 4. 候选方案差异测试

- A：集中验证 SideNav 单体的状态组合和旧 key。
- B/最终方案：纯模型契约、子组件 props/emits、模型与组合渲染一致；`showcase.navGroups` 从 SideNav 移交 ui store 后行为兼容，验证重复初始化幂等和 storage 异常。统一 v1 key 属后续方案，不纳入首发测试。
- C：另测 full↔rail、tooltip/标签、移动钻取进入/返回、route 优先、快速点击+跳转+搜索无旧上下文、v2 迁移。

## 5. 必须真实浏览器验证

Chrome/Firefox/Safari 在上述视口检查：1023/1024 临界、层叠/滚动/footer、背景不可操作、Tab/Shift+Tab/Esc、焦点可见与归还、200% zoom、长标题/长列表、light/dark、两密度、reduced-motion、触控目标、对比度。逐 viewport 保存基准截图。自动 E2E/视觉工具若引入需另行批准。

## 6. 命令与发布门禁

```bash
cd capability-showcase-frontend
npm test -- src/navigation/navigationModel.test.ts src/components/layout/SideNav.test.ts
npm test
npm run type-check
npm run build
```

完成标准：命令全绿；关键矩阵 100%；三浏览器人工清单通过；无 P0/P1、键盘陷阱、双 focus trap、隐藏可聚焦项、权限入口漂移、多个 `aria-current` 或不可恢复菜单。任一出现则不发布。
