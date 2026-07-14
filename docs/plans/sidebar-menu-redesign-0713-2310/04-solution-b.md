# 方案 B：统一导航模型与组件化树

## 定位

保留现有 IA 和 URL，把 SideNav 中的数据适配、搜索、active、展开、持久化与渲染职责分离，建立可测试的导航子系统。

## 架构

1. **模型层（拟新增）** `src/navigation/navigationModel.ts`：以现有 Module/Capability、收藏、查询、route 信息为输入，纯函数输出有序分组、模块、能力、祖先 active、搜索摘要和收藏副本语义。
2. **状态层** `stores/ui.ts`：显式 desktop/mobile action；首版推荐保留旧存储键并加强校验，避免无必要迁移。模块展开是否持久化仍待验证。
3. **展示层** SideNav 只编排搜索、列表、footer、焦点；拟新增 `NavGroupSection.vue`、`NavModuleRow.vue`、`NavCapabilityRow.vue`、`NavEmptyState.vue`。
4. **壳层** App/AppHeader 管理布局、scrim、inert、触发器和真实 ARIA 状态。

## 核心流程

`catalog.modules + favorites.ids + query + route + canAdmin` → `buildNavigationModel()`（拟定名）→ SideNav/子组件。当前 route 祖先强制可见但不修改偏好；子组件只通过 props/emits，不直接访问 route/catalog/localStorage。共享 matcher 可同时供 CommandPalette 使用，但 SideNav 的 `ui.filter` 与面板本地 query 不合并。

## 视觉模型

- 仍为单列树，形成清晰的组/模块/能力三级规范。
- 收藏副本只显示弱“当前”提示；所属模块树保留唯一主 active。
- 图标解析集中：`Module.icon` 有值时只作为安全文本/受控内置映射，当前为空时使用本地受控 fallback；不使用 `v-html`。
- 五态元数据集中共享；MethodBadge/状态标识支持 compact；密度通过 token 驱动。

## 精确范围

修改：`SideNav.vue`、`App.vue`、`AppHeader.vue`、`stores/ui.ts`、`config/moduleGroups.ts`、`CommandPalette.vue`、`useGlobalShortcuts.ts`、`StateBadge.vue`、`MethodBadge.vue`、`tokens.css`、README。

拟新增：`navigationModel.ts/.test.ts`、四个导航子组件、SideNav/AppHeader/ui/moduleGroups 测试。默认不改 router、catalog types/yml/json/store、模块页、后端与部署。

## 成本、扩展性与弱点

- 估算 5–8 开发日，需团队校准；综合风险中等。
- 模型、active、搜索可纯函数测试；新增模块仍遵循 order/Other，长期维护最佳。
- 文件/props/emits 数量增加；边界切得过细会过度设计。
- 保留三级树，未彻底解决未来超大目录的信息负荷。

## 兼容、灰度与回滚

首版不新增持久化 schema，兼容成本最低；若实施者决定引入 `showcase.navigation.v1`，必须从旧 key 幂等导入、逐字段校验、至少一个发布周期保留旧 key且不删除。仓库无已确认导航运行时 flag，灰度依赖分环境静态包或待验证的构建开关。回滚旧包后新 key 应被无害忽略。
