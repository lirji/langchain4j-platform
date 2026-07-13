# test-designer：RBAC 测试方案与可验证验收

## 1. 测试原则

- 先保住现有认证/API-key/JWT 契约，再增加 RBAC 行为。
- 内存和 JDBC 执行同一 store contract；JDBC 使用项目现有 H2 `MODE=MySQL`，另保留真实 MySQL smoke。
- 每个测试清理 `TenantContext`，避免 ThreadLocal 泄漏。
- 不以解析未验签 JWT 字符串替代契约测试；用 `InternalToken.verify` 验证。
- 并发测试只断言安全不变量（唯一成功、集合完整、无覆盖），不假定线程调度顺序。
- 当前分支有 knowledge/ES/公共库未提交改动，最终全仓回归必须覆盖它们，避免 RBAC 实施时误覆盖。

## 2. 候选方案专项测试

| 方案 | 必要专项测试 | 判断 |
|---|---|---|
| A CSV | CSV escaping/排序/长度、全表引用扫描、检查-删除竞态 | 竞态无法由结构彻底消除，是淘汰依据之一 |
| B 关系化 | 基线/早期schema迁移、关系事务、双写一致性、索引反查、并发初始化 | 可由 H2+MySQL smoke确定性覆盖 |
| C edge 展开 | session 新旧 claim、cache stampede、auth timeout、缓存失效、多副本 | 测试和故障矩阵明显扩大 |
| D PDP | 每个下游 action 映射、PDP HA、双跑比对、fail policy、性能 | 超出本期边界 |

后续章节按最终选定的 B 编写。

## 3. 单元测试

### 3.1 Role/User 领域

**文件：扩充现有 `RoleServiceTest`**

- 多角色 scopes 去重并集。
- direct scopes 与角色 scopes 并集。
- null/empty roles、null/empty direct scopes。
- 未知角色不授予 scope并产生可观测告警。
- `rbac.enabled=false` 时只返回 direct scopes。
- 返回 Set 不能修改 store 内 Role/User 状态。
- 角色名大小写归一；scope 格式非法被拒。
- username 新写入 trim+小写、长度边界正确；集合迭代/JWT scopes 顺序稳定且快照不可变。

完成标准：每个分支有断言，尤其不允许未知角色导致登录获得额外权限。

### 3.2 RegistrationRuleEngine

**文件：新增 `RegistrationRuleEngineTest`，并保留/扩充现有 `RegistrationTest` 的服务级场景**

- `alice@acme.com` 命中域规则，tenant/roles 正确。
- 域匹配大小写不敏感，规则顺序 first-match。
- 普通 username、尾部 `@`、多 `@` 等输入按定义处理。
- 未命中使用 `public/viewer` 当前默认。
- rule/default tenant 为空是配置错误；`__public__` 被拒。
- 空角色/未知角色由 AuthService 注册校验拒绝，用户没有落库。

### 3.3 AuthService

**保持基线 `AuthServiceTest` 源码不改，新增 `RbacAuthServiceTest`**

- seed alice 登录的 effective scopes 包含 direct scopes 和 admin 角色 scopes。
- bob/viewer 与旧 direct scopes 行为一致。
- 注册 disabled→403；enabled→成功并直接登录。
- 注册/管理员创建/管理员改密统一验证密码长度边界；既有较短密码 hash 仍可登录，不做破坏性强制迁移。
- 重复 username→409，原用户 password/tenant/roles 未被覆盖。
- 注册规则引用未知角色→配置/业务错误且不建用户。
- 角色修改后：旧 access token scopes 不变；refresh/re-login scopes 更新。
- 注册在 refresh session 创建处注入失败：用户创建一并回滚；重试不会遇到“账号已建但客户端从未成功”的半成品。
- 用户 disabled/deleted 后 refresh→401。
- 旧 6 参 UserAccount 无 roles 仍可登录。

### 3.4 AdminService

**计划新增 `AdminServiceTest`**

- create user：密码 BCrypt、角色/direct scopes/tenant 正确，重复 409。
- update profile：username/userId 不被意外改；可修改 tenant/enabled/password/direct scopes。
- replace roles：幂等；未知角色 400；用户不存在 404。
- role create/update：同名冲突、格式校验、scope 替换而非追加。
- role update 移除 scope：所有绑定用户的 refresh sessions 被撤销；仅新增 scope 时不误撤销。
- delete assigned role→409；解绑后→204；不存在→404。
- 任何操作不能留下 0 个启用的 role-admin 用户。
- `__public__` tenant 拒绝。

## 4. Store contract 与数据库测试

### 4.1 内存/JDBC共同契约

对 `InMemory*Store` 和 `Jdbc*Store` 参数化执行：

- createIfAbsent 同名只成功一次。
- username/role name 大小写查找一致。
- 用户 roles 多对多 round-trip。
- role scopes 替换 round-trip。
- 兼容 CSV 恰好1024字符可写，1025字符在服务层400且关系表/CSV均不变化。
- 删除用户级联清理 USER_ROLE 语义一致。
- 删除被引用角色拒绝。
- findAll/list 顺序稳定，DTO 不泄密。

### 4.2 JDBC schema/migration

**计划新增 `JdbcRbacMigrationTest`**

场景 A：空库。创建 USERS、ROLES、ROLE_SCOPE、USER_ROLE、AUTH_SCHEMA_STATE；seed 一次。

场景 B：main 基线 USERS 表（无 ROLES）且含自定义用户。升级后：

- ROLES 列存在；原 SCOPES 不变；用户仍能登录。
- 不凭用户名猜测普通用户角色。
- 配置的 bootstrap admin 才获得 admin。

场景 C：当前早期 schema（USERS.ROLES/ROLES.SCOPES CSV）。升级后：

- CSV 去空白/去重后写关系表。
- 未知 role 记录告警、不产生 USER_ROLE，state=`NEEDS_REMEDIATION`；rbac.enabled=true 时启动失败。
- 修复缺失 role/清理脏绑定后重跑得到 COMPLETE；第二次启动不重复/复活已删除关系。

场景 D：迁移中途异常。事务回滚，state 不为 COMPLETE；下次启动可重试。

场景 E：DDL 权限/语法错误。启动失败，不得被 `addColumnIfMissing` 静默吞掉。

场景 F：两个 initializer 并发。两者最终完成，seed 无重复，不能有一个因 DuplicateKey 退出。

### 4.3 事务/并发

- 20 个线程同名注册：1 成功、19 冲突；最终 hash 与成功请求一致。
- 并发 replace roles：最终集合等于某一个完整请求，绝非两者混合。
- role update 在批量插 scope 中注入异常：旧 scope 集合完整保留，legacy CSV 也未变化。
- 绑定与删除同一角色并发：最终要么绑定成功且角色存在，要么删除成功且无绑定；不允许 dangling relation。
- refresh token revokeByUsername 对不存在用户/无 session 幂等。

### 4.4 真实 MySQL smoke

H2 不能证明 MySQL DDL error code、外键和 `SELECT ... FOR UPDATE` 完全一致。Compose MySQL 执行：

1. 从基线 USERS DDL 启动 auth-service。
2. 两副本并发启动。
3. 创建/绑定/更新/删除角色。
4. 重启并核对关系/CSV一致。
5. 检查 explain 使用 `IDX_USER_ROLE_ROLE`。

完成标准：无手工修表、无重复 seed、无 silent SQL error。

## 5. Controller/API 测试

### 5.1 AuthController

**保持基线 `AuthControllerTest` 源码不改，新增 `RbacAuthControllerTest`**

- register 请求/响应/cookie 与 login 同约定。
- registration disabled 统一错误体。
- `AuthExceptionHandler` 对 AuthController/AdminController 都生效。
- `/me` 仍只返回 username/tenant/effective scopes。

### 5.2 AdminController

**扩充现有 `AdminControllerTest`**

- anonymous、chat-only、ingest-only 调所有 `/auth/admin/**`→403。
- direct `role-admin` 和角色展开得到的 `role-admin` 均可访问。
- CRUD 状态码：create 201，get/list 200，update/replace 200，delete 204，GET/PUT/PATCH missing 404，DELETE missing 仍 204，conflict 409。
- role-admin 校验先于 writes flag：未授权调用始终403；已授权但管理写关闭时 POST/PUT/PATCH/DELETE 为503且 store 未被调用。
- 响应 JSON 不含 `passwordHash/password/refresh/tokenHash`。
- PUT roles 重放两次结果一致。
- 分页 limit 上限和非法参数 400（若实现分页）。

### 5.3 Edge open path

**保持两个基线 gateway 测试源码不改，新增 `EdgeOpenPathsTest`、`RbacApiKeyCompatibilityTest`、`RbacSessionScopePropagationTest`**

- `/auth/register` 在 SessionBearerAuthFilter 与 ApiKeyToInternalTokenFilter 均直接透传。
- `/auth/admin/**` 不是 open path，无 Bearer/API-key 时仍 401。
- 原 login/refresh/logout open path 测试不改。
- `RegistrationThrottle` 按 IP 计入成功/失败尝试；更换 username 不能绕过；窗口过期恢复。多副本节点本地限制的已知边界需记录。
- `trust-forwarded-for=false` 时伪造 XFF 不改变 key；开启后只在测试模拟“代理已清洗”的前提下取第一项；过期 key 会清理，超过 max-keys 不会无界增长。

## 6. JWT 与跨模块集成测试

1. 基线 `SessionTokenIssuerTest` 原样通过；新增 `RbacSessionTokenIssuerTest` 验证显式 effective scopes 重载，旧重载仍签 direct scopes。
2. 基线 `SessionBearerAuthFilterTest` 原样通过；新增 `RbacSessionScopePropagationTest` 验证会话 token 的完整 scopes 集合在换发后的内部 token 中精确保留。
3. 基线 `ApiKeyToInternalTokenFilterTest` 原样通过；新增 `RbacApiKeyCompatibilityTest` 验证原绑定及配置 scopes 精确不变。
4. 配置回归：`dev-key-acme` 必须从当前分支的额外 `role-admin/public-ingest` 恢复为基线 `[chat, ingest, approve, agent, channel, eval, vision, voice]`，其它 binding 也与基线逐项一致。
5. `InternalTokenTest`/RS256 测试原样通过，证明 claim 结构未变。
6. 端到端：登录→拿 Bearer→gateway→一个现有 `hasScope` 端点。viewer 上传知识文档 403，editor 200；approve 权限同理。
7. 刷新后权限变化：管理员给用户加 editor，旧 token 上传仍 403，新 refresh token 对应 access token 上传成功。

## 7. 条件装配测试

**计划新增 `AuthStoreConditionTest`**

- 无 `app.auth.store`：恰好一个 InMemoryUserAccountStore、InMemoryRoleStore、InMemoryRefreshSessionStore；无 DataSource/JDBC store。
- `app.auth.store=in-memory`：同上。
- `app.auth.store=jdbc` + H2 datasource：恰好一个 JdbcUserAccountStore、JdbcRoleStore、JdbcRefreshSessionStore、JdbcRbacSchemaInitializer 和事务管理器；无内存 store。
- 非法 store 值：应用快速失败，不能在没有 store 时启动到运行期。
- `rbac.enabled=false`：AdminController 不暴露或明确返回不可用；登录仍直配 scopes。
- `rbac.enabled=true` 且 `admin-writes-enabled=false`：admin GET 可用，所有写方法503；开启后写方法才进入业务服务。
- Helm 渲染测试/人工断言：只有 auth-service 与 edge-gateway 注入 SESSION_JWT_SECRET，其它下游没有该变量。

## 8. 回归命令与完成标准

实施 Agent 应按顺序执行：

```bash
mvn -pl auth-service -am test
mvn -pl edge-gateway,platform-security -am test
mvn -pl knowledge-service -am test
mvn test
```

再执行 compose/MySQL smoke。完成标准：

- 全部既有测试通过，不通过修改既有断言来“适配”破坏性变化。
- 新 RBAC 测试全部通过。
- API-key 配置文件无权限增量。
- H2 与 MySQL 的 schema/migration 都通过。
- 并发测试重复运行至少 20 次无 flaky。

## 9. 最终业务验收清单

- [ ] 一个用户绑定 viewer+approver 后，登录有效 scopes 为 chat+approve+direct scopes，且无重复。
- [ ] 修改角色 scopes 后，refresh 获得新权限，旧 access token 仅在 TTL 内保留旧权限。
- [ ] 未授权用户无法读写 admin API。
- [ ] 角色被引用时不能删除。
- [ ] 同名并发注册不覆盖账号。
- [ ] main 基线库和早期 CSV 库均可无损升级。
- [ ] 内存/JDBC行为一致。
- [ ] API-key 路径行为和 scopes 精确不变。
- [ ] `__public__` 不能成为用户 tenant；admin 角色可承载 `public-ingest`。
- [ ] 关闭 RBAC flag 后既有 direct scopes 用户可继续工作。
