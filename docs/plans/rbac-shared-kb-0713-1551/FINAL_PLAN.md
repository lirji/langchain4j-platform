# FINAL_PLAN — AI 微服务平台 RBAC 最终实施方案

> 状态：最终规划，不包含业务代码修改。本文基于 `feat/rbac-shared-kb` 当前工作树、旧 `docs/plans/rbac-shared-kb/FINAL_PLAN.md`、早期未提交实现及真实调用链重做。文中新增类/方法均标为“计划新增”，不是对现有仓库的虚假陈述。

## 1. 背景

平台已经具备完整的认证和 scope 传播链：auth-service(:8092) 校验账号密码并签发会话访问 JWT；edge-gateway 验证 Bearer 后换发短时内部 JWT；下游 `InternalTokenAuthFilter` 恢复 `TenantContext`，跨服务由 `OutboundTenantForwarder` 逐跳传播；业务代码用 `TenantContext.current().hasScope(...)` 鉴权。

当前缺口是用户只直接绑定 scopes，没有可管理的角色层。分支上已有早期实现：`Role`、`RoleStore`、内存/JDBC stores、`RoleService`、`SeedRoles`、`RegistrationRuleEngine`、`AdminService`，`UserAccount` 已加 roles，`AuthService.issueFor` 已开始展开角色，JDBC 使用 `USERS.ROLES`/`ROLES.SCOPES` CSV。与此同时 knowledge-service 有公共知识库/ES 未提交改动。

本方案不推倒这些成果：保留领域类、双 store 形态、RoleService 和签发接缝，补齐管理/注册/API/测试；同时修正当前 CSV-only 数据结构在并发注册、角色引用完整性、事务和多实例迁移上的结构性问题。

## 2. 目标与非目标

### 2.1 目标

- 全局 Role 聚合多个 scope；用户绑定多个角色。
- 有效 scopes 固定为 `角色 scopes 并集 ∪ 用户 direct scopes`。
- 登录和刷新时把有效 scopes 写入现有会话 JWT；edge 换发后继续传到所有下游。
- 提供角色 CRUD、用户 CRUD、用户角色全量替换、direct scopes 管理。
- 提供默认关闭的自助注册、默认角色和邮箱域规则映射。
- 内存/JDBC两套实现行为一致，仍由 `app.auth.store` 条件装配。
- JDBC 使用裸 JdbcTemplate + 加法式 DDL，支持 main 基线库和当前早期 CSV 库无损升级。
- API-key 配置、JWT claim、TenantContext 和下游 scope 关口不变。
- 具备并发、事务、幂等、迁移、灰度、监控和回滚方案。

### 2.2 非目标

- 不把 roles 放进内部 JWT，不修改 `TenantContext.Tenant`。
- 不让 edge 或下游实时查角色，不引入 PDP/OPA。
- 不改变 API-key→tenant/user/scopes 目录，也不给现有 key 增权。
- 不做 tenant-scoped role、角色继承、资源 ACL/ABAC。
- 不做角色管理前端。
- 不新增 Kafka/RBAC 事件或修改 `platform-protocol`。
- 不引入 JPA/Flyway/Liquibase。
- 不在本任务中补完公共知识库；仅兼容其 `__public__` 和 `public-ingest`。

## 3. 已确认的业务规则

1. Role 是 auth-service 的全局权限字典；tenant 与 RBAC 正交。
2. `UserAccount.scopes` 保持 direct scopes 语义，升级不清空。
3. `effectiveScopes = directScopes ∪ expand(roles)`，去重。
4. 会话/内部 JWT 只传 effective scopes；下游对 Role 无感知。
5. 登录和 refresh 重新展开角色；已签 access token 不回溯。
6. 未知历史角色 fail-closed：不授予 scope并告警；所有新写入必须引用已存在角色。
7. 注册默认关闭；规则从 username 中的邮箱域提取，按配置顺序 first-match；未命中沿用当前默认 `public/viewer`。
8. 用户 tenant 不允许为公共库保留值 `__public__`。
9. `/auth/admin/**` 要求 `role-admin`，这是平台级而非 tenant 级管理权限。
10. 删除被引用角色返回 409，不级联解绑。
11. 不能通过一次管理操作留下 0 个启用且有效 scopes 含 `role-admin` 的用户。
12. 角色名小写归一；scope 是不做 CRUD 的字符串权限标识，格式为 `^[a-z][a-z0-9:-]{0,63}$`。
13. 注册用户必须至少有一个已存在角色；历史 direct-scope-only 用户仍合法。
14. `PUT /auth/admin/users/{username}/roles` 是幂等全量替换。
15. GET/PUT/PATCH 的目标不存在返回 404；DELETE 对不存在目标仍返回 204，使删除可安全重放。
16. 新建/修改密码统一走可配置密码策略（默认最少 8、最多 128 字符）；只影响新写入，不强制迁移既有 BCrypt hash。
17. username 按当前 store 行为 trim + 小写、长度 1–128；已有数据不批量重命名。role name 格式为 `^[a-z][a-z0-9_-]{0,63}$`。
18. 自助注册必须同时满足 `rbac.enabled=true` 与 `registration.enabled=true`，避免在 direct-only 灰度态创建 role-only 账号。
19. legacy CSV 双写期，`USERS.SCOPES`、`USERS.ROLES`、`ROLES.SCOPES` 序列化结果必须分别不超过现有 `VARCHAR(1024)`；写事务前预检，超限返回400。

待部署前确认但不阻断开发的业务项：默认 tenant `public` 是否对应真实业务租户；生产 bootstrap admin 用户名单。代码默认保持当前 demo 语义，生产 values 必须显式覆盖。

## 4. 当前代码与调用链分析

### 4.1 认证传播链

```text
AuthController.login/refresh
  → AuthService.login/refresh
  → AuthService.issueFor
  → RoleService.effectiveScopes（当前工作树已接入）
  → SessionTokenIssuer.mintAccessToken(user,effectiveScopes)
  → 会话 JWT: sub/uid/scopes
  → SessionBearerAuthFilter.verify + InternalToken.mint
  → 内部 JWT: sub/uid/scopes
  → InternalTokenAuthFilter
  → TenantContext.Tenant
  → 下游 hasScope("ingest"/"approve"/...)
```

API-key 请求在 `ApiKeyToInternalTokenFilter` 直接用 `InternalSecurityProperties.KeyBinding.scopes` 签内部 JWT，不经过 auth-service。两条链只在内部 JWT 处汇合。

### 4.2 可复用现状

- `UserAccount.roles` 与 6 参兼容构造器。
- `Role`、`RoleStore`、`InMemoryRoleStore`、`JdbcRoleStore`。
- `SeedRoles` 和 SeedUsers 的 alice/admin、bob/viewer、analyst-a/analyst 映射。
- `RoleService.effectiveScopes` 的并集算法。
- `RegistrationRuleEngine` 的 first-match 骨架与 `__public__` 拒绝。
- `AdminService` 的用例层位置。
- `SessionTokenIssuer` 显式 effective scopes 重载。
- `AuthService.issueFor` 作为 login/refresh 的唯一生产签发接缝。
- `@ConditionalOnProperty(app.auth.store)` 双实现模式和现有 H2 测试基础。

### 4.3 必须修正的早期实现问题

- 注册是 find-then-save；内存 put/JDBC delete+insert 可覆盖同名用户。
- `JdbcRoleStore.save` delete+insert，失败会丢角色。
- CSV 无法可靠约束角色引用；角色绑定与删除存在竞态。
- `addColumnIfMissing` 吞掉所有 DataAccessException，会掩盖真实 DDL 故障。
- `COUNT(*)==0` 再 seed 在多实例同时启动时竞态。
- 注册规则没有校验角色存在；AdminService 不能管理 direct scopes。
- `AuthController.register`、`AdminController`、`AdminDtos`、`RegisterRequest` 和首批测试已经存在，不应重写；但当前管理 API 缺单项 GET、分页、direct/effective scopes、明确的 create/update 语义和条件开关。
- AuthController/AdminController 各自复制 AuthException handler，需统一为 `@RestControllerAdvice`。
- `EdgeOpenPaths` 已精确放行 `/auth/register`，但该 open path 同时绕开 `EdgeRateLimitFilter`。
- `AuthProperties.Registration` 与 application.yml 已有配置；通用 application.yml 中无条件内置 acme/globex 示例域规则，生产误开启时存在错误授权风险，应移出通用配置。
- `edge-gateway/application.yml` 当前给 `dev-key-acme` 额外加入 `role-admin/public-ingest`，违反“API-key 老路与既有测试零改动”；实施时必须恢复基线精确 scopes，而不是把这项增权当作 RBAC 接缝。
- `AuthService` 当前把 5 参构造改为 9 参，基线 `AuthServiceTest/AuthControllerTest` 因而被机械修改；应恢复兼容构造入口，让旧测试源码不因依赖注入变化而改写。
- Role/User 集合仍可变，ConcurrentHashMap 列表顺序不稳定。
- 旧库升级后没有可靠首个 role-admin 引导。
- Helm values 当前没有 auth-service、AUTH_URI、session/auth DB 配置，是现有部署缺口。
- `/auth/register` 将成为 edge open path，而 `EdgeRateLimitFilter` 也共用 `EdgeOpenPaths`；必须在 auth-service 内增加按客户端 IP 的独立注册节流，不能只复用“username+IP 且只记录密码失败”的 `LoginThrottle`。

### 4.4 同分支公共知识库现状

`PublicKb`、`RetrievalRequest.publicTenantId`、vector/keyword/ES 公共召回、`DocumentService.upload(...,shared)` 已存在；`DocumentController` 也已实现 `visibility=public|shared` 与 `public-ingest` 关口，并有对应早期测试。当前代码未发现 Graph source 使用 `PublicKb/publicTenantId`，是否补齐属于公共库任务的待验证项。本 RBAC 实施只承认 `public-ingest` 是真实 scope，并拒绝用户 tenant=`__public__`，不修改 knowledge-service。

## 5. 四个候选方案与评分

评分 1–5，5 最佳；复杂度/测试难度/回滚成本的 5 表示更低。

| 方案 | 正确性 | 改动风险 | 复杂度 | 可维护性 | 扩展性 | 测试难度 | 回滚成本 | 总分 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A CSV 聚合 + 签发时展开 | 3 | 5 | 5 | 3 | 2 | 4 | 5 | 27/35 |
| B 关系化 auth RBAC + 签发时展开 | 5 | 4 | 3 | 5 | 5 | 4 | 3 | **29/35** |
| C roles 进会话 JWT、edge 展开缓存 | 4 | 2 | 2 | 3 | 4 | 2 | 2 | 19/35 |
| D 集中 PDP、下游每请求决策 | 5 | 1 | 1 | 2 | 5 | 1 | 1 | 16/35 |

- A 最快，但 CSV 的引用完整性/反查/并发删除弱点不可彻底消除。
- B 保持 token/downstream 契约，同时用关系表解决完整性，最符合本任务。
- C 改变会话 token 并让 edge 依赖角色缓存/auth 可用性，爆炸半径过大。
- D 违反“下游继续按 scope”的硬约束，直接淘汰。

详细评审见 `03-solution-a.md` 至 `06-solution-d.md` 和 `comparison.md`。

## 6. 最终方案及选择原因

### 6.1 方案

以 B 为骨架，吸收 A 的兼容优点：

1. 保留当前 Role/User/Store/Service 领域边界和 AuthService 签发接缝。
2. JDBC 新增 `USER_ROLE`、`ROLE_SCOPE`，关系表为完成迁移后的权威数据源。
3. 保留 `USERS.ROLES`、`ROLES.SCOPES` 一个兼容版本并同事务双写，不在本期 drop。
4. 有效 scopes 仍只在登录/刷新时物化；会话 JWT、edge、内部 JWT、下游零结构变化。
5. `app.auth.rbac.enabled` 默认 false，Compose demo 显式 true，生产 Helm 分阶段开启。
6. 注册和管理变更通过 store 的原子方法；JDBC 使用本地事务和行锁，内存实现使用共享临界区获得等价语义。

### 6.2 选择原因

- 对现有代码同构，早期实现绝大部分可复用。
- 把复杂性限制在 auth-service 控制面，不把角色服务带入 edge/下游请求数据面。
- 关系表支持引用约束、按角色反查用户、批量展开和未来管理能力。
- JWT/API-key/下游兼容性最好，灰度和故障域可控。

### 6.3 已知弱点

- 迁移器、关系表和兼容双写比 CSV-only 多一层复杂度。
- 双写期存在漂移可能，必须同事务并做启动一致性检查。
- 权限撤销不能使已签 access JWT 立即失效，最长延迟为 SESSION_ACCESS_TTL（当前默认 60m）。
- 关闭 RBAC或代码回滚后，新建的 role-only 用户没有 direct permissions；灰度必须延后开放新写入，并准备回滚物化步骤。
- 平台级 role-admin 权限较大，尚无 tenant admin。
- scope 无中央 catalog；本期只做格式校验、文档和测试。

## 7. 精确到文件 / 类 / 方法的修改清单

以下“新增”均为计划新增。

路径约定：7.1–7.4 中未写模块前缀的 auth Java 文件都位于 `auth-service/src/main/java/com/lrj/platform/auth/`，DTO 位于其 `dto/` 子目录；7.5 的 edge Java/测试分别位于 `edge-gateway/src/main/java/com/lrj/platform/edge/`、`edge-gateway/src/test/java/com/lrj/platform/edge/`。测试文件若未写前缀，位于 `auth-service/src/test/java/com/lrj/platform/auth/`。所有新增文件沿用相同包路径。

### 7.1 auth-service 领域与配置

| 文件 | 类/方法 | 修改 |
|---|---|---|
| `AuthProperties.java` | 新增 `Rbac`、`Seed`、`PasswordPolicyProperties`、`ClientIp` 嵌套配置；规范 `Registration/Throttle` | `rbac.enabled/adminWritesEnabled/bootstrapAdminUsers`、`seed.enabled`、密码长度、注册节流、是否信任 XFF；管理写开关用于灰度读写分离 |
| `UserAccount.java` | compact constructor、6参兼容构造 | 新写入 username trim+小写；scopes/roles trim、去重、排序后用不可变 `LinkedHashSet` 快照（不用迭代顺序未定义的 `Set.copyOf`）；保留旧构造 |
| `Role.java` | compact constructor | name 小写归一并校验格式；scopes trim、去重、排序后不可变；description trim且最大256 |
| `SeedRoles.java` | `defaults()` | 保留现有五角色；admin 保留 `role-admin`/`public-ingest`；测试精确 scopes |
| `SeedUsers.java` | `defaults(...)` | 保留当前三用户 direct scopes 与角色，不清空 direct scopes |

### 7.2 Store 与 schema

| 文件 | 类/方法 | 修改 |
|---|---|---|
| `UserAccountStore.java` | 保留 `findByUsername`；计划增加 `createIfAbsent`、`updateProfile`、`replaceRoles`、`findByRole`、`findPage`、`count`、`findAll`、`delete` | 原子语义；分页与管理 API 对齐；默认方法继续保证既有 lambda 测试可编译，但生产两实现必须覆盖 |
| `RoleStore.java` | 计划增加 `findByNames`、`createIfAbsent`、`update`、`isAssigned`；保留查询/删除 | 避免 save=upsert 模糊语义 |
| `InMemoryUserAccountStore.java` | 上述方法 | putIfAbsent；稳定排序；复合操作在共享 RBAC mutation 临界区内执行 |
| `InMemoryRoleStore.java` | 上述方法 | 原子 create/update；返回不可变快照；稳定排序 |
| `JdbcUserAccountStore.java` | `findByUsername/createIfAbsent/updateProfile/replaceRoles/findByRole/findPage/count/delete` | 读写 USERS+USER_ROLE；移除 DELETE+INSERT；关系表权威、CSV影子双写；分页按 USERNAME 稳定排序 |
| `JdbcRoleStore.java` | `findByName/findByNames/createIfAbsent/update/isAssigned/delete` | 读写 ROLES+ROLE_SCOPE；移除 DELETE+INSERT；删除引用409由服务映射 |
| `RefreshSessionStore.java` | 计划新增 `revokeByUsername(String)`、`deleteByUsername(String)` | 权限降低/禁用/删除时撤销 refresh sessions |
| `InMemoryRefreshSessionStore.java`、`JdbcRefreshSessionStore.java` | 实现上述方法 | 内存 compute；JDBC 利用现有 USERNAME 索引批量 UPDATE/DELETE |
| `AuthJdbcConfig.java` | 新增 `authTransactionManager` bean | `DataSourceTransactionManager(authDataSource)`，仅 jdbc 条件装配 |
| **新增** `JdbcRbacSchemaInitializer.java` | `initializeSchema()`、`migrateLegacyCsv()`、`seedIfEnabled()` | 集中 DDL、迁移状态、精确异常处理、多实例幂等；两个 JDBC store 通过构造器依赖它，明确先初始化后查询 |
| **新增** `RbacMutationExecutor.java`、`InMemoryRbacMutationExecutor.java`、`JdbcRbacMutationExecutor.java` | `execute(Supplier<T>)` | 接口统一复合写；内存实现使用共享锁并按 store 条件装配；JDBC 实现使用 TransactionTemplate + 固定 schema-state 锁行并按 jdbc 条件装配，串行化低频 RBAC 控制面变更 |

说明：全局 mutation lock 只覆盖注册与管理写，不覆盖登录读；控制面吞吐换取跨 store 的确定性和最后管理员保护。若后续注册流量成为瓶颈，可演进为按 username/role 行锁，本期不提前复杂化。

### 7.3 核心服务

| 文件 | 类/方法 | 修改 |
|---|---|---|
| `RoleService.java` | `expand`、`effectiveScopes`；计划新增 `requireRolesExist`、`validateScopes`、`validateLegacyCsvLength` | rbac flag关闭时 direct-only；批量查询；未知角色 fail-closed + metric/log；双写期在事务前拒绝 `USERS.SCOPES/ROLES` 或 `ROLES.SCOPES` 超过1024字符 |
| `RegistrationRuleEngine.java` | `resolve`、`domainOf`、`requireSafeTenant` | 规则/值 trim、first-match明确；保留 `__public__` 拒绝；多@输入按最后一个@解析（实现与测试一致） |
| `AuthService.java` | `register(username,password,clientIp)`、`issueFor`；恢复旧 5 参构造 | register 检查 rbac+registration flags和纯 IP 节流；密码校验/BCrypt 在锁外完成，mutation executor 内重新校验角色、createIfAbsent 并调用 issueFor，使 JDBC 用户与 refresh session 同事务提交；冲突409；完整构造器标为 Spring 注入入口，deprecated 兼容构造保持 direct-only |
| `AdminService.java` | 将现有方法收敛为 `listUsers(offset,limit)/countUsers/getUser/createUser/updateUserProfile/replaceRoles/deleteUser` 与 `listRoles/getRole/createRole/updateRole/deleteRole` | direct scopes 管理、引用409、最后管理员保护、actor审计日志；密码校验/BCrypt 在锁外；用户权限降低时撤销该用户 refresh sessions，角色移除 scope 时反查全部绑定用户并撤销其 refresh sessions |
| `SessionTokenIssuer.java` | 保留两个 `mintAccessToken` 重载 | 生产继续调用显式 effective scopes；旧重载只为兼容测试/调用，不加 roles claim |
| **新增** `RbacMetrics.java` | mutation/registration/unknownRole/migration 指标 | 使用现有 Actuator/Micrometer，不引 platform-audit 的 LLM 依赖 |
| **新增** `RegistrationThrottle.java` | `checkAndRecord(String clientIp)` | 按 IP 的固定窗口限流；成功/失败尝试都计数，避免攻击者用不同 username 绕过；惰性清理过期 key 并设容量上限，防止内存被随机 IP 撑满；多副本为节点本地限制，严格全局限流是后续项 |
| **新增** `PasswordPolicy.java` | `validate(String rawPassword)` | 注册、管理员创建和改密共用；长度默认 8–128，错误映射400；PasswordHasher继续只负责BCrypt |

### 7.4 HTTP 与 DTO

| 文件 | 类/方法 | 修改 |
|---|---|---|
| `AuthController.java` | 加固现有 `register(RegisterRequest,HttpServletRequest)`、`clientIp(HttpServletRequest)` | 保留 `POST /auth/register`、LoginResponse 与 refresh cookie；login 继续传 username+IP throttle key，register 改传纯 clientIp；移除本地 exception handler |
| `AdminController.java` | 扩充现有 `requireRoleAdmin`、`requireAdminWritesEnabled` 及 CRUD handlers | 保留 `@RequestMapping("/auth/admin")`；增加详情/分页/direct scopes；RBAC flag控制整体装配，admin-writes flag在 POST/PUT/PATCH/DELETE 返回503 `rbac_writes_disabled`，GET可先灰度 |
| **新增** `AuthExceptionHandler.java` | `handle(AuthException)` | `@RestControllerAdvice`，统一 Auth/Admin 错误 `{error,message}` |
| `AdminDtos.java` | 原位定义 `CreateUserRequest(username,password,tenant,roles,directScopes,enabled)`、`UpdateUserRequest(tenant,password,directScopes,enabled)`、`ReplaceRolesRequest(roles)`、`CreateRoleRequest(name,scopes,description)`、`UpdateRoleRequest(scopes,description)`、`UserAdminView(username,userId,tenant,directScopes,roles,effectiveScopes,enabled)`、`RoleView` | 取代当前语义含混的 `AssignRolesRequest/RoleRequest`，仍保留在一个聚合 DTO 文件中；所有响应只用安全 view |
| `RegisterRequest.java` | 保留现有 record | 请求字段不变；由 service 完成归一/策略校验 |
| `auth-service/application.yml` | `app.auth.rbac/seed/password-policy/registration` | 暴露安全默认值；registration 默认 false；移除通用配置中的 acme/globex 示例规则 |

`LoginRequest/LoginResponse/UserView` 不改字段。登录/刷新/me 返回的 scopes 是有效 scopes。

### 7.5 edge、测试、部署与文档

- `edge-gateway/.../EdgeOpenPaths.java`：保留当前精确 `/auth/register` 并补测试；`/auth/admin/**` 仍受认证，生产逻辑原则上无需再改。
- `edge-gateway/src/main/resources/application.yml`：把 `dev-key-acme` 从当前 `[chat, ingest, approve, agent, channel, eval, vision, voice, role-admin, public-ingest]` 恢复为基线 `[chat, ingest, approve, agent, channel, eval, vision, voice]`；其它 binding 原样保留。
- 基线 `ApiKeyToInternalTokenFilterTest.java`、`SessionBearerAuthFilterTest.java` 原样通过；新增 `EdgeOpenPathsTest.java`、`RbacApiKeyCompatibilityTest.java`、`RbacSessionScopePropagationTest.java` 承载 register open path、配置精确基线和内部 scopes 传播断言。
- 基线 `AuthServiceTest/AuthControllerTest` 的原断言与 5 参构造调用保持不改；当前工作树对构造调用的机械修改应恢复。现有新增 `RoleServiceTest`、`RegistrationTest`、`AdminControllerTest` 原位补强；再新增 `RbacAuthServiceTest`、`RbacAuthControllerTest`、`RbacSessionTokenIssuerTest`、`RegistrationRuleEngineTest`、`AdminServiceTest`、store contract、migration、condition tests。基线 `JdbcStoresTest/SessionTokenIssuerTest` 原样通过。
- `deploy/docker-compose.yml`：auth-service 增 `AUTH_RBAC_ENABLED=true`、`AUTH_RBAC_ADMIN_WRITES_ENABLED=true`（仅 demo）、`AUTH_REGISTRATION_ENABLED=false`、bootstrap/seed；不改 edge api-key scopes。
- `deploy/helm/platform/values.yaml`、`templates/secret.yaml`、`templates/externalsecret-sample.yaml`：补现有 auth-service/`AUTH_URI`/auth DB缺口；新增仅 auth-service 与 edge-gateway 引用的 session-JWT Secret，绝不把会话签名密钥注入所有下游；RBAC默认 false，生产显式 bootstrap。
- `deploy/helm/README.md`、`README.md`、`docs/平台工程/数据库与中间件清单.md`、Postman collection 更新。
- 不修改 knowledge-service、platform-security 生产代码和 platform-protocol。

## 8. 数据库变更

### 8.1 现有表保留

- USERS：保留全部基线列；只确保存在早期实现的 nullable `ROLES VARCHAR(1024)` 兼容列，不为本期再加无业务用途的列。
- ROLES：保留当前早期表与 `SCOPES VARCHAR(1024)`；不改变现有列含义。
- AUTH_SESSION：结构不变。

### 8.2 新表

```sql
CREATE TABLE IF NOT EXISTS ROLE_SCOPE (
  ROLE_NAME VARCHAR(128) NOT NULL,
  SCOPE VARCHAR(128) NOT NULL,
  CREATED_AT BIGINT NOT NULL,
  PRIMARY KEY (ROLE_NAME, SCOPE),
  CONSTRAINT FK_ROLE_SCOPE_ROLE FOREIGN KEY (ROLE_NAME)
    REFERENCES ROLES(NAME) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS USER_ROLE (
  USERNAME VARCHAR(128) NOT NULL,
  ROLE_NAME VARCHAR(128) NOT NULL,
  CREATED_AT BIGINT NOT NULL,
  PRIMARY KEY (USERNAME, ROLE_NAME),
  INDEX IDX_USER_ROLE_ROLE (ROLE_NAME),
  CONSTRAINT FK_USER_ROLE_USER FOREIGN KEY (USERNAME)
    REFERENCES USERS(USERNAME) ON DELETE CASCADE,
  CONSTRAINT FK_USER_ROLE_ROLE FOREIGN KEY (ROLE_NAME)
    REFERENCES ROLES(NAME)
);

CREATE TABLE IF NOT EXISTS AUTH_SCHEMA_STATE (
  STATE_KEY VARCHAR(128) NOT NULL PRIMARY KEY,
  STATE_VALUE VARCHAR(64) NOT NULL,
  UPDATED_AT BIGINT NOT NULL
);
```

MySQL/H2 对命名 constraint/INDEX 内联语法必须由测试确认；如 H2 需要分开 CREATE INDEX，仅调整 DDL 写法，不取消 MySQL 外键。任何非 duplicate-column/table-already-exists 异常都必须 fail-fast。

初始化器还要幂等插入固定 `STATE_KEY='rbac-mutation-lock'` 行，`JdbcRbacMutationExecutor` 每次控制面写事务先 `SELECT ... FOR UPDATE` 锁该行。`JdbcUserAccountStore` 和 `JdbcRoleStore` 构造器显式接收 `JdbcRbacSchemaInitializer` 参数，禁止依赖未定义的 Spring bean 构造顺序。

### 8.3 迁移算法

1. 创建/补齐父表和兼容列。
2. 创建关系表和 schema-state。
3. 对 `rbac-relations-v1` 状态行加锁；不存在则安全插入并在 duplicate key 后重读。
4. 若非 COMPLETE：
   - 逐 ROLE 解析 `ROLES.SCOPES`，去空/去重，插 ROLE_SCOPE。
   - 逐 USER 解析 `USERS.ROLES`，仅对已存在角色插 USER_ROLE；未知角色不授权、记录计数并把状态置 `NEEDS_REMEDIATION`，不得标 COMPLETE。
   - 为配置的 bootstrap admin users 幂等绑定 admin，绝不凭 direct scopes 自动猜角色。
   - 无未知角色且关系计数/引用校验通过后才标 COMPLETE；修复缺失角色或清理脏绑定后可幂等重跑。
5. COMPLETE 后所有读以关系表为权威；本版本所有写在同一事务更新关系表和 legacy CSV。
6. 本期不删除兼容列，不做 contract migration。

seed 顺序固定为：幂等插入 `ROLES` → `ROLE_SCOPE` → `USERS` → `USER_ROLE`。`seed.enabled=false` 且库中没有 admin 角色/首个用户时，bootstrap 不得创建默认密码账号，也不得静默跳过；保持 RBAC disabled 并以明确启动诊断提示先走受控 SQL/一次性部署。只有 schema/migration COMPLETE、admin 角色存在且 bootstrap 目标用户均可验证时才允许开启 RBAC。

`legacy_drift` 只比较“可解析且角色存在”的 canonical 投影；未知角色由独立指标/状态表示，不能被漂移算法忽略后仍宣称可启用。若部署配置已要求 `rbac.enabled=true` 而 migration 非 COMPLETE 或 bootstrap 校验失败，应用必须 fail-fast，避免部分副本 direct-only、部分副本 relation-authoritative。

## 9. 接口变更

### 9.1 公共注册

- `POST /auth/register`
- 请求：`{ "username": "...", "password": "..." }`
- 成功：200 + 现有 LoginResponse + refresh cookie。
- 典型错误：400 输入/保留 tenant，403 disabled，409 username_taken，429 throttle，500 registration_misconfigured。

### 9.2 管理角色（全部要求 role-admin）

- `GET /auth/admin/roles`
- `GET /auth/admin/roles/{name}`
- `POST /auth/admin/roles` → 201
- `PUT /auth/admin/roles/{name}` → 200，全量替换 scopes/description
- `DELETE /auth/admin/roles/{name}` → 204（不存在也 204）；已引用 409

### 9.3 管理用户

- `GET /auth/admin/users?offset=0&limit=50`
- `GET /auth/admin/users/{username}`
- `POST /auth/admin/users` → 201，可指定 roles/directScopes/enabled
- `PATCH /auth/admin/users/{username}` → 200，可修改 tenant/password/directScopes/enabled；username/userId 不可变
- `PUT /auth/admin/users/{username}/roles` → 200，幂等全量替换
- `DELETE /auth/admin/users/{username}` → 204（不存在也 204）

所有响应使用安全 view，绝不序列化 UserAccount 本身。用户列表按 username 稳定排序，`limit` 默认 50、最大 200，并通过 `X-Total-Count` 返回总数。

PATCH 中字段缺失或 null 表示“不修改”，`directScopes: []` 表示明确清空；roles 只能通过专用 PUT 全量替换。角色 PUT 以 path name 为唯一标识，请求体不再重复 name；scopes 是必填全量集合，description 可为 null。

当前未提交早期 API 使用 `PUT /users/{username}`、`POST /users/{username}/roles`、角色 POST-upsert 和 200 delete。实施时在首次对外发布前按上述契约收敛；若该分支 API 已被外部环境实际调用（**待验证**），保留旧 POST 绑角色作为一个版本的 deprecated alias，内部委托同一 replaceRoles 用例，禁止维持两套不同语义。

## 10. 配置变更

计划配置：

```yaml
app:
  auth:
    rbac:
      enabled: ${AUTH_RBAC_ENABLED:false}
      admin-writes-enabled: ${AUTH_RBAC_ADMIN_WRITES_ENABLED:false}
      bootstrap-admin-users: ${AUTH_RBAC_BOOTSTRAP_ADMIN_USERS:alice}
    seed:
      enabled: ${AUTH_SEED_ENABLED:true}
    password-policy:
      min-length: ${AUTH_PASSWORD_MIN_LENGTH:8}
      max-length: ${AUTH_PASSWORD_MAX_LENGTH:128}
    client-ip:
      trust-forwarded-for: ${AUTH_TRUST_FORWARDED_FOR:false}
    registration:
      enabled: ${AUTH_REGISTRATION_ENABLED:false}
      default-tenant: ${AUTH_REGISTRATION_DEFAULT_TENANT:public}
      default-role: ${AUTH_REGISTRATION_DEFAULT_ROLE:viewer}
      throttle:
        enabled: ${AUTH_REGISTRATION_THROTTLE_ENABLED:true}
        max-attempts: ${AUTH_REGISTRATION_MAX_ATTEMPTS:10}
        window: ${AUTH_REGISTRATION_WINDOW:PT10M}
        max-keys: ${AUTH_REGISTRATION_MAX_KEYS:10000}
```

规则列表主要通过部署专用 yaml/config-server 配置；复杂 list 不强行压成单个环境变量，也不在本地 application.yml 写一个可能遮蔽远端配置的空 `rules: []`。生产必须显式配置 bootstrap admin 并核验账号归属。`seed.enabled=false` 时 bootstrap 只给已存在用户绑定角色，不会凭空创建带默认密码的管理员；全新生产库必须先通过受控 SQL/一次性部署流程创建首个用户，不能启用 demo 密码。

## 11. JWT、消息与跨模块契约

- 会话 JWT：仍为 `sub=tenant, uid=userId, scopes=[effective...]`，不新增 roles claim。
- 内部 JWT：完全不变。
- `TenantContext.Tenant`：完全不变。
- API-key KeyBinding：完全不变。
- Java 测试/手工构造兼容：`AuthService` 保留旧 5 参构造；它不启用角色展开，生产 Spring 始终选择完整构造器。该入口标记 deprecated 并由兼容测试锁定。
- LoginResponse/UserView：字段不变。
- Kafka/eventbus/platform-protocol：无消息结构变更、无新 topic。
- 新 admin DTO 是 auth-service 外部 HTTP 契约，放本模块 dto 包，不放 platform-protocol。

## 12. 分阶段实施步骤及依赖关系

总体依赖：阶段 1 → 阶段 2 → 阶段 3 → 阶段 4 → 阶段 5。每阶段连续执行，不等待额外“继续”；只有真实业务待确认或危险操作才暂停。

### 阶段 1 — 数据结构与领域模型

1. 稳定 `UserAccount`/`Role` 不可变与归一规则，保留兼容构造。
2. 扩展 Store 端口的原子语义，先完成内存实现 contract。
3. 新增 auth transaction manager、JdbcRbacSchemaInitializer、关系表/状态表/legacy backfill。
4. 重构 JDBC stores，移除 delete+insert，关系表权威+CSV双写。
5. 增 RefreshSessionStore 按用户撤销。
6. 加 rbac/admin-writes/seed/bootstrap properties 和 application.yml，但默认不开 RBAC/管理写。

完成标准：

- main 基线 schema、当前早期 CSV schema、空库三种 H2 升级通过。
- 内存/JDBC store contract 通过。
- 多次初始化幂等、非 duplicate DDL 异常 fail-fast。
- 现有 auth 测试仍通过；没有 Controller 行为变化。

### 阶段 2 — 核心业务逻辑

1. RoleService 增 flag、批量展开、未知角色监控、校验。
2. 新增 mutation executor；AdminService 全部写用例在其内完成。
3. 完成用户 direct scopes、角色绑定、引用冲突、最后管理员保护；角色 scope 减少时撤销全部受影响用户的 refresh sessions。
4. RegistrationRuleEngine 输入硬化；AuthService.register 改原子 create 并校验角色。
5. 权限降低/禁用/删除时撤销相关 refresh sessions。
6. 保持 AuthService.issueFor→SessionTokenIssuer effective scopes 接缝。

完成标准：

- Role/Admin/Registration/Auth 单元测试全绿。
- 并发注册只有一个成功，无覆盖。
- 角色/用户复合写注入异常时整体回滚。
- 旧 direct-scope-only 用户结果不变。

### 阶段 3 — 接口与适配层

1. 加固现有 RegisterRequest 和 AuthController.register，接入注册专用节流。
2. 新 AuthExceptionHandler，移除 controller 局部 handler。
3. 原位扩充现有 AdminController/AdminDtos 和 role-admin 关口，收敛 HTTP 契约。
4. 新 RegistrationThrottle；保留 EdgeOpenPaths 的精确 register 放行；扩 gateway 测试。
5. Compose/Helm 接入 flags、auth-service/auth DB配置和 auth/edge 专用 session Secret；恢复 `dev-key-acme` 基线 scopes，确保最终 API-key bindings 相对基线无权限 diff。

完成标准：

- HTTP 状态码、错误体和 refresh cookie 契约通过。
- 非 role-admin 所有 admin API 403；无凭证仍由 gateway 401。
- 响应不含敏感字段。
- `dev-key-acme` 已移除当前分支额外的 `role-admin/public-ingest`，全部 API-key scopes 与基线逐项一致并通过回归。
- Helm template 中真实出现 auth-service，edge 有 AUTH_URI，auth/edge 共享正确 session secret，任一下游 Deployment 均没有 SESSION_JWT_SECRET。

### 阶段 4 — 测试

1. 完成 `test-plan.md` 的单元、store contract、H2 migration、并发、controller、condition 测试。
2. Compose 真实 MySQL 双副本初始化 smoke。
3. 登录→gateway→下游现有 hasScope 端点端到端验证。
4. 跑 auth/edge/security/knowledge 模块和全仓测试。

完成标准：

- `mvn -pl auth-service,edge-gateway,platform-security -am test` 通过。
- `mvn -pl knowledge-service -am test` 通过。
- `mvn test` 通过。
- MySQL smoke 无手工修表、无重复 seed/脏关系。
- 并发测试重复 20 次无 flaky。

### 阶段 5 — 文档与最终检查

1. 更新 README、Helm README、数据库清单、Postman collection。
2. 文档列出有效 scope 规则、角色生效时机、API-key不参与角色、回滚限制。
3. `git diff` 核对未覆盖 knowledge/ES/公共库工作树变更。
4. 检查 secret、密码 hash、token 未进入日志/响应/提交。

完成标准：

- 接口/配置/schema/灰度/回滚均有可执行文档。
- API-key配置无权限变化。
- 未修改 platform-security/downstream生产代码。
- 工作树差异与本清单逐项对应，无无关改动。

## 13. 测试方案摘要

完整矩阵见 `test-plan.md`，最关键验收如下：

- RoleService：多角色+direct scopes 去重并集；未知角色 fail-closed；flag关闭 direct-only。
- 注册：关闭、默认、域规则、未知角色、保留 tenant、重复/并发。
- Admin：403、CRUD状态码、安全 DTO、引用409、最后管理员保护、幂等 PUT。
- 会话：注册建户与 refresh session 同事务；用户/角色降权后相关 refresh sessions 被撤销；旧 access 仍仅受 TTL 约束。
- Store：内存/JDBC同契约；事务回滚；角色绑定/删除竞态。
- 迁移：空库、main USERS、早期 CSV、失败重试、多副本并发。
- JWT：session→edge internal scopes 精确保留；claim形状不变。
- API-key：既有 `[chat]` 等 scopes 精确不变。
- 生效时机：角色修改后旧 access 不变，refresh/re-login 更新。
- 下游：viewer 的 ingest/approve 被拒，授予对应角色后新 token 放行。

## 14. 风险、监控、灰度与回滚

### 14.1 风险与控制

| 风险 | 控制 |
|---|---|
| 角色撤销后旧 JWT 仍有效 | 明示最长 SESSION_ACCESS_TTL；灰度期降到 5–15m；降低权限时撤销 refresh session |
| CSV/关系表漂移 | 同事务双写；启动一致性检查和 metric；schema-state 明确权威切换 |
| 多副本 seed/迁移竞争 | 状态行锁、逐行 insert、只吞 DuplicateKeyException；JDBC stores 构造依赖 initializer |
| 注册覆盖已有用户 | createIfAbsent + PK；禁止 delete+insert；统一密码策略 |
| CSV 兼容列被静默截断/写失败 | 服务层限制序列化后长度≤1024；关系表与CSV同事务；边界测试覆盖 |
| 删除被引用角色/并发绑定 | 事务、固定锁顺序、USER_ROLE引用约束、409 |
| 管理员自锁死 | mutation executor 串行控制面变更，提交前检查至少一个启用 role-admin |
| bootstrap 误授权 | 生产显式名单，启动日志列出目标但不含密码，部署前人工核验 |
| RBAC 影响 API-key | 不改 bindings；回归断言精确 scopes |
| DDL 错误被吞 | 只识别明确 duplicate 错误，其他 fail-fast |
| 与 shared-KB/ES 工作树冲突 | RBAC 不修改 knowledge-service；独立跑 knowledge tests并 diff复核 |
| 全局 mutation lock 降低注册吞吐 | 本期注册默认关闭且管理写低频；另有本地 IP 节流；监控锁等待，后续按角色/用户细化锁 |
| session secret 泄到下游 | Helm 使用 auth/edge 专用 Secret 和 envFrom；模板测试断言其它 Deployment 不含该变量 |
| 伪造 X-Forwarded-For 绕注册节流 | auth-service 不对公网直暴露；edge/Ingress 清洗 XFF，只信受控代理链；生产验收包含该配置检查 |

### 14.2 监控与审计日志

计划指标（避免 username/tenant 等高基数标签）：

- `auth_rbac_mutations_total{action,outcome}`
- `auth_rbac_unknown_role_total`
- `auth_registration_total{outcome}`
- `auth_rbac_migration_state`（0/1）
- `auth_rbac_legacy_drift_total`
- `auth_rbac_mutation_seconds`

AdminService 记录结构化安全日志：actor tenant/user、action、target type/id、outcome、变更前后角色/scope摘要；不记录 password/hash/token。403/409/注册失败率纳入告警。

客户端 IP 默认只取 `remoteAddr`。只有 Ingress/edge 已覆盖而非透传外部 X-Forwarded-For 时，生产才可开启 `AUTH_TRUST_FORWARDED_FOR=true` 并取受控代理链第一项；否则保持 false，不能让调用者通过伪造 XFF 轮换限流 key。

### 14.3 灰度发布

1. **预检查**：备份 auth DB；确认生产 bootstrap admin；确认当前 direct scopes 数据；将 access TTL 临时降到 5–15m。
2. **Expand 部署**：新代码以 `AUTH_RBAC_ENABLED=false` 上线；只建表/迁移，登录仍 direct scopes；核对 migration COMPLETE 和 drift=0。
3. **隔离环境启用**：该 flag 是全局开关，不能声称按 tenant 灰度；先在 staging/独立 canary 环境开启 RBAC，现有用户 direct scopes 保底，重新登录比对 scopes。生产同一环境的 auth 多副本必须使用一致配置，禁止单副本开关造成随机权限。
4. **生产先读后写**：生产全体 auth 副本开启 RBAC、保持 `AUTH_RBAC_ADMIN_WRITES_ENABLED=false`，验证登录和 admin GET/审计；稳定后全体副本一致开启管理写，registration 仍关闭。
5. **开放新用户**：稳定一轮 access+refresh TTL 后才允许 role-only 用户/registration。
6. **全量**：恢复正常 TTL（是否保持更短由安全要求决定），继续保留 legacy 双写至少一个版本。

### 14.4 回滚

- 在步骤 2–4、尚未产生 role-only 用户时：关闭 `AUTH_RBAC_ENABLED` 即回 direct scopes；保留新表无害。必要时回滚二进制到 main，main 会忽略新增列/表。
- 已产生 role-only 用户后：不能直接回 main并宣称权限不变。先导出每个用户 current effective scopes，经过审批后临时写入 USERS.SCOPES，再关 flag/回滚；保留备份以便恢复。
- 不 drop 新表/列；不执行 destructive rollback。
- API-key 路径始终存在，可作为受控业务连续性通道，但不临时给 key 增 `role-admin`。
- 若迁移未 COMPLETE：保持 RBAC flag false，修复 DDL/数据后重试；不得跳过状态强制启用。

## 15. 最终验收清单

### 功能

- [ ] 角色 CRUD、用户 CRUD、角色全量绑定、direct scopes 管理均可用。
- [ ] 多角色与 direct scopes 的有效权限为去重并集。
- [ ] 登录与 refresh 把 effective scopes 写入 session JWT。
- [ ] edge 换发后内部 JWT/TenantContext scopes 精确一致。
- [ ] 注册默认关闭；开启后默认/域规则正确。
- [ ] `__public__` 不能成为用户 tenant；admin 角色可含 public-ingest。

### 安全与一致性

- [ ] 所有 admin API 无 role-admin 为 403。
- [ ] 响应/日志无 passwordHash、token、cookie值。
- [ ] 同名并发注册只有一个成功，不覆盖。
- [ ] 引用中的角色不能删除；复合写失败完整回滚。
- [ ] 至少保留一个启用的 role-admin 用户。
- [ ] 权限降低撤销 refresh sessions；access token 延迟已文档化并验证。

### 兼容与迁移

- [ ] main 基线 USERS、早期 CSV schema、空库均可升级。
- [ ] 现有 direct scopes 一项不丢。
- [ ] 内存/JDBC行为一致且条件装配唯一。
- [ ] API-key配置无 diff、既有测试零语义改动。
- [ ] platform-security/downstream生产代码无改动。
- [ ] 无 Kafka/platform-protocol 变更。

### 测试与交付

- [ ] auth/edge/security/knowledge 定向测试和全仓测试通过。
- [ ] 真实 MySQL 双副本初始化与 CRUD smoke 通过。
- [ ] Compose/Helm 配置可启动 auth+edge，会话 secret一致。
- [ ] README、数据库清单、Helm说明、Postman示例完成。
- [ ] 最终 diff 未覆盖当前 knowledge/ES/shared-KB 未提交工作。

## 16. 资深架构师终审记录

终审已基于最后一次读取到的工作树重新核对，已修复以下原规划矛盾：注册/AdminController/DTO/edge open path 已存在而非待新增；公共写入关口已落地；`dev-key-acme` 当前非兼容增权必须撤销；全局 RBAC flag 不能宣称按 tenant 灰度；管理读写需独立开关；注册建户与 refresh session 必须同事务；角色降权需撤销所有受影响用户 refresh sessions；基线测试通过兼容构造保持源码不改；三处 legacy CSV 均受1024长度约束。

本规划任务因“唯一可写目录”约束未运行 Maven/Helm 渲染或生成 `target/`；所有编译、H2/MySQL 和部署验证均已明确列入阶段4完成门槛，当前状态不得误报为已通过。

## 17. Claude 跨模型复核记录（阶段二，2026-07-13）

由 Claude 对照 `feat/rbac-shared-kb` 真实工作树逐条核验本方案，结论：**整体准确、可直接执行**。已确证的关键论断（均属实）：`AuthService.issueFor → RoleService.effectiveScopes` 已接入（AuthService.java:130-131）、`SessionTokenIssuer` 两个 `mintAccessToken` 重载已存在、`UserAccount` 已含 `roles` + 兼容构造、`EdgeOpenPaths` 已放行 `/auth/register`、`AdminController/AdminService/AdminDtos/RegisterRequest` 及 `RoleServiceTest/RegistrationTest/AdminControllerTest` 均已存在（故本方案“加固/补全既有实现”而非“新增”的定性正确）、`AuthController` 与 `AdminController` 各自重复 `@ExceptionHandler(AuthException.class)`（统一为 `@RestControllerAdvice` 的建议成立）。

**codex 未越界写业务代码**：`??` stray 文件是分支早前实施会话的产物（`AdminService.java` mtime 15:49，早于 codex 进程 15:51 启动），codex 写入严格限于本 plan 目录；脚本 stray 告警系对既有未提交改动的正常提示。

复核发现两处需修正/存疑，据此修订如下：

1. **构造器参数数量口误（已修正认知，不改方案方向）**：§4.3、§6.2、§11 表述的 `AuthService` “5 参改为 9 参”不准确——实际为 **5 参 → 8 参**（`userStore, sessionStore, passwordHasher, issuer, throttle, roleService, registrationRules, props`），且**当前工作树只有这一个 8 参构造，并无任何兼容旧构造**。因此 §7.3 / §11 中“恢复旧 5 参构造”应理解为**本期新增**一个 `@Deprecated` 的 direct-only 兼容构造（仅供既有测试源码不被机械改写），而非“恢复被删掉的东西”。`AuthServiceTest`（+16/-2）与 `AuthControllerTest`（+5/-1）确因构造变化被机械修改，加回兼容构造后应还原其源码。

2. **`dev-key-acme` 增权回退 = 需用户拍板的决策点（非无条件执行）**：edge `application.yml` 的 diff 证实本分支**有意**把 `dev-key-acme` 从基线 `[chat, ingest, approve, agent, channel, eval, vision, voice]` 扩为追加 `role-admin, public-ingest`（且带解释性注释）。§6.1 / §7.5 / §14 主张回退以守“API-key 老路零改动”硬约束——该主张在“RBAC 不给既有 key 静默增权”的安全立场上成立；但若这是为**演示控制台用 api-key 直接跑 `/auth/admin/**` 与公共库写入**而刻意保留的开发便利，则回退会移除该便利。**本方案默认按 codex 建议回退**，但在实施阶段 3 执行此项前需用户确认；若用户选择保留，则相应放宽“API-key 零改动”验收项并在文档注明例外。
