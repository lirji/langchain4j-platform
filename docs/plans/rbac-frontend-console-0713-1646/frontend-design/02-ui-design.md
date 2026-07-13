# 02 · UI / 视觉 / 交互设计 —— RBAC 配套前端控制台

> 视角：UI Designer。本文**只做设计，不改任何代码**。
> 硬约束：**最大化复用** `capability-showcase-frontend` 现有设计系统与组件，不引入新 UI 库、不另起风格。
> 权威输入：`FINAL_PLAN.md` §7/§8、`01-requirements.md`、`IMPLEMENTATION_PROGRESS.md`（阶段 A1 已定稿的真实后端契约）、`src/styles/tokens.css` + `base.css`、以及现有页面/组件源码（均已逐一核对）。
> 所有线框为等宽 ASCII，标注均可据以实现。凡后端未定稿处，显式标注为 **【假设】** 或 **【待澄清】**。

---

## 0. 契约校准（先于设计的事实基准）

`IMPLEMENTATION_PROGRESS.md` 的阶段 A1 已落地并跑绿，**真实契约与 FINAL_PLAN §9.2 的"412/428"设想不同**，设计一律以下述**已发布契约**为准：

| 维度 | 已发布（A1，权威） | FINAL_PLAN §9.2 设想（不采用） |
|---|---|---|
| 前置版本头 | `If-Match: <version>`（裸数字，非引号 ETag） | 同 |
| 版本冲突 | **`409 {error:"version_conflict", message}`** | 412 precondition_failed |
| 缺前置版本 | 后端 `expectedVersion=null` 即不校验（向后兼容）；UI 恒发 If-Match | 428 precondition_required |
| 版本来源 | 资源体内的 `version` 字段（GET 不保证返回 ETag 头） | ETag 头 |
| 写开关关闭 | `503`（`requireRoleAdminWrite()`） | 同 |
| 业务冲突（最后管理员/角色被引用/重复名） | `409`，`error` 值非 `version_conflict` | 409 |

**设计结论**：前端 409 处理必须**按响应体 `error` 判别**——
- `error === 'version_conflict'` → 走 **VersionConflictDialog**（草稿 vs 服务端最新，刷新重做，**不覆盖**）；
- 其它 409（`last_admin` / `role_referenced` / `duplicate_*` 等，以后端 message 为准）→ **就地 danger 提示**，不弹冲突对话框。

已确认字段（前端直接建模，不臆造）：
- `GET /auth/public-config` → `{registrationEnabled:boolean, passwordMinLength:int, passwordMaxLength:int}`（边缘 open，无需鉴权）。
- `UserAdminView{username, userId, tenant, directScopes[], roles[], effectiveScopes[], enabled, version}`。
- `RoleView{name, scopes[], description, version, assignedUserCount}`。
- 列表分页：`GET /auth/admin/users?offset&limit&q&tenant&role&enabled` + 响应头 `X-Total-Count`。
- **【待澄清 / 依赖 A2】** `GET /rag/config`、`KnowledgeHit.visibility`、`GET /rag/documents?visibility=tenant|public` 的最终形态由 A2 子代理回交后锁定；本文 RAG 段按 FINAL_PLAN §7.5 语义设计，字段名标注为假设。

---

## 1. 设计原则（承接现有语言，不新增审美）

现有控制台的视觉基因（本设计全程沿用）：

1. **冷调 slate + 技术蓝**，流式/共享用青色（`--stream`）区分；玻璃面（`--glass-bg` / `--glass-bg-strong`）+ 发丝渐变线做层次，多卡网格不叠 `backdrop-filter`（性能护栏）。
2. **诚实呈现**：不可执行/无权限的东西也可见并说明原因，绝不制造"看起来能用"的假象（RAG 的 `flag-off` 诚实锁定、gate 的人话 reason 即先例）。RBAC 管理域承接此心智：缺 `role-admin` → 专用 Forbidden 而非空白/404。
3. **状态从不只靠颜色**：每个状态附**图标 + 文案 + 计数**（见 `StateBadge`、Overview 分布条图例）。RBAC 的 enabled/visibility/version 冲突同此规则。
4. **密度自适应**：`data-density='compact'` 收紧 `--control-h` / `--row-py` / `--card-pad`；表格与抽屉必须随密度令牌缩放。
5. **暗色对等**：所有 token 浅/深成对（WCAG AA）。新界面只用语义 token，**不写死颜色**，即自动获得暗色与 AA。
6. **键盘可达 + 焦点管理**：浮层统一用 `useFocusTrap`（Esc 关闭、Tab 环绕、关闭归还焦点），沿用 CommandPalette / HistoryDrawer 的既有实现。

---

## 2. 设计令牌盘点与复用映射

### 2.1 状态色 / 语义色（100% 覆盖，零新增颜色）

| 语义诉求 | 复用 token | 既有用法先例 |
|---|---|---|
| **危险**（删除 / 禁用 / 移除管理员 / 共享写） | `--danger` `--danger-soft` `--danger-border` `--glow-danger`；`.btn--danger`；`StateBadge[tone=danger]` | RAG 删除、`display-only` 锁定 |
| **警告**（缺 scope / 陈旧版本 / API Key 覆盖 / 需二次确认） | `--warning` `--warning-soft` `--warning-border`；`InfoNote[tone=warning]`；`ScopeBadge` | RAG 降级横幅、gate hint |
| **成功**（保存成功 / 已启用 / 有效分数） | `--success` `--success-soft` `--success-border`；`authctl__user` 绿 chip | 登录身份 chip、检索分数 |
| **信息 / 中性说明** | `--info`（indigo）；`InfoNote[tone=info]`；`--neutral` `--neutral-soft` `--neutral-border` | 工作台 notice |
| **主色 / 主操作 / 租户库** | `--primary` 家族 + `--gradient-primary`；`.btn--primary`；`--primary-soft` pill | 主 CTA、导航选中 |
| **共享 / 公共库强调** | `--stream` 家族（cyan，**与租户蓝显著区分**）+ `--stream-soft` `--stream-border` | 流式/live 徽章 |
| **禁用 / off** | `--neutral` 家族；`.btn:disabled`（opacity .55） | `flag-off` 态 |

> 为何"共享=`--stream` 而非 `--info`"：`--info`(#4338ca indigo) 与 `--primary`(#2563eb blue) 明度/色相过近，租户 vs 共享区分度不足；`--stream`(cyan) 是既有第二强调色族，浅/深都成对且 AA 达标，读作"另一分区"最清晰。

### 2.2 徽章 / 药丸 / 芯片映射

| 新界面元素 | 复用样式源 | token |
|---|---|---|
| 角色 chip（roles[]） | Overview `.ov__quick-link` 药丸 | `--primary-soft` + `--primary-border`，`--radius-pill` |
| scope chip（只读 direct / effective 摘要） | RAG `.rag__id` mono 药丸 | `--surface-2` + `--font-mono` + `--fs-xs` |
| **需授权** scope（角色编辑里高亮 write 类 scope） | `ScopeBadge` | `--warning` 家族 + 🔑 |
| enabled 状态药丸 | `authctl__user`（启用=绿）/ `SourceBadge`（禁用=中性） | 启用 `--success-soft`；禁用 `--neutral-soft` |
| version 标记 | HistoryDrawer `.entry__status` mono | `--font-mono` `--fs-xs` `--text-subtle` |
| **visibility 徽章（租户 / 共享）** | 新建 `VisibilityBadge`（极薄壳，见 §6） | 租户 `--primary-soft`；共享 `--stream-soft` |
| assignedUserCount 计数药丸 | SideNav `.nav__module-count` | `--surface-3` + `--radius-pill` |

### 2.3 表格 / 密度 / 布局

| 诉求 | 复用 | 说明 |
|---|---|---|
| 只读原始数据表（冲突对比、详情 JSON 兜底） | `_shared/ResultTable.vue` | 直接用（mono、sticky th、横向滚动、AA `role=region`） |
| **交互式管理列表**（Users / Roles，行内操作 + 状态药丸） | **不复用 ResultTable**（它是 mono 只读原始表）；用 `<table>` + 既有 token 复刻 `.rt__th/.rt__td` 观感 + 行内控件 | 见 §4；表头 `--surface-2`、行 `--row-py`、hover `--surface-2` |
| 行密度 | `--row-py`(8/5) `--control-h`(36/30) 随 `data-density` | 自动跟随全局密度开关 |
| 页容器 | `.page` / `.page--wide`（`--content-max` 1320px） | 管理页用 `.page--wide` |
| 分区容器 | `_shared/WorkbenchSection.vue`（title/subtitle/actions/notice/折叠） | RAG 双视图、Register 直接复用 |
| 富页头 + 分布条 | `layout/ModuleHeader.vue` | 管理域页头**不复用**（无五态语义）；改用轻量 AdminHeader（§3.3） |
| 提示条 | `_shared/InfoNote.vue`（5 tone，左强调竖条，glass） | 所有 banner/notice |
| 空/加载/错误态 | `common/EmptyState.vue`（empty/loading/error + action） | 列表三态 |
| 统计卡 | `common/StatCard.vue`（label/value/sub/tone/trend） | 管理域概要（可选） |
| 骨架屏 | 全局 `.skeleton`（shimmer，仅 transform） | 列表首载骨架行 |
| 右侧抽屉 | `common/HistoryDrawer.vue` 的 drawer 骨架（scrim + panel + focus trap + 滑出过渡） | UserEditor/RoleEditor 抽屉照搬结构 |
| 命令面板集成 | `common/CommandPalette.vue`（新增 action 项即可） | §3.4 |

### 2.4 令牌缺口结论 —— 仅 1 项**可选**新增

现有令牌**覆盖本控制台约 100%**。逐项复核后，唯一有实际必要的**最小新增**：

```css
/* tokens.css :root 追加（可选；仅当"抽屉内再开对话框"需要明确层级时）*/
--z-modal: 70;   /* 居中确认/冲突对话框：高于 drawer(40)/popover(60)，低于 cmdk(80)/toast(90) */
```

- **理由**：`VersionConflictDialog` / `DangerConfirmDialog` 是**居中模态**，且**从抽屉内（`--z-drawer:40`）唤起**，必须叠在抽屉之上。现有 z 梯度无"模态"层；`--z-cmdk:80` 语义专属命令面板。给对话框一个 70 的专属层最干净。
- **降级方案（若不愿加 token）**：对话框直接复用 `--z-cmdk` 的栈位——可行，但语义略含糊。**推荐加 `--z-modal:70`**，这是全设计唯一的令牌增量。
- **不需要新增**：颜色（全复用）、间距/圆角/字号（`--space-*`/`--radius-*`/`--fs-*` 齐全）、toast 层（本期"成功/失败就地呈现"，用内联 `InfoNote` + `aria-live`，不做全局 toast，`--z-toast` 保留不动）。

---

## 3. 信息架构与导航

### 3.1 路由与门禁（承接 FINAL_PLAN §7.1，懒加载 + Bearer-only）

```
/login                公开
/register             仅 public-config.registrationEnabled=true 可达；否则守卫重定向 /login
/                     能力总览（需登录，可选 catalog 门禁）
/m/:moduleId/:capId   能力域（现状）
/admin                → 重定向 /admin/users
/admin/users          需 Bearer + effectiveScopes 含 role-admin；懒加载 chunk；不受 catalog 门禁阻塞
/admin/roles          同上
/forbidden            无权限深链落点（专用页，非 404/空白）
```

门禁裁决单一来源：`usePermission()`（新，§6）读 `session.credentialMode` + `auth.hasScope('role-admin')`。**管理域恒用 Bearer**，即使填了 API Key 也不带 Key（避免用未知 Key 做平台级操作）。

### 3.2 顶栏（AppHeader 改造：身份 chip + 管理入口 + 凭证模式）

现状 `AuthControl` 只显示 `👤 用户名`（tenant 藏在 title）。改造为**身份 chip 三段**：`username · tenant · 凭证模式`，并在 API Key 覆盖时给醒目警告条。管理入口按条件出现。

**桌面顶栏（≥1024px），Bearer 模式且具备 role-admin：**
```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│ ☰  ◆ 能力控制台   总览 › 用户管理        [ ⌕ 搜索能力…  ⌘K ]   🕘  ▤  ?   ┌───────────────┐  ◐ │
│                                                                          │ 👤 alice · acme│    │
│                                                                          │  🅑 Bearer   ▾ │    │
│                                                                          └───────────────┘    │
└──────────────────────────────────────────────────────────────────────────────────────────┘
                  ▲ 管理入口只在此条件下作为侧栏分区 + 面包屑 + cmdk 出现
```

**身份 chip 下拉（点击 ▾ 展开，popover，`--z-popover`）：**
```
┌────────────────────────────────────┐
│ 👤 alice                           │
│ 租户 acme                          │
│ 凭证 🅑 Bearer（账号会话）          │
│ 有效权限 12 项 · 含 role-admin ●   │  ← effectiveScopes.length；role-admin 单独标记
│────────────────────────────────────│
│ ⚙ 用户管理                    →    │  ← 仅 role-admin 显示
│ ⚙ 角色管理                    →    │  ← 仅 role-admin 显示
│────────────────────────────────────│
│ 高级：直连 API Key（可选覆盖）  ▸  │  ← 沿用现 ApiKeyInput，折叠
│ 登出                              │
└────────────────────────────────────┘
```

**API Key 覆盖 Bearer 时——顶栏正下方 sticky 警告条（高对比，`InfoNote[tone=warning]` 满宽）：**
```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│ ⚠ 能力请求将使用 API Key，账号权限预判已暂停（以服务端结果为准）。 管理中心仍只用账号会话。 [清除 Key] │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```
- chip 的凭证段随 `session.credentialMode` 切换徽章：`🅑 Bearer`（success 底）/ `🔑 API Key`（warning 底）/ `— 未登录`（neutral）。
- 未登录：chip 变「登录」主按钮（现状保留）。
- 无 role-admin 的登录用户：chip 正常显示，但**无 ⚙ 管理入口**（下拉、侧栏、cmdk 三处同源隐藏）。

### 3.3 侧栏分区（SideNav 增"平台管理"组，权限受控）

在现有能力分组之上，**当且仅当** `role-admin` 时追加一个置顶/置底的「平台管理」组，复用 `.nav__group` 折叠骨架与 `.nav__module-link` 行样式：

```
┌─ 侧栏 ────────────────┐
│ [ 🔍 筛选能力…    / ] │
│ 🏠 总览 Overview      │
│                       │
│ ⚙ 平台管理            │  ← eyebrow 组头，仅 role-admin 出现
│   👥 用户管理     50  │  ← .nav__module-link + 计数药丸（X-Total-Count）
│   🛡 角色管理      8  │
│                       │
│ 对话与检索            │  ← 现有能力分组，原样保留
│   💬 对话             │
│   📚 RAG 检索         │
│ 智能体与编排  …       │
│ …                     │
└───────────────────────┘
```
- 管理组图标：用户 `👥`、角色 `🛡`（对齐现有 emoji 图标风格 `MODULE_ICON`）。
- 管理项**不带能力五态点**（无 `CapabilityState` 语义）；选中态复用 `.nav__cap.active` / `.nav__module-head.active`（软主色 + 左强调条）。
- 能力项缺权仍可见（现有"可发现"原则）；管理项则**整组按 role-admin 显隐**（平台级入口，非能力）。

### 3.4 命令面板集成（CommandPalette 加 action 组，同源门禁）

在 `actions` computed 内**条件追加**（仅 role-admin，与侧栏同一 `usePermission` 源，杜绝一处隐藏另一处可点）：
```
操作
  ⚙ 打开用户管理        ⌘⇧U
  🛡 打开角色管理        ⌘⇧R
  ＋ 新建角色…
```
- 无 role-admin 时这三项不进入 `groups`（fuzzy 也搜不到）。
- 选中即 `router.push('/admin/...')` 并 `ui.closeCmdk()`，与现有 cap 跳转一致。

### 3.5 管理域页头（轻量 AdminHeader，非 ModuleHeader）

管理页无"能力五态分布"，故不套 `ModuleHeader`。用轻量页头（`.page--wide` 内），复用 eyebrow + StatCard 语汇：

```
┌──────────────────────────────────────────────────────────────────────────┐
│ 平台管理                                                        [ 状态徽章 ] │  ← eyebrow
│ 用户管理                                                                    │  ← h1 fs-xl
│ 管理全局用户的租户归属、角色分配与启停。direct scopes 只读，授权经角色下发。  │  ← desc text-muted
│                                                                            │
│ [👥 用户 50] [✔ 启用 46] [🛡 角色 8] [✍ 写开关 开]                          │  ← 一行 StatCard（可选）
└──────────────────────────────────────────────────────────────────────────┘
```
- 右上「状态徽章」= 写开关：`writesEnabled` → `InfoNote` 内联小徽章；关闭时全页 mutation 按钮禁用并显 §4.9 的 503 banner。

---

## 4. 逐屏线框（ASCII）+ 交互规格

> 通用：所有列表用 `usePagedQuery`（防抖 300ms + AbortController 取消在途 + 序号丢弃乱序响应）；所有抽屉/对话框用 `useFocusTrap`；所有写操作带 `If-Match: <version>`；所有 mutation 按钮在 `writesEnabled=false` 时禁用并 title 说明。

### 4.1 用户列表 `/admin/users`

```
┌─ AdminHeader（用户管理，见 §3.5）──────────────────────────────────────────────────┐
├────────────────────────────────────────────────────────────────────────────────────┤
│ 筛选栏（sticky，随 header 之下）                                                      │
│ [ 🔍 用户名 q…            ] [租户 ▾ 全部] [角色 ▾ 全部] [启用: ⟨全部|启用|禁用⟩] [＋ 新建用户] │
│                                                          防抖300ms·取消旧请求  role-admin写 │
├────────────────────────────────────────────────────────────────────────────────────┤
│ 用户名        租户     角色              有效权限        状态       版本   操作           │
│ ───────────────────────────────────────────────────────────────────────────────────  │
│ alice        acme     [role-admin][ops] 12 项 ⓘ        ●启用⟳    v3     [详情][编辑]   │
│ bob          globex   [chat-only]       2 项 ⓘ         ●启用⟳    v0     [详情][编辑]   │
│ carol        acme     [analyst]         5 项 ⓘ         ○禁用⟳    v1     [详情][编辑]   │
│ dave         tenantA  （无角色）         0 项           ●启用⟳    v0     [详情][编辑]   │
│ …                                                                                      │
├────────────────────────────────────────────────────────────────────────────────────┤
│ 共 50 · 第 1–50 页               [‹ 上一页]  1 / 1  [下一页 ›]   每页 [50 ▾]           │
└────────────────────────────────────────────────────────────────────────────────────┘
```

列与控件规格：
- **用户名**：文本，等宽可选；点击整行或「详情」打开只读抽屉，「编辑」打开可编辑抽屉。
- **角色**：`roles[]` → primary-soft chip；超 2 个折叠为 `[role-admin] +2`，hover title 全量。
- **有效权限**：`effectiveScopes.length` + `ⓘ`（hover popover 列全量 scope，只读）。**绝不**把 `directScopes` 误标为 effective（§01 规则 2）。
- **状态**：`●启用`(success) / `○禁用`(neutral) 药丸 + 行内 `⟳` 快速启停开关（见下）。
- **版本**：`v{version}` mono subtle，供用户与冲突对话框对账。
- **操作**：`[详情]`(ghost sm) `[编辑]`(ghost sm)。

**行内启停（`⟳` 乐观开关）**：
- 点击 → 立即乐观翻转药丸（optimistic），按钮进入 `.btn--loading`；发 `PATCH /users/{u}` 带 `If-Match: v{version}` + `{enabled:!enabled}`。
- 成功：药丸定态，`version` 自增（用响应体新 version 覆盖行）。
- **409 version_conflict**：回滚药丸 + 弹 VersionConflictDialog（§4.8）。
- **其它 409（最后管理员保护：禁用会降到 0 个 role-admin）**：回滚 + 行下就地 danger `InfoNote`（服务端 message）。
- **503**：回滚 + 顶部写开关 banner（§4.9）。
- 禁用自己：先过 DangerConfirmDialog（§4.7）。

四态：
```
loading（首载）：骨架行 ×8（.skeleton 占位每列宽度，shimmer）
┌─────────────────────────────────────────────┐
│ ▨▨▨▨   ▨▨▨   ▨▨▨▨▨   ▨▨▨   ▨▨   ▨   ▨▨▨▨    │  ← .skeleton
│ …                                            │
└─────────────────────────────────────────────┘

empty（筛选无结果）：EmptyState variant=empty icon=∅
  "没有匹配用户" · "调整筛选条件，或清空后重试" · [清空筛选]

empty（全空/无数据）：EmptyState icon=👥 "暂无用户" · [＋ 新建用户]

error（列表加载失败）：EmptyState variant=error
  "加载失败" · humanizeError 文案 · [重试]

refetch（改筛选时）：不闪回旧结果——保留当前行，表体顶部 2px 进度条 + 表体 aria-busy=true 半透明遮罩；
  新结果到达前旧行可读不可操作（操作按钮 disabled）。乱序响应按序号丢弃。
```

无障碍：`<table>` 语义化 `scope=col`；启停开关 `role=switch` + `aria-checked`；筛选段控 `aria-pressed`；`ⓘ` popover `aria-describedby`；筛选/结果计数 `aria-live=polite`。

### 4.2 用户详情 / 编辑抽屉（右侧滑出，复用 HistoryDrawer 骨架）

结构复用 `HistoryDrawer`：`Teleport→scrim→aside[role=dialog,aria-modal] + useFocusTrap`，宽 420px（比历史 360 略宽，容纳表单），滑出过渡一致。

**编辑态：**
```
┌─ 抽屉（右滑，420px）─────────────────────────────┐
│ 编辑用户                                     ✕   │  ← close 首焦点
│ 👤 alice   ·  租户 acme   ·  v3                   │  ← 用户名/租户/版本只读头
├──────────────────────────────────────────────────┤
│ ⚠ 你正在编辑自己的账号。改动角色/启停/密码会影响你 │  ← 仅编辑自己时；InfoNote warning
│    自己的会话，保存后可能需要重新登录。            │
├──────────────────────────────────────────────────┤
│ DIRECT SCOPES（只读）                             │  ← eyebrow
│ [ingest] [chat] [read]           本期只读 · 授权走角色 │  ← surface-2 mono chip + 说明
│                                                    │
│ 角色（可编辑）                            RolePicker│
│ ┌──────────────────────────────────────────────┐ │
│ │ [role-admin ✕] [ops ✕]           [＋ 添加角色 ▾]│ │  ← 已选 chip 可移除；下拉多选
│ └──────────────────────────────────────────────┘ │
│ 有效权限预览（预测）                              │  ← direct ∪ 展开(所选角色)；标"预测"
│ [read][ingest][chat][ops:*][role-admin] … 12 项   │  ← 依赖 adminRoles 的 scopes；下次登录/刷新生效
│                                                    │
│ 状态                                              │
│ (●) 启用   ( ) 禁用                                │  ← BooleanField/单选
│                                                    │
│ 重置密码（可选）                                  │  ← eyebrow
│ [ 新密码（留空=不修改）        👁 ]               │  ← 空=不发送该字段（§01 规则：空≠清空）
│ 密码需 8–64 位                                     │  ← 来自 admin/public-config 策略
├──────────────────────────────────────────────────┤
│ 版本冲突/错误就地区（InfoNote）                    │
├──────────────────────────────────────────────────┤
│                          [取消]   [保存更改]      │  ← 保存=btn--primary，提交时 .btn--loading
└──────────────────────────────────────────────────┘
```

**只读详情态**（从「详情」进入）：同布局，角色/状态/密码区改为只读展示，底部仅 `[编辑]` `[关闭]`。

交互规格：
- **direct scopes 只读**：明确 chip + "本期只读 · 授权走角色"，无编辑控件（对齐 §01 规则 8 与 A1 DTO 不收 direct scopes）。
- **角色可编辑**：`RolePicker`（§6）从 `adminRoles` 真实角色多选；已选 chip 可移除。若 `adminRoles` 加载失败 → RolePicker 禁用 + 提示"角色列表加载失败，暂不能改角色"（§01 边界：不能把空角色列表当"无角色"）。
- **有效权限预览**：前端用 `adminRoles` 的 `role→scopes` 现场计算 `directScopes ∪ ⋃ selectedRoles.scopes`，标注**"预测（下次登录/刷新后生效）"**——诚实呈现 access token 的撤权延迟（§01 规则 5）。
- **密码重置**：留空 → 请求体**不含** password 字段；非空 → 触发 DangerConfirm（重置他人密码是敏感操作）。策略上下限来自 `GET /auth/admin/config`（min/max length），无则回退 public-config。
- **保存**：`PATCH /users/{u}`（tenant/enabled/password 变更）与 `PUT /users/{u}/roles`（角色变更）；两者都带 `If-Match: v{version}`。若同时改了 profile 与角色，按后端两端点顺序提交（角色端点在后），任一步 409 即整体停并提示（不半提交静默）。
- **保存成功**：关抽屉、行数据热更新（新 version）、列表顶内联 success `InfoNote`（"用户 alice 已更新"，`aria-live`）。焦点归还到触发行的「编辑」按钮（focus-trap 自带）。
- **编辑自己且非最后管理员**：保存成功后按后端会话策略——若降权/改租户，提示"权限已变更，请重新登录"并提供登出入口（不把旧 token 当最新）。
- **提交期**：保存按钮禁用防重复；`Enter` 在文本字段内提交（密码字段 Enter 不直接触发 DangerConfirm，需显式点保存）。

四态：loading（抽屉内骨架块）/ error（抽屉内 EmptyState error + 重试）/ conflict（§4.8）/ 503（保存按钮禁用 + 抽屉内 503 note）。

### 4.3 角色列表 `/admin/roles`

```
┌─ AdminHeader（角色管理）──────────────────────────────────────────────────────────┐
│ [ 🔍 角色名 q… ]                                                     [＋ 新建角色]   │
├────────────────────────────────────────────────────────────────────────────────────┤
│ 角色名        说明                          scopes               绑定用户  版本  操作  │
│ ───────────────────────────────────────────────────────────────────────────────────  │
│ role-admin   平台管理员                     🔑 8 项 ⓘ            12 人 →   v2    [编辑][删除⊘]│
│ ops          运维                           🔑 5 项 ⓘ            3 人 →    v0    [编辑][删除⊘]│
│ chat-only    仅对话                         🔑 2 项 ⓘ            0 人      v1    [编辑][删除] │
│ analyst      数据分析                        🔑 5 项 ⓘ            1 人 →    v0    [编辑][删除⊘]│
├────────────────────────────────────────────────────────────────────────────────────┤
│ 共 8                                                                                   │
└────────────────────────────────────────────────────────────────────────────────────┘
```
- **说明**：`description` 纯文本渲染（**禁 `v-html`**，§01 §4）。
- **scopes**：计数 + `ⓘ` popover 分组列全量（复用 ScopePicker 的只读渲染）。
- **绑定用户**：`assignedUserCount`；`>0` 时为链接 `N 人 →`，点击跳 `/admin/users?role={name}`（预填筛选）。
- **删除保护**：`assignedUserCount>0` → 删除按钮 `[删除⊘]` 禁用，title="被 N 个用户引用，先解除绑定"；`=0` → `[删除]` 可点，仍过 DangerConfirm；服务端最终 409 `role_referenced` 就地提示（防并发窗口）。
- 四态同 §4.1（loading 骨架 / empty EmptyState / error 重试）。角色列表通常小，可不分页（`X-Total-Count` 仍读，>阈值再启用分页）。

### 4.4 角色编辑 `RoleEditor`（抽屉，ScopePicker 按域分组）

```
┌─ 抽屉（右滑，460px）──────────────────────────────────────────┐
│ 编辑角色 / 新建角色                                        ✕   │
│ 🛡 ops                                            v0 · 3 人绑定 │  ← 编辑态：名只读 + 版本 + 绑定数
├────────────────────────────────────────────────────────────────┤
│ 角色名                                                         │
│ [ ops                        ]  （创建后不可改名）             │  ← 编辑态禁用；hover 说明"改名=新建+迁移+删旧"
│ 说明                                                          │
│ [ 运维角色，可编排与审批                    ]                  │
│                                                                │
│ 权限 scopes（按域分组）                        ScopePicker     │  ← eyebrow
│ ┌────────────────────────────────────────────────────────────┐│
│ │ ▸ 对话        [chat] [chat:stream]                          ││  ← 组可折叠；勾选式
│ │ ▾ 知识        [☑ ingest] [☑ read] [ public-ingest ] [rerank]││
│ │ ▸ 智能体      [agent:run] [agent:dag]                       ││
│ │ ▸ 审批        [approve]                                     ││
│ │ ▸ 分析        [sql]                                         ││
│ │ ▸ 通道        [channel:send]                                ││
│ │ ▸ 多模态      [vision] [voice]                              ││
│ │ ▾ 平台管理    [☑ role-admin ⚠]                              ││  ← 高权项 warning 标记
│ │ ▾ 未识别（保留）  [ legacy:foo ☑ ]                          ││  ← scopeCatalog 未知；显示且保留，不丢
│ └────────────────────────────────────────────────────────────┘│
│                                                                │
│ ⚠ 修改 scopes 将影响 3 个绑定用户的有效权限（下次登录/刷新后生效）。│  ← 编辑且有绑定时；InfoNote warning
├────────────────────────────────────────────────────────────────┤
│ 冲突/错误就地区                                                │
├────────────────────────────────────────────────────────────────┤
│                                  [取消]   [保存角色]          │
└────────────────────────────────────────────────────────────────┘
```
交互规格：
- **ScopePicker 分组**：域来自 `config/scopeCatalog.ts`（对话/知识/智能体/审批/分析/通道/多模态/平台管理，对齐 FINAL_PLAN §7.4）；每个 scope 有人话 label + hint（hover）。
- **未知 scope 保留**：真实角色里 catalog 未收录的 scope 归入「未识别（保留）」组，**默认勾选、不丢弃、不阻止保存**（§01 §3.5 / FINAL_PLAN 风险"scope catalog 漂移"）。
- **高权 scope 视觉**：`role-admin`、`public-ingest`、`*:delete` 等加 `⚠` + warning 描边（`ScopeBadge` 语汇），提示放权后果。
- **改名不可**：编辑态 name 禁用；新建态 name 可编辑（唯一，重名 → 创建 409 `duplicate_role` 就地提示）。
- **影响预览**：`assignedUserCount>0` 且 scopes 有变 → 顶部 warning banner 数量提示。
- **保存**：新建 `POST /roles`；编辑 `PUT /roles/{name}` + `If-Match: v{version}`。409 分流同 §4.8。成功关抽屉 + 列表热更新 + 内联 success。

### 4.5 注册 `/register`

复用 `LoginView` 的视觉语言（极光背景 + 玻璃卡片，自成一体亮色，不跟随暗色），表单换为注册字段。**仅** `public-config.registrationEnabled=true` 可达（路由守卫；否则 → `/login`）。

```
┌─ 极光背景（复用 lp-aurora）───────────────────────────────────────────────┐
│                     ┌──────────────────────────────────────┐              │
│   品牌区（宽屏）    │  ◆ 能力控制台                          │              │
│   ┌──────────────┐  │  创建账号                              │              │
│   │ 对话·检索·   │  │  内部试用台 · 注册后自动登录            │              │
│   │ 智能体·多模态│  │                                        │              │
│   │              │  │ ⚠ 错误就地区（role=alert）             │              │
│   │  特性列表    │  │                                        │              │
│   │              │  │ 用户名   [ 👤            ]              │              │
│   └──────────────┘  │ 密码     [ 🔒          👁 ]             │              │
│                     │ 确认密码 [ 🔒          👁 ]             │              │
│                     │          密码需 8–64 位（来自服务端策略）│              │
│                     │                                        │              │
│                     │        [    创 建 账 号    ]           │              │
│                     │  已有账号？ 返回登录                    │              │
│                     └──────────────────────────────────────┘              │
└────────────────────────────────────────────────────────────────────────────┘
```
规格：
- 字段仅 `username / password / confirmPassword`——**不暴露 tenant/role 选择**（§01 §3.2，服务端规则决定）。
- 密码策略提示（长度上下限）来自 `public-config`（非写死常量）；确认密码前端即校验一致。
- 提交 → `POST /auth/register`；成功返回会话 → 复用 `authStore` 写入 → `catalog.refreshLive()` → 跳 `sanitizeRedirect` 或 `/`（与登录同流）。
- 错误：`409 duplicate_username`（"用户名已存在"）、`429`（"注册过于频繁，稍后再试"）、注册关闭（`403/503` → "注册当前未开放" + 返回登录）——均优先服务端 message。
- 入口来源：`LoginView` 在 `registrationEnabled` 时显示「创建账号」链接（否则不显示或灰示"注册未开放"）。
- **移除 demo 硬编码**：`LoginView` 去掉包内 `DEMO_PASSWORD` 自动提交（受 `VITE_DEMO_LOGIN_ENABLED` 门禁；关闭时 demo 卡仅回填用户名，不自动登录）——本条属登录页安全整改，注册设计一并记录。

### 4.6 Forbidden `/forbidden`

无权限深链落点。居中，复用 `EmptyState` 语汇 + 明确出路（不空白、不 404、不循环跳转）。

```
┌───────────────────────────────────────────────┐
│                                               │
│                    ⛔                         │
│              无权访问该页面                    │
│  该页面需要平台管理员（role-admin）权限。      │
│  你的账号当前不具备，或会话权限已发生变化。     │
│                                               │
│  · 当前身份：alice · 租户 acme                 │  ← 诊断信息
│  · 凭证模式：🅑 Bearer                         │
│                                               │
│      [ 返回总览 ]     [ 重新登录 ]             │
└───────────────────────────────────────────────┘
```
分支文案：
- **API Key 模式命中管理路由**：额外说明"管理中心只接受账号会话，请用账号登录后再试"（对齐 Bearer-only）。
- **曾有权限、会话变化**（登录后被降权）：说明"你的角色可能已被调整；请重新登录以刷新权限"。
- 提供可复制 `X-Trace-Id`（若有）供诊断（§12.2 监控）。

### 4.7 危险确认对话框 `DangerConfirmDialog`（居中模态）

用于：删除用户/角色、禁用/删除自己、移除自己 role-admin、共享库写/删。居中模态（`--z-modal`），focus-trap，破坏性按钮 `.btn--danger`。

```
        ┌──────────────────────────────────────────────┐
        │ ⚠ 删除角色 ops                            ✕  │
        ├──────────────────────────────────────────────┤
        │ 此操作不可撤销。                              │
        │                                              │
        │ · 角色：ops                                  │
        │ · 当前绑定用户：0 人（无引用，可删除）        │
        │                                              │
        │ 如需继续，请输入角色名确认：                  │  ← 高危项要求键入名字（删除类）
        │ [ ops____________ ]                          │
        ├──────────────────────────────────────────────┤
        │                    [取消]   [确认删除]        │  ← 确认默认 disabled，键入匹配才启用
        └──────────────────────────────────────────────┘
```
- **键入确认**仅对最高危（删除、禁用/降权自己）启用；一般确认（如共享入库）用勾选或直接确认。
- 「取消」为初始焦点（防误删）；Esc = 取消；`.btn--danger` 提交时 `.btn--loading`。
- 服务端二次保护（最后管理员/引用）仍可能 409 → 对话框内就地 danger 提示，不静默关闭。

### 4.8 版本冲突对话框 `VersionConflictDialog`（居中模态，草稿 vs 服务端）

**仅** `409 error==='version_conflict'` 触发。**不提供无脑覆盖**；引导"查看差异 → 刷新取最新 → 重做"。

```
        ┌──────────────────────────────────────────────────────────┐
        │ ⚠ 保存冲突：该用户已被他人修改                        ✕  │
        ├──────────────────────────────────────────────────────────┤
        │ 你基于 v3 编辑，但服务端当前已是 v5。为避免覆盖他人改动， │
        │ 请查看差异后刷新重做。                                    │
        │                                                          │
        │ 字段          你的草稿(v3)          服务端最新(v5)         │  ← 复用 ResultTable 只读对比
        │ ────────────────────────────────────────────────────────  │
        │ roles         [admin, ops]          [admin]     ⚠差异      │
        │ enabled       true                  true                  │
        │ tenant        acme                  acme                  │
        │ ────────────────────────────────────────────────────────  │
        │ ⚠ 有 1 处字段被他人改动（已高亮）。                       │
        ├──────────────────────────────────────────────────────────┤
        │              [放弃我的改动，加载最新]   [用最新为基底重做] │
        └──────────────────────────────────────────────────────────┘
```
- 「服务端最新」来自 409 响应体的**安全资源摘要**（A1 已带 message；若后端未回整资源，则前端重新 `GET` 最新再比对——**【假设】** 后端 409 体至少含 `currentVersion`，缺则 UI 触发一次 refetch）。
- 「加载最新」：丢弃草稿，抽屉重载服务端 v5。
- 「用最新为基底重做」：把服务端 v5 作为新基线，**保留用户改动意图但需其复核**（把草稿差异高亮标注在重载后的表单上，version 更新为 v5，用户确认后再保存）。
- 差异表：复用 `ResultTable`（`rows` = 字段 × [草稿, 最新]，`caption` 说明），差异行加 `⚠差异` 徽标（token `--warning`）。

### 4.9 写开关关闭（503）全局态

`writesEnabled=false`（`AUTH_RBAC_ADMIN_WRITES_ENABLED` 关，后端 503）：
```
┌──────────────────────────────────────────────────────────────────────────┐
│ ℹ 管理写操作当前已关闭（只读模式）。可浏览用户/角色，暂不能创建/编辑/删除。 │  ← InfoNote info，页头下 sticky
└──────────────────────────────────────────────────────────────────────────┘
```
- 来源：进入 `/admin/**` 时读 `GET /auth/admin/config` 的 `writesEnabled`（fail-closed：读不到当作关闭）。
- 所有 mutation 按钮（新建/编辑保存/删除/行内启停）`disabled` + title="写操作已关闭"。
- 若前端漏判、请求仍打到后端得 503 → 就地 danger + 回滚乐观态 + 触发一次 config 重读同步 banner。

### 4.10 RAG 双视图（`RagWorkspaceView` 升级，租户库 / 共享库）

在现有「文档库」左栏顶部加**分段 tab**（复用 HistoryDrawer `.drawer__filters` 分段控件语汇），其余布局（检索台、入库、GraphRAG）保留。

```
┌─ 文档库 ───────────────────────────────────────────────────┐
│ ⟨ 我的租户库 │ 共享知识库 ⟩            [刷新文档]           │  ← 分段控件 role=tablist；共享仅 publicEnabled 显示
│ ───────────────────────────────────────────────────────── │
│ 📄 退款政策.md          [租户]        分析类   doc_abc  [详情][删除] │  ← VisibilityBadge 租户=primary
│ 📄 公共FAQ.md           [共享]        通用     doc_pub  [详情][删除⚠]│  ← 共享=stream；共享删除需 public-ingest+确认
│ ──────                                                     │
│ 元数据：visibility · uploadedAt · version · segmentCount · sizeBytes · category │  ← 行展开显示完整元数据
└────────────────────────────────────────────────────────────┘

┌─ 文档入库（WorkbenchSection 折叠）─────────────────────────┐
│ 可见性  (●) 当前租户   ( ) 共享（全租户可检索）             │  ← 显式选择 visibility
│ ☐ 我确认共享文档将对所有租户检索可见                        │  ← 选"共享"时必勾才可提交（DangerConfirm 语义）
│ [文件] [JSON] [Obsidian]  —— 共享图片：⊘ 禁用（后端暂不支持）│  ← 共享+图片禁用 + 说明
└────────────────────────────────────────────────────────────┘

检索台命中卡（现状）+ 每条命中加来源徽章：
│ 0.873  doc_abc  分析类  [租户]     ← VisibilityBadge，来自服务端 hit.visibility（不猜）
│ 0.812  doc_pub  通用    [共享]
```
规格：
- **tab 门禁**：`共享知识库` tab 仅 `rag-config.publicEnabled` 显示；关闭时只留租户库 + `InfoNote` 说明"共享库当前未开放"。**不**在关闭时声称查询含共享（§01 §3.6）。
- **visibility 徽章**：租户 `--primary-soft`、共享 `--stream-soft`；文本+图标双标（AA）。命中卡的徽章**只读服务端 `hit.visibility`**，绝不按 docId/名称推断（依赖 A2 的 `KnowledgeHit.visibility`，**【假设】** 字段名 `visibility`，取值 `tenant|public`）。
- **共享入库**：`visibility=public/shared`；必须勾"确认全租户可见"才可提交（DangerConfirm 语义内联化）；`public-ingest` scope 缺失 → Bearer 模式前置禁用+说明，API Key 模式反应式（403 人话）。
- **共享图片禁用**：可见性=共享时，图片入库入口 `⊘` 禁用 + "后端暂不支持共享图片"（§01 §3.6）。
- **共享删除**：`[删除⚠]` 需 `public-ingest` + DangerConfirm。
- **完整元数据**：文档行可展开显示 `visibility/uploadedAt/version/segmentCount/sizeBytes/category`（依赖 A2 的 typed DocumentInfo）。
- 四态沿用现状（未登录 warning、加载中、空态 EmptyState、错误 danger）。

---

## 5. 流畅度与微交互

### 5.1 乐观更新与回滚
- **适用**：行内启停、（可选）角色多选即时反馈。
- **流程**：本地先翻转 UI → 请求 → 成功用响应体（新 `version`）落定 → 失败**回滚到操作前快照**并就地提示。
- **版本推进**：每次成功写都用后端返回的新 version 覆盖本地行/抽屉（A1："非版本写也 bump 版本"），避免下次操作即冲突。
- **不自动重放含密码请求**：网络结果不明时不重试；改为按 username 重新 `GET` 对账（§01 §6）。

### 5.2 骨架屏 vs spinner（明确边界）
| 场景 | 手段 |
|---|---|
| 列表/抽屉**首次**加载（无既有内容） | `.skeleton` 骨架行/块（结构预览，减少跳变） |
| 列表**refetch**（筛选/翻页，已有内容） | 保留旧内容 + 表体顶 2px 进度条 + `aria-busy`，**不骨架闪回** |
| 按钮级动作（保存/删除/启停） | `.btn--loading`（原位 spinner，不改布局） |
| 抽屉内详情加载 | 抽屉内骨架块 |
| 全局静默续期（bootstrap） | 无 spinner（后台，`bootstrapDone` 门守卫） |

### 5.3 防抖 / 取消 / 乱序保护（`usePagedQuery`）
- 筛选输入防抖 **300ms**（§01 建议 250–350ms）。
- 每次请求带 `AbortController`，**发起新请求前 abort 旧请求**；`isAbortError` 静默忽略（不报错）。
- **乱序丢弃**：递增 seq，只接受最新 seq 的响应，杜绝"慢的旧响应覆盖新结果"。
- 同类在途请求**最多 1 个**。

### 5.4 抽屉 / 弹窗焦点与键盘（全部复用 `useFocusTrap`）
- **打开**：焦点入首元素（抽屉=close 或首字段；DangerConfirm=取消按钮）。
- **Tab / Shift+Tab**：环绕容器内可聚焦元素。
- **Esc**：关闭（等价取消；破坏性操作不因 Esc 而执行）。
- **Enter**：在文本表单内提交主操作（**破坏性对话框除外**——需显式点按或键入确认）。
- **关闭**：焦点归还打开前元素（focus-trap 内置）。
- **层叠**：抽屉内唤起对话框时，对话框 `--z-modal:70` 覆盖抽屉；关闭对话框焦点回抽屉。
- 移动/平板（≥768px）：抽屉 `max-width:92vw`（同 HistoryDrawer），对话框满宽内边距。

### 5.5 过渡与降级
- 抽屉滑出、cmdk 缩放入场、卡片入场：全部已有（`--ease-out` / `card-in` / `cmdk-in`）。
- `prefers-reduced-motion`：全局已降级（动画→0.001ms，glass blur 降档）；新组件只用 token 动画即自动继承。
- 乐观翻转、骨架 shimmer 均为 transform-only（省电、无重排）。

### 5.6 无障碍与对比度
- 颜色**从不单独表意**：enabled/visibility/version 冲突/scope 高权都附图标+文字（承接 StateBadge 规则）。
- 所有交互控件 40–44px 命中区（`--control-h:36` + padding 达标；行内小控件 `--control-h-sm:30` 配足够 padding）。
- 语义：列表 `<table scope>`；对话框 `role=dialog aria-modal`；开关 `role=switch aria-checked`；tab `role=tablist/tab aria-selected`；状态区 `aria-live=polite`，错误 `role=alert`。
- 服务端文本（用户名/tenant/说明/错误）**一律文本节点**渲染，**禁 `v-html`**（防 XSS，§01 §4）。
- 暗色：仅用语义 token，自动获得 `data-theme=dark` 对等（AA）。登录/注册页自成亮色体系（沿用现状，刻意不跟随暗色）。
- 凭证安全：access token / API Key / 密码**绝不**进 localStorage/URL/日志/DOM data 属性（沿用 auth/session store 硬约束）。

---

## 6. 组件复用清单 + 新组件规格

### 6.1 直接复用（零改动或仅传参）

| 现有组件 | 在 RBAC 控制台的用途 |
|---|---|
| `common/EmptyState.vue` | 列表 empty/loading/error 三态 + 重试 action |
| `common/StatCard.vue` | AdminHeader 概要（用户数/启用数/角色数/写开关，可选） |
| `common/CommandPalette.vue` | 追加管理 action 组（同源门禁） |
| `common/HistoryDrawer.vue` | **抽屉骨架蓝本**（scrim+panel+focus-trap+滑出过渡）供 UserEditor/RoleEditor 照搬 |
| `common/CopyButton.vue` | 复制 userId / traceId / scope 列表 |
| `composables/useFocusTrap.ts` | 所有抽屉/对话框焦点陷阱 |
| `_shared/WorkbenchSection.vue` | RAG 双视图分区、入库分区、Register 分段（折叠） |
| `_shared/InfoNote.vue` | 全部 banner/notice（API Key 覆盖/自编辑/影响预览/503/冲突就地区） |
| `_shared/ResultTable.vue` | VersionConflictDialog 的草稿 vs 最新只读对比表；详情 JSON 兜底 |
| `form/DynamicForm.vue` + `FieldWrapper` + `fields/*` | Register 表单、密码/文本字段（复用校验与 `validate()` 暴露） |
| `capability/badges/ScopeBadge.vue` | 角色编辑里高权 scope 标记 |
| `capability/badges/StateBadge.vue`（语汇） | 状态药丸的图标+文字+tone 范式 |
| `layout/AppHeader.vue` | 顶栏（改造：身份 chip + 管理入口，见 §3.2） |
| `layout/SideNav.vue` | 侧栏（新增受控"平台管理"组，见 §3.3） |
| `layout/AuthControl.vue` | 身份 chip 三段化改造（username·tenant·凭证模式 + 覆盖警告） |
| 全局 `.btn*` / `.form-control` / `.skeleton` / `.page--wide` / `.eyebrow` | 按钮、表单控件、骨架、页容器、小标题 |

### 6.2 需新增组件（对齐 FINAL_PLAN §8.2，均用现有 token/语汇，不引新库）

| 新组件 | 职责 | 复用的既有语汇 | 关键 props/状态 |
|---|---|---|---|
| `admin/ScopePicker.vue` | 按域分组的 scope 勾选器；未知 scope 归"未识别"组且保留；高权标记 | ScopeBadge、`.nav__group` 折叠、`config/scopeCatalog.ts` | `modelValue:string[]`, `readonly?`, 分组来自 scopeCatalog；emit 全量（含未知） |
| `admin/RolePicker.vue` | 从 `adminRoles` 多选角色；已选 chip 可移除；列表失败禁用 | Overview 药丸、下拉 popover | `modelValue:string[]`, `roles:RoleView[]`, `disabled?` |
| `admin/VersionConflictDialog.vue` | 409 `version_conflict` 差异对比 + 刷新重做（不覆盖） | `ResultTable`、居中模态、`useFocusTrap`、`--z-modal` | `local`, `server`, emits `reload`/`rebase`/`cancel` |
| `admin/DangerConfirmDialog.vue` | 删除/禁用自己/降权/共享写的二次确认（高危键入确认） | `.btn--danger`、居中模态、`useFocusTrap`、`--z-modal` | `title`, `summary`, `requireTypeToConfirm?:string`, emits `confirm`/`cancel` |
| `admin/VisibilityBadge.vue`（极薄） | 租户/共享二态徽章（图标+文字+tone） | ScopeBadge/SourceBadge 药丸样式 | `visibility:'tenant'\|'public'`（租户 primary / 共享 stream） |
| `admin/AdminDataTable.vue`（可选薄封装） | Users/Roles 交互表：表头/密度/骨架/空错态壳 | `ResultTable` 的 th/td token + `EmptyState` + `.skeleton` | `columns`, `rows`, `loading`, slots per-cell/actions |
| `admin/AdminHeader.vue`（轻量） | 管理页头（eyebrow+标题+描述+StatCard 行+写开关徽章） | `.eyebrow`、`StatCard`、`InfoNote` | `title`, `subtitle`, `stats?`, `writesEnabled` |

页面级新增（承接 §8.2，视觉规格见 §4）：`auth/RegisterView.vue`、`admin/AdminLayout.vue`、`admin/UsersView.vue`、`admin/UserEditor.vue`、`admin/RolesView.vue`、`admin/RoleEditor.vue`、`admin/ForbiddenView.vue`。

> 新组件一律**只用语义 token + 现有工具类**，不新增颜色/间距原子；唯一令牌增量为可选 `--z-modal:70`（§2.4）。

---

## 7. 验收对照（本设计满足的关键体验点）

- [x] 顶栏身份 chip 显示 username · tenant · 凭证模式；API Key 覆盖高对比警告 + 清除入口。
- [x] 管理入口（侧栏/下拉/cmdk）**同源** `usePermission` 门禁，仅 role-admin + Bearer 出现；无权深链 → 专用 Forbidden。
- [x] Users 列表：服务端分页/筛选/300ms 防抖/取消旧请求/乱序丢弃；refetch 不闪回；行内乐观启停可回滚。
- [x] User 编辑抽屉：direct scopes 只读、角色可编辑、有效权限"预测"标注、密码空=不改、自编辑风险提示、`If-Match` 保存。
- [x] 冲突按 `error` 分流：`version_conflict`→VersionConflictDialog（草稿 vs 最新，刷新重做不覆盖）；其它 409→就地 danger；503→只读 banner + 禁用写；403→Forbidden。
- [x] Roles 列表 + Role 编辑：ScopePicker 分域、未知 scope 保留、绑定用户数、删除保护（引用/最后管理员）、改名不可。
- [x] Register：运行时开关门禁、密码策略来自服务端、无 tenant/role 选择、复用登录视觉；LoginView 去 demo 硬编码自动提交。
- [x] RAG 双视图：租户/共享 tab（门禁）、显式 visibility 选择、共享确认、共享图片禁用、命中 visibility 徽章来自服务端。
- [x] 全程键盘可达 + 焦点管理 + AA 对比 + 暗色对等 + 无 `v-html` + 凭证不落盘。
- [x] 复用最大化：新界面映射到既有组件/工具类；令牌增量仅 1 项可选（`--z-modal`）。

---

## 8. 待澄清 / 依赖项（实现前需确认，避免臆造）

1. **【依赖 A2】** `GET /rag/config` 与 `KnowledgeHit.visibility` / `GET /rag/documents?visibility=` 的最终字段名与取值（本文假设 `visibility∈{tenant,public}`）。
2. **【待澄清】** 409 `version_conflict` 响应体是否携带"服务端最新资源摘要 / currentVersion"；若否，VersionConflictDialog 需自行 refetch 最新再比对（已在 §4.8 给出降级路径）。
3. **【待澄清】** `GET /auth/admin/config`（含 `writesEnabled` / 密码策略）是否已就绪；若未就绪，写开关 banner 与密码上下限回退用 `public-config`，并对 mutation fail-closed。
4. **【待澄清】** 普通登录用户是否可列共享文档元数据（§01 §7.1）；本设计默认"共享读开则元数据可读，写/删仍需 `public-ingest`"。
5. **【待澄清】** 密码"高危键入确认"的门槛（是否所有密码重置都需确认，还是仅重置他人/自己）——本文默认"重置他人密码需 DangerConfirm"。
6. **【产品】** AdminHeader 的 StatCard 概要是否需要（用户/角色总数、启用数、写开关）——可选，不阻断核心闭环。
```
