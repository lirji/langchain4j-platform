# 方案 A：保守增量优化

## 定位

保留 `SideNav.vue` 单组件和“语义组 → 模块 → 能力”三级树，只修复导航正确性、响应式、可访问性与视觉层级。适合要求快速、低风险发布的场景。

## 架构与流程

- 继续由 SideNav 读取 catalog、route、favorites、ui 与 `canAdmin`，保留 `navModules`、`navGroups`、`favCaps`。
- 当前路由优先于用户折叠偏好：当前组和当前模块强制可见，但不写回偏好。
- 搜索保持现有字段；模块命中但能力不命中时给提示，零结果给空态与清除入口。
- 总览改为只在 `route.name==='overview'` 时 active；能力 active 同时核对 moduleId/capId。
- 桌面/移动开关使用各自状态；关闭区不可聚焦；移动端补 Esc、背景隔离与焦点归还。

## 视觉与状态

- 组头是分区，模块为主导航，能力为次级端点；active 强度唯一。
- 保留 emoji 和 MethodBadge，但统一尺寸、对齐；五态增加非颜色线索和可访问名称。
- 移动目标至少 44px；桌面行高由密度 token 控制。
- 继续使用 `showcase.navGroups`、`showcase.navCollapsed`，仅加强 plain-object/boolean 校验，无 schema 迁移。

## 改动范围

修改 `SideNav.vue`、`App.vue`、`AppHeader.vue`、`stores/ui.ts`、`useGlobalShortcuts.ts`、`styles/tokens.css`；按需扩展 `StateBadge.vue`/`MethodBadge.vue` 的 compact 形态。拟新增 SideNav、AppHeader、ui store 测试。路由、catalog 契约和后端不改。

## 成本、扩展性与弱点

- 估算 2–4 开发日，需团队校准；回滚最简单。
- 可承接当前 9/82，但长树问题只是缓解。
- SideNav 仍是约 620 行单体，搜索与 CommandPalette 仍可能漂移，长期维护性较差。

## 风险控制

发布停止条件：当前深链不可见、隐藏导航仍可 Tab、管理权限入口漂移、桌面折叠无法恢复、移动抽屉无法关闭或焦点不能归还。通过回退前端静态包即可回滚，不涉及数据迁移。
