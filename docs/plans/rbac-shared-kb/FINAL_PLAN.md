# FINAL_PLAN — RBAC 权限体系 + 公共/共享知识库

> 说明：本 FINAL_PLAN 由 Claude 直接产出并**逐条对照真实仓库核验**（Codex `codex exec` 在本无终端环境反复阻塞于读 stdin，40 分钟零产出，已弃用）。所有类名/方法/表/行号均来自实测。

## 背景

页面流式对话「帮我查询退款政策，从我本地知识库」返回"未在文档中找到相关内容"。经端到端排查（真实 curl 复现）确诊：**不是流式或 RAG 的问题，而是租户隔离**——所有文档只灌在租户 `acme` 下（Qdrant 只有 `knowledge_segments_acme`），而登录账号 bob→globex / analyst-a→tenantA 查的是各自空分区。同时发现入库需要 `ingest` scope，而 bob 只有 `[chat]`，连灌都灌不进去。

由此用户确认要做两件事（决策已定）：
1. **RBAC**：DB + admin API 的可运行时管理角色体系，支持三种分配——admin 后台指派、自助注册默认角色、按规则（如邮箱域）自动映射。
2. **公共/共享知识库**：一个所有租户可读的公共库，退款政策这类通用文档放这里全租户可查，同时**保留**原有 per-tenant 硬隔离。

**预期结果**：管理员能在运行时建用户/派角色；新用户可自助注册拿默认角色；通用文档放公共库后 bob/analyst-a 等任意租户都能检索到——而彼此的私有数据仍互不可见。

## 目标 / 非目标

**目标**
- auth-service 引入 `角色 → scopes` 抽象；用户可绑定多个角色；登录**签发令牌那一刻**把角色展开成 scopes（下游零改动）。
- 角色/用户落 MySQL（`AUTH_STORE=jdbc`），提供 `/auth/admin/**` 运行时管理；内存 store 仍为默认（本地/单测零依赖）。
- 三条分配路径：admin 指派、`/auth/register` 自助注册默认角色、注册规则引擎（邮箱域→租户+角色）。
- knowledge-service 增加保留"公共租户"分区：写入受新 `public-ingest` scope 控制，查询时并入每个租户结果，隔离不破。
- 全部新行为**默认关闭 / 加法式变更**，向后兼容 api-key 双模与既有 scopes。

**非目标**
- 不做集中式 PDP / 每次下游调用查角色表（保持"scopes 进 JWT"的简洁）。
- edge-gateway 的 api-key 目录**不引入角色**，保持直配 scopes（它是服务凭证）；RBAC 只作用于登录账号路径。
- 公共库的 **GraphRAG 分支**并入本期为可选（默认只并 向量/keyword/ES 三路文本分支），图谱公共化列为 phase-2。
- 不引入 Flyway/JPA；沿用裸 JdbcTemplate + `CREATE TABLE IF NOT EXISTS` / `ALTER TABLE ADD COLUMN`。

## 已确认的业务规则

- **RBAC 管"能做什么"（scopes），租户隔离管"能看到哪份数据"**——两者正交。给 bob 加 admin 角色也看不到 acme 私有数据；要让 bob 看到通用文档，走公共库。
- 有效 scopes = `union(展开(user.roles)) ∪ user.directScopes`（保留直配 scope 用于兜底/迁移）。
- admin API 由新 scope `role-admin` 保护；公共库写入由新 scope `public-ingest` 保护。两者归入 `admin` 角色。
- 保留租户 id `__public__` 为公共库专用，**禁止**真实租户/注册用户占用该 id。
- 自助注册默认**关闭**（`app.auth.registration.enabled=false`），demo 显式开启；公共库读取默认**关闭**（`app.rag.public.enabled=false`）。

## 当前代码与调用链（实测锚点）

**授权链（已是 scope-based，这是 RBAC 能低成本落地的根因）**
- `TenantContext.Tenant(tenantId,userId,scopes)` + `hasScope()` — `platform-security/.../TenantContext.java:40-44`。
- 登录 scopes 来源：`UserAccount.scopes()` → `SessionTokenIssuer.mintAccessToken` 建 `Tenant`（`SessionTokenIssuer.java:41-43`）→ 签进会话 JWT claim `sub/uid/scopes`（`InternalToken.java:99-113`）→ 下游 `hasScope` 校验（`knowledge DocumentController.java:133-137`、`workflow WorkflowController.java:101`）。
- 双模认证：`edge-gateway` `SessionBearerAuthFilter`（Bearer→内部JWT）与 `ApiKeyToInternalTokenFilter`（api-key→内部JWT）并存；api-key→租户+scopes 目录在 `edge-gateway/application.yml` `platform.security.api-keys`。

**auth-service 现状**
- 账号 store 只读：`UserAccountStore { Optional<UserAccount> findByUsername(String) }`（无写方法）；`InMemory*`（默认 `matchIfMissing`）/ `Jdbc*`（`AUTH_STORE=jdbc`）。
- `USERS` DDL（`JdbcUserAccountStore.java:38-47`）：`USERNAME PK, PASSWORD_HASH, TENANT, USER_ID, SCOPES VARCHAR(1024), ENABLED, CREATED_AT`；空表时用 `SeedUsers.defaults` 灌 alice/bob/analyst-a。
- `AuthController`（`/auth`）只有 `login/refresh/logout/me`——**无注册/无用户管理端点**。
- `PasswordHasher`（BCrypt，`hash`/`matches`）可复用于建账号。
- `AuthServiceApplication` 排除 `DataSourceAutoConfiguration`；JDBC 相关 bean 仅在 `AUTH_STORE=jdbc` 时装（`AuthJdbcConfig.java:14`），新 JDBC store 复用 `authDataSource` bean。
- DTO 在本地包 `com.lrj.platform.auth.dto`（非 platform-protocol）。

**knowledge-service 租户隔离（3 处 + 入库 4 sink，全部由单一 `tenantId` 派生）**
- 查询编排 `KnowledgeQueryService.query(query,topK,minScore,category)`：`tenantId=TenantContext.current().tenantId()`（:233），装入 `RetrievalRequest(query,variants,tenantId,category,limit,minScore)`（:251），按 向量→keyword→ES(extra)→graph 取源（:253-266）→ `fusion.fuse` → `reranker`。
- 向量：`VectorRetrievalSource.retrieve` 用 `metadataKey("tenantId").isEqualTo(request.tenantId())`（:44）+ `storeRouter.forTenant(tenantId,dim).search(...)`（:60-61）。集合名 `ManagedEmbeddingStoreRouter.collectionName` = `base + "_" + tenant`（:45-50，base=`knowledge_segments`）。
- keyword：`KeywordSearchService.search` 读 `documentMirror.all(tenantId)`（:29-37）；`DocumentMirror.byTenant` 按 `tenantId` metadata 分区（`all(tenantId)`:39-42）。
- ES：`EsKeywordRetrievalSource.retrieve`→`gateway.search(tenantId,category,query,limit)`；`ElasticsearchEsGateway.search` DSL `filter:[{term:{tenantId}}, {term:{category}}]`（:203-207），单一共享索引 `knowledge_segments_text`（tenants 靠 filter 隔离，非分索引）。
- 入库 `DocumentService.upload`：`tenantId=TenantContext.current().tenantId()`（:179）→ 4 sink 全部按此 id：向量 `storeRouter.forTenant`（:209）、`documentMirror.add`（:210）、`segmentIndexer.index`（:212）、`graphIngestor.ingest`（:213）。删除侧 `deleteInternal` 对称（:278-296）。
- 写入 scope 关口：`DocumentController.requireIngest()`（:133-137）检查 `hasScope("ingest")`，`uploadFile/uploadJson/delete` 处调用。
- **公共/共享分区当前不存在**（config `isolation: shared` 只是"单存储+metadata过滤"，仍 per-tenant）。

## 候选方案对比

| 方案 | 做法 | 评价 |
|---|---|---|
| **A（选定）** | 角色只落 auth-service，**mint 时展开成 scopes**；公共库用保留租户 `__public__` 分区，读写各加 flag/scope | 改动集中、下游零改、复用现有 store/隔离机制、可灰度回滚。**弱点**：角色变更需重新登录/刷新才生效（JWT 已签的 scopes 不回溯）——可接受（会话 TTL 60min，或 admin 改后提示重登）。 |
| B | 在 platform-security 建集中式 RBAC + 每次下游调用查角色表（PDP） | 更"企业级"，但触及每个服务、破坏"scopes 进 JWT"的简洁、性能与耦合都差。**否**：过度设计、高爆炸半径。 |
| C | 公共库另建独立物理索引/集合；角色仅配置文件（无 DB/admin API） | 配置化无法满足"运行时自助分配"决策；独立物理库重复入库管线。**否**：不符决策、重复造轮子。 |

**选定 A** 的原因：与现有架构同构（store 接口+内存/Jdbc 双实现、scopes 进 JWT、tenantId 单点派生隔离），改动最小且可灰度；已知弱点（角色生效延迟到下次签发）用"改角色即失效会话/提示重登"缓解，本期可接受。

## 精确到文件的改动清单

### auth-service（RBAC 主战场）

**领域模型 / 存储**
- `UserAccount.java`：record 增加末位字段 `Set<String> roles`（compact ctor 同样 null-guard）。**波及所有构造点**（SeedUsers、两个 store 的 map/insert、测试）。
- 新 `Role.java`：record `Role(String name, Set<String> scopes, String description)`。
- 新 `RoleStore.java` 接口：`Optional<Role> findByName(String)`, `List<Role> findAll()`, `void save(Role)`, `void delete(String)`。
- 新 `InMemoryRoleStore.java`（`@ConditionalOnProperty app.auth.store in-memory matchIfMissing`）+ `JdbcRoleStore.java`（`havingValue jdbc`，内联 `CREATE TABLE IF NOT EXISTS ROLES(NAME VARCHAR(128) PK, SCOPES VARCHAR(1024), DESCRIPTION VARCHAR(256), CREATED_AT BIGINT)`，空表时用 `SeedRoles` 灌）。
- 新 `SeedRoles.java`（仿 `SeedUsers`）：`viewer=[chat]`、`editor=[chat,ingest]`、`analyst=[chat,analytics]`、`approver=[chat,approve]`、`admin=[chat,ingest,approve,agent,channel,eval,vision,voice,analytics,role-admin,public-ingest]`。
- `UserAccountStore.java`：接口增补写方法 `void save(UserAccount)`, `void update(UserAccount)`, `List<UserAccount> findAll()`, `void delete(String)`；`InMemoryUserAccountStore`/`JdbcUserAccountStore` 实现之。
- `JdbcUserAccountStore.java`：`init` 增补幂等 `ALTER TABLE USERS ADD COLUMN ROLES VARCHAR(1024)`（catch 重复列异常）；`mapUser`/insert 读写 ROLES（`String.join(",", roles)`）。
- `SeedUsers.java`：给 alice 追加 `roles={admin}`（保留其直配 scopes 作兜底）；bob `roles={viewer}`；analyst-a `roles={analyst}`。

**核心逻辑**
- 新 `RoleService.java`：`Set<String> expand(Set<String> roleNames)` = 各角色 scopes 并集；`Set<String> effectiveScopes(UserAccount)` = `expand(roles) ∪ directScopes`。
- 集成点（唯一 mint 处）：`SessionTokenIssuer.mintAccessToken(UserAccount)`（:41-43）改为用 `roleService.effectiveScopes(user)` 建 `Tenant`（构造注入 `RoleService`）。`refresh` 路径经 `AuthService.issueFor` 复用同一 mint，自动获得最新展开。
- 新 `RegistrationRuleEngine.java`：按 `app.auth.registration.rules`（`[{emailDomain, tenant, roles}]`）匹配用户名/邮箱 → `(tenant, roles)`；无匹配用 `default-tenant`/`default-role`。
- `AuthService.java`：增 `register(RegisterRequest)`（校验重名、规则映射、默认角色、`registration.enabled` 关口、复用 `LoginThrottle`）与 admin 用例方法 `createUser/updateUser/assignRoles/listUsers/deleteUser`（写 store）。

**接口 / 适配**
- 新 `AdminController.java`（`@RequestMapping("/auth/admin")`）：`POST /users`、`GET /users`、`PUT /users/{username}`、`POST /users/{username}/roles`、`DELETE /users/{username}`、`GET/POST/PUT/DELETE /roles`。**每个方法头部 `requireRoleAdmin()`**（`hasScope("role-admin")` 否则 403，仿 `DocumentController.requireIngest`）。
- `AuthController.java`：增 `POST /auth/register`（open 端点，返回 `LoginResponse` 直接登录或 201）。
- 新 DTO（本地 `com.lrj.platform.auth.dto`）：`RegisterRequest`、`CreateUserRequest`、`UpdateUserRequest`、`AssignRolesRequest`、`UserAdminView`、`RoleView`。
- `AuthProperties.java` + `application.yml`：增 `app.auth.registration.{enabled:false, default-tenant, default-role:viewer, rules:[]}`。
- `edge-gateway`：`EdgeOpenPaths.java` 放行 `/auth/register`（仿 login/refresh/logout）；`application.yml` 给 `dev-key-acme` 追加 scopes `role-admin, public-ingest`（api-key 路径也能测 admin/公共库）。

### knowledge-service（公共/共享库）

**领域 / 常量 / 配置**
- 新 `PublicKb.java`（或常量类）：`TENANT_ID = "__public__"`。
- `application.yml` 增 `app.rag.public.{enabled:false, tenant-id:__public__}`；对应 `RagPublicProperties`（`@ConditionalOnProperty` 或直接 `@Value` 注入）。
- `RetrievalRequest.java`（:16-23）增末位字段 `String publicTenantId`（null=不并公共库）；**波及所有构造点**（主要是 `KnowledgeQueryService:251`）。

**核心逻辑**
- `KnowledgeQueryService.query`：当 `public.enabled` 时把 `publicTenantId` 填入 `RetrievalRequest`（:251）。
- `VectorRetrievalSource.retrieve`（:44/:60-61）：`publicTenantId!=null` 时对 `forTenant(PUBLIC,dim)` 再查一次（filter `tenantId==PUBLIC` [+category]），结果并入 `out`。
- `KeywordSearchService.search`（:30-37）：并入 `documentMirror.all(PUBLIC)`。
- `ElasticsearchEsGateway.search`（:203-207）：租户过滤由 `term tenantId` 改为 `terms tenantId:[tenant, PUBLIC]`（或 `should`+`minimum_should_match=1`）；单索引无需新建。
- 去重天然安全：docId=SHA-256(`tenant:displayName`)，公共文档 docId 与租户文档不撞，`fusion` 现有 mergeKey `docId#index` 直接工作。

**写入 / 关口**
- `DocumentController.java`：JSON/multipart 上传增可选字段 `visibility`（`tenant`默认 | `public`）；`visibility=public` 时调 `requirePublicIngest()`（`hasScope("public-ingest")`）并把标志透传。
- `DocumentService.upload`：新增入参（如 `boolean shared`）；为 `true` 时以 `PublicKb.TENANT_ID` 作为 `tenantId` 计算 docId/stamp metadata（:179/:190），4 sink 自动写公共分区。删除侧 `deleteInternal` 同样支持按公共租户删。
- 保留租户 id 校验：`DocumentService.upload` 与注册/建户路径拒绝真实租户 == `__public__`。

## 数据库 / 接口 / 配置变更

- **DB（仅 `AUTH_STORE=jdbc`）**：新 `ROLES` 表；`USERS` 加 `ROLES` 列（`ALTER TABLE ADD COLUMN`，幂等）。knowledge 侧公共分区无新表（复用 Qdrant 集合命名 / 单 ES 索引 / 内存 mirror）。
- **接口**：新增 `POST /auth/register`、`/auth/admin/**`（受 `role-admin`）；`/rag/documents` 增 `visibility` 字段（受 `public-ingest`）；`/rag/query` 契约不变（公共并入对调用方透明）。
- **配置**：`app.auth.registration.*`；`app.rag.public.*`；新 scopes `role-admin` / `public-ingest`；edge api-key `dev-key-acme` 补两 scope。
- **新 scopes/角色**均为加法；默认 flag 关闭 → 老行为不变。

## 分阶段实施步骤（按依赖）

**阶段 1 — 数据结构与领域模型**
- auth：`UserAccount` +roles；`Role`/`RoleStore`/`InMemoryRoleStore`/`JdbcRoleStore`/`SeedRoles`；`UserAccountStore` 写方法 + 两实现；USERS `ALTER ADD ROLES`；`SeedUsers` 派角色。
- knowledge：`PublicKb` 常量 + `app.rag.public.*` 配置；`RetrievalRequest` +publicTenantId。
- 完成标准：编译通过；`mvn -pl auth-service test`、`mvn -pl knowledge-service test` 原有测试全绿（签名变更已全量修复）。

**阶段 2 — 核心业务逻辑**
- auth：`RoleService`；`SessionTokenIssuer` 用 effectiveScopes；`RegistrationRuleEngine`；`AuthService.register/admin` 用例。
- knowledge：`DocumentService` 公共写分支；三路 query-merge；`KnowledgeQueryService` 填 publicTenantId。
- 完成标准：新单测（见下）覆盖展开/合并/隔离并绿。

**阶段 3 — 接口与适配层**
- auth：`AdminController`（role-admin 关口）、`/auth/register`、DTO、`AuthProperties`/yml、`EdgeOpenPaths` 放行、edge api-key 补 scope。
- knowledge：`DocumentController` visibility + `requirePublicIngest`。
- 完成标准：`curl` 手测通过（见验证）；无权限访问返回 403。

**阶段 4 — 测试**（见测试方案）；**阶段 5 — 文档与最终检查**：README/docs（RBAC、公共库）、`deploy/seed-kb.sh` 增"灌公共库"选项（退款政策入公共库）、application.yml 注释；`git diff` 复核。

## 测试方案

- **RoleService**：`expand({admin})` 含 role-admin/public-ingest；`effectiveScopes` = 角色∪直配；未知角色忽略不报错。
- **登录展开**（关键回归）：bob `roles={viewer}` 登录后 JWT scopes=`[chat]`；把 bob 改 `editor` 后重登→含 `ingest`。
- **Admin 授权**：无 `role-admin` 调 `/auth/admin/users` → 403；有则 CRUD 生效（H2 + InMemory 各一套）。
- **自助注册**：`registration.enabled=false` → 403/关闭；开启后拿 `default-role`；规则 `@acme.com→acme/editor` 命中；保留 id `__public__` 被拒。
- **公共库写**：无 `public-ingest` 传 `visibility=public` → 403；有则文档进 `__public__` 分区。
- **公共库读 + 隔离**（核心）：文档入公共库后，租户 A / 租户 B 查询都命中该文档；A 私有文档 B 查不到（隔离不破）；category 过滤在公共分支仍生效；`public.enabled=false` 时行为与今日一致。
- 纪律：`@AfterEach TenantContext.clear()`；纯 POJO + H2，不起 Spring context。

## 风险、监控、灰度与回滚

- **签名变更波及面**（`UserAccount`+roles、`RetrievalRequest`+publicTenantId）：阶段 1 一次性修全部构造点与测试，编译器兜底。
- **角色生效延迟**：已签 JWT 的 scopes 不回溯；admin 改角色后建议使会话失效或提示重登（`RefreshSession` 可按 username revoke）。
- **公共库越权/泄漏**：`__public__` 为保留 id、用户不可指定为自身租户；公共**写**限 `public-ingest`；公共**读**并入不改变"看不到别的真实租户"的隔离。
- **ES `terms` 改写**：确保 `should/minimum_should_match` 不误放大召回；加公共分支单测。
- **灰度**：`app.rag.public.enabled` / `app.auth.registration.enabled` 独立开关，可先开 admin API（改 store，无 flag）再开注册/公共读。
- **回滚**：两个 flag 关闭即回到今日行为；DB 变更均加法（ROLES 表/列可留存无害）；edge api-key scope 变更可撤。
- **监控**：admin 操作打审计日志（复用 platform-audit 习惯）；公共库命中在 hit `source`/docId 可辨（来自 `__public__`）。

## 最终验收清单

- [ ] 管理员（role-admin）可经 `/auth/admin/**` 建用户、派/改角色、改租户；无 role-admin 一律 403。
- [ ] 新用户可 `/auth/register` 自助注册并获默认角色；规则映射按邮箱域落对租户+角色。
- [ ] 角色变更后重登，JWT scopes 随之变化；下游 `hasScope` 行为一致（下游代码零改动）。
- [ ] 退款政策等文档灌入公共库后，acme/globex/tenantA 任意登录用户在页面 `/chat/stream` 都能查到；各租户私有数据仍互不可见。
- [ ] 两个 feature flag 关闭时，系统行为与本次改动前完全一致（向后兼容）。
- [ ] `mvn -pl auth-service -am test`、`mvn -pl knowledge-service test` 全绿。
