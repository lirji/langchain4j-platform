# 左侧菜单重设计最终实施计划

> 状态：规划定稿，尚未修改任何业务代码。本文件中的“拟新增”文件/符号当前不存在；实施 Agent 可调整命名，但不得改变职责和验收边界。
>
> 资深架构复审（2026-07-13）已完成并修订：首发不统一两套搜索算法；移动抽屉的 focus trap/inert 责任收口到壳层；`showcase.navGroups` 明确移交 ui store 且不迁移 key；补齐 App/快捷键测试；全树仅目标链接使用 `aria-current="page"`。

## 一、背景、目标与非目标

`capability-showcase-frontend` 当前以 `SideNav.vue` 承载筛选、总览、管理、收藏、分组、模块、能力、偏好和全部样式。真实目录为 9 个模块、82 项能力；三级树在 288px 宽度内混合 emoji、长双语标题、HTTP badge、计数和颜色状态点，存在信息拥挤。代码还存在可复现的行为缺陷：admin 与总览双 active、当前深链可被折叠隐藏、桌面折叠后缺少可见恢复入口、移动按钮 ARIA 绑定错误、隐藏导航可聚焦、筛选无结果无反馈。

目标：在不改变业务能力和用户既有路由心智的前提下，提高导航可理解性、当前路径可见性、视觉一致性、响应式可靠性和可访问性；同时把约 620 行 SideNav 中的纯导航逻辑拆为可测试模型。

非目标：不改后端、数据库、消息、业务 API、鉴权协议、catalog 契约、模块工作台或执行门禁；不引入服务端偏好、行为采集、微前端、可拖拽宽度、“最近访问”或 rail/context 首发；不以隐藏入口替代授权。

## 二、已确认业务规则

1. `capabilities.yml`/catalog 是模块和能力事实源；`stores/catalog.ts::modules` 的 `Module.order` 是排序权威，未知模块进入 Other。
2. 保持 `/`、`/m/:moduleId`、`/m/:moduleId/:capId` 和 `/admin/*`；不改 `resolveRouteAccess()`。
3. public 页面无壳；admin/forbidden 的 `bypassCatalog` 不受目录失败拖累。
4. 管理入口三处继续只依赖 `usePermission().canAdmin`；只有 Bearer role-admin 且开关开启时显示。
5. 普通能力不按 scope 隐藏，五态/方法仍诚实展示；后端是最终安全边界。
6. 收藏只存 capability id；本地导航偏好只含非敏感 UI 状态，读写失败安全降级。
7. 当前 route 祖先可见的优先级高于折叠偏好，但强制展开不写回偏好。
8. 移动端导航后关闭；关闭/折叠导航不可聚焦；主题、密度、focus-visible、reduced-motion 保持。

## 三、当前代码与调用链

`main.ts::bootstrap()` → App 挂载 → `App.vue::onMounted()` 调用 `catalog.load()` → `stores/catalog.ts::load()`/`fetchCatalog()` → `modules` 按 order 排序 → SideNav 读取 catalog/ui/favorites/route/`canAdmin` → `navModules` 搜索、`navGroups` 语义分组 → RouterLink 进入 ModuleHost 或 admin。

壳层由 `App.vue` 根据 `sidebarOpen`/`navCollapsed` 设置类与 scrim；入口由 `AppHeader.vue::toggleNav()` 在 1023/1024 两侧分流；裸 `/` 由 `useGlobalShortcuts.ts::onKeydown()` 聚焦固定 id。管理可见性来自 `usePermission.ts::canAdmin`，路由守卫来自 `router/index.ts::resolveRouteAccess()`。

当前状态：`showcase.navGroups` 在 SideNav、`showcase.navCollapsed` 在 ui store、收藏在 `showcase.favorites`。`manualExpand` 仅组件内存。当前 `readGroupState()` 未严格校验 plain object/boolean。

## 四、候选方案对比与评分

评分 1–5 且 5 最佳；权重为正确性22%、风险18%、复杂度12%、维护15%、扩展12%、测试11%、回滚10%。

| 方案 | 正确性 | 风险 | 复杂度 | 维护 | 扩展 | 测试 | 回滚 | 加权 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A 单体增量 | 4 | 5 | 5 | 2 | 2 | 3 | 5 | 3.75 |
| B 模型+组件化、保留 IA | 5 | 4 | 3 | 5 | 5 | 4 | 4 | **4.37** |
| C rail+context | 3 | 2 | 2 | 4 | 5 | 2 | 2 | 2.88 |

A 易交付但保留单体和长树技术债；B 在 URL/IA 不变时获得可测试模型和清晰组件边界；C 长期扩展最好，但 route/context 双状态、移动钻取和用户再学习尚无研究支撑。详见 `comparison.md`。

## 五、最终方案及选择原因

采用 **B 的模型和组件化骨架 + A 的单列树交互与分阶段发布**，吸收 C 的唯一 active 链、主次层次和“折叠必须可恢复”原则，不交付 rail/context。

### 目标结构

- 拟新增 `navigationModel.ts`：纯函数构造导航视图模型，不读取 DOM/localStorage、不修改输入。
- SideNav：只连接 store/route、搜索框、footer 和子组件；不自行注册全局焦点监听。
- NavGroupSection/NavModuleRow/NavCapabilityRow/NavEmptyState：固定三级结构，明确 props/emits，不做递归通用树。
- ui store：保留 theme/density/floating overlays；增加显式 desktop/mobile set/open/close，并接管原 SideNav 内 `showcase.navGroups` 的严格读写，禁止 resize 用 toggle。
- App/AppHeader：成为响应式可见性和触发 ARIA 的唯一壳层事实源。
- 搜索：首发不共享匹配算法或字段集合。SideNav 保留 substring+description，CommandPalette 保留 fuzzy 且不含 description；二者各自用特征测试锁定。只共享无行为差异的文本规范化辅助函数（如最终确有必要）和五态元数据，query 永不共享。算法统一必须另行评审。
- 状态：共享五态元数据；菜单使用 compact、非纯颜色标识。MethodBadge 提供 compact variant。

### 首发兼容决策

首发保留 `showcase.navGroups`、`showcase.navCollapsed`、`showcase.favorites`，不引入新的 v1 key。`showcase.navGroups` 的读写从 SideNav 移入 `ui.ts`（或拟新增导航状态 composable，二选一；推荐 ui store），只接受 plain object 且值为 boolean；纯 `navigationModel` 只接收展开状态，不碰 storage。模块展开仍为内存态，是否持久化为待验证后续项。统一 schema 必须作为后续独立评审，首发禁止顺手引入。

### 已知弱点

- 仍为三级树，未来目录显著超过当前规模时可能再次过载。
- 新增文件与 props/emits 协作增加；组件边界过细会过度设计。
- 图标、双语短标签和最终宽度没有产品稿，必须在实施前冻结视觉基线。
- SideNav 与 CommandPalette 继续有不同匹配语义，用户可能困惑；首发以不改变行为为优先，算法统一作为后续产品决策。
- 没有现成运行时导航 flag；灰度主要依赖分环境静态包和可快速回退的构建产物。

## 六、精确修改清单

### 修改现有文件

1. `src/components/layout/SideNav.vue`
   - 删除/迁出 `NavModule`、`NavGroup` 建模和 `MODULE_ICON`/五态重复元数据。
   - 保留 store/route 连接、`onNavigate()`、搜索输入和 footer 编排。
   - 总览 active 改用 route name；收藏副本弱化；当前祖先强制可见。
   - 搜索模块命中/零结果/清除；导航语义、`aria-current`、完整名称、焦点容器。
2. `src/App.vue`
   - `.app-nav` 桌面/移动可见性；关闭态 inert/`aria-hidden`；scrim；断点切换清理。
   - 仅移动抽屉打开时启用焦点陷阱，并把背景 main/header 设为 inert/`aria-hidden`；桌面折叠不启用 trap。
   - 保持 public/bypassCatalog 和 RouterView 过渡不变。
3. `src/components/layout/AppHeader.vue`
   - `toggleNav()` 使用明确 desktop/mobile action。
   - 菜单按钮桌面可见且可恢复；移动 `aria-expanded` 绑定 `sidebarOpen`，桌面绑定 `navCollapsed`；为关闭后的焦点归还提供稳定 ref/id。
4. `src/stores/ui.ts`
   - 保留 `toggleSidebar()`/`closeSidebar()`/`toggleNavCollapsed()` 的兼容出口；拟新增显式 `openSidebar()`、`setSidebarOpen()`、`setNavCollapsed()`（最终命名可调整）。
   - 接管 `showcase.navGroups` 的 plain-object/boolean 校验、读写与展开 action；storage 异常安全降级。
   - `openCmdk()`、`openHistory()`、`openShortcuts()` 先关闭移动抽屉，禁止两个 document 级 focus trap 并存；resize 使用 set，不用 toggle。
5. `src/config/moduleGroups.ts`
   - 在保留 `GROUP_ORDER`、`groupIdForModule()`、`groupLabel()` 的前提下集中安全的组/模块导航展示元数据与 fallback；未知模块仍 Other。
6. `src/components/common/CommandPalette.vue`
   - 移除重复五态元数据；保留自身 fuzzy matcher 和字段集合；不合并 query 状态。仅可复用经特征测试证明无行为差异的文本规范化辅助函数。
7. `src/composables/useGlobalShortcuts.ts::onKeydown()`
   - 裸 `/` 遇隐藏导航时按已冻结交互先打开并 `nextTick` 聚焦，或打开 CommandPalette；不得聚焦屏外元素。
8. `src/components/capability/badges/StateBadge.vue`
   - 导出/复用五态元数据或新增 compact variant，保持现有完整 badge 行为。
9. `src/components/capability/badges/MethodBadge.vue`
   - 新增 compact variant，保留现有默认 52px 形态兼容其他页面。
10. `src/styles/tokens.css`
    - 拟新增导航宽度、组/模块/能力行高、移动触控与缩进 token；light/dark 颜色用现有语义 token。
11. `README.md`
    - 更新菜单/快捷键行为；将 80→实际生成值的文档漂移一并修正时注明以 catalog 为准。

### 拟新增文件

- `src/navigation/navigationModel.ts`：`NavigationModel`、`NavigationGroup`、`NavigationModule`、`NavigationCapability` 与 `buildNavigationModel()`、matcher（名称可调整）。
- `src/navigation/navigationModel.test.ts`
- `src/components/layout/navigation/NavGroupSection.vue`
- `src/components/layout/navigation/NavModuleRow.vue`
- `src/components/layout/navigation/NavCapabilityRow.vue`
- `src/components/layout/navigation/NavEmptyState.vue`
- `src/composables/useNavigationDrawer.ts`：拟新增移动抽屉壳层 composable，组合现有 `useFocusTrap()`，负责 Esc、初始焦点、关闭后 `nextTick` 归还菜单按钮；禁止 SideNav/子行重复监听。
- `src/components/layout/SideNav.test.ts`
- `src/components/layout/AppHeader.test.ts`
- `src/App.test.ts`
- `src/stores/ui.test.ts`
- `src/config/moduleGroups.test.ts`
- `src/composables/useGlobalShortcuts.test.ts`

### 默认不修改、必须回归

`router/index.ts`、`types/catalog.ts`、`capabilities.yml`、`public/catalog.json`、`scripts/gen-catalog.mjs`、`stores/catalog.ts`、`stores/favorites.ts`、`ModuleHost.vue`、Breadcrumb、DensityToggle、全部模块视图、Vite/Vitest、Docker/nginx/compose、所有 Java 模块。

## 七、数据库、接口、配置、消息结构变更

- 数据库：无表/索引/数据迁移。
- 业务接口与 URL：无变更；不新增后端请求。
- Catalog 接口/类型/YAML/生成脚本：无变更。
- 消息/Kafka/outbox/protocol DTO：无变更。
- 服务配置、环境变量、端口、部署：无变更。
- 浏览器本地配置：首发沿用现有三个 key，仅加强读取校验；不保存敏感数据。

## 八、分阶段实施步骤、依赖与完成标准

### 阶段 1：数据结构与领域模型

依赖：冻结 SideNav 与 CommandPalette 现有搜索特征、active 规则和待验证产品选择。

任务：先分别冻结 SideNav substring+description 与 CommandPalette fuzzy 的搜索特征；实现纯模型类型、构建函数、SideNav 搜索匹配、Other fallback、收藏副本、目标 current/祖先标记；集中五态与安全图标元数据；不改 catalog schema。全树只有目标链接可有 `aria-current="page"`，组/模块祖先只标 `isAncestor` 并强制展开，收藏副本不得有 `aria-current`。

完成标准：输入不被修改；真实 9/82、空/未知/长标题 fixture 全通过；排序、搜索、唯一 active、当前祖先和失效收藏均有纯函数断言；无后端/契约变更。

### 阶段 2：核心业务逻辑

依赖：阶段 1 纯模型稳定。

任务：在 ui store 增加显式 mobile/desktop action，并接管原 SideNav 的 `showcase.navGroups` 严格校验/读写；实现当前 route 强制可见、搜索临时展开/清空恢复、断点状态同步；决定裸 `/` 行为（推荐：隐藏时打开对应导航后聚焦；若焦点/抽屉冲突则打开 CommandPalette）。首发禁止新增 v1 key。

完成标准：合法旧 key 兼容；坏 JSON/数组/错类型/异常不阻断；重复 set 幂等；resize 不产生 scrim 残留；任何当前深链均可见且不改用户偏好。

### 阶段 3：接口与适配层

这里的“接口”是前端组件/壳层适配，不是后端 API。

依赖：阶段 1/2。

任务：实现四个子组件；SideNav 改为编排容器；拟新增 `useNavigationDrawer.ts` 并由 App 壳层统一接入移动 focus trap、inert、scrim、正确 ARIA/恢复入口；SideNav 增加移动端可见关闭按钮作为 trap 内操作。CommandPalette/History/Shortcuts 打开前关闭抽屉。接入 compact badge、导航 token；CommandPalette 只复用五态等无行为差异元数据，不共享 matcher；不动 router/catalog。对不支持 inert 的目标浏览器，采用 `aria-hidden` + focus trap 的降级并列入实测。

完成标准：所有既有链接保持；admin 三处一致；全树仅目标链接一个 `aria-current=page`，祖先仅 `isAncestor`；隐藏区不可 Tab；移动关闭按钮/Esc/scrim/导航关闭并归还焦点；所有全局浮层与移动抽屉互斥；主题/密度/长标题无结构问题。

### 阶段 4：测试

依赖：阶段 3 功能完成。

任务：补模型搜索特征、SideNav、子组件、App、AppHeader、ui、moduleGroups、global shortcuts 与 drawer focus；运行全量测试/type-check/build；Chrome/Firefox/Safari 进行 320/768/1023/1024/1440、200% zoom、键盘、主题/密度/reduced-motion/长列表视觉回归并留截图。

完成标准：`npm test`、`npm run type-check`、`npm run build` 全绿；关键矩阵 100%；无 P0/P1、键盘陷阱、双 focus trap、权限漂移、多个 `aria-current`、不可恢复菜单；三浏览器清单通过。

### 阶段 5：文档与最终检查

依赖：阶段 4 通过。

任务：更新 README 行为与快捷键；记录最终 token/断点/视觉基线、人工回归截图和未解决待验证项；核对 git diff 仅含授权范围；核对生成物策略，禁止手工只改 `public/catalog.json`。

完成标准：另一个开发者可按文档复现；变更清单与实际一致；数据库/API/消息“无变更”已复核；回滚步骤演练；无业务代码范围外修改。

## 九、测试方案

自动：模型表驱动；两种现有搜索 matcher 分别做特征测试；SideNav memory router + Pinia；子组件 props/emits/ARIA；App/AppHeader 断点和移动 focus；ui storage/幂等/浮层互斥；global shortcuts 隐藏态；权限四态三处一致；现有 guard/permission/catalog/module views 回归。

人工：真实浏览器验证媒体查询、层叠、滚动、footer、触控、focus-visible、焦点隔离/归还、200% zoom、对比度、长标题/100+ 项、主题/密度/reduced-motion。详细矩阵和命令见 `test-plan.md`。

## 十、风险、监控、灰度与回滚

### 风险与缓解

- 权限漂移：canAdmin 保持单一来源，三处集成矩阵阻断发布。
- 隐藏焦点/移动焦点冲突：App 壳层 inert + focus trap；与 CommandPalette/History/Shortcuts 互斥回归。
- 当前项隐藏/双 current：模型保证全树仅目标链接一个 `aria-current=page`；组/模块仅 `isAncestor`，收藏副本不 current；route name 而非“无 moduleId”判断总览。
- storage 导航不可恢复：沿用旧 key、严格校验、显式 set、桌面按钮常驻。
- 搜索漂移：两种现有 matcher 各自冻结，不共享算法/字段/query；只共享无行为差异的规范化和状态元数据；统一算法另行评审。
- 断点挤压：1023/1024/1440 真实浏览器门禁；最终尺寸待视觉验证。
- 性能：O(82) 足够，不引入虚拟滚动；避免每子组件重复过滤和多层 blur。
- 图标安全：不使用 `v-html`/远程内容，只用受控本地 SVG或安全文本 fallback。

### 监控

本次无服务端运行指标。发布后监控使用前端可观察信号：路由导航是否成功、前端异常/白屏（若现有部署有日志平台则接入，具体平台待验证）、人工 smoke、支持反馈。不得为本任务擅自新增用户行为采集。localStorage 解析异常应静默降级，不记录敏感值。

### 灰度

1. 开发环境真实 catalog 与四种身份验证。
2. 测试/预发布环境部署新静态包，冻结截图基线。
3. 小范围内部用户验证常用任务；若部署系统支持静态包分流则使用，否则分环境逐级发布。运行时 flag 当前不存在，不得写成既有能力。
4. 通过门禁后全量发布。

### 回滚

回退到上一版前端静态构建产物；无需数据库、消息或服务回滚。因首发保留旧 localStorage key，旧包可直接读取；若实现中违背计划新增 key，也必须保留旧 key，确保旧包无害忽略新 key。回滚后执行总览、模块深链、admin Bearer、API Key 覆盖和移动抽屉 smoke。

## 十一、最终验收清单

- [ ] 当前 catalog 的全部模块/能力和未知模块 fixture 可达。
- [ ] 总览、admin、模块、能力当前态唯一，祖先可见。
- [ ] 搜索字段、模块命中、零结果、清空恢复符合规则。
- [ ] 收藏失效安全，重复项不产生第二主 active。
- [ ] 管理入口在 SideNav/AppHeader/CommandPalette 三处一致。
- [ ] 关闭/折叠菜单不可 Tab；桌面永远可恢复。
- [ ] 移动抽屉可由按钮、scrim、Esc、导航关闭并归还焦点。
- [ ] 五态非纯颜色；长标题有完整名称；移动触控尺寸合格。
- [ ] 1023/1024、五个 viewport、light/dark、两密度、reduced-motion、200% zoom 通过。
- [ ] storage 合法/畸形/异常通过，未存敏感数据。
- [ ] router/catalog/API/数据库/消息/部署无变更。
- [ ] `npm test`、`npm run type-check`、`npm run build` 全绿，三浏览器人工清单通过。
- [ ] 灰度与静态包回滚已演练，README/视觉基线更新。

## 十二、实施前必须确认

视觉基调与图标方案、菜单最终宽度、隐藏侧栏时 `/` 行为、模块展开是否持久化、目标浏览器/截图基线。若这些未确认，不阻塞阶段 1 的模型/特征测试，但阻塞阶段 3 的最终视觉定稿。

---

## 十三、跨模型复核记录（Claude / Opus，2026-07-13）

对照真实仓库逐条核验，**未发现事实错误或调用链遗漏**，修复方案逐条成立：

- ✅ 9 模块 / 82 能力 / catalog v1 —— `public/catalog.json` 实测一致。
- ✅ `Module.icon?`（`types/catalog.ts:81`）存在但 9 模块均未提供，侧栏用本地 `MODULE_ICON` emoji 兜底。
- ✅ 总览双 active、移动端 ARIA 错绑、隐藏导航可 Tab、`MethodBadge:52px`、`StateBadge` META 未导出、CommandPalette `fuzzy()` vs SideNav substring、README「80 条能力」漂移 —— 全部属实。
- ✅ ui store 方法调用方仅 `App.vue`/`AppHeader.vue`/`SideNav.vue`（均在改动集内），保留兼容出口 + 新增显式 action 不波及其它文件。

**复核补充（比原文更精确）：** 桌面折叠恢复入口问题实际更严重 —— `.header__menu` 桌面 `display:none`（仅 ≤1023px 显示），故桌面折叠开关**当前根本无法从 UI 触发**，不只是"折叠后无法恢复"。计划项 3「菜单按钮桌面可见且可恢复」正好覆盖此修复，方案无需改动。

**⚠️ 需用户拍板的取向问题（唯一保留项）：**
用户诉求核心是"**不好看、不够直观 → 重新设计**"，偏**视觉/观感**。而本终选方案（B 骨架 + A 交互）实质是**架构重构 + 正确性修复 + 轻度视觉打磨**：保留现有三级树与现有视觉语言（毛玻璃、emoji、288px 宽），把真正的"视觉重设计"（图标体系、层级密度、配色处理、是否降为两级结构等）**全部推迟**到"实施前必须确认 / 阻塞阶段 3 视觉定稿"。即：本计划能显著提升**可理解性/可访问性/可维护性**，但对用户直接想要的"**好看**"只做保守增量。

处理方式：在阶段三向用户明确此取向，由用户在"保守（照当前计划）"与"更大胆的视觉重设计（现在就定视觉方向）"之间选择；用户选定后再据以调整 FINAL_PLAN 的阶段 3 视觉范围。**在用户批准前不改任何业务代码。**

---

## 十四、视觉基线定稿（方案③ 工作台/彩色编码，已选定 2026-07-14）

用户取向选择：**「计划 + 真正视觉重设计」**（保留 B+A 架构骨架，叠加实打实视觉重设计）。
视觉方向：先派 3 个 UI 设计 Agent 并行产出 3 套低保真方案（`mockups.html`，随规划留存），用户选定 **方案③：工作台 / 彩色编码**。

### 视觉基线（阶段 3 落地依据，已冻结）

1. **分组强调色系统**（颜色只点睛、不铺满）：
   - 对话与检索 = 蓝 `#2563eb`；智能体与编排 = 紫 `#7c3aed`；多模态 = 琥珀 `#d97706`；平台工程与互操作 = 青 `#0e7490`；平台管理 = slate `#475569`；收藏 = gold `#d97706`。
   - 每色派生三件套 `--g / --g-soft(10–13%) / --g-line(28–32%)`；用于图标 chip 底、组头小色条、计数徽章、能力左连接轨、选中 pill。
   - 强调色**只表分类不表状态**，与五态状态色语义不交叉。
2. **模块行 icon-first**：左侧 26×26 圆角色块 chip（组色柔和底 + 单色 inline SVG 线性图标），彻底替换随机 emoji；中文名（13/600）+ 英文副名（10.5，可截断）+ 计数 pill + caret。chip 同时作为「折叠为图标轨道」的锚点。
3. **组头**：小色条 + 加粗标签 + 右侧元信息（模块数 / 管理域标签）。
4. **五态状态点：颜色 + 形状双编码**（就绪=绿实心圆 / 降级=绿+琥珀警示环 / 需授权=琥珀菱形 / 未启=灰空心环 / 仅展示=红方块）+ 底部固定图例文字。颜色非唯一信息载体（可访问性硬约束）。
5. **侧栏宽度** `--sidenav-w` 定为 **280px**（现状 288，收窄 8）。
6. **深色主题**：基色令牌切换；强调色不变色相、柔和底透明度提到约 18–22%、主色整体提亮一档（如蓝→`#60a5fa`）以满足暗底对比；chip/色条/选中 pill 逻辑复用变量。
7. **实现要求**：图标一律受控本地 inline SVG，**禁止 `v-html`/远程内容**；分组色进 `tokens.css` 作为**追加**令牌，不改旧值；6 色为分类色上限，未来分组增多改「同色系深浅」而非继续加彩。
8. **落地位置**：分组→强调色的映射集中到 `config/moduleGroups.ts`（与 `GROUP_ORDER` 同源）；未知模块归 Other，用中性 slate。

### 「实施前必须确认」项 —— 已拍板默认值

- **隐藏侧栏时裸 `/` 行为**：桌面若 `navCollapsed` 隐藏，先展开侧栏再 `nextTick` 聚焦筛选框；移动端若抽屉关闭且存在焦点/浮层冲突，则打开 CommandPalette。绝不聚焦屏外元素。
- **模块展开是否持久化**：首发**内存态**（不新增 storage key，避免把视觉重设计与迁移风险绑定）；仅保留并加强现有 `showcase.navGroups`/`showcase.navCollapsed`/`showcase.favorites` 校验。
- **菜单最终宽度**：280px（见上）。
- **目标浏览器 / 截图基线**：Chrome / Firefox / Safari 最新版；viewport 320 / 768 / 1023 / 1024 / 1440 + 200% zoom；light/dark × comfortable/compact。

### 授权状态

用户已（1）选定「计划 + 视觉重设计」取向、（2）选定方案③ 为视觉基线（选择时的提问明确「据此定稿阶段 3 并开始实现」）。**据此视为批准进入阶段四实施**。实施仍严格分阶段 ①→⑤，每阶段编译+测试+`git diff`+更新 `IMPLEMENTATION_PROGRESS.md`。若实现中出现与计划不符，先分析原因再更新本文件。
