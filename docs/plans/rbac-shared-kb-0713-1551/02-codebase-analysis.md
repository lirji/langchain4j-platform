# 02 — codebase-explorer：现状、调用链与影响面

## 1. 仓库与工作树状态

- Java 21、Spring Boot 3.3.5、多模块 Maven。
- auth-service 已在根 `pom.xml` 的 modules 中，监听 8092；edge-gateway 监听 8080。
- 当前分支 `feat/rbac-shared-kb` 与 main 同基线 `7d12d62`，RBAC/公共知识库/ES 相关内容均为未提交工作树修改。
- 旧规划声称“阶段 1 完成、阶段 2 进行中”。当前真实工作树已继续推进：`RoleService` 已接入 `AuthService.issueFor`，注册、`AdminService`、`AdminController`、DTO、edge open path、配置与首批测试都已出现；但接口契约、direct scopes 管理、条件开关、统一异常、注册限流、完整测试和存储并发安全尚未完成。
- 本次只做只读分析并写规划目录，没有运行 Maven：测试会产生 `target/`，不符合“唯一可写目录”的约束。旧进度文档记载的历史 BUILD SUCCESS 不能替代对当前后续工作树的验证，因此当前编译/测试状态标记为**待实施阶段验证**。

## 2. 当前认证与权限传播调用链

### 2.1 登录/刷新到会话 JWT

1. `auth-service/.../AuthController.login(LoginRequest,HttpServletRequest)` 调用 `AuthService.login`。
2. `AuthService.login` 经 `UserAccountStore.findByUsername`、`PasswordHasher.matches` 和 `LoginThrottle` 校验。
3. 当前工作树的 `AuthService.issueFor(UserAccount)` 调用 `RoleService.effectiveScopes`，再调用 `SessionTokenIssuer.mintAccessToken(user,effective)`。
4. `SessionTokenIssuer` 使用 `InternalSecurityProperties.Session.jwtSecret/accessTtl` 构造独立 `InternalToken`，把 `TenantContext.Tenant(tenant,userId,effectiveScopes)` 签成会话 JWT。
5. `AuthService.issueFor` 创建 `RefreshSession`。刷新时 `AuthService.refresh` 会按 session.username 回查最新账号，所以能够重新展开角色。

### 2.2 edge 换发内部 JWT

1. `edge-gateway/.../SessionBearerAuthFilter` 在非 open 路径读取 `Authorization: Bearer`。
2. 它用 session secret 验签后得到 Tenant，并用平台内部 JWT bean重新 `mint`。
3. 新内部 JWT 放入 `X-Internal-Token`，外部 Authorization 被删除。
4. 如果无有效 Bearer，请求交给 `ApiKeyToInternalTokenFilter`；后者仍从 `platform.security.api-keys` 读取 tenant/user/scopes。
5. 两条路径在内部 JWT 处汇合，但 API-key 不经过 auth-service/RoleStore。

### 2.3 下游恢复与逐跳传播

- `platform-security/.../InternalTokenAuthFilter.resolve` 优先验 `X-Internal-Token`，可选接受直连 API-key fallback，然后设置 `TenantContext` 与 MDC，请求完成后 clear。
- `InternalToken.mint/verify` 的 claim 为 `sub`、`uid`、`scopes`、`iat`、`exp`。
- `OutboundTenantForwarder` 读取 `TenantContext.captureRaw()`，为服务间 REST 再签内部 JWT。
- `TenantContext.Tenant.hasScope` 只对 scope 集合做 contains。因此在 auth-service 签发时展开角色可以实现下游零角色感知。

### 2.4 当前真实 scope 关口

- `knowledge-service/.../DocumentController.requireIngest()`：`ingest`。
- `knowledge-service/.../MultimodalImageSearchController.requireIngest()`：`ingest`。
- `workflow-service/.../WorkflowController.requireApprove()`：`approve`。
- 其它 scope（chat/agent/channel/eval/vision/voice/analytics）主要存在于 api-key/种子配置和能力编排中；仓库没有统一 scope catalog。

## 3. auth-service 当前模型与存储

### 3.1 基线模型

- `UserAccount(username,passwordHash,tenant,userId,scopes,enabled)`。
- 基线 `UserAccountStore` 只有 `findByUsername`，所以既有测试可把它写成 lambda。
- `JdbcUserAccountStore` 的 USERS 基线表：

  `USERNAME PK, PASSWORD_HASH, TENANT, USER_ID, SCOPES VARCHAR(1024), ENABLED, CREATED_AT`

- `RefreshSessionStore` 有内存/JDBC 双实现；`AUTH_SESSION` 已按 USERNAME 建索引，但接口没有 `revokeByUsername`。

### 3.2 当前未提交 RBAC 实现

| 文件 | 已有内容 | 完成度 |
|---|---|---|
| `UserAccount.java` | 新增 `roles`，保留 6 参兼容构造；scopes/roles 拷贝为 LinkedHashSet | 基本可用，但集合仍可变 |
| `Role.java` | `name/scopes/description` record | 基本可用，缺名称归一和不可变集合 |
| `RoleStore.java` | `findByName/findAll/save/delete` | CRUD 端口初稿，无原子 create/update 语义 |
| `InMemoryRoleStore.java` | 条件装配、ConcurrentHashMap、SeedRoles | `save` 会覆盖，列表无稳定顺序 |
| `JdbcRoleStore.java` | `ROLES(NAME PK,SCOPES,DESCRIPTION,CREATED_AT)`；空表 seed | delete+insert upsert 非原子，无引用完整性 |
| `SeedRoles.java` | viewer/editor/analyst/approver/admin；admin 含 role-admin/public-ingest | 可复用；会给 alice 新增权限 |
| `RoleService.java` | roles 展开并与 direct scopes 合并；未知角色忽略 | 主方向正确；需告警/flag/验证辅助 |
| `UserAccountStore.java` | 用 default 方法新增 save/update/findAll/delete，保住函数式接口 | 兼容思路可复用；写语义过宽 |
| `InMemoryUserAccountStore.java` | put 型 save/update，findAll/delete | 并发注册会覆盖 |
| `JdbcUserAccountStore.java` | ALTER USERS ADD ROLES；读写 CSV；save 先删后插 | 有数据丢失/竞争窗口，DDL 异常吞得过宽 |
| `RegistrationRuleEngine.java` | username 邮箱域 first-match；默认 tenant/role；拒绝 `__public__` | 可复用；未校验角色存在、空规则角色 |
| `AuthProperties.java` | 增加 Registration 配置对象 | 已由 `application.yml` 暴露；缺 RBAC/seed/password/注册专用 throttle 配置 |
| `AuthService.java` | 注入 RoleService/规则/props；issueFor 展开；新增 register | 控制器已接；注册不是原子 create，复用登录 throttle 且没有按 IP 记录全部注册尝试 |
| `SessionTokenIssuer.java` | 新增显式 effective scopes 重载，保留旧重载 | 兼容性好，生产接缝已正确 |
| `AdminService.java` | 用户/角色初步用例 | 已有 Controller/DTO；仍不管理 direct scopes，删除/并发/会话处理不完整 |
| `AuthController.java` / `RegisterRequest.java` | 已有 `POST /auth/register`，成功复用 login 响应/cookie | 可复用；异常仍为 controller 局部 handler，IP 来源信任边界未收口 |
| `AdminController.java` / `AdminDtos.java` | 已有列表、创建、更新/绑角色、删除和角色列表/保存/删除 | 早期契约；无详情/分页/directScopes/effectiveScopes，角色保存是 upsert，删除返回 200，两个 controller 重复异常处理 |
| `RegistrationTest.java` / `RoleServiceTest.java` / `AdminControllerTest.java` | 已覆盖默认/规则注册、并集和部分管理路径 | 可扩充；尚未覆盖 JDBC、事务、并发、条件装配与完整 HTTP 契约 |

另一个兼容缺口是 `AuthService` 当前只保留了新增依赖后的 9 参构造器，导致基线 `AuthServiceTest/AuthControllerTest` 被迫做机械改写；这与“既有测试零改动”不一致。最终实现应把完整构造器标为 Spring 注入入口，并恢复旧 5 参构造兼容（旧入口按 direct-only 行为，标记 deprecated），RBAC 场景由新测试显式使用完整构造器。

### 3.3 当前 JDBC 失败场景

1. `JdbcUserAccountStore.save` 先 DELETE 再 INSERT；进程在两条语句间失败会丢账号。
2. 注册先 find 再 save；两个请求可互相覆盖密码、tenant 和角色。
3. `JdbcRoleStore.save` 同样先删再插；角色短暂不存在，失败会永久删除。
4. `addColumnIfMissing` 捕获全部 `DataAccessException`，权限不足、连接异常、SQL 拼写错也会被误判成“列已存在”。
5. `COUNT(*)==0` 后 seed 在多副本启动时存在竞态。
6. roles/scopes 以 CSV 存储，无法用数据库约束阻止删除被引用角色，也难以原子维护用户-角色关系。
7. 删除/禁用用户不批量撤销 refresh session；刷新仍会因回查失败而拒绝，但会话表会留下垃圾行。

## 4. HTTP、配置与部署缺口

### 4.1 HTTP

- `AuthController` 已有 `/auth/login|register|refresh|logout|me`；register 成功复用 `LoginResponse` 和 refresh cookie，方向正确。
- `AdminController` 已有 `/auth/admin/users|roles` 初版，且每个 handler 都调用 `requireRoleAdmin()`；`AdminDtos.UserAdminView` 不暴露密码。
- 当前管理契约仍不完整：没有单项 GET、分页、direct scopes 写入或 effective scopes 视图；`PUT /users/{username}` 是全量替换且 `enabled=null` 会被解释为 true；绑角色使用 POST；角色 `POST` 是含覆盖语义的 upsert；DELETE 返回 200 body，缺少引用冲突和幂等删除定义。
- `AuthController` 与 `AdminController` 各自复制 `@ExceptionHandler(AuthException.class)`，应抽为统一 `@RestControllerAdvice`。
- 现有 `AdminDtos.java` 和 `RegisterRequest.java` 应原位演进，不应为了形式拆成大量 DTO 文件；这能最大化保留早期实现。

### 4.2 edge

- `EdgeOpenPaths` 已精确放行 `/auth/register`，且 `/auth/admin/**` 没有被放行；三个 gateway filter 共用这一判定源。
- 因 `EdgeRateLimitFilter` 也共用 open 判定，register 当前绕过 edge rate limit；现有 `LoginThrottle` 又按 username+IP 且只记录登录失败，不能防不同 username 的批量注册，需 auth 本地独立 IP throttle。
- **已发现与硬性需求冲突的当前改动**：`edge-gateway/application.yml` 给 `dev-key-acme` 新增了 `role-admin`、`public-ingest`。这不是 RBAC 必需改动，会改变 API-key 老路权限；实施时必须把该 binding 恢复到基线精确集合 `[chat, ingest, approve, agent, channel, eval, vision, voice]`，并用测试/配置 diff 锁定。专用 `dev-key-acme-ingest` 等既有 binding 不改。

### 4.3 auth 配置

- `AuthProperties.Registration` 及 `app.auth.registration.*` 已存在；当前 `application.yml` 无条件内置 acme.com/globex.com 示例规则，生产误启用 registration 时会产生真实授权。应把示例移到文档或 demo 部署配置，本地通用配置只保留默认关闭和安全默认值；不要写 `rules: []` 遮蔽 Config Server 列表。
- 还没有 RBAC 灰度开关、seed/bootstrap 管理员配置。
- Docker Compose 的 auth-service 已使用 JDBC 并配置 DB/session secret，可增加非敏感 feature flags。
- Helm `values.yaml` 当前 `services` 中没有 auth-service，`config` 中没有 AUTH_URI/AUTH_STORE/AUTH_DB_URL，shared secret 中也没有 SESSION_JWT_SECRET/AUTH_DB_PASSWORD。这是已有部署缺口，生产 RBAC 上线前必须补齐。

## 5. 早期公共知识库改动的共存分析

当前 knowledge-service 工作树已经存在：

- `PublicKb.TENANT_ID="__public__"`。
- `RetrievalRequest.publicTenantId` 和兼容构造器。
- vector/keyword/ES 检索源已开始并入公共分区，已有 `PublicKbQueryTest` 等早期测试。
- `DocumentController` 已识别 `visibility=public|shared`，调用 `DocumentService.upload(...,true)` 并用 `public-ingest` 把守公共写入；已有 `DocumentControllerPublicTest`。
- 当前代码检索不到 Graph source 对 `PublicKb`/`publicTenantId` 的引用，公共 GraphRAG 是否应并入仍属公共库任务的待验证项。
- `application.yml` 已有 `app.rag.public.enabled=false`。

RBAC 实施必须保留这些未提交内容，且仅做以下接缝：

- SeedRoles 可继续包含 `public-ingest`。
- RegistrationRuleEngine/AdminService 继续拒绝 `__public__` 作为用户 tenant。
- RBAC 不修改 knowledge-service 文件；公共库后续 controller/graph/delete 行为由其独立计划完成。

## 6. 可直接复用的代码

- `Role`、`UserAccount.roles`、兼容构造器。
- `SeedRoles`、`SeedUsers` 的基本角色映射。
- `RoleService.effectiveScopes` 的核心并集算法。
- `RegistrationRuleEngine` 的配置顺序和域匹配骨架。
- `AdminService` 的用例层位置与密码哈希复用。
- `SessionTokenIssuer.mintAccessToken(user,effectiveScopes)`。
- `AuthService.issueFor` 作为登录和刷新唯一的有效权限签发点。
- 现有 `@ConditionalOnProperty(app.auth.store)` 双实现模式。
- `AuthJdbcConfig` 的显式 DataSource。
- `EdgeOpenPaths` 单一 open-path 判定源。
- `InternalToken`/`SessionBearerAuthFilter`/`InternalTokenAuthFilter`/`TenantContext` 全链路，无需生产代码修改。
- 现有 H2 MySQL mode `JdbcStoresTest` 的建库方式可复用于新增迁移/事务测试，但基线测试源码不改。

## 7. 最终方案的精确影响文件

路径约定：下列 auth Java 文件均位于 `auth-service/src/main/java/com/lrj/platform/auth/`（DTO 位于其 `dto/` 子目录），auth 测试均位于 `auth-service/src/test/java/com/lrj/platform/auth/`；edge Java/测试分别位于 `edge-gateway/src/main/java/com/lrj/platform/edge/`、`edge-gateway/src/test/java/com/lrj/platform/edge/`。据此每个文件名都能唯一还原为仓库精确路径。

### 7.1 必改现有文件

**auth-service**

- `AuthProperties.java`：补 rbac/admin-writes/seed/bootstrap/password/registration throttle 配置并规范 registration。
- `AuthJdbcConfig.java`：提供 JDBC transaction manager（仅 jdbc 条件下）。
- `UserAccount.java`、`Role.java`：归一化与不可变集合。
- `UserAccountStore.java`、`RoleStore.java`：原子 create、关系替换、引用查询语义，同时保留旧测试兼容入口。
- `InMemoryUserAccountStore.java`、`InMemoryRoleStore.java`：原子 putIfAbsent、稳定列表、同步关系修改。
- `JdbcUserAccountStore.java`、`JdbcRoleStore.java`：改为关系表权威读写；移除 delete+insert。
- `SeedUsers.java`、`SeedRoles.java`：保留现有映射，明确新增 scope。
- `RoleService.java`：RBAC flag、未知角色告警、校验辅助。
- `RegistrationRuleEngine.java`：输入/规则归一与安全校验。
- `AuthService.java`：原子注册、角色存在校验、有效 scopes 签发；恢复旧 5 参兼容构造。
- `AdminService.java`：完整用户/角色用例、direct scopes、409 规则、最后管理员保护。
- `RefreshSessionStore.java`、两套实现：增加按 username 撤销/清理方法。
- `AdminController.java`、`dto/AdminDtos.java`：原位补齐详情、分页、direct/effective scopes 与明确 CRUD 语义。
- `AuthController.java`、`dto/RegisterRequest.java`：加固现有 register，异常 handler 抽离。
- `auth-service/src/main/resources/application.yml`：公开 feature flag/registration/bootstrap 配置。
- `AuthServiceTest.java`、`AuthControllerTest.java`：恢复基线兼容构造调用且不放宽原断言；当前新增的 `RoleServiceTest.java`、`RegistrationTest.java`、`AdminControllerTest.java` 原位扩充。基线 `JdbcStoresTest.java`、`SessionTokenIssuerTest.java`、`PasswordHasherTest.java` 原样保留。

**edge-gateway**

- `EdgeOpenPaths.java`：现有精确 `/auth/register` 保留并补回归测试，无需再改生产逻辑。
- `edge-gateway/src/main/resources/application.yml`：撤销当前 `dev-key-acme` 的 `role-admin/public-ingest` 权限增量，恢复基线精确 scopes；其它 binding 不改。
- 基线 `ApiKeyToInternalTokenFilterTest.java`、`SessionBearerAuthFilterTest.java` 原样通过；新增 `EdgeOpenPathsTest.java`、`RbacApiKeyCompatibilityTest.java`、`RbacSessionScopePropagationTest.java` 承载 open path、配置基线和 scopes 传播新断言，避免改写既有测试。

**部署/文档**

- `deploy/docker-compose.yml`：auth RBAC/registration/bootstrap flags；不改 api-key scopes。
- `deploy/helm/platform/values.yaml`：补 auth-service 现有部署缺口及 RBAC 配置/Secret。
- `deploy/helm/platform/templates/secret.yaml`、`externalsecret-sample.yaml`：新增 auth/edge 专用 session-JWT Secret，禁止注入所有下游。
- `deploy/helm/README.md`：auth/RBAC values 与回滚说明。
- `deploy/langchain4j-platform.postman_collection.json`：注册、角色、用户管理示例。
- `docs/平台工程/数据库与中间件清单.md`：新表、配置、账号角色说明。
- `README.md`：登录/RBAC/API-key 双模和管理 API 概览。

### 7.2 计划新增文件

- `auth-service/.../JdbcRbacSchemaInitializer.java`：集中创建/演进/一次性迁移 RBAC 表，避免两个 store 构造顺序和多副本 seed 竞争。
- `auth-service/.../RbacMutationExecutor.java`、`InMemoryRbacMutationExecutor.java`、`JdbcRbacMutationExecutor.java`：统一复合写临界区/事务。
- `auth-service/.../RbacMetrics.java`：低基数 RBAC/迁移指标。
- `auth-service/.../AuthExceptionHandler.java`（`@RestControllerAdvice`）。
- `auth-service/.../RegistrationThrottle.java`：register open path 的 IP 级尝试限制。
- `auth-service/.../PasswordPolicy.java`：注册/管理员建户/改密的统一策略。
- 新测试：`RbacAuthServiceTest`、`RbacAuthControllerTest`、`RbacSessionTokenIssuerTest`、`RegistrationRuleEngineTest`、`AdminServiceTest`、`RbacStoreContractTest`（或内存/JDBC各一份）、`JdbcRbacMigrationTest`、`AuthStoreConditionTest`；现有早期 `RoleServiceTest`、`RegistrationTest`、`AdminControllerTest` 原位扩充。
- edge 新测试（精确目录 `edge-gateway/src/test/java/com/lrj/platform/edge/`）：`EdgeOpenPathsTest.java`、`RbacApiKeyCompatibilityTest.java`、`RbacSessionScopePropagationTest.java`。

### 7.3 明确不改

- `platform-security` 生产代码。
- 所有下游业务控制器的现有 scope 校验。
- `platform-protocol`（RBAC DTO 只属于 auth-service，不是服务间契约）。
- edge-gateway 的 API-key 业务模型与基线 bindings（但必须纠正当前分支对 `dev-key-acme` 的两项非兼容增权）。
- knowledge-service 的公共库/ES 未提交代码。
- Kafka/eventbus 消息类型。
- `auth-service/pom.xml` 及基线测试源码（现有 JDBC/Actuator/H2 依赖足够；通过兼容构造与新增测试覆盖 RBAC）。
