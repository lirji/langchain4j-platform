# 04 — architecture-designer：方案 B（auth 本地关系化 RBAC，签发时物化 scopes）

## 1. 核心思路

保留当前早期实现的领域类、服务层和签发接缝，但把 JDBC 内部的用户-角色、角色-scope 改为关系表：

- `USER_ROLE(USERNAME,ROLE_NAME)`
- `ROLE_SCOPE(ROLE_NAME,SCOPE)`

当前 `USERS.ROLES` 与 `ROLES.SCOPES` CSV 不立即删除：用于从早期实现迁移和一个版本窗口内的代码回滚；新实现以关系表为权威并同步写 legacy CSV。有效 scopes 仍只在 auth-service 登录/刷新时展开；JWT、edge、下游均不变。

## 2. 为什么不是推倒重来

以下现有成果全部保留：`Role`、`UserAccount.roles`、`RoleStore`/`UserAccountStore` 抽象、内存/JDBC双实现、`SeedRoles`、`RegistrationRuleEngine`、`RoleService`、`AdminService`、`SessionTokenIssuer` 重载、`AuthService.issueFor` 接缝。变化主要封装在 JDBC store/初始化器和管理用例中。

## 3. 模块职责

- `JdbcRbacSchemaInitializer`（计划新增）：一次性负责 USERS/ROLES 兼容列、关系表、迁移标记和 seed，解决当前两个 store 各自建表和多副本竞态。
- `JdbcUserAccountStore`：用户行与 USER_ROLE 的聚合读取、原子创建、角色全量替换。
- `JdbcRoleStore`：ROLES 与 ROLE_SCOPE 的聚合读取、原子 create/update/delete、引用检测。
- 内存 stores：使用 ConcurrentMap，但复合变更由同步临界区保证与 JDBC 相同语义。
- `RoleService`：唯一权限物化器；RBAC flag 关闭时返回 direct scopes。
- `AdminService`：业务规则、最后管理员保护、DTO 前的安全输出。
- `AuthController/AdminController`：HTTP 与 scope 关口。
- edge/downstream：保持现有职责。

## 4. 核心流程

### 4.1 登录/刷新

1. UserStore 一次读取 USERS，加一次 USER_ROLE 查询得到 UserAccount。
2. RoleService 批量展开角色（RoleStore `findByNames`），未知角色不授予权限并告警。
3. direct scopes 与角色 scopes 去重并集。
4. AuthService 把有效 scopes 交给 SessionTokenIssuer。
5. edge 只复制 scopes 到内部 JWT；下游照旧 `hasScope`。

### 4.2 注册

1. 检查 rbac.enabled、registration.enabled、注册专用 IP throttle 和输入格式。
2. RegistrationRuleEngine 决定 tenant/roles。
3. 校验 tenant 非 `__public__`、所有角色存在、角色集非空。
4. UserStore 在一个事务中 INSERT USERS + INSERT USER_ROLE + 同步 USERS.ROLES；唯一键冲突映射 409。
5. 同一事务内沿用 issueFor 创建 AUTH_SESSION；会话创建失败时用户也回滚，成功提交后才返回 token/cookie。

### 4.3 角色更新与删除

- POST create：插入 ROLES/ROLE_SCOPE；同名 409。
- PUT update：锁定角色行，删除旧 ROLE_SCOPE 后批量插入新集合，并同步 ROLES.SCOPES；事务失败整体回滚。
- DELETE：锁定角色，查询 USER_ROLE；有引用 409，无引用则删除 ROLE_SCOPE/ROLES。关系约束作为第二道保护。

### 4.4 用户角色替换

锁定 USERS 行；按稳定顺序锁定目标 ROLES；验证后删除该用户 USER_ROLE，再插入目标集合并更新 USERS.ROLES。并发请求串行化，最终为一个完整集合，采用明确的 last-writer-wins。

## 5. 数据库设计

### 5.1 保留表/列

- USERS 原字段全部保留；早期 `ROLES VARCHAR(1024)` 保留为兼容影子。
- ROLES 的 `SCOPES VARCHAR(1024)` 保留为兼容影子。
- AUTH_SESSION 保持不变，可新增 store 方法按 USERNAME revoke/cleanup。

### 5.2 新表

```text
ROLE_SCOPE(
  ROLE_NAME VARCHAR(128) NOT NULL,
  SCOPE VARCHAR(128) NOT NULL,
  CREATED_AT BIGINT NOT NULL,
  PRIMARY KEY(ROLE_NAME,SCOPE)
)

USER_ROLE(
  USERNAME VARCHAR(128) NOT NULL,
  ROLE_NAME VARCHAR(128) NOT NULL,
  CREATED_AT BIGINT NOT NULL,
  PRIMARY KEY(USERNAME,ROLE_NAME),
  INDEX IDX_USER_ROLE_ROLE(ROLE_NAME)
)

AUTH_SCHEMA_STATE(
  STATE_KEY VARCHAR(128) PRIMARY KEY,
  STATE_VALUE VARCHAR(64) NOT NULL,
  UPDATED_AT BIGINT NOT NULL
)
```

最终实现时使用当前项目大写表/列风格。外键是否启用需以 MySQL/H2 兼容测试为准；最终推荐 USER_ROLE→USERS CASCADE、USER_ROLE→ROLES RESTRICT、ROLE_SCOPE→ROLES CASCADE。若 H2 方言阻塞，保留事务级检查并把“外键待验证”记录在实现 PR。

### 5.3 迁移

1. Expand：CREATE 新表、ADD legacy 列（如缺失），不改读路径。
2. Backfill：受 `AUTH_SCHEMA_STATE.rbac-relations-v1` 锁和标记保护，把 ROLES.SCOPES、USERS.ROLES CSV 去重写入关系表；引用未知角色只告警不导入。
3. Switch：标记 COMPLETE 后关系表权威读取；新写同时更新关系表和 legacy CSV。
4. Contract：至少稳定一个版本后再考虑停止 legacy 双写；本期不删列。

## 6. 配置与接口

- `app.auth.rbac.enabled`：默认 false，灰度开启。
- `app.auth.rbac.admin-writes-enabled`：默认 false，允许先验证登录和管理 GET，再开启控制面写。
- `app.auth.rbac.bootstrap-admin-users`：demo 默认 alice，生产显式配置。
- `app.auth.seed.enabled`：保留 demo seed，生产可关闭。
- `app.auth.registration.*`：沿用当前 Properties。
- 新 admin API 全部要求 role-admin；LoginResponse/JWT 形状不改。

## 7. 扩展性与实施成本

- 实施成本：中等，主要是初始化迁移、store contract 和事务测试。
- 登录可批量查角色/scopes；按角色反查用户有索引。
- 后续加角色审计、分页、批量绑定、tenant-scoped role 都有正常演进路径。
- 仍不具备即时撤销；这是刻意维持无状态下游的代价。

## 8. 风险评审

### 兼容性

JWT/API-key/下游完全兼容。数据库比 A 复杂，但 legacy CSV 双写能兼容当前早期实现并支持短期代码回滚。

### 事务、并发与幂等

关系替换可在数据库事务内原子提交；唯一键保证注册和绑定去重。多副本 DDL/seed 由 per-row insert + DuplicateKey 精确处理和 schema-state 锁保护。

### 性能

比 CSV 多 join/query，但角色集合很小；通过 batch query 和 `IDX_USER_ROLE_ROLE` 可控。不要在 RoleService 每个 role 发一条查询。

### 安全

权限撤销仍受 access TTL 限制。关系完整性显著好于 A；bootstrap admin 配置是上线时最敏感的操作项。

### 灰度与回滚

RBAC flag 可关闭权限展开；DB 只做加法。真正创建 role-only 用户之后，回滚到 main 会使其只剩 direct scopes，因此灰度应先覆盖已有用户、稳定后再开放注册/管理写入；必要时回滚前把有效 scopes 临时物化到 USERS.SCOPES。

## 9. 适用判断

方案 B 最符合“完整、可运行时管理、内存/JDBC 双实现、下游零改动”和架构级数据一致性要求。缺点是迁移与双写复杂，并且不能即时撤销，但这些弱点可明确测试和运维控制。
