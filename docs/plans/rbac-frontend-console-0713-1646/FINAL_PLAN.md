# FINAL_PLAN — RBAC 配套前端控制台

> 状态：可执行规划，不包含业务代码修改。基于 2026-07-13 `feat/rbac-shared-kb` 的真实工作树。文中明确写“计划新增”的文件、类、方法和接口尚不存在；其它名称均已从仓库核对。实施前必须先使当前 RBAC WIP 编译与测试恢复为绿。

## 0. 跨模型复核修订（Claude，2026-07-13）

Codex 规划所依据的后端快照**落后于当前 WIP**。逐条对照仓库并实跑 `mvn -pl auth-service -am test` 后，修正如下（原文相关段落保留但以本节为准）：

**① 后端 RBAC WIP 已经是绿的，阶段0 几乎为空。** 实跑结果：`BUILD SUCCESS`，auth-service 57 个测试全过（含 `AdminControllerTest`/`RbacCoreLogicTest`/`RegistrationTest`/`JdbcRbacMigrationTest`/`JdbcStoresTest`）。

**② 以下 Codex 列为"待新增/待收口"的后端能力，实际已存在，无需再做：**
- `AuthService` 构造器与测试**不存在 11 vs 8 参不匹配**——测试已用 11 参（末尾 `new InMemoryRbacMutationExecutor(), props`）。§4.5 的开工阻塞项作废。
- `AdminService` **已注入并经 `RbacMutationExecutor` 原子执行**所有写用例（§4.3、阶段2 步骤1 作废）。
- **分页 / `getUser` / `patchUser` / `replaceRoles` 已存在**（`listUsers(offset,limit)` + `X-Total-Count`）。§4.3 "无分页/详情"作废。
- **最后管理员保护 + 角色引用保护**已在 `AdminService`（`不能移除最后一个启用的 role-admin`、`角色被 N 个用户引用不能删除`，含预检）。
- `adminWritesEnabled` **已生效**（`AdminController.requireRoleAdminWrite()` 关则 503）。
- `RefreshSessionStore.revokeByUsername/deleteByUsername` **已存在**（默认方法）。
- `PasswordPolicy` / `RegistrationRuleEngine` / 自助注册 **已落地**。
- `DocumentController` 上传 **已支持 `visibility` 参数 + `public-ingest` 校验**。

**③ 真实剩余的后端缺口只剩少量（且多为"读侧使能"，体量远小于原文阶段1/2）：**
1. `GET /auth/public-config`（注册页运行时开关/密码策略）——不存在，需新增。
2. `GET /rag/config`（前端感知共享库/图片是否开启）——不存在，需新增。
3. `KnowledgeHit` 无 `visibility/shared` 字段——查询命中的"租户/共享"badge 需要它。
4. `DocumentController.list()/get/delete` **仍固定当前租户**（仅 upload 带 visibility）——共享库文档管理视图需要它们支持 visibility。
5. 乐观锁 / `VERSION` 列 / If-Match——**不存在**。这是"两管理员并发静默覆盖"的唯一真实缺口；鉴于最后管理员/引用保护已在临界区内，且本控制台面向少量管理员，**建议降级为可选后续项**，不作为前端落地的前置阻塞。

**④ 重心校正（贴合本次诉求）：** 用户要的是**前端系统**（盘点现有页面改动点、突出 UX/易用/流畅）。前端侧 Codex 的判断准确——**当前确无任何 admin/register/RBAC 页面**（`modules/` 下只有能力域 + `auth/LoginView`），且 `LoginView` 硬编码了 `DEMO_PASSWORD='demo12345'` 自动提交（真实安全点）。因此真正的工作量在前端（§7、§8.1、§8.2），后端只需上述 4 个小的只读/加法使能 +（可选）乐观锁。**据此，实施顺序应前端优先，阶段0/1/2 的后端重活按本节收敛。**

## 1. 背景

平台已有账号密码登录和完整租户上下文传播：auth-service 签发会话 JWT，edge-gateway 将 Bearer 换成内部 JWT，下游恢复 `TenantContext` 并用 scopes 授权。当前分支正在增加全局 Role、用户角色关系、自助注册与 `role-admin` 管理 API；同时将共享知识库实现为保留分区 `__public__`，写入需 `public-ingest`，查询可与当前租户结果合并。

现有 `capability-showcase-frontend` 已有登录页、内存 access token、httpOnly refresh cookie、single-flight refresh、API Key兼容、能力目录和 RAG 工作台，但还不是 RBAC 管理控制台：没有用户/角色页面，没有 scope 路由，不区分 API Key覆盖身份，也不能管理或标识共享知识来源。

## 2. 目标与非目标

### 2.1 目标

- 在现有 Vue SPA 内增加懒加载、领域隔离的用户/角色管理中心。
- 让登录、路由、能力执行、导航和错误提示理解“Bearer effective scopes”与“API Key权限未知”的差异。
- 提供顺畅的用户检索、创建、编辑、角色分配、启停、删除，以及角色创建、scope编辑、影响预览和引用保护体验。
- 提供注册页，运行时遵从后端注册开关与密码策略，不让用户选择 tenant/role。
- 把 RAG 工作台升级为租户库/共享库双视图，支持共享文本入库、管理与查询来源标识。
- 通过后端分页、乐观锁、事务边界、session撤销和稳定错误合同，保证管理页面不是只适合 demo 的薄壳。
- 保持 access token/API Key仅在内存、refresh token仅在 httpOnly cookie。
- 具备测试、迁移、监控、灰度、回滚和跨版本兼容方案。

### 2.2 非目标

- 前端不成为授权边界；所有 admin/public mutation 仍由服务端 scopes控制。
- 不做 tenant-scoped role、角色继承、ABAC、资源 ACL、scope CRUD或注册规则管理 UI。
  > 边界更新（2026-07-14，部分已被「继承式 RBAC」实现超越）：现已新增**租户基础角色**与**用户组**管理页（作用域绑定 + 向下继承），前端在 `🏢 租户`/`👪 用户组` tab 与 UserEditor 的有效权限归因区。仍**不做**：per-tenant 角色（角色恒全局）、角色/组嵌套、ABAC/资源 ACL、scope CRUD。详见 `docs/平台工程/rbac-and-public-kb.md`。
- 不引入独立管理 SPA或 Console BFF；保留未来拆分边界。
- 不让 API Key进入 RBAC 管理中心；能力试用仍保留 Key兼容。
- 不实现共享 GraphRAG；当前 `GraphRetrievalSource` 不并公共分区，UI不得宣称支持。
- 不解决 knowledge 多 sink 的分布式事务；只提供准确状态、监控和失败恢复说明。
- 本规划阶段不修改任何业务代码。

## 3. 已确认业务规则

1. Role 全局，tenant 与角色正交；用户只有一个 tenant。
2. RBAC开启时 `effectiveScopes = directScopes ∪ expand(roles)`；关闭时仅 direct scopes。
3. 登录/refresh响应中的 `UserView.scopes` 是有效 scopes；不含 roles。
4. 下游只认 scopes，不传 roles；API Key也仍直接绑定 scopes。
5. `/auth/admin/**` 必须有 `role-admin`；这是平台级权限。
6. 前端 scope gate仅改善体验；直接请求仍必须由后端返回403。
7. 管理中心只使用登录 Bearer，不使用 `sessionStore` 中优先级更高的 API Key。
8. 本期 direct scopes 只读展示，不在 UI 修改；历史 direct-only用户继续有效，新用户通过 roles授权。
9. 用户 tenant 不得为 `__public__`；注册页不暴露 tenant/role选择。
10. 注册默认关闭，且当前实现要求 RBAC与registration同时开启；成功注册即登录。
11. 共享库是 `__public__` 保留分区；读与当前租户合并不允许读取其它真实租户。
12. 租户写需 `ingest`；共享写/删需 `public-ingest`；共享图片当前不支持。
13. 普通登录用户在共享开关开启时可看共享文档元数据；**待验证：**是否允许未来查看完整正文，当前 `DocumentInfo`本身不含正文。
14. 角色/用户权限变化只在新 access token中体现；旧 token不回溯，前端必须承认该延迟。
15. 创建以 username/role name唯一键去重；更新采用 version/ETag检测陈旧写。
16. 不能删除/禁用/降权到零个“effective scopes含 `role-admin`”的启用用户（不只检查角色名）；必须由后端在同一事务/临界区保护。

## 4. 当前代码与调用链

### 4.1 认证与租户传播

```text
LoginView -> authStore.login -> POST /auth/login
  -> AuthController.login
  -> AuthService.login -> issueFor
  -> RoleService.effectiveScopes（RBAC开时）
  -> SessionTokenIssuer.mintAccessToken
  -> access JWT(sub=tenant, uid=userId, scopes)
  -> SessionBearerAuthFilter.verify
  -> InternalToken.mint
  -> InternalTokenAuthFilter
  -> TenantContext.Tenant
  -> downstream hasScope(...)
```

refresh复用 `AuthService.issueFor`，因此能读取最新角色；现有前端 `authStore.refresh()` 已做进程内 single-flight。API Key走 `ApiKeyToInternalTokenFilter`，不经过 auth-service/RBAC。

### 4.2 前端请求链

`sessionStore.runContext()` 当前使 API Key覆盖 Bearer；`runCapability/streamCapability` 据此发请求。`authorizedFetch` 只在使用 Bearer且401时 refresh一次。`executionGate` 目前不读取登录 scopes，对 `scope-required` 只提示后等待403。

`App.vue` 当前让所有非公开路由依赖 capability catalog；未来 admin路由必须脱离此门禁。

### 4.3 当前 Admin API与缺口

当前已有用户 list/create/full update/assign roles/delete，以及角色 list/save/delete；每个 controller方法调用 `requireRoleAdmin()`。缺口：无分页/详情/effective scopes/version；nullable update会把 roles null变空、enabled null变true；角色保存是upsert；删除不保护引用/最后管理员；AdminService未使用已存在的 mutation executor；未撤销受影响 refresh sessions；`adminWritesEnabled`尚未生效。

### 4.4 当前共享知识链

`DocumentController` 已接受 visibility 并校验 `public-ingest`；`DocumentService.upload(...,shared)` 用 `__public__` 写入各 sink。查询的 vector/keyword/ES会并公共分区，graph不会。文档 list/get/delete仍固定当前 tenant，query hit也无 visibility；前端目录上传参数还没有 visibility。

### 4.5 当前分支开工阻塞项

- `AuthService` 生产构造器是11参，但多个现有测试仍调用8参；必须先修复并跑绿。
- `AuthProperties.rbac.adminWritesEnabled`、bootstrap admin、trustForwardedFor当前仅声明未接生产逻辑。
- 当前 WIP测试进度文档与实际构造器已漂移，实施以源码和实际测试为准。

## 5. 候选方案与评分

评分1–5，5最好；复杂度/测试难度/回滚成本的5表示更低。

| 方案 | 正确性 | 改动风险 | 复杂度 | 可维护性 | 扩展性 | 测试难度 | 回滚成本 | 总分 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A 现有SPA最小增量 | 2 | 5 | 5 | 2 | 2 | 4 | 5 | 25 |
| B 同SPA领域化+契约加固 | 5 | 3 | 3 | 5 | 4 | 3 | 4 | **27** |
| C 独立RBAC管理SPA | 5 | 2 | 2 | 4 | 5 | 2 | 4 | 24 |
| D Console BFF+服务端会话 | 5 | 1 | 1 | 4 | 5 | 1 | 2 | 19 |

A易回滚但保留静默覆盖和共享管理缺口；C长期边界清楚但会复制壳/认证并引出跨SPA refresh竞争；D浏览器令牌安全最好，却新增有状态服务、CSRF和运维依赖。详见四个方案文档和 `comparison.md`。

## 6. 最终方案及选择原因

### 6.1 方案

采用组合方案：

- 以B为骨架：同一SPA内建立独立admin domain，后端补分页、明确DTO、VERSION/ETag、事务和共享visibility合同。
- 吸收C的隔离：admin懒加载、Bearer-only、独立API/store，不让API Key和catalog门禁进入管理域。
- 吸收A的回滚：UI kill switches、GET/list合同兼容、后端写开关和共享开关独立。当前mutation是未发布WIP，首次发布前直接收敛为安全合同，不保留可绕过乐观锁的旧写别名。
- 不采用D：本期不新增BFF/服务端session；前端API边界保留未来迁移可能。

### 6.2 选择原因

- 最大复用现有 authStore、tokens、路由、组件和RAG工作台。
- 不增加新的运行时故障点，符合当前只有一个静态SPA的仓库规模。
- 把真正的正确性放在auth/knowledge服务，不用UI假装解决并发和越权。
- 普通能力路径保持稳定，管理代码可按路由懒加载。
- schema变更加法且可被旧代码忽略，灰度/回滚清晰。

### 6.3 已知弱点

- 整个控制台仍是一个发布单元，不如独立admin SPA可单独发布。
- 跨auth、knowledge、edge的合同加固增加协调成本。
- 已签access token的撤权延迟最长仍接近TTL；撤销refresh不能即时撤销access。
- knowledge多sink入库/替换仍非事务。
- scope说明仍是前端已知字典，不是中央scope registry。
- 平台级 `role-admin` 权限很大，尚无tenant admin。

## 7. 最终信息架构与体验

### 7.1 路由

- `/login`：公开登录。
- `/register`：仅public-config确认开启时可达。
- `/`、`/m/**`：现有能力域，需要登录，可选catalog门禁。
- `/admin` -> `/admin/users`：需要Bearer + `role-admin`。
- `/admin/roles`：需要Bearer + `role-admin`。
- `/forbidden`：无权限深链落点。

### 7.2 顶栏与导航

- 身份chip显示 username、tenant、凭证模式。
- API Key覆盖时显示高对比警告“能力请求将使用API Key，账号权限预判暂停”，并提供清除。
- 管理中心只在Bearer role-admin时出现；命令面板同规则。
- 管理页面不显示API Key输入，避免身份混淆。

### 7.3 用户页面

- 服务端分页，每页默认50；筛选 username/tenant/role/enabled，300ms防抖并取消旧请求。
- 列表显示 username、tenant、roles、effective scopes摘要、enabled、version。
- 详情抽屉/页面显示direct scopes只读、角色可编辑、可选密码重置。
- 冲突弹窗展示本地草稿与服务端最新字段差异；用户刷新后重做，不提供无脑覆盖。

### 7.4 角色页面

- scope按对话、知识、智能体、审批、分析、通道、多模态、平台管理分组。
- scope说明来源为计划新增 `scopeCatalog.ts`，真实角色中的未知scope仍显示和保留。
- 编辑前显示绑定用户数；删除在用角色链接到用户筛选。

### 7.5 RAG页面

- “当前租户库 / 共享知识库”tabs；共享功能关闭时只展示租户库和准确说明。
- 上传显式选择visibility；共享文本确认全租户可检索，共享图片禁用。
- 文档展示visibility、uploadedAt、version、segmentCount、sizeBytes、category。
- 查询命中用服务端visibility显示“租户/共享”badge，不通过名称/docId推断。

## 8. 精确修改清单

### 8.1 前端现有文件

| 文件 | 类/函数/区域 | 修改 |
|---|---|---|
| `src/main.ts` | `bootstrap()` | auth bootstrap后加载最小public config；失败不阻断登录 |
| `src/App.vue` | route shell/catalog gate | 按route meta决定是否需要catalog；admin不受catalog失败阻塞 |
| `src/config.ts` | 常量 | 新增 `RBAC_CONSOLE_ENABLED`、`SHARED_KB_UI_ENABLED`、`DEMO_LOGIN_ENABLED` kill switches |
| `src/router/index.ts` | routes、`resolveAuthNavigation` | 新增register/admin/forbidden懒加载路由；增加requiredScopes与Bearer-only裁决；保留redirect清洗 |
| `src/api/auth.ts` | DTO、请求函数 | 新增计划接口 `registerRequest`、`fetchPublicAuthConfig`；保持login/refresh合同 |
| `src/stores/auth.ts` | state/computed | 保存public config状态；新增`hasScope`；refresh后严格替换user/scopes |
| `src/stores/session.ts` | credential computed | 暴露 `credentialMode=bearer|api-key|none`、`apiKeyOverridesBearer`；能力域继续Key优先 |
| `src/api/authorizedFetch.ts` | 请求包装 | 提取Bearer-only管理请求路径；401仍single-flight且只重试一次 |
| `src/api/errors.ts` | `humanizeError` | 按credential mode解释401/403；区分409业务冲突、412版本冲突、428缺前置版本和503 writes disabled |
| `src/utils/gate.ts` | `GateContext`、`executionGate` | 接收credentialMode/effectiveScopes；Bearer预判、API Key unknown；保持flag/danger优先级 |
| `src/components/layout/AuthControl.vue` | template/actions | 显示tenant/模式/API Key覆盖警告；admin域不显示Key入口 |
| `src/components/layout/AppHeader.vue` | header links | 增管理入口与身份信息空间；响应式收纳 |
| `src/components/layout/SideNav.vue` | nav groups | 能力/管理分区同一permission source；缺权能力保持可发现 |
| `src/components/common/CommandPalette.vue` | searchable actions | 同步管理入口和scope裁决，避免旁路 |
| `src/components/capability/CapabilityRunner.vue` | gate | 传credential mode和登录scopes；缺权说明精确 |
| `src/composables/useCapabilityRun.ts` | `run()` | 从session取得统一permission context，避免通用runner与专用页面裁决漂移 |
| `src/modules/auth/LoginView.vue` | demo/register | 去除静态包内 `DEMO_PASSWORD` 自动提交；demo卡只填用户名或由demo flag开启；显示注册入口 |
| `src/modules/rag/RagWorkspaceView.vue` | document/query workspace | 双visibility tab、typed DocumentInfo、共享上传确认/删、query badge、完整元数据 |
| `src/modules/chat/ChatConsoleView.vue`、`agent/AgentLabView.vue`、`analytics/AnalyticsLabView.vue`、`workflow/WorkflowDeskView.vue`、`tasks/AsyncMonitorView.vue`、`channel/ChannelConsoleView.vue`、`interop/InteropEvalView.vue` | 所有真实 `executionGate(...)` 调用点 | 统一改用 `session.permissionContext()`；不得保留旧的仅hasCredential裁决 |
| `capabilities.yml` | rag upload specs | file加multipart `visibility`，JSON加body `visibility`；scope/说明与真实controller对齐 |
| `public/catalog.json` | generated artifact | 只通过 `npm run gen:catalog` 更新 |
| `.env.example` | VITE配置 | 文档化三个kill switches；明确构建期语义 |
| `vite.config.ts` | dev proxy | 保持 `/auth`；确认 `/rag/config` 走业务前缀 |
| `nginx.conf` | reverse proxy | 保持auth同源；若管理API仍/auth无需新location；补安全headers实施时评审 |
| `Dockerfile` | ARG/ENV | 增三个Vite build args，生产demo默认false |
| `package.json` | scripts/devDependencies | 计划增加Playwright脚本/依赖；不引重量UI库 |

### 8.2 前端计划新增文件

| 文件 | 计划新增职责 |
|---|---|
| `src/api/admin.ts` | Bearer-only users/roles API；解析ETag/X-Total-Count；不引用API Key |
| `src/api/knowledge.ts` | visibility-aware文档list/get/delete与runtime config |
| `src/types/admin.ts` | UserSummary/UserDetail/RoleView/page/conflict DTO |
| `src/types/knowledge.ts` | DocumentInfo/Visibility/KnowledgeRuntimeView |
| `src/stores/adminUsers.ts` | 分页筛选、详情快照、version、局部失效 |
| `src/stores/adminRoles.ts` | 角色列表、绑定计数、version、scope保留 |
| `src/composables/usePermission.ts` | 单一scope/credential裁决与缺权原因；与session `permissionContext()`衔接 |
| `src/composables/usePagedQuery.ts` | debounce、abort、乱序响应保护 |
| `src/config/scopeCatalog.ts` | 已知scope的人话说明；未知值不丢弃 |
| `src/modules/auth/RegisterView.vue` | 注册表单与动态密码策略 |
| `src/modules/admin/AdminLayout.vue` | 管理域壳与子导航 |
| `src/modules/admin/UsersView.vue`、`UserEditor.vue` | 用户列表/编辑闭环 |
| `src/modules/admin/RolesView.vue`、`RoleEditor.vue` | 角色列表/scope编辑闭环 |
| `src/modules/admin/ForbiddenView.vue` | 权限不足/会话变化提示 |
| `src/components/admin/ScopePicker.vue`、`RolePicker.vue` | 无障碍选择器 |
| `src/components/admin/VersionConflictDialog.vue` | 412版本差异处理；409走对应业务错误展示 |
| `src/components/admin/DangerConfirmDialog.vue` | 删除/禁用/共享写确认 |
| 对应 `*.test.ts`、`e2e/*.spec.ts`、`playwright.config.ts` | 单元/组件/E2E |

### 8.3 auth-service现有文件

| 文件 | 类/方法 | 修改 |
|---|---|---|
| `AuthService.java` | 构造器、`register`、`refresh`、`issueFor` | 先修测试签名；register传纯client IP；保持最新scope签发；明确session撤销行为 |
| `AuthController.java` | 计划新增 `publicConfig()`；`clientIp(...)` | `GET /auth/public-config`；遵从trustForwardedFor；仅返回注册开关/密码长度 |
| `AdminController.java` | `listUsers`、计划新增`config/getUser/patchUser/replaceRoles/getRole/updateRole`、`requireAdminWritesEnabled` | `GET /auth/admin/config` 返回合同/写状态；查询分页/筛选；所有更新/删除必须If-Match；首次发布前移除当前WIP的无版本upsert/全量覆盖语义 |
| `AdminService.java` | create/update/assign/delete/saveRole/deleteRole | 注入PasswordPolicy/RoleService/RefreshSessionStore/RbacMutationExecutor；partial update；引用/最后管理员保护；权限变化撤refresh |
| `AdminDtos.java` | records | 新增命名明确的directScopes/effectiveScopes/version/userId/assignedUserCount；冲突体；密码只入不出 |
| `AuthProperties.java` | Rbac/ClientIp | 真正消费adminWrites/trust配置；移除当前未实现且会造成“自动重授管理员”歧义的`bootstrapAdminUsers`；增加只读状态输出但不泄露规则 |
| `UserAccount.java` | record/兼容构造器 | 计划新增末位`long version`；现有7参/6参构造器委托version=0，避免种子与测试一次性断裂 |
| `Role.java` | record/兼容构造器 | 计划新增末位`long version`；现有3参构造器委托version=0 |
| `UserAccountStore.java`及两实现 | 计划新增条件更新/分页查询 | `updateIfVersion(UserAccount,long)`、`deleteIfVersion(String,long)`、join/batch filter；避免`findByRole` N+1 |
| `RoleStore.java`及两实现 | 条件更新/引用查询 | `updateIfVersion(Role,long)`、`deleteIfVersion(String,long)`、assigned count；删除竞态在mutation executor内保护 |
| `RefreshSessionStore.java`及两实现 | `revokeByUsername/deleteByUsername` | 从已有端口接入AdminService变更流程 |
| `JdbcUserAccountStore.java` | `init/map/update` | USERS加VERSION；关系替换+version在外层事务原子提交 |
| `JdbcRoleStore.java` | `init/map/update` | ROLES加VERSION；ROLE_SCOPE替换+version原子提交 |
| `RbacMutationExecutor`及实现 | `execute/run` | AdminService所有复合写必须使用；内存/JDBC语义一致 |
| `PasswordPolicy.java` | `validate` | admin建户/重置密码与register共用 |
| `application.yml` | app.auth.* | 保持默认关闭；配置注释与实际读取一致 |

计划新增 auth DTO文件可放 `dto/AuthPublicConfig.java`；`AdminDtos`中计划新增 `AdminRuntimeView(contractVersion,rbacEnabled,writesEnabled,passwordMinLength,passwordMaxLength)`。实施者应遵循现有本地DTO风格，不能放密码/令牌到响应。

### 8.4 knowledge/platform-protocol现有文件

| 文件 | 类/方法 | 修改 |
|---|---|---|
| `platform-protocol/.../knowledge/KnowledgeHit.java` | record | 加法新增 `visibility`；更新全部构造点/合同测试 |
| 计划新增 `platform-protocol/.../knowledge/KnowledgeRuntimeView.java` | record | `contractVersion/publicEnabled/sharedImagesSupported`，供 `GET /rag/config` |
| `DocumentController.java` | `list/get/delete` | 增visibility query；public list策略；public delete用public-ingest；旧无参数默认tenant |
| `DocumentService.java` | 计划新增 `list/get/delete(..., boolean shared)` overload | 复用registry的tenant参数；现有方法delegate shared=false |
| `DocumentInfo.java` | record | 已有tenantId等字段；合同明确visibility可由tenantId在controller映射，不必破坏record |
| `KnowledgeQueryController.java` | `toReply`、计划新增`config()` | 映射visibility；`GET /rag/config`返回运行时共享状态 |
| `KnowledgeQueryService.java` | `Hit`、计划新增`publicKbEnabled()` | Hit携shared/visibility信息；只读暴露运行时状态 |
| `RetrievalHit.java` | record | 计划新增 `boolean shared`，贯穿融合 |
| `VectorRetrievalSource.java` | `searchTenant/toHit` | 根据查询tenant是否public设置shared |
| `InMemoryKeywordRetrievalSource.java` | `retrieve` | 从segment metadata tenantId设置shared |
| `EsSearchHit.java`、`ElasticsearchEsGateway.java` | record/search mapping | 返回tenantId，供ES hit设置shared |
| `EsKeywordRetrievalSource.java` | `retrieve` | public第二次查询的hit标shared |
| `GraphRetrievalSource.java` | `retrieve` | shared恒false；文档明确graph未公共化 |
| `HybridFusionService.java` | `toHit/fuseRrf/mergeHits` | 融合时保留一致visibility；若冲突fail-safe并测试 |
| `application.yml` | app.rag.public | 保持默认false，config响应反映真实值 |

### 8.5 edge/deploy/docs

| 文件 | 修改 |
|---|---|
| `edge-gateway/.../EdgeOpenPaths.java` | 精确放行计划新增 `/auth/public-config`；`/auth/admin/**`和`/rag/config`仍鉴权 |
| `edge-gateway/src/main/resources/application.yml` | allowedHeaders加`If-Match`；exposedHeaders加`ETag,X-Total-Count`；不使用通配origin |
| `deploy/docker-compose.yml`、`docker-compose.rag-full.yml` | 注入实际RBAC/registration/public flags；前端build args；顺序灰度默认false |
| `deploy/helm/platform/values.yaml`及实际templates | 配置同上；secret不进Vite args |
| `deploy/start-dev.sh/start-all.sh/start-local.sh` | 输出真实开关/登录方式；不打印密码/token |
| 前端README、`docs/平台工程/能力展示控制台.md`、`rbac-and-public-kb.md` | 更新UI流程、合同、灰度和限制 |

## 9. 数据库、接口、配置与消息结构

### 9.1 数据库

前端无数据库，不新增浏览器持久化。

auth JDBC计划加法迁移：

```sql
ALTER TABLE USERS ADD COLUMN VERSION BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ROLES ADD COLUMN VERSION BIGINT NOT NULL DEFAULT 0;
```

实际实现继续沿用当前store内联幂等DDL约定；只能吞“列已存在”，其它DDL错误fail-fast。旧行version=0。更新使用 `WHERE PK=? AND VERSION=?` 并原子 `VERSION=VERSION+1`。不删除CSV影子列，不新增knowledge表。

迁移前/后对账：USERS↔USER_ROLE、ROLES↔ROLE_SCOPE数量与引用；孤儿关系必须在开启写UI前清理或阻断并报告。

首个管理员不通过每次启动自动授权：demo继续由现有`SeedUsers`给alice绑定admin；生产在`AUTH_SEED_ENABLED=false`时使用受控一次性SQL/迁移创建首个用户、admin角色及USER_ROLE关系，并在启用RBAC前验证其effective scopes含`role-admin`。当前未被代码消费的`bootstrapAdminUsers`配置应移除，避免运维误以为它已生效或重启后意外重授权限。

### 9.2 接口

计划新增/扩展：

- `GET /auth/public-config`（open，最小非敏感配置）。
- `GET /auth/admin/config`（需`role-admin`）：返回`contractVersion=2`、rbacEnabled、writesEnabled和密码长度，供新UI能力协商并fail-closed。
- `GET /auth/admin/users` 增offset/limit/q/tenant/role/enabled，响应头`X-Total-Count`。
- `GET /auth/admin/users/{username}`。
- `PATCH /auth/admin/users/{username}` + 必需 `If-Match`；陈旧返回412。
- `PUT /auth/admin/users/{username}/roles` + 必需 `If-Match`；当前WIP的POST分配接口在首次发布前替换，不保留绕过入口。
- `POST /auth/admin/roles` 仅创建（重复409）；`PUT /auth/admin/roles/{name}` 更新且必需 `If-Match`。
- 用户/角色DELETE也必需 `If-Match`，避免基于旧页面删除已变化对象。
- Admin views新增directScopes/effectiveScopes/version等明确字段。
- `GET /auth/admin/roles/{name}`，详情返回ETag。
- `GET /rag/config`（需认证，返回固定contractVersion和真实publicEnabled/sharedImagesSupported）。
- `GET /rag/documents?visibility=tenant|public`；GET/DELETE单文档同样接受visibility。
- `KnowledgeHit.visibility` 加法字段。

稳定错误体至少 `{error,message}`。业务冲突（重复名、角色被引用、最后管理员保护）使用409；所有If-Match陈旧统一使用412 `precondition_failed`，响应含`currentVersion`和安全的当前资源摘要。缺少If-Match返回428 `precondition_required`。该选择必须在合同测试冻结。

### 9.3 配置

现有后端：`AUTH_RBAC_ENABLED`、`AUTH_RBAC_ADMIN_WRITES_ENABLED`、`AUTH_REGISTRATION_ENABLED`、password policy、`RAG_PUBLIC_ENABLED`。

计划新增前端构建开关：

- `VITE_RBAC_CONSOLE_ENABLED=false`
- `VITE_SHARED_KB_UI_ENABLED=false`
- `VITE_DEMO_LOGIN_ENABLED=false`

Vite flag只可强制关闭；是否可用仍由服务端运行时config/scopes决定。生产不得把密钥、demo密码、角色映射规则烘焙进静态包。

### 9.4 消息结构

本期不新增Kafka/eventbus消息，不修改现有消息结构。RBAC和共享知识UI以同步HTTP为准。未来若引入跨页面即时权限失效，可另行设计低敏 `rbac.policy.changed`，但不能在本期文档中当作已存在。

## 10. 分阶段实施与依赖

```text
阶段0 后端WIP收口
  -> 阶段1 数据结构与领域模型
  -> 阶段2 核心业务逻辑
  -> 阶段3 接口与适配层
  -> 阶段4 测试/灰度演练
  -> 阶段5 文档与最终检查
```

### 阶段0 — 开工门槛

1. 修复AuthService构造器与测试调用不一致。
2. 运行auth/knowledge/edge现有测试，建立真实baseline。
3. 冻结当前Admin DTO与shared KB WIP，不再并行改签名。
4. 移除或明确禁用当前未生效的bootstrap admin配置；按“demo seed / 生产受控SQL”冻结首管理员流程。

完成标准：三个相关模块编译、现有测试绿；git diff确认不覆盖无关用户改动；阻塞项记录清零。

### 阶段1 — 数据结构与领域模型

1. USERS/ROLES加VERSION、map与条件更新；H2/MySQL幂等迁移测试。
2. `UserAccount`/`Role`增加version及兼容构造；Admin DTO增加明确direct/effective/version字段；GET/list的旧JSON消费者保持加法兼容。
3. `KnowledgeHit`/`RetrievalHit`增加visibility/shared并全链编译修复。
4. 前端新增admin/knowledge types、scope catalog和permission纯函数。

依赖：阶段0。

完成标准：旧数据无损，重复迁移幂等；旧direct scopes不变；所有record构造点编译；纯类型/permission单测绿。

### 阶段2 — 核心业务逻辑

1. AdminService全部mutation进入RbacMutationExecutor。
2. 实现partial update、version冲突、引用保护、最后管理员并发保护、统一PasswordPolicy、refresh session撤销。
3. 优化分页/role filter，消除当前findByRole N+1。
4. DocumentService新增public list/get/delete overload；query visibility贯穿vector/keyword/ES/fusion。
5. 前端admin stores实现分页、abort、草稿/version/conflict；permission gate升级。

依赖：阶段1。

完成标准：同version并发写仅一个成功；事务故障完整回滚；无孤儿关系；tenant/public正负隔离测试绿；前端store不自动重放mutation。

### 阶段3 — 接口与适配层

1. AuthController增加public-config，修client IP语义。
2. AdminController增加分页/详情/PATCH/幂等PUT/写开关；详情返回ETag，更新/删除必需If-Match，缺失428、陈旧412。
3. KnowledgeQueryController增加runtime config；DocumentController增加visibility管理。
4. Edge精确open path与CORS headers。
5. 前端拆route shell，新增register/admin页面与组件，改顶栏/侧栏/命令面板。
6. RAG双tab、visibility上传/删除/命中badge；更新capabilities.yml并生成catalog。
7. Docker/compose/Helm接入kill switches和真实后端flags。

依赖：阶段2合同稳定。

完成标准：强类型前端只调用冻结合同；不存在无If-Match可绕过的admin更新/删除入口；admin请求即使有API Key也只带Bearer；catalog失败不阻塞admin；生产包无demo密码；GET/list兼容回归通过，分支内旧mutation脚本已同批迁移。

### 阶段4 — 测试

1. 完成 `test-plan.md` 的前端单元/组件/合同/E2E。
2. 完成auth并发/事务/migration、knowledge隔离/visibility、edge CORS/open path测试。
3. 10k用户分页与前端性能预算测试。
4. 灰度开关组合、新旧前后端兼容、回滚演练。
5. 安全测试：XSS、凭证不落盘、管理Bearer-only、跨tenant负向断言。

依赖：阶段3。

完成标准：所有命令绿；性能/安全/回滚验收无P0/P1缺陷；失败截图和日志无敏感信息。

### 阶段5 — 文档与最终检查

1. 更新前端README、能力控制台、RBAC/共享库和部署文档。
2. 生成接口示例与错误码矩阵；标明graph不共享、access撤权延迟、direct scopes只读。
3. 检查Vite静态包、CORS、cookie、flags、Helm模板实际渲染。
4. `git diff --check`、`git status`、生成物一致性、无密钥扫描。

依赖：阶段4。

完成标准：另一个开发Agent只读文档即可部署/验证；文档与真实默认值一致；最终diff无无关文件。

## 11. 测试方案摘要

完整矩阵见 `test-plan.md`。最低必过：

- permission：Bearer满足/缺权、API Key unknown、admin Bearer-only。
- auth：login/refresh/logout/register/public-config、single-flight、scopes替换。
- admin：分页、partial update、空密码不发送、409业务冲突/412版本冲突/428缺If-Match、最后管理员、角色引用、session撤销。
- migration：baseline/CSV/current schema三路径、重复启动、回滚注入。
- shared KB：A/B都见public、B不见A私有、public off回归、visibility融合不丢、public delete授权。
- UI：深链、Forbidden、键盘/焦点、移动布局、API Key覆盖提示。
- E2E：admin建角色/用户/登录生效；两浏览器并发冲突；共享入库/跨tenant查询。
- 安全：静态包/storage/URL/log无凭证；XSS文本化；CORS精确。

建议执行命令（实施阶段）：

```bash
cd capability-showcase-frontend
npm test
npm run type-check
npm run build
npm run test:e2e

cd ..
mvn -pl auth-service -am test
mvn -pl knowledge-service -am test
mvn -pl edge-gateway -am test
```

## 12. 风险、监控、灰度与回滚

### 12.1 主要风险与缓解

| 风险 | 缓解 |
|---|---|
| access token撤权延迟 | 撤refresh；高风险自改退出；记录TTL；后续token version另案 |
| 最后管理员/角色引用竞态 | mutation executor内重查并写；version；并发测试 |
| API Key身份混淆 | admin Bearer-only；顶栏显著覆盖状态；统一permission source |
| shared多sink部分写 | traceId、结构化日志、sink失败指标、后台对账/重灌脚本；UI不宣称原子 |
| VERSION迁移失败 | 加法DDL、备份、幂等H2/MySQL验证、非duplicate fail-fast |
| public数据误暴露 | 保留tenant禁止注册；所有源明确tenant过滤；A/B负向测试 |
| scope catalog漂移 | 目录requiredScopes+静态说明；未知scope原样保留；合同测试 |
| 管理页面拖慢普通用户 | route lazy-load；admin不依赖catalog；bundle budget |
| 新旧合同混跑 | capabilities/version检测；写UI fail-closed；当前未发布mutation在首次发布前一次收敛，不维持双写合同 |

### 12.2 监控

计划新增低基数指标/结构化日志（名称实施时按现有Micrometer命名规范确认）：

- admin请求：成功/403/409业务冲突/412版本冲突/428缺版本/503写关闭、action、target type，不记录target明文高基数字段到metric tag。
- auth refresh失败率、registration 409/429、unknown role告警。
- knowledge shared upload/query/delete成功失败、各retrieval source失败和shared hit数量。
- DB migration状态、USER_ROLE/ROLE_SCOPE漂移计数。
- 前端用 `X-Trace-Id` 展示可复制诊断ID；不把token/password送监控。

告警建议：5分钟admin mutation 5xx>1%、冲突率异常突增、shared upload失败连续3次、migration drift>0、403相对基线上升3倍。

### 12.3 灰度顺序

1. schema加VERSION，RBAC/public/UI flags全关。
2. 开RBAC角色展开但admin writes关，验证登录/refresh和下游scopes。
3. 部署新前端，RBAC UI开但只读；仅alice等bootstrap admin验证列表。
4. 开admin writes，小范围创建测试角色/用户；观察冲突/撤session。
5. 开shared UI只读和query visibility；`RAG_PUBLIC_ENABLED`小流量验证隔离。
6. 最后开public upload/delete和registration；生产demo login保持关。

### 12.4 回滚

- UI异常：关闭 `VITE_RBAC_CONSOLE_ENABLED`/`VITE_SHARED_KB_UI_ENABLED`并回滚静态镜像；能力/login仍可用。
- Admin写异常：先关 `AUTH_RBAC_ADMIN_WRITES_ENABLED`，保留GET诊断；不立即关RBAC角色展开。
- 角色展开异常：关 `AUTH_RBAC_ENABLED`回direct scopes；注意role-only新用户会失权，因此写开放前必须有回滚对账/物化方案。
- Shared异常：关 `RAG_PUBLIC_ENABLED`和shared UI；保留 `__public__` 数据，不删除集合/索引。
- schema回滚：旧代码忽略VERSION列；不drop列/表。若需恢复对象，使用备份和审计日志，不手工猜关系。
- edge/CORS异常：回滚headers/open path；public-config不可用时注册入口fail-closed。

## 13. 最终验收清单

- [ ] 当前RBAC WIP编译与baseline测试先恢复为绿。
- [ ] login/refresh返回的effective scopes与角色/直配并集一致，RBAC off回归direct-only。
- [ ] 普通用户无管理入口，直接深链到Forbidden，直接HTTP仍403。
- [ ] admin API在API Key已填写时仍只使用Bearer。
- [ ] 用户列表服务端分页/筛选，无N+1；10k数据性能达标。
- [ ] 创建/编辑/角色替换/启停/删除完整，密码不回显、不落盘、空值不误改。
- [ ] 两管理员并发编辑不会静默覆盖；冲突保留草稿。
- [ ] 角色删除引用保护、最后管理员保护在并发下成立。
- [ ] 用户/角色权限降低按规则撤销refresh；旧access延迟被文档和UI明确说明。
- [ ] 注册入口运行时遵从开关/密码策略；用户不能选tenant/role；429/409可读。
- [ ] 顶栏显示username、tenant、credential mode，API Key覆盖显著。
- [ ] Bearer能力按effective scopes预判；API Key保持unknown反应式鉴权。
- [ ] 租户库/共享库tabs、共享文本确认、共享图片禁用、共享删权正确。
- [ ] tenant A/B都能查共享，B不能查A私有；public off行为回归。
- [ ] 查询hit的visibility来自服务端合同，vector/keyword/ES融合不丢；graph限制明确。
- [ ] admin路由不被catalog失败阻塞，管理代码懒加载，性能预算达标。
- [ ] access token/API Key/password不进入storage、URL、日志、截图或生产静态包。
- [ ] CORS/cookie/redirect/XSS/键盘焦点测试通过。
- [ ] 前端、auth、knowledge、edge测试和E2E全绿。
- [ ] UI/后端flags组合、旧前端+新后端、新前端+兼容后端、回滚演练通过。
- [ ] VERSION迁移幂等，CSV/关系表数据对账无漂移，旧代码可忽略新列。
- [ ] Compose/Helm实际渲染配置与文档一致，无secret进入Vite args。
- [ ] 最终文档、接口fixture、生成catalog和代码一致；diff无无关改动。

## 14. 实施交接提示

实施Agent应先读取本文件、`02-codebase-analysis.md`和`test-plan.md`，从阶段0开始连续执行。不要直接先画管理页面；只有后端编译绿、Admin DTO/并发语义/共享visibility冻结后，才进入页面实现。任何待验证业务项若会改变数据可见性或服务Key权限，必须暂停向用户确认。

## 15. 资深架构终审记录

终审已再次对照当前源码完成，并修复以下初稿遗漏/矛盾：

- visibility不只影响knowledge内部 record，还必须修改真实跨服务DTO `platform-protocol/.../KnowledgeHit.java`。
- `executionGate(...)` 不只由通用runner调用；已把chat/agent/analytics/workflow/tasks/channel/interop/RAG等全部真实调用点列入修改清单。
- 数据库有VERSION但领域对象无版本会无法实现条件更新；已明确 `UserAccount`、`Role` 增末位version并保留兼容构造器。
- 一边声称防静默覆盖、一边保留无If-Match旧写接口自相矛盾；现统一为缺If-Match=428、陈旧=412、业务冲突=409，当前未发布WIP mutation在首次发布前一次收敛。
- 前端需要知道admin writes是否开启；已增加受`role-admin`保护的admin config与合同版本，而不是靠失败猜测。
- 当前`bootstrapAdminUsers`仅声明未执行，且每次启动自动重授权限危险；已改为demo seed + 生产受控一次性SQL，并要求移除误导配置。
- 明确本期无消息结构变化，避免把未来权限变更事件当作现有能力。

终审后仍保留的唯一业务决策点是“普通登录用户能否列出共享文档元数据”；已在业务规则中标记待验证，实施前确认即可，不影响先完成认证、RBAC管理和visibility合同基础设施。
