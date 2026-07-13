# 现有代码与影响面分析

> 视角：codebase-explorer。分析基于 2026-07-13 的 `feat/rbac-shared-kb` 工作树。该工作树含大量未提交 RBAC/共享库改动；本文区分“当前已存在”与“计划新增”。本轮未运行 Maven/npm 测试，因为任务限制只允许向本规划目录写入，而构建会写 `target/`、缓存或生成目录。

## 1. 仓库与分支状态

- 当前提交为 `7d12d62`，与 `main`/`origin/main` 同点；RBAC/共享库主要存在于未提交工作树。
- `capability-showcase-frontend` 本轮没有工作区改动，仍是已提交的登录版能力展示 SPA。
- 当前 RBAC 工作树尚未收口：`AuthService` 生产构造器已有 11 个参数，但 `AuthServiceTest`、`AuthControllerTest`、`RegistrationTest` 仍调用旧 8 参数构造器；静态阅读可确认编译签名不一致。
- `AuthProperties.rbac.adminWritesEnabled`、`bootstrapAdminUsers` 和 `clientIp.trustForwardedFor` 目前只有配置声明，未发现生产逻辑读取。

这意味着前端实施必须以“后端先编译绿并冻结契约”为门槛，不能把当前 WIP 当成稳定 API。

## 2. 当前前端结构

### 2.1 构建和部署

- `capability-showcase-frontend/package.json`：Vue 3.5、Pinia 2、vue-router 4、Vite 6、Vitest；没有组件库、请求缓存库或 E2E 框架。
- `vite.config.ts`：dev 代理业务前缀与 `/auth` 到 edge；`VITE_EDGE_BASE_URL` 同时决定代理目标。
- `nginx.conf`：只把 `/auth/` 同源反代到 `edge-gateway:8080`；业务能力通常按构建期 `VITE_EDGE_BASE_URL` 直调。
- `Dockerfile`：只接收 `VITE_EDGE_BASE_URL` 和 `VITE_BASE` build args。
- `deploy/docker-compose.yml`：前端容器端口 8093，build 仅注入 edge URL；edge CORS 允许 Authorization/X-Api-Key 且 allowCredentials=true。

### 2.2 应用壳和路由

- `src/main.ts`：强制登录模式下先调用 `auth.bootstrap()`，再挂 router/app，避免首帧误跳。
- `src/router/index.ts`：只有 `/login`、总览、模块/能力和 fallback；守卫只判断“是否登录”，没有 scope/role 元数据。
- `src/App.vue`：公开登录页不套壳；所有其它路由统一被 capability catalog 的 loading/error 门禁包住。这会让未来管理页被无关目录失败阻塞。
- `src/components/layout/AppHeader.vue`、`SideNav.vue`：导航完全来自 capability catalog，没有管理中心分区。
- `src/modules/ModuleHost.vue`：按 module id 选择专用工作台；管理中心更适合独立路由而非伪装成 capability module。

### 2.3 登录与会话

调用链：

```text
LoginView.doLogin
  -> authStore.login
  -> api/auth.loginRequest POST /auth/login credentials=include
  -> authStore.setSession(accessToken, AuthUser)
  -> catalog.refreshLive(best effort)
  -> router.replace(sanitized redirect)

页面刷新
  -> main.bootstrap
  -> authStore.bootstrap/refresh single-flight
  -> POST /auth/refresh 携 httpOnly cookie
  -> 恢复 accessToken + user 到内存

业务请求
  -> sessionStore.runContext
  -> apiKey 非空则 X-Api-Key，否则 Authorization: Bearer
  -> authorizedFetch
  -> Bearer 401 时 single-flight refresh + 原请求重试一次
```

可复用代码：

- `src/api/auth.ts` 的 `postAuth/readJson` 和安全 cookie 约定。
- `src/stores/auth.ts` 的内存会话、single-flight refresh、bootstrap。
- `src/api/authorizedFetch.ts` 的 401 一次重试。
- `src/router/index.ts` 的 `sanitizeRedirect`。
- `src/api/errors.ts` 的 `ApiError` 基础结构。

需要改进：

- `AuthUser` 只有 username/tenant/scopes，没有 roles、token expiry 或 session version。
- `LoginView.vue` 在源码中硬编码 demo 密码并自动提交，且无注册入口。
- `AuthControl.vue` 只显示 username，tenant 藏在 title；API Key 覆盖状态不醒目。
- `humanizeError(401/403)` 始终按 API Key 话术，不适合 Bearer/RBAC。
- 管理 API 不能直接复用 capability `runCapability`：它是目录驱动的演示执行器，管理表单需要强类型 DTO、并发控制和字段级错误。

### 2.4 权限与能力目录

- `capabilities.yml`/生成的 `public/catalog.json` 是能力 requiredScopes 的事实源。
- `src/utils/gate.ts` 的 `executionGate` 只接收 `hasApiKey`（实为 hasCredential）和 confirmed。
- 对 `scope-required`，当前逻辑始终允许执行并提示“API Key 不透明，等 403”；即使是已知 scopes 的登录会话也不预判。
- `SideNav`、`CommandPalette`、`CapabilityRunner`、专用工作台各自读 catalog/gate，权限策略尚未统一到“credential mode + effective scopes”。

可复用代码：

- `Capability.requiredScopes` 类型和目录数据。
- `executionGate` 作为单一裁决点的设计位置。
- `ScopeBadge`、`StateBadge`、`InfoNote`、`EmptyState` 等轻量组件。

### 2.5 RAG 工作台

- `RagWorkspaceView.vue` 已提供租户文档列表/详情/删除、三种入库 runner、检索命中卡和 GraphRAG 区。
- 文档解析 `DocItem` 只取 docId/title/category，忽略后端 `DocumentInfo` 已有的 tenantId、sizeBytes、segmentCount、version、uploadedAt。
- 上传能力目录没有 `visibility` 参数，因此通用 DynamicForm 无法触发当前后端共享写入。
- 文档提示文本固定为“当前租户”；没有共享 tab。
- 查询 `Hit` 前端模型只解析 score/docId/category/text；后端也没有 visibility。

可复用代码：

- `callCap(...)` 的 gate + `runCapability` 组合。
- 检索结果解析、高亮、loading/error/empty 状态。
- `WorkbenchSection`、`ResultTable`、`InfoNote`。

## 3. 当前后端 RBAC 结构

### 3.1 认证传播链

```text
AuthController.login/refresh/register
  -> AuthService
  -> AuthService.issueFor
  -> RoleService.effectiveScopes (RBAC enabled 时)
  -> SessionTokenIssuer.mintAccessToken(user, effectiveScopes)
  -> access JWT(sub=tenant, uid=userId, scopes)
  -> edge SessionBearerAuthFilter.verify
  -> InternalToken.mint
  -> 下游 InternalTokenAuthFilter
  -> TenantContext.Tenant(tenantId,userId,scopes)
  -> controller/service hasScope(...)
```

API Key 走 `ApiKeyToInternalTokenFilter`，直接把静态 binding 的 tenant/user/scopes 签进内部 JWT，不经过角色展开。

### 3.2 真实领域模型与存储

- `UserAccount(username,passwordHash,tenant,userId,scopes,roles,enabled)`；scopes/roles 归一排序。
- `Role(name,scopes,description)`；角色名小写归一，`NAME_PATTERN` 常量存在但 `AdminService.saveRole` 当前只校验非空，没有使用该 pattern。
- `RoleService.expand/effectiveScopes/requireRolesExist`；未知历史角色 fail-closed。
- JDBC 表由 store 内联 DDL 创建：
  - `USERS(USERNAME,PASSWORD_HASH,TENANT,USER_ID,SCOPES,ROLES,ENABLED,CREATED_AT)`，其中 ROLES 是影子 CSV。
  - `USER_ROLE(USERNAME,ROLE_NAME,CREATED_AT)` 为用户角色权威关系。
  - `ROLES(NAME,SCOPES,DESCRIPTION,CREATED_AT)`，SCOPES 是影子 CSV。
  - `ROLE_SCOPE(ROLE_NAME,SCOPE,CREATED_AT)` 为角色 scope 权威关系。
  - `AUTH_SESSION(TOKEN_HASH,USERNAME,CREATED_AT,EXPIRES_AT,REVOKED)`。
- 无外键、无 VERSION/UPDATED_AT；控制面并发无法检测陈旧覆盖。
- `JdbcRbacMutationExecutor` 已存在，但当前 `AdminService` 不使用；其用户更新会先 `updateProfile` 再 `replaceRoles`，没有由 AdminService 明确包事务。

### 3.3 真实 HTTP 契约

认证：

- `POST /auth/login`、`POST /auth/register`、`POST /auth/refresh` 返回 `LoginResponse(accessToken,expiresInSeconds,user)`。
- `POST /auth/logout` 返回 204 并清 cookie。
- `GET /auth/me` 返回 `UserView(username,tenant,scopes)`。

管理：

- `GET /auth/admin/users` -> `List<UserAdminView>`。
- `POST /auth/admin/users` -> 201 + `UserAdminView`。
- `PUT /auth/admin/users/{username}` -> 全量 tenant/roles/enabled + 可选 password。
- `POST /auth/admin/users/{username}/roles` -> 全量替换 roles，但使用 POST。
- `DELETE /auth/admin/users/{username}` -> 200 `{username,deleted:true}`。
- `GET /auth/admin/roles` -> `List<RoleView>`。
- `POST /auth/admin/roles` -> upsert 语义。
- `DELETE /auth/admin/roles/{name}` -> 200 `{name,deleted:true}`。

当前 DTO 限制：

- `UserAdminView.scopes` 实际来自 `UserAccount.scopes()`，即 direct scopes，不是 effective scopes。
- 没有 userId、effectiveScopes、version、updatedAt、绑定用户数。
- 用户列表无分页/筛选；角色无单项 GET/明确 update。
- `UpdateUserRequest` 字段为 nullable，但 controller 把 `roles=null` 转空集合、`enabled=null` 转 true，容易意外清角色/启用账号。
- `AdminService.createUser` 用固定最少 6 位，不调用已存在的 `PasswordPolicy`；update password 也未验证最大长度。
- 删除用户/角色对不存在目标仍返回成功；角色删除不检查引用；用户禁用/删权不撤销 refresh session。
- `adminWritesEnabled` 没被 controller 使用；默认 false 目前不能真正阻止写。

这些是前端不能自行弥补的契约/安全前置项。

## 4. 当前共享知识库结构

### 4.1 写入

- `DocumentController.uploadFile/uploadJson` 读取 visibility；`public|shared` 调 `requireWrite(true)`，要求 `public-ingest`。
- `DocumentService.upload(..., boolean shared)` 把 tenantId 切换为 `PublicKb.TENANT_ID`。
- 同一 tenantId 派生向量 store、DocumentMirror、ES metadata、graph ingest 和 DocumentRegistry。
- 共享图片返回 400 `public image ingestion not supported`。

### 4.2 查询

- `KnowledgeQueryService.query` 在 `app.rag.public.enabled=true` 时构造带 publicTenantId 的 `RetrievalRequest`。
- `VectorRetrievalSource` 分别查询当前 tenant 与 public tenant。
- `KeywordSearchService` 合并两个 DocumentMirror 分区。
- `EsKeywordRetrievalSource` 对两个 tenant 分别搜索后合并。
- `GraphRetrievalSource` 未使用 publicTenantId，当前共享图谱命中不并入。

### 4.3 管理缺口

- `DocumentService.list/get/delete` 固定使用 `TenantContext.current().tenantId()`。
- DELETE 只要求 `ingest`，且无法指向 public tenant。
- `KnowledgeQueryService.Hit` 与 `RetrievalHit` 不携带 visibility/tenantId；融合后无法可靠标记共享来源。
- `RAG_PUBLIC_ENABLED` 在 compose 中默认 false；前端没有运行时查询开关状态的接口。

## 5. 数据、事务、并发与幂等现状

- 用户名、角色名和复合主键提供创建去重基础；`createIfAbsent` 能处理同名并发创建。
- refresh 注册路径计划由 `RbacMutationExecutor` 包装；AdminService 当前没有统一使用该执行器。
- 用户角色和角色 scopes 是 delete-all + insert-all 全量替换。JDBC 只有在外层事务生效时才原子；内存多个 map 更新也需要同一临界区。
- 没有乐观锁。两位管理员读同一对象再更新时，后写静默覆盖。
- 角色删除不会原子检查 USER_ROLE 引用；删除后用户会保留未知 role 关系但展开为空。
- 文档重传在 `DocumentService.upload` 中先 `deleteInternal(prev)` 再写多个 sink，向量/内存/ES/graph/registry 不是数据库事务。
- 前端 `authorizedFetch` 会在网关鉴权阶段 401 后重试一次；因为 401 发生在业务路由前，对管理 mutation 的这次自动重试通常安全。网络异常不得自动重试 mutation。

## 6. 可复用代码清单

前端直接复用：

- `src/stores/auth.ts`：会话内存态、refresh single-flight。
- `src/api/authorizedFetch.ts`：Bearer 401 处理。
- `src/api/errors.ts`：扩展统一错误模型。
- `src/router/index.ts`：站内 redirect 校验。
- `src/utils/gate.ts`：扩展为 credential-aware permission gate。
- `src/components/common/EmptyState.vue`、`InfoNote.vue`、`WorkbenchSection.vue`、`ResultTable.vue`。
- `src/modules/rag/RagWorkspaceView.vue`：在原工作台上演进，而非另建重复 RAG 页面。
- 现有 CSS tokens、主题、密度、焦点 trap 和 responsive shell。

后端直接复用：

- `RoleService`、`UserAccountStore`、`RoleStore` 双实现。
- `RbacMutationExecutor`、`RefreshSessionStore.revokeByUsername/deleteByUsername`。
- `PasswordPolicy`。
- `DocumentService` 的 tenantId 派生和 DocumentRegistry overload 模式。
- `PublicKb.TENANT_ID`。
- edge 的 Bearer/API Key 双模与下游 TenantContext 链。

## 7. 受影响文件清单

以下是最终推荐方案的预计影响面；“计划新增”不是当前仓库事实。

### 7.1 前端修改

- `capability-showcase-frontend/src/main.ts`
- `capability-showcase-frontend/src/App.vue`
- `capability-showcase-frontend/src/config.ts`
- `capability-showcase-frontend/src/router/index.ts`
- `capability-showcase-frontend/src/stores/auth.ts`
- `capability-showcase-frontend/src/stores/session.ts`
- `capability-showcase-frontend/src/api/auth.ts`
- `capability-showcase-frontend/src/api/authorizedFetch.ts`
- `capability-showcase-frontend/src/api/errors.ts`
- `capability-showcase-frontend/src/utils/gate.ts`
- `capability-showcase-frontend/src/components/layout/AppHeader.vue`
- `capability-showcase-frontend/src/components/layout/AuthControl.vue`
- `capability-showcase-frontend/src/components/layout/SideNav.vue`
- `capability-showcase-frontend/src/components/common/CommandPalette.vue`
- `capability-showcase-frontend/src/components/capability/CapabilityRunner.vue`
- `capability-showcase-frontend/src/composables/useCapabilityRun.ts`
- `capability-showcase-frontend/src/modules/auth/LoginView.vue`
- `capability-showcase-frontend/src/modules/rag/RagWorkspaceView.vue`
- `capability-showcase-frontend/src/modules/chat/ChatConsoleView.vue`
- `capability-showcase-frontend/src/modules/agent/AgentLabView.vue`
- `capability-showcase-frontend/src/modules/analytics/AnalyticsLabView.vue`
- `capability-showcase-frontend/src/modules/workflow/WorkflowDeskView.vue`
- `capability-showcase-frontend/src/modules/tasks/AsyncMonitorView.vue`
- `capability-showcase-frontend/src/modules/channel/ChannelConsoleView.vue`
- `capability-showcase-frontend/src/modules/interop/InteropEvalView.vue`
- `capability-showcase-frontend/capabilities.yml`
- `capability-showcase-frontend/public/catalog.json`（由脚本生成，不手改）
- `capability-showcase-frontend/.env.example`
- `capability-showcase-frontend/vite.config.ts`
- `capability-showcase-frontend/nginx.conf`
- `capability-showcase-frontend/Dockerfile`
- `capability-showcase-frontend/package.json`

计划新增前端文件：

- `src/api/admin.ts`、`src/api/knowledge.ts`
- `src/types/admin.ts`、`src/types/knowledge.ts`
- `src/stores/adminUsers.ts`、`src/stores/adminRoles.ts`
- `src/composables/usePermission.ts`、`src/composables/usePagedQuery.ts`
- `src/config/scopeCatalog.ts`
- `src/modules/auth/RegisterView.vue`
- `src/modules/admin/AdminLayout.vue`、`UsersView.vue`、`UserEditor.vue`、`RolesView.vue`、`RoleEditor.vue`、`ForbiddenView.vue`
- `src/components/admin/ScopePicker.vue`、`RolePicker.vue`、`VersionConflictDialog.vue`、`DangerConfirmDialog.vue`
- 与上述 api/store/view/component 同目录的 `*.test.ts`
- `e2e/auth-rbac.spec.ts`、`e2e/shared-kb.spec.ts`、`playwright.config.ts`（计划引入 Playwright 时）

### 7.2 auth-service 前置/适配修改

- `auth-service/src/main/java/com/lrj/platform/auth/AuthService.java`
- `AuthController.java`、`AdminController.java`、`AdminService.java`
- `AuthProperties.java`、`PasswordPolicy.java`
- `UserAccount.java`、`Role.java`
- `UserAccountStore.java`、`RoleStore.java`
- `InMemoryUserAccountStore.java`、`JdbcUserAccountStore.java`
- `InMemoryRoleStore.java`、`JdbcRoleStore.java`
- `RefreshSessionStore.java` 及两个实现
- `RbacMutationExecutor.java` 及两个实现
- `dto/AdminDtos.java`、`dto/UserView.java`（保持登录响应兼容，只做加法字段需谨慎）
- 计划新增 `dto/AuthPublicConfig.java`；`AdminDtos`计划增加管理合同版本/写状态视图
- `auth-service/src/main/resources/application.yml`
- 对应现有测试：`AuthServiceTest`、`AuthControllerTest`、`AdminControllerTest`、`RegistrationTest`、`RoleServiceTest`、`JdbcRbacMigrationTest`
- 计划新增：`AdminServiceTest.java`、`AdminConcurrencyTest.java`、`AuthPublicConfigTest.java`、store contract tests。

### 7.3 knowledge-service 适配修改

- `platform-protocol/src/main/java/com/lrj/platform/protocol/knowledge/KnowledgeHit.java`
- 计划新增 `platform-protocol/src/main/java/com/lrj/platform/protocol/knowledge/KnowledgeRuntimeView.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/DocumentController.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/KnowledgeQueryController.java`
- `lifecycle/DocumentService.java`、`lifecycle/DocumentInfo.java`
- `KnowledgeQueryService.java`
- `search/RetrievalHit.java`
- `search/VectorRetrievalSource.java`
- `search/InMemoryKeywordRetrievalSource.java`
- `search/EsKeywordRetrievalSource.java`
- `search/HybridFusionService.java`
- `es/EsSearchHit.java`、`es/ElasticsearchEsGateway.java`
- `knowledge-service/src/main/resources/application.yml`
- 现有 `PublicKbQueryTest`、`DocumentControllerPublicTest` 及 RAG 测试；计划新增 public list/delete/visibility contract tests。

### 7.4 edge/deploy/docs 适配修改

- `edge-gateway/src/main/java/com/lrj/platform/edge/EdgeOpenPaths.java`
- `edge-gateway/src/main/resources/application.yml`
- `deploy/docker-compose.yml`、`deploy/docker-compose.rag-full.yml`
- `deploy/helm/platform/values.yaml` 及相关 Helm deployment template（实际模板路径实施时由 `rg AUTH_RBAC` 再确认）
- `deploy/start-dev.sh`、`deploy/start-all.sh`、`deploy/start-local.sh`
- `capability-showcase-frontend/README.md`
- `docs/平台工程/能力展示控制台.md`
- `docs/平台工程/rbac-and-public-kb.md`

## 8. 结论

现有 SPA 的认证底座、组件风格和 RAG 工作台可复用，最合理的演进点是同一 Vue 应用内增加懒加载管理域，并把通用请求层升级为“明确凭证模式 + 强类型管理 API”。但当前后端管理契约缺分页、effective scopes、并发版本、引用保护和共享文档管理；若不先补这些，前端只能做一个适合 demo、会静默覆盖和误导权限的薄壳。
