# IMPLEMENTATION_PROGRESS — RBAC + 公共知识库

分支：`feat/rbac-shared-kb`（从 main 切出，带 main 上既有的 ES 未提交改动）。

## 阶段 1 — 数据结构与领域模型 ✅

**auth-service**
- `UserAccount.java`：record 加 `Set<String> roles`；保留 6 参向后兼容构造器（老调用/测试零改动）。
- `Role.java`（新）：`record Role(name, scopes, description)`。
- `RoleStore.java`（新）：接口 `findByName/findAll/save/delete`。
- `SeedRoles.java`（新）：viewer/editor/analyst/approver/admin 默认角色字典；引入新 scope `role-admin`、`public-ingest`（归入 admin）。
- `InMemoryRoleStore.java` / `JdbcRoleStore.java`（新）：`@ConditionalOnProperty app.auth.store` 双实现；ROLES 表 `CREATE TABLE IF NOT EXISTS` + 空表种子。
- `UserAccountStore.java`：加 `save/update/findAll/delete` 为 **default 方法**（保持单抽象方法 → 既有 lambda 测试不破）。
- `InMemoryUserAccountStore` / `JdbcUserAccountStore`：实现写方法；Jdbc 幂等 `ALTER TABLE USERS ADD COLUMN ROLES`，读写 ROLES 列。
- `SeedUsers.java`：alice→admin、bob→viewer、analyst-a→analyst（保留直配 scopes 兜底）。

**knowledge-service**
- `PublicKb.java`（新）：保留租户 id `__public__` + `isPublic()`。
- `RetrievalRequest.java`：加 `publicTenantId` 字段 + 6 参向后兼容构造器。
- `application.yml`：新增 `app.rag.public.{enabled:false, tenant-id:__public__}`。

**验证**：`mvn -pl auth-service,knowledge-service -am test` → BUILD SUCCESS；knowledge 142 项 + auth 全部通过，既有测试零改动。

**完成标准自检**：✅ 编译通过 ✅ 原有测试全绿 ✅ 签名变更全部由向后兼容构造器兜住，无遗漏构造点。

## 阶段 2 — 核心业务逻辑 ✅

**auth**：`RoleService`（expand/effectiveScopes）；`SessionTokenIssuer` 增 `mintAccessToken(user, effectiveScopes)` 重载；`AuthService` 注入 RoleService/RegistrationRuleEngine/AuthProperties，`issueFor` 用有效 scopes、新增 `register(...)`；`RegistrationRuleEngine`（邮箱域→租户+角色，保留租户校验）；`AdminService`（用户/角色 CRUD 纯逻辑，租户/角色校验）；`AuthProperties.Registration` 配置。
**knowledge**：`KnowledgeQueryService` 加公共库开关(@Value)+ query 填 `publicTenantId`；`VectorRetrievalSource` 两分区查询；`KeywordSearchService` 4 参重载并入公共分区；`EsKeywordRetrievalSource` 两次 gateway.search 合并（不动 EsGateway 接口）；`DocumentService.upload` 加 `shared` 参数写 `__public__`。
**验证**：三次 `-am test` 全绿；既有测试仅 `AuthService` 构造点 3 处线程新依赖（未弱化）。

## 阶段 3 — 接口与适配层 ✅

**auth**：`AuthController` 加 `POST /auth/register`；`AdminController(/auth/admin/**)` 每端点 `requireRoleAdmin()`；`RegisterRequest`/`AdminDtos`；`EdgeOpenPaths` 放行 `/auth/register`；edge api-key `dev-key-acme` 补 `role-admin`/`public-ingest`；auth application.yml 注册配置 + 示例规则。
**knowledge**：`DocumentController` 加 `visibility=public` + `requireWrite(shared)`（public-ingest 关口），非公共路径保持原 overload 调用（既有 DocumentControllerTest 零改动）。
**验证**：auth+knowledge+edge-gateway `-am test` BUILD SUCCESS。

## 阶段 4 — 测试 ✅

新增/更新（全绿）：`RoleServiceTest`(4)、`AdminControllerTest`(5)、`RegistrationTest`(5)、`AuthServiceTest` +登录展开(1)、`PublicKbQueryTest`(4：公共可读/关闭不可见/隔离不破/类目过滤)、`DocumentControllerPublicTest`(2：public-ingest 关口)。为可测性给 `KnowledgeQueryService` 加了 `setPublicKb(...)` 测试钩子（仿 `setQueryExpander`）。

## 阶段 5 — 文档与最终检查 ✅

`deploy/seed-kb.sh` 加 `--public`（灌公共库，自动切 dev-key-acme）；新增 `docs/平台工程/rbac-and-public-kb.md`（三层正交模型、三条分配路径、公共库读写与验证 curl）。

## 遗留 / 后续
- 图谱(GraphRAG)公共化：本期未并（非目标），如需再扩 `GraphRetrievalSource`/`GraphSearchService`。
- 端到端联调：需重建 knowledge/auth 镜像并置 `RAG_PUBLIC_ENABLED=true` 才能在跑着的栈上验证 bob 跨租户查退款政策（单测已覆盖逻辑）。
