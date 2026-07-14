# 左侧菜单重设计：需求分析

## 1. 范围与事实基线

本次只重设计 `capability-showcase-frontend` 的左侧导航及其直接壳层联动，不修改业务服务。当前前端是 Vue 3、TypeScript、Pinia、Vue Router、Vite 的静态 SPA。菜单唯一实现为 `capability-showcase-frontend/src/components/layout/SideNav.vue`。

当前 `capability-showcase-frontend/public/catalog.json` 由 `capabilities.yml` 生成，实际包含 9 个模块、82 项能力：chat 10、rag 11、agent 18、tasks 8、analytics 3、workflow 7、multimodal 8、interop-eval 12、channel 5。`README.md` 所述“80 条”已经漂移，不能作为验收常量。

当前层级为：筛选框 → 总览 → 有权限的平台管理 → 有内容的收藏 → 语义分组 → 模块 → 能力 → 密度/目录版本。语义分组来自 `src/config/moduleGroups.ts`；模块顺序来自 `src/stores/catalog.ts::modules` 对 `Module.order` 的排序。

## 2. 用户与目标

- 日常试用者：能在不断增长的能力目录中理解分组、快速定位目标并辨认当前位置。
- 高频用户：保留收藏、折叠偏好、密度、快捷键等效率能力。
- Bearer `role-admin` 用户：清晰区分能力导航和平台管理，且三处管理入口权限一致。
- 小屏/触屏用户：能可靠打开、浏览、关闭抽屉，背景不可误操作。
- 键盘、读屏、色觉差异和 reduced-motion 用户：当前项、展开态、能力状态均可感知。
- 目录维护者：新增/未知模块不会消失，不能把 9/82 硬编码为上限。

目标是改善信息层级、扫读效率、视觉一致性、当前路径可见性、响应式行为和可访问性，同时建立可自动测试的导航边界。

## 3. 已确认业务规则

1. `capabilities.yml`/catalog 仍是模块与能力事实源；模块按 `Module.order`，未知模块经 `groupIdForModule()` 落入“其它”。
2. 保持 `/`、`/m/:moduleId`、`/m/:moduleId/:capId`、现有 `/admin/*` 路由契约。
3. `route.meta.public` 页面不显示壳层；admin/forbidden 的 `bypassCatalog` 行为必须保留。
4. 管理入口只使用 `usePermission().canAdmin`；其可见性不能替代 `router/index.ts::resolveRouteAccess()` 和后端授权。
5. API Key 覆盖 Bearer 或 RBAC 开关关闭时，SideNav、AppHeader、CommandPalette 都不得显示管理入口。
6. 普通能力不能因 `requiredScopes`、flag-off 或 display-only 被菜单静默隐藏；仍按既有五态诚实呈现。
7. 收藏只保存 capability id；失效 id 安全忽略，不保存请求、响应、凭证或身份。
8. localStorage 只保存非敏感 UI 偏好；缺失、畸形、读写失败必须降级而非阻断渲染。
9. 移动端点击导航目的地后关闭抽屉；折叠操作不得误触路由。
10. 保留 light/dark/system、comfortable/compact、`:focus-visible`、`prefers-reduced-motion` 和 backdrop-filter 降级。
11. 目录版本若展示，来自 `catalog.version`；未加载时使用明确占位。
12. 不破坏 `stores/ui.ts` 中命令面板、历史、快捷键浮层互斥规则。

## 4. 功能与体验需求

- R1：明确区分总览、平台管理、收藏、业务分组、模块、能力六种语义层级。
- R2：支持按既有字段 `Capability.title/id/path/description/tags` 与 `Module.title/id` 定位；提供无结果和清除反馈。
- R3：深链、刷新、前进后退后，当前能力及祖先可见；用户偏好不能把当前路径无提示隐藏。
- R4：总览、管理、模块、能力的 active 唯一且与路由语义一致；收藏副本不能产生第二个同强度 active。
- R5：保留 HTTP 方法与五态，但五态不得只依赖颜色、8px 圆点或鼠标 tooltip。
- R6：在 82 项能力规模下默认视图不过载，comfortable/compact 都无重叠和不可点击项。
- R7：桌面折叠与移动抽屉使用各自正确状态；开关的可见名称及 `aria-expanded` 与实际一致。
- R8：关闭或折叠的导航不可进入 Tab 顺序；移动抽屉支持背景隔离、Esc、焦点进入与归还。
- R9：侧栏隐藏时裸 `/` 不得把焦点送入屏外筛选框。
- R10：未知模块、空模块、失效收藏、超长中英标题、能力数 0/100+ 均有确定呈现。
- R11：新增视觉规则复用/扩展现有 token，浅深主题成对处理，不引入未经批准的远程图标或依赖。
- R12：搜索时的强制展开不写回持久偏好；清空后恢复搜索前状态。

## 5. 边界和失败场景

- catalog 为 loading/error/null；admin/forbidden 仍可进入。
- 模块标题命中而能力不命中、完全无匹配、中文输入、空白和大小写查询。
- 当前组被持久化折叠、当前模块被手动收起、能力在收藏和模块树重复出现。
- `showcase.navGroups` 是数组、非 boolean 值、坏 JSON，或 localStorage 抛异常。
- 普通 Bearer、admin Bearer、API Key 覆盖、登出/权限变化、RBAC kill switch 关闭。
- 320/768/1023/1024/1440px、旋转与 resize；桌面折叠后切小屏、抽屉打开后切桌面。
- Tab/Shift+Tab、Enter/Space、Escape、关闭后的焦点归还、200% zoom。
- light/dark/system、两种密度、reduced-motion、backdrop-filter 不可用。

## 6. 非目标

- 不修改后端、数据库、消息、业务 API、鉴权协议、五态定义或执行门禁。
- 不重做模块工作台、能力运行表单、响应查看器或管理 CRUD 主内容。
- 不新增能力、行为采集、服务端偏好、跨设备同步、微前端或路由体系重构。
- 不以隐藏入口代替安全控制。
- 不把修正 README 的 80→82 作为菜单重设计的业务前置；若实现时更新，仅属文档一致性修复。

## 7. 待验证事项

以下不是已确认规则，实施前必须冻结或做低保真验证：

1. 目标视觉基调、品牌稿、竞品参考、图标资产许可和客观成功指标。
2. 是否只允许视觉调整，还是允许改变语义分组/导航信息架构。
3. 桌面收起形态是完全隐藏、保留 icon rail，还是不可收起。
4. 模块默认展开策略、是否持久化模块展开状态。
5. 收藏位置、默认展开和重复当前项的视觉语义。
6. 侧栏 substring 与命令面板 fuzzy 是否必须统一；是否搜索管理入口。
7. 隐藏侧栏时裸 `/` 是先展开筛选还是改开命令面板。
8. emoji、catalog `Module.icon`、本地 SVG 三者的选择；当前 catalog 未提供 icon。
9. 菜单最终宽度、断点、是否需可拖拽（默认不做）。
10. 移动端 Esc/focus trap 的具体 WCAG 口径；本计划按 WCAG AA 方向验收。
11. 是否加入“最近访问”（当前只在 CommandPalette，默认不加入侧栏）。
12. 浏览器、OS、字体、viewport 和截图基线。

## 8. 验收标准

- AC1：当前真实 catalog 的 9 个模块、82 项能力均可浏览/筛选到达；未知模块 fixture 进入“其它”。
- AC2：`/` 仅总览 active；模块/能力深链显示唯一当前项和祖先；admin/forbidden 不误亮总览。
- AC3：历史折叠或手动状态不能隐藏当前路径；前进后退后状态正确。
- AC4：筛选覆盖既有字段；模块命中、能力命中和零结果均有反馈；清空恢复展开偏好。
- AC5：失效收藏不报错；收藏副本与原树不形成两个同强度 active。
- AC6：普通/API Key 用户三处无管理入口；admin Bearer 三处一致；路由/后端仍是最终授权。
- AC7：1023/1024px 两侧开关真实可用，ARIA 同步；resize 无残留 scrim 或不可恢复导航。
- AC8：隐藏导航不可 Tab；移动端遮罩、Esc、导航可关闭并归还焦点。
- AC9：五态具有非颜色线索和可访问名称；light/dark、两密度、reduced-motion 通过检查。
- AC10：空/未知/长标题/100+ 项不遮挡关键操作，完整名称可获得。
- AC11：合法旧偏好、缺失/畸形/不可读写 storage 都安全；若换 schema，有兼容与回滚规则。
- AC12：新增导航自动测试，且 `npm test`、`npm run type-check`、`npm run build` 全通过，三浏览器人工回归通过。
