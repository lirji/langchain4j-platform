# capability-showcase-frontend 移动端适配 — 决策记录 + 实施计划（rev.2）

> 目标仓库: `/Users/liruijun/personal/LLM/langchain4j-platform/capability-showcase-frontend`
> （Vue3 + TS + Vite + Pinia，无第三方 UI 库，token 设计系统，vitest+jsdom）
> 状态: **已通过独立评审修订（rev.2），待批准，未改任何代码**
>
> 修订记录 rev.2（独立评审 4 blocking + 10 建议全部吸收）：
> B1 pointer:coarse 改用分组选择器压制密度覆盖；B2 触控验收分级 + 小操作按钮/Boolean/File 字段单列补规则；
> B3 16px 防缩放由 ≤640 改挂 pointer:coarse；B4 header__pop 保留 min-width 语义。
> 另：hover 揭示清单 7+→实为 3 处；删除 no-op 工作项（brand 文字 ≤1023 已隐藏）；补 Breadcrumb 归一风险；
> 补 --card-min 300→260 动机（修 320px 溢出）；补 CallbackView dvh；测试文件数 66→63；
> 字段组件 .form-control 覆盖 7→5/7；useBreakpoint 测试提前到 Phase 0；scrollIntoView 加可选调用防御。

---

# 第一部分 · 勘察结论摘要（5 方向共识）

1. **不是从零适配**：平板级响应式已完成——≤1023 抽屉导航 + scrim + 焦点陷阱 + inert、六大双栏视图塌单列、⋯ 溢出菜单、`--page-px` token 响应式覆盖、导航行 44px 触控。缺的是 **≤640 手机级精修**。
2. **最高杠杆点**：全部 82 个能力页收敛在 `CapabilityRunner` + `DynamicForm`（7 个字段组件中 **5 个**走全局 `.form-control` 类；Boolean/File 两个另行处理）——改一处覆盖全站绝大部分表单与执行界面。
3. **三大 token 缺口**：断点 token（1023/860/767/640/480 混用无真源）、safe-area（全库无 `env(safe-area-inset-*)`）、触控尺寸（仅 `--nav-touch-min:44px` 且限导航行，`--control-h` 36/30px、compact 密度 30/26px 均低于 44px）。
4. **触屏交互硬伤**：hover-only 揭示共 **3 处**（chat 消息操作 `.msg__actions`、能力卡收藏星 `.card__fav`、历史抽屉条目操作 `.entry__actions`，均 `opacity:0` → `:hover`）在触屏不可达；另有大量小操作按钮（`.msg__act`/`.entry__btn` 等 padding 2px 8px）远低于触控尺寸；全站无 `(hover)/(pointer)` 能力查询。
5. **iOS 专项坑**：全站输入 ≤14px → Safari 聚焦自动放大；Login/Register/Callback/CommandPalette 裸 `100vh`/`15vh`；chat composer `sticky bottom:0` 无软键盘/safe-area 处理；`background-attachment:fixed` 伤性能。
6. **测试盲区**：jsdom 无 `matchMedia` → `useIsDesktop` 恒回退桌面，**所有移动分支从未被测试执行**（存量 63 个 `*.test.ts`）；`css:false` → 布局断言不可行；无 e2e 基建。
7. **使用场景**：内部开发者试用台（README/docs 明证）→ 适配深度定位「能用(usable)」而非「精致(polished)」。

---

# 第二部分 · 决策记录

## D1. 总路线：纯 CSS 为主 + 受控 JS 增强（选 A+B 收敛版，弃 C）

| 路线 | 结论 | 理由 |
|---|---|---|
| A. 纯 CSS 响应式（统一断点 + 逐组件 @media） | **主体** | 与现状同构（抽屉/token 覆盖/触控高度全是纯 CSS 做的）；零新依赖；vitest 免疫（不破坏现有 63 个测试文件的 DOM 断言） |
| B. 泛化 `useBreakpoint` + 条件渲染 | **受控采纳** | `useIsDesktop` 本就是单档 useBreakpoint，泛化是顺延。**约束：条件渲染只用于壳层/行为分支（滚动、抽屉），内容区一律 CSS 适配**，避免断点切换销毁组件丢状态 |
| C. 引入 vant / 全站重构 | **否决** | 与自建 token 设计系统（玻璃拟态/双主题/密度/强调色）正面冲突；可替换面小（7 字段+1 运行器）破坏面大（大量 DOM 断言测试）；违反零运行时依赖部署哲学；放弃「改 runner 一处 82 页全生效」的架构优势 |

## D2. 断点治理：canonical 两档 1024 / 640

- **1024**（`min-width:1024`=桌面，`max-width:1023`=平板及以下抽屉+单列）与 **640**（手机精修）为唯一合法宽度断点；能力查询（pointer/hover）另算。
- 离群值归一：`Breadcrumb.vue` 767→1023；`LoginView/RegisterView` 860→1023、480→640（副作用见 §10 风险表）。
- 断点常量以 **TS 为单一真源**（`useBreakpoint.ts` 导出 `BREAKPOINTS`），CSS 侧在 `tokens.css` 顶部注释声明同值，code review 约定对齐。**不引入 postcss-custom-media**。
- 768–1023 平板中间态维持现状（抽屉+单列），不做专门层。

## D3. 触控与密度：能力查询而非断点（rev.2 修正胜出机制）

- 用 `@media (pointer: coarse)` 抬升 `--control-h`→44px、`--control-h-sm`→38px。**胜出机制（B1 修正）**：密度覆盖选择器 `:root[data-density='compact']` 特异性 (0,2,0) 高于 `:root`，纯声明顺序无效；必须用**分组选择器**同时压制两档：
  ```css
  @media (pointer: coarse) {
    :root, :root[data-density='compact'] { --control-h: 44px; --control-h-sm: 38px; }
  }
  ```
  （不能只写 `:root[data-density]`：`applyDensity()` onMounted 才写 dataset，首帧属性不存在。）
- **触控目标分级（B2 修正）**：token 抬升只覆盖消费 `--control-h` 的 `.btn`/`.form-control`/图标按钮；自定义小操作按钮（`.msg__act`、`.entry__btn` 等）与不走 `.form-control` 的 Boolean/File 字段**单列文件级规则**（coarse 下 `min-height:32px` + 间距），验收按两级标准（§9）。
- 用 `@media (hover: none)` 把 3 处 hover 揭示改为常显（逐组件 scoped 内补；Vue scoped 样式完全支持 media query）。
- 副作用声明：触屏笔记本会得到更大控件与常显操作——可接受。

## D4. iOS 输入缩放：`pointer: coarse` 下输入字号 16px（rev.2 改挂载条件）

**B3 修正**：缩放问题根因是触屏 Safari 而非视口宽度。改为 `@media (pointer: coarse)` 下 `.form-control` 及各视图自有输入提到 16px——平板（641-1023）全覆盖，桌面窄窗口零影响（原 ≤640 方案会让桌面用户缩窗时字号跳变，且平板区间漏防）。G4 措辞同步：「coarse pointer 设备上输入不触发聚焦缩放」。

## D5. 宽表格：保留横向滚动，不做卡片化（对应「能用」定位）

`ResultTable`/JSON 面板/curl 块维持容器内 `overflow-x:auto`，仅加滚动可见性提示（边缘渐隐）。**不重构** ResultTable。

## D6. 范围裁定（默认假设，批准时可改）

| # | 问题 | 默认假设 |
|---|---|---|
| 1 | 目标最小宽度 | **375px 可用，320px 不崩**（不横向溢出、可完成操作） |
| 2 | AUTH_MODE | 三态（apikey/oidc/dual）登录页**全部**适配，不偏科 |
| 3 | 适配深度 | 能用不精致；不做底部导航/手势/下拉刷新 |
| 4 | 宽表格 | 横滚保留（D5） |
| 5 | hover/快捷键触屏替代 | 3 处 hover 揭示常显**纳入**；`⌘⏎` 等快捷键靠既有按钮兜底，不加手势 |
| 6 | 能力裁剪 | **不裁**，82 能力手机全量可达 |
| 7 | 44px 触控 | 纳入，按 D3 两级标准 |

## D7. 明确推迟项（本期不做，单列 backlog）

- **Playwright e2e**：移动回归当前零护栏是事实，但引入 e2e 基建是独立决策；本期用「vitest matchMedia 分支测试 + 手工真机清单」兜底。
- **SSE 移动后台中断治理**（`visibilitychange` 检测/流超时/apikey 模式重订）：真实缺陷但属行为层改造，与布局适配解耦。
- **FileField `capture` 拍照直启**：需扩 `ParamSpec` 类型 + capabilities.yml，属目录 schema 变更。
- 底部导航 MobileNav、PWA、字号 rem 化（无障碍缩放）、微信内置浏览器 OIDC 专项。

---

# 第三部分 · 实施计划

## 1. Goals / Non-goals

**Goals**
- G1 375px 视口下全站无页面级横向溢出（宽内容在自身容器内横滚），320px 不崩。
- G2 触屏可达性：3 处 hover 揭示常显可点；coarse 下主控件 ≥44px、次级行内操作 ≥32px（两级标准）。
- G3 六大核心流手机可完成：登录（三态）、总览浏览、导航进模块、能力执行（含 API Key direct mode）、chat 流式对话、RAG 上传/检索、任务查询。
- G4 iOS Safari 专项：**coarse pointer 设备**输入不触发聚焦缩放、软键盘不吞 composer、dvh 全覆盖、safe-area 生效。
- G5 断点归一到 1024/640（TS 单一真源），`useIsDesktop`→`useBreakpoint` 泛化且首帧同步初始化。
- G6 桌面（≥1024 且 fine pointer）**零视觉变化**。
- G7 新增移动分支的 JS 逻辑有 vitest 覆盖（matchMedia stub）；存量 63 个测试文件 + type-check 全绿。

**Non-goals**：D7 全部；不改任何后端/API；不改 capabilities.yml/目录生成链；不重构 ResultTable；不动 CSP/部署链。

## 2. 路由与页面流（全部不变，仅呈现层）

5 条静态路由不增不减。各流手机形态：登录=单栏卡片（≤1023 藏品牌区）；主控制台=顶栏☰抽屉（既有）；能力页=请求表单在上/响应在下，**执行后自动滚到响应区**（新增小行为）；chat=模式 chips 横滚一行 + 消息流 + 底部 composer（safe-area）；rag=单列堆叠（文档库→检索台→入库）。

## 3. 组件树（无新增组件；标注改动类型）

```
App.vue                       [CSS: bg-attachment、safe-area | JS: useBreakpoint 换源]
├─ AppHeader.vue              [CSS: popover 宽度钳制(保 min-width 语义)；brand 文字≤1023 已隐藏→仅核验]
│  ├─ AuthControl.vue         [CSS: ≤640 chip 压缩、下拉/警告条宽度钳制]
│  ├─ ApiKeyInput.vue         [CSS: ≤640 44vw→60vw]
│  ├─ Breadcrumb.vue          [CSS: 767→1023 归一]
│  ├─ ThemeToggle/DensityToggle [CSS: 触控尺寸走 token，无独立改动]
├─ SideNav.vue + navigation/* [不动（已达标）]
├─ ModuleHost → 9 专用视图     [CSS: 逐视图 ≤640 精修（§6 Phase 4）| chat 另有 JS]
│  └─ CapabilityRunner.vue    [CSS: ≤640 动作条/curl | JS: 执行后 scrollIntoView?.(phone)]
│     ├─ DynamicForm/FieldWrapper [CSS: 间距]  fields/Boolean+File [CSS: coarse 触控规则单补]
│     └─ ResponseViewer/SseConsole/SseStageConsole/SseEventTimeline [CSS: 工具条换行/字号]
├─ CommandPalette.vue         [CSS: 15vh→dvh 化、≤640 顶距/高度]
├─ HistoryDrawer.vue          [CSS: ≤640 全宽 + safe-area + hover:none 常显 + entry__btn 触控]
├─ ShortcutsDialog/SessionExpiredDialog [CSS: dvh/内边距]
└─ 公开页 LoginView/RegisterView/CallbackView [CSS: 断点归一、100dvh、输入 16px(coarse)]
composables: useIsDesktop.ts → useBreakpoint.ts（useIsDesktop 保留为薄包装，3 个消费方零改动）
styles: tokens.css（断点声明注释、safe-area token）+ base.css（coarse 分组选择器、≤640 token 覆盖、16px）
```

## 4. 状态与边界情况

- 断点切换（旋屏/分屏）：`App.vue` 既有 `watch(isDesktop)` 清抽屉逻辑保留；`useBreakpoint` 改为 **setup 同步初始化**（修复首帧按桌面渲染缺陷），jsdom 无 matchMedia 时仍回退 desktop（测试基线不变；本项目无 SSR）。
- 软键盘：composer/浮层用 `dvh` + `safe-area-inset-bottom`；不引入 visualViewport 监听（D7）。chat 自动滚动加「用户离底 >80px 即暂停、回底恢复」守卫（jsdom 下 scrollTop/scrollHeight=0 判定"在底部"，存量测试行为不变——评审已核）。
- 320px：`--sidenav-w:280px` 抽屉近全宽（可接受）；气泡 `max-width:82%` ≤640 放宽到 92%；`--card-min` 300→260 正是修复 320px 下 `minmax(300px,1fr)` 必然溢出（内容区约 288px）。
- compact 密度 × coarse pointer：分组选择器保证触控端胜出（D3）。
- 深色/浅色、`data-accent`、reduced-motion：所有新样式只消费既有 var()，不硬编码色值。
- 横屏：不做 orientation 专项，依赖 dvh + 单列布局自然适应。

## 5. API 契约

**零变更。** 纯前端呈现层改造：不改请求路径/头（X-Api-Key 注入链不动）、不改 catalog schema、不改 OIDC 配置、不改 nginx CSP。

## 6. 文件级改动清单

**Phase 0 基础层（2 文件 + 2 新增，含测试）**
| 文件 | 改动 |
|---|---|
| `src/composables/useBreakpoint.ts`（新增） | `BREAKPOINTS={desktop:1024, phone:640}` 常量 + `useBreakpoint()` 返回 `{isDesktop, isPhone}`；setup 同步初始化；无 matchMedia 回退 desktop |
| `src/composables/useBreakpoint.test.ts`（新增，同 commit） | 三态（desktop/tablet/phone）+ 无 matchMedia 回退 + 监听变更（用 `src/test/viewport.ts` stub，见 Phase 5——stub 工具随本 Phase 先建） |
| `src/composables/useIsDesktop.ts` | 改为 useBreakpoint 的薄包装（导出签名不变，3 个消费方零改） |
| `src/styles/tokens.css` | 顶部断点声明注释（1024/640 与 TS 对齐）；新增 `--safe-top/--safe-bottom: env(safe-area-inset-*)` |

**Phase 1 全局样式层（1 文件）**
| `src/styles/base.css` | ① **B1 修正版** coarse 覆盖：`@media (pointer:coarse){ :root, :root[data-density='compact'] { --control-h:44px; --control-h-sm:38px } }`；② **B3 修正版**：同 media 块内 `.form-control { font-size:16px }`（mono 变体同步）；③ ≤640 追加 token 覆盖：`--fs-display:28px`、`--card-min:260px`、`--section-gap` 收窄（GenericModuleView/OverviewView 卡墙经此 token 生效，无须改文件）；④ 玻璃 blur 在 coarse 降档（性能） |

**Phase 2 壳层与公开页（7 文件）**
| 文件 | 改动 |
|---|---|
| `src/App.vue` | `background-attachment: fixed`→`scroll`（≤1023）；抽屉底部 `padding-bottom: var(--safe-bottom)` |
| `src/components/layout/AppHeader.vue` | **B4 修正版**：`.header__pop` `min-width:200px`→`min-width: min(200px, calc(100vw - 2*var(--page-px)))`（保下限语义，桌面零变化）；brand 文字仅核验（≤1023 已隐藏，无工作项） |
| `src/components/layout/AuthControl.vue` | ≤640 身份 chip 压缩（藏租户段留用户+徽章）；下拉/警告条宽度钳制 `max-width: calc(100vw - 2*var(--page-px))` |
| `src/components/layout/ApiKeyInput.vue` | ≤640 `max-width:44vw`→`60vw` |
| `src/components/layout/Breadcrumb.vue` | 断点 767→1023（行为变化入 §10 风险表） |
| `src/modules/auth/LoginView.vue` + `RegisterView.vue` | 断点 860→1023、480→640；`min-height:100vh` 补 `100dvh`；`.lp-input` 16px 挂 coarse；`lp-demo-desc` nowrap→可换行 |
| `src/modules/auth/CallbackView.vue` | `min-height:100vh` 补 `100dvh`（评审补项） |

**Phase 3 高杠杆共享组件（8 文件）**
| 文件 | 改动 |
|---|---|
| `src/components/capability/CapabilityRunner.vue` | ≤640：动作条主按钮全宽、次要按钮换行；curl 块字号/断行微调。JS：执行完成且 `isPhone` 时响应区 `scrollIntoView?.({behavior:'smooth'})`（可选调用防御 jsdom） |
| `src/components/form/DynamicForm.vue` / `FieldWrapper.vue` | ≤640 字段纵距、label 与徽章换行 |
| `src/components/form/fields/BooleanField.vue` / `FileField.vue` | **B2 补项**：不走 `.form-control`，coarse 下单补触控规则（checkbox 点击区 ≥32px；FileField 提示文案「点击或拖拽」按 hover:none 切「点击选择文件」） |
| `src/modules/_shared/ResultTable.vue` | 容器加横滚边缘渐隐提示（纯 CSS）；≤640 `td max-width:32ch`→`24ch` |
| `src/components/capability/SseConsole.vue`（+Stage/Timeline） | 工具条 ≤640 换行；`min-width:120px` 标签钳制（**无 hover 揭示项**——评审修正） |
| `src/components/common/CommandPalette.vue` | `padding:15vh`→`10dvh`、面板 `max-height:70vh`→`70dvh`，≤640 顶距 `--space-4` |
| `src/components/common/HistoryDrawer.vue` | ≤640 `width:100vw` + safe-area；`.entry__actions` hover:none 常显；`.entry__btn` coarse `min-height:32px` |
| `src/components/common/ShortcutsDialog.vue` / `SessionExpiredDialog.vue` | vh→dvh、≤640 内边距 |
| `src/components/capability/CapabilityCard.vue` | `.card__fav` hover:none 常显 |

**Phase 4 模块长尾（9 视图，chat 优先）**
| 文件 | 改动 |
|---|---|
| `src/modules/chat/ChatConsoleView.vue` | ① `.msg__actions` hover:none 常显 + `.msg__act` coarse `min-height:32px`；② 模式 chips ≤640 单行横滚；③ composer `padding-bottom: var(--safe-bottom)`、textarea 16px(coarse)、param 输入 `width:140px`→`flex:1 1 120px`；④ JS：自动滚动「离底 >80px 暂停、回底恢复」守卫；⑤ 气泡 ≤640 `max-width:92%` |
| `src/modules/rag/RagWorkspaceView.vue` | `.rag__params repeat(3)`→≤640 `1fr`；文档行/分页 ≤640 换行布局 |
| `src/modules/tasks/AsyncMonitorView.vue` + `AsyncTaskTimeline.vue` | 时间线标签 max-width ≤640 放宽换行（**无 hover 揭示项**——评审修正） |
| `src/modules/OverviewView.vue` | quick chips 换行校验；`.ov__quick-link` 补 `:active` 态（卡墙经 --card-min token 生效） |
| `src/modules/agent/AgentLabView.vue` | `.ag__adv-field--wide min-width:220px` ≤640 放开 |
| `src/modules/workflow/WorkflowDeskView.vue` | `.wf__comment min-width:160px` ≤640 放开 |
| `src/modules/interop/InteropEvalView.vue` | `.ie__ret-params repeat(2)` ≤640 `1fr` |
| `src/modules/analytics/AnalyticsLabView.vue` | JSON 面板 ≤640 max-height 微调（表格靠 ResultTable 共享改动） |
| `src/modules/channel/ChannelConsoleView.vue` / `multimodal/MultimodalConsoleView.vue` | ≤640 校验性精修 |

**Phase 5 测试补齐（1 新增 + 更新若干；stub 工具已随 Phase 0 建）**
| 文件 | 改动 |
|---|---|
| `src/test/viewport.ts`（Phase 0 建） | `stubMatchMedia({desktop, phone})`：可控 `matches` + `addEventListener`，测试后还原 |
| `src/App` 相关测试 | 用 stub 补移动分支：抽屉开合、navHidden、断点切换清理 |
| `src/components/layout/AppHeader.test.ts` | 补移动分支用例：☰ → `ui.toggleSidebar` |
| `src/components/capability/CapabilityRunner` 测试 | 补 phone 下执行后 scrollIntoView 调用断言（stub `Element.prototype.scrollIntoView`） |
| `src/modules/chat/ChatConsoleView.interaction.test.ts` | 补自动滚动暂停/恢复守卫用例（mock scrollTop/scrollHeight） |

## 7. 按依赖排序的实施步骤

1. **Phase 0**：viewport stub + useBreakpoint（含测试）+ useIsDesktop 包装 + tokens 断点声明/safe-area → `npm test` 存量全绿 + 新测试绿。
2. **Phase 1**：base.css 全局覆盖（coarse 分组/16px/640 token）→ DevTools 375px 全站速览。
3. **Phase 2**：壳层 + 公开页 → 手机三态登录流可走通。
4. **Phase 3**：共享组件（Runner 优先）→ 任一能力页 375px 可执行、响应可读。
5. **Phase 4**：模块长尾，顺序 chat → rag → tasks → 其余（按使用频度）。
6. **Phase 5**：剩余测试补齐 + 验收清单统一跑。
每步一个 commit（Conventional Commits，`feat(frontend): ...`），可独立 revert。

## 8. 测试策略

- **vitest 层**：useBreakpoint 三态、App/AppHeader 移动分支（stubMatchMedia）、chat 滚动守卫、runner scrollIntoView——全部是 JS 逻辑分支。CSS/@media 效果 jsdom 测不了（`css:false`），不做伪断言。
- **存量保护**：改动优先纯 CSS（对 63 个现有测试文件免疫）；DOM 敏感点（chat chips 容器、AuthControl chip 压缩）如需改结构，同 commit 更新对应选择器断言。
- **手工清单**（DevTools device toolbar 375×667 / 320×568 / 768×1024 / 横屏）：逐流走查 G3 六大流 + 每模块着陆页无横向溢出 + 560-640 区间卡墙观感（--card-min 260 可能出双窄列）。
- **真机 iOS Safari**（`npm run dev -- --host` 局域网临时起，不改 vite 配置落库）：输入聚焦不缩放、软键盘下 composer 可见、抽屉滚动不穿透、OIDC 整页跳转回环（需可达 Casdoor）。
- **门禁**：`npm test` + `npm run type-check` 全绿。

## 9. 验收标准

1. 375px：全站每页无 body 横向滚动；宽内容（表格/代码/curl/JSON）在自身容器内横滚且有视觉提示。
2. 触屏（DevTools touch 模拟）：**两级标准**——消费 `--control-h` 的主控件（`.btn`/表单输入/图标按钮）≥44px；自定义次级行内操作（`.msg__act`/`.entry__btn`/checkbox 点击区）≥32px 且相邻间距 ≥8px；3 处 hover 揭示无 hover 即可见可点。
3. 六大核心流在 375px 全部可完成（含 apikey 与 oidc 两种登录、危险能力二次确认、SSE 流式+停止）。
4. iOS 真机：任一输入框聚焦不触发页面缩放；chat 键盘弹出后发送按钮可见可点。
5. 桌面（≥1024、fine pointer）：改动前后视觉零差异（抽查总览/chat/rag/runner 四页）。
6. 断点审计：`grep -rn '@media (max-width' src` 仅剩 1023/640（能力查询/reduced-motion/color-scheme 除外）。
7. `npm test`（63+ 新增）与 `npm run type-check` 全绿。

## 10. 风险与回滚

| 风险 | 缓解 | 回滚 |
|---|---|---|
| 登录页断点归一（860→1023）改变 861-1023 窗口布局 | 该区间本就是抽屉态，视觉验证 1000px 一档 | 单 commit revert |
| **Breadcrumb 767→1023：768-1023 平板丢失中间层级路径**（评审补） | 该区间 header 本就收紧、抽屉导航可达全层级；可接受并明示 | 单文件 revert |
| pointer:coarse 影响触屏笔记本（控件变大、输入 16px、操作常显） | 已声明为可接受副作用（D3/D4） | 删除对应 media 块 |
| `--card-min` 300→260：560-640 视口可能出双窄列 | 手工走查该档观感，不佳则加 ≤640 强制单列 | token 一行 revert |
| useBreakpoint 同步初始化改变首帧行为 | 回退语义保持 desktop=true；三态测试随 Phase 0 落地 | useIsDesktop 接口未动，revert 单文件 |
| chat 滚动守卫改变自动滚动手感 | 阈值 80px + 回底恢复；jsdom 下判定"在底部"存量测试不变（评审已核）；补交互测试 | 独立小函数，revert 即恢复 |
| 存量测试被 DOM 微调破坏 | 优先纯 CSS；结构改动同 commit 修测试 | commit 粒度 revert |
| iOS 真机问题 CI 无护栏（无 e2e） | 手工清单 + 后续 Playwright（D7 backlog） | — |
| 静态站部署 | 无 flag；回滚=重部署上一镜像 | docker 镜像回退 |

## 11. Backlog（本期不做，见 D7）

Playwright 移动冒烟 / SSE 后台中断治理 / FileField capture / 底部导航 / rem 字号无障碍 / 微信 WebView OIDC 专项 / visualViewport 键盘精调。
