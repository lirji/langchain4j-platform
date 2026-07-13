# 01 — requirements-analyst：RBAC 需求与验收边界

## 1. 分析口径

本文件只分析和规划，不代表代码已实现。结论来自以下仓库事实：

- 当前分支为 `feat/rbac-shared-kb`，基线提交为 `7d12d62`。
- 已阅读旧规划 `docs/plans/rbac-shared-kb/FINAL_PLAN.md` 与进度记录 `IMPLEMENTATION_PROGRESS.md`。
- 已阅读当前工作树中 auth-service 的全部生产代码和测试、edge-gateway 会话/API-key 过滤链、platform-security 的 JWT/TenantContext 实现，以及 knowledge-service 公共库相关未提交改动。
- 文中“现有”指当前工作树真实存在；“计划新增/计划修改”指后续开发 Agent 应实施的内容。
- 公共知识库是同分支上的并行工作。本次主目标是 RBAC；公共库功能本身的补完不纳入本计划，但其保留租户 `__public__` 和已在 `DocumentController` 落地的 `public-ingest` 写权限必须与 RBAC 兼容。

## 2. 核心业务需求

### 2.1 必须实现

1. 在 auth-service 中引入全局 Role：一个角色聚合零到多个 scope；一个用户绑定一个或多个角色。
2. 有效权限计算规则固定为：

   `effectiveScopes = user.directScopes ∪ scopes(user.roles)`

   其中当前 `UserAccount.scopes` 即 direct scopes，必须保留，不能迁移后清空，也不能改变既有账号的含义。
3. 登录和刷新签发会话访问 JWT 时计算有效 scopes；JWT 继续只携带 `sub`、`uid`、`scopes` 等现有 claim，不把角色传播到下游。
4. edge-gateway 继续把会话 JWT 中的 tenant/user/scopes 原样换发进短时内部 JWT；下游继续由 `InternalTokenAuthFilter` 还原 `TenantContext`，业务控制器继续调用 `hasScope`。不要求下游理解 Role。
5. 提供角色增删改查、用户增删改查、用户角色全量替换绑定、用户 direct scopes 管理接口。
6. 提供自助注册与 `RegistrationRuleEngine`：按用户名中的邮箱域匹配配置规则，决定 tenant 与初始角色；未命中时使用默认 tenant/role。
7. 内存和 JDBC 两套存储均可运行，由现有 `app.auth.store=in-memory|jdbc` 和 `@ConditionalOnProperty` 选择；默认内存模式不增加外部依赖。
8. JDBC 继续使用裸 `JdbcTemplate`、`CREATE TABLE IF NOT EXISTS` 和受控的 `ALTER TABLE ADD COLUMN` 演进，不引入 JPA/Flyway/Liquibase。
9. api-key 认证路径、配置目录、JWT claim 形状和既有测试保持兼容；不得为了方便测试给现有 api-key 追加 `role-admin` 或其它新权限。
10. 旧 USERS 行只有 direct scopes、没有角色时仍可登录并获得原权限。
11. `/auth/register` 成为 edge open path 后必须在 auth-service 内按客户端 IP 独立限流；不同 username 不能绕过。

### 2.2 安全与一致性需求

- 所有 `/auth/admin/**` 操作要求 `TenantContext.current().hasScope("role-admin")`；scope 来源可以是角色，也可以是 break-glass direct scope。
- 管理接口绝不返回 `passwordHash`、刷新令牌或会话令牌哈希。
- 注册和创建用户必须使用原子“仅当不存在才创建”，不能采用“先查再 delete+insert/upsert”；同名并发请求只能有一个成功。
- 注册、管理员创建用户和改密使用同一密码策略；不把密码策略散落为多个不同的硬编码长度。
- 角色写入前校验角色名/scope 格式；用户角色绑定、注册规则中的角色必须已存在。
- 未知的历史角色在登录展开时按 fail-closed 处理：不授予任何 scope，并记录告警；不得因单个脏角色扩大权限。
- 删除已被用户引用的角色返回 409，不做隐式级联解绑。
- 用户禁用/删除后刷新立即失败；已签发访问 JWT 的权限最多保留到现有 `SESSION_ACCESS_TTL` 到期。这一时效性限制必须显式监控和文档化。
- 用户/角色集合在领域对象和 DTO 边界做防御性复制，避免调用方修改 store 内部状态。

## 3. 向后兼容不变量

| 不变量 | 验证方式 |
|---|---|
| `X-Api-Key` 路径仍由 `ApiKeyToInternalTokenFilter` 按 `platform.security.api-keys` 直配 scopes 签内部 JWT | 既有 gateway 测试保持不改；新增断言 scopes 精确相等 |
| 会话 JWT 和内部 JWT 都仍使用 `sub=tenantId`、`uid=userId`、`scopes=list` | `SessionTokenIssuerTest`、`SessionBearerAuthFilterTest`、`InternalTokenTest` |
| `LoginResponse`、`UserView` 结构不破坏；其中 scopes 改为有效 scopes 是预期增强 | 现有 controller/service 测试加角色场景 |
| 下游 `TenantContext.Tenant` 和 `hasScope` 不改签名 | platform-security 零生产代码修改 |
| 没有角色的老用户仍使用 direct scopes | 内存与 H2 升级测试 |
| `app.auth.store` 未设置时仍只装配内存 User/Role/Session store | Spring context 条件装配测试 |
| `app.auth.rbac.enabled=false` 时签发结果回到 direct scopes，管理 API 不生效 | 灰度/回滚测试 |
| 基线测试源码不因构造器破坏而被迫改写 | 为 `AuthService` 保留旧 5 参构造兼容入口；RBAC 新断言放新增/扩展测试，不删除或放宽旧断言 |

## 4. 已确认的业务规则

以下规则由任务描述或当前代码/早期实现直接确认：

1. Role 定义是 auth-service 内的全局字典，不按 tenant 复制。`Role.java` 当前注释也明确“角色与租户正交”。
2. tenant 决定数据边界，scope 决定可执行动作；RBAC 不替代租户隔离。
3. 用户可拥有多个角色；direct scopes 始终参加并集，作为兼容和应急兜底。
4. 角色在签发时展开，因此角色或 scope 修改不会追溯修改已经签出的 JWT。
5. 刷新路径会按 `RefreshSession.username` 回查最新 `UserAccount`，所以刷新可以获得最新角色展开结果。
6. 自助注册默认关闭。当前 `AuthProperties.Registration.enabled` 默认即为 `false`。
7. 注册规则当前只能从 username 推断邮箱域，因为 `UserAccount` 和登录/注册请求中没有独立 email 字段。
8. `__public__` 是当前工作树公共知识库的保留 tenantId，注册和管理员建户均不得分配该 tenant。
9. `role-admin` 是本期新增的管理权限。`public-ingest` 已在同分支 `SeedRoles`、`PublicKb`、`DocumentController` 及测试中出现，RBAC 角色可承载它，但公共库剩余工作不属于本次 RBAC 主线。

## 5. 为形成可执行方案而采用的默认决策

这些不是仓库已有事实，而是本方案为消除歧义给出的安全默认；开发前若产品方明确反对，应只调整对应规则和测试，不重做架构。

1. 角色名统一 trim + 小写；scope 统一 trim，使用小写短标识。API 拒绝空值、逗号和不符合 `^[a-z][a-z0-9:-]{0,63}$` 的值。
2. 角色 scopes 允许为空，便于“先建角色后配置”；但注册用户至少要获得一个存在的角色。
3. `PUT /auth/admin/users/{username}/roles` 采用全量替换语义，天然幂等；空集合仅允许用户仍有 direct scopes，避免创建完全无权限的误配置账号。
4. 角色被引用时 DELETE 返回 409；先解绑再删。
5. 管理员不能通过一次变更使“启用且有效 scopes 含 `role-admin`”的用户数降为 0，返回 409，避免平台永久锁死。
6. 自助注册成功后沿用登录接口行为：返回 `LoginResponse` 并设置刷新 cookie；重复 username 返回 409。
7. 规则按配置顺序 first-match；未命中使用当前代码默认 `defaultTenant=public`、`defaultRole=viewer`。
8. 管理 API 是平台级管理，不限制为当前 tenant 内管理；tenant-admin/组织级委派是后续能力。
9. 不对 scope 建可写 CRUD。scope 是下游约定的不可变字符串权限标识；角色 CRUD 只组合 scope。
10. 删除接口采用幂等语义：目标不存在仍返回 204；查询/更新不存在返回 404。
11. username 沿用现有两套 store 的大小写不敏感语义：新建时 trim + 小写，长度 1–128；已有记录读取时不做破坏性批量改名。role name 同样 trim + 小写，格式 `^[a-z][a-z0-9_-]{0,63}$`。
12. RBAC/registration 两个 flag 必须同时开启才允许自助注册，避免在 direct-only 模式创建 role-only 无权限账号。
13. 双写兼容期，序列化后的 `USERS.SCOPES`、`USERS.ROLES` 和 `ROLES.SCOPES` 各自不得超过现有 `VARCHAR(1024)`；服务层预检后返回 400，不把数据库截断异常暴露为半成品写入。

## 6. 歧义与易遗漏点

| 项目 | 当前事实 | 本计划处理 | 标记 |
|---|---|---|---|
| `public` 默认租户是否真实存在 | 只在 `AuthProperties` 默认值出现，无租户目录 | 保留现值；部署前由业务确认其数据边界 | 待验证 |
| username 是否保证为 email | 无独立 email 字段，普通 username 也可注册 | 只有包含 `@` 的 username 才走域规则 | 已约束 |
| 角色是否 tenant-scoped | 当前 `Role` 明确全局 | 本期全局；租户级自定义角色非目标 | 已约束 |
| 管理员能否跨 tenant 管理 | 无组织/tenant admin 模型 | `role-admin` 为平台级 | 已约束 |
| scope 的权威目录 | 仓库中主要由 yml 和 `hasScope` 字面量约定 | 不新增中央目录；只做格式校验和文档清单 | 已约束 |
| 删除角色的级联语义 | 早期 `RoleStore.delete` 直接删 | 改为有引用 409 | 已约束 |
| 权限撤销即时性 | 访问 JWT 默认 60 分钟，无 token version/introspection | 令牌到期前可能仍有效；灰度期可缩短 TTL | 已知限制 |
| 旧库谁获得首个 admin | 旧 USERS 行没有 roles | 显式 bootstrap-admin-users 配置，demo 默认 alice | 待部署确认 |
| 公共知识库是否同批交付 | 旧计划是联合交付，新任务只要求完整 RBAC | RBAC 只兼容 `public-ingest`/`__public__`；公共库补完另行验收 | 范围决定 |
| Helm 是否已部署 auth-service | `deploy/helm/platform/values.yaml` 当前 services 列表没有 auth-service，config 也没有 AUTH_URI/session/auth DB 项 | 作为现有部署缺口纳入 RBAC 上线阶段 | 已发现问题 |

## 7. 非目标

- 不把角色写入内部 JWT，也不修改 `TenantContext.Tenant`。
- 不让下游服务查询 auth-service，不引入集中 PDP/OPA。
- 不改变现有 api-key 对应 tenant/user/scopes，也不把服务凭证改造成用户角色。
- 不实现 tenant-scoped role、角色继承、条件权限、资源级 ACL/ABAC。
- 不实现角色管理前端；本期交付 HTTP API、测试和接口文档。
- 不新增 Kafka RBAC 事件；本期没有消息协议变更。
- 不引入数据库迁移框架。
- 不在本任务中补完公共知识库查询/写入/删除/GraphRAG 行为。

## 8. 验收标准

### 8.1 功能验收

- 管理员可创建、查询、修改、删除未引用角色；非 `role-admin` 调用所有 admin API 均为 403。
- 管理员可创建/查询/修改/禁用/删除用户，并幂等替换其角色和 direct scopes。
- 用户有多个角色时，登录响应、会话 JWT、网关换发的内部 JWT、下游 TenantContext 中均出现角色 scopes 与 direct scopes 的去重并集。
- 角色变更后旧访问令牌不变；刷新或重新登录得到新 scopes。
- 未绑定角色的历史用户登录权限与改造前一致。
- 注册关闭时 `/auth/register` 返回 403；开启后默认规则、邮箱域规则、重复注册、未知角色、保留 tenant 均按设计响应。

### 8.2 存储与迁移验收

- 内存模式和 JDBC 模式接口行为一致。
- 从基线 USERS 表升级、从当前早期 `USERS.ROLES`/`ROLES.SCOPES` CSV 状态升级都不丢 direct scopes 或角色。
- 多实例同时初始化不会因 COUNT-then-seed 竞争导致启动失败。
- 并发注册同名用户不会覆盖密码/tenant/roles；只能一个成功。
- JDBC 角色绑定和角色 scope 替换要么完整提交，要么完整回滚。

### 8.3 兼容与回归验收

- edge-gateway api-key 相关生产配置无 diff，既有 API-key 测试原样通过。
- platform-security 生产代码无改动，JWT claim 契约测试通过。
- `mvn -pl auth-service,edge-gateway,platform-security -am test` 通过；全仓 `mvn test` 通过。
- 同分支 knowledge-service 的现有测试也通过，证明 RBAC 规划没有覆盖公共库/ES 未提交实现。
