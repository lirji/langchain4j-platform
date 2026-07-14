# RBAC 权限体系 + 公共/共享知识库

本页说明两个正交的能力：**RBAC**（能做什么，scopes）与**公共知识库**（跨租户可读的通用文档）。二者与既有的**租户硬隔离**（能看到哪份数据）配合使用。

## 心智模型：三层正交

| 维度 | 决定 | 载体 |
|---|---|---|
| 认证 | 你是谁 | api-key 或登录会话 Bearer（网关换发内部 JWT） |
| RBAC（授权） | 你能做哪些操作 | 角色 → scopes，登录签发令牌那一刻展开进 JWT |
| 租户隔离 | 你能看到哪份数据 | tenantId（Qdrant 分集合 + ES term + DocumentMirror 分区） |

> 关键：给用户加 `admin` 角色**不会**让他看到别的租户的数据——那是租户隔离。要让通用文档跨租户可见，用**公共库**。

> **继承式 RBAC（Google Cloud IAM 式作用域绑定）**：授权层支持三层继承——`有效 scopes = 个人直配 ∪ 个人角色 ∪ 租户基础角色 ∪ 用户组角色`。角色**恒为全局能力包**（绝不做 per-tenant 角色，这是防"角色爆炸"的根）；把角色**绑定在层级节点**（租户 / 组 / 个人），权限沿层级向下继承。一条租户绑定即覆盖该租户全体成员、几十条组绑定即替代数千条个人分配——5000 人规模的痛在管理侧而非运行时（scope 仍只在登录展开一次）。详见下文「继承式 RBAC」。租户维度只承载"基础权限"这一层，仍**不**决定看哪份数据（那是租户隔离）。

## RBAC

### 角色与 scope

- 角色 = 一个命名的 scope 集合（`SeedRoles`）：`viewer=[chat]`、`editor=[chat,ingest]`、`analyst=[chat,analytics]`、`approver=[chat,approve]`、`admin=[全部 + role-admin + public-ingest]`。
- 用户可挂多个角色（`UserAccount.roles`）+ 直配 scopes（direct scopes，兜底）。**有效 scopes = 个人角色展开 ∪ 直配 ∪ 租户基础角色展开 ∪ 用户组角色展开**（`EffectivePermissionResolver`；后两层受 `AUTH_RBAC_INHERITANCE_ENABLED` 灰度，关时退回 `直配 ∪ 个人角色`，与加继承前逐字节一致）。
- 合成只发生在签发令牌那一刻（`AuthService.issueFor` → `EffectivePermissionResolver` → `SessionTokenIssuer`），签进会话 JWT。**下游服务零改动、JWT 形状不变**——仍只 `TenantContext.hasScope(...)`，对来源（个人/租户/组）无感知。
- 新增两个平台 scope：`role-admin`（管账号/角色）、`public-ingest`（写公共库）。
- ⚠️ 改角色/角色 scope 后需**重新登录/刷新**才在新 JWT 生效（已签发的 scopes 不回溯，会话默认 60min）；**降权**（禁用/有效 scopes 收缩/删号/角色 scope 缩减）会撤销受影响用户的 refresh session，尽快切断续期。

### RBAC 开关与灰度（默认关，安全上线）

- `AUTH_RBAC_ENABLED`（默认 **false**）：关时登录**只用直配 scopes**（不展开角色），行为回到加 RBAC 前，现有用户 direct scopes 保底。Compose demo 显式开为 true；生产分阶段：先 expand 部署建表/迁移 → 开 `AUTH_RBAC_ENABLED` 只读验证 → 再开 `AUTH_RBAC_ADMIN_WRITES_ENABLED`（管理写；关时写端点返 **503**）→ 最后才开注册。
- `AUTH_RBAC_BOOTSTRAP_ADMIN_USERS`：迁移/种子时确保拥有 admin 角色的用户名单（生产必须显式配真实名单）。`AUTH_SEED_ENABLED=false` 时空库不灌 demo 账号（生产首个用户走受控 SQL）。

### 存储（关系化）

- `AUTH_STORE=in-memory`（默认，种子角色/账号，重启回种子态）或 `jdbc`。
- JDBC 下角色以**关系表为权威**：`USER_ROLE`（用户→角色，供正确的按角色反查）、`ROLE_SCOPE`（角色→scope）；旧 `USERS.ROLES` / `ROLES.SCOPES` CSV 列保留一个版本作**影子双写**（便于回滚/兼容读）。首启把 CSV 幂等回填进关系表，支持 main 基线库 / 早期 CSV 库 / 空库三种无损升级。`CREATE TABLE IF NOT EXISTS` 幂等演进，无 Flyway，无外键（引用完整性由服务层保证）。复合写（用户+角色+refresh）经 `RbacMutationExecutor` 原子执行（JDBC 事务 / 内存全局锁）。
- 继承层新增表（同样惰性建、无外键、VERSION 乐观锁）：`TENANT_POLICY`（租户→版本）+ `TENANT_ROLE`（租户→基础角色，租户仍是隐式字符串、非一等实体，故只做策略覆盖不建 TENANTS 表）；`AUTH_GROUP`（用 `AUTH_GROUP` 而非保留字 `GROUP`）+ `GROUP_ROLE`（组→角色）；`USER_GROUP`（用户↔组成员）。无策略/无组 == 与"未引入继承"逐字节等价。

### 继承式 RBAC（租户基础角色 + 用户组）

在"个人角色 ∪ 直配"两级之上加两层向下继承的**作用域绑定**（`AUTH_RBAC_INHERITANCE_ENABLED=true` 才折进 JWT）：

- **租户基础角色**：给某租户绑一组全局角色，该租户**全体成员**继承。一条绑定覆盖成千上万人（管理侧不爆炸的关键）。角色仍全局、与租户正交——租户只是"给谁发"的作用域节点，不决定看哪份数据。
- **用户组**：命名的成员容器 + 一组全局角色绑定；成员经组继承这些角色。组**全局、不嵌套**（`user→group→role→scope` 固定三层 DAG，天然无环）。用几十条组绑定替代数千条个人分配。
- **合成点唯一**：`EffectivePermissionResolver.resolve(user)` 批量展开四层并集（登录一次算完，无 N+1），并产出逐 scope 归因（`direct` / `role:x` / `tenant:x` / `group:g:x`）供 `GET /auth/admin/users/{u}/effective-permissions` 与前端"有效权限来源"展示。
- **护栏全覆盖新降权来源**：「最后一个启用的 role-admin」保护改为经 resolver 前瞻评估——含**仅经租户/组获得** role-admin 的用户（改角色 scope / 改租户绑定 / 改组角色 / 移出组 / 删组均预检 → 409）。降权撤销亦扩展：租户基础/组角色收缩、移出组、删组会撤销受影响用户的 refresh session。删角色的引用检查并查用户 / 用户组 / 租户三处（任一引用 → 409 不级联）。
- **为什么不上 Zanzibar/ReBAC**：那是"按单对象共享的超大规模 ACL"（谁能看*这一篇*文档），与本平台 11 个粗粒度 scope、控制器级校验、JWT 烘焙、下游零查库不是同一问题；上它等于推翻这套改成每次校验外部查一次 ACL。除非未来要做"把某知识库精确共享给某几个用户"这类**按资源**授权，才值得考虑。
- **设计边界**：**角色恒全局**——per-tenant 角色、角色/组嵌套仍是非目标（这才是防爆炸的根）；本期新增的是"角色绑定被作用域化 + 向下继承"，不是角色继承。

管理示例（承接上文 `$TOKEN`；写端点同受 `role-admin` + admin-writes 开关 + If-Match 乐观锁）：

```bash
# 给租户 globex 绑基础角色（首次绑定：租户 version 为 -1，用 If-Match: -1）
curl -X PUT $GW/auth/admin/tenants/globex/roles -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'If-Match: -1' -d '{"roles":["viewer"]}'
# 建用户组并绑角色（201）→ 加成员（If-Match 组 version）→ 也可从用户侧 PUT 组
curl -X POST $GW/auth/admin/groups -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"eng","description":"工程组","roles":["editor"]}'
curl -X PUT $GW/auth/admin/groups/eng/members -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'If-Match: 0' -d '{"members":["bob"]}'
# 查某用户有效权限的分层归因（每条 scope 从哪来）
curl "$GW/auth/admin/users/bob/effective-permissions" -H "Authorization: Bearer $TOKEN"
```

### 三条分配路径

前置：**管理面走"登录会话 Bearer"**（api-key 老路不含 `role-admin`，回退基线后不参与角色管理）。先登录持 admin 角色的账号拿令牌：

```bash
GW=http://localhost:8080   # 边缘网关（compose）
TOKEN=$(curl -s -X POST $GW/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"demo12345"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
```

1. **admin 手动指派** —— `/auth/admin/**`（受 `role-admin`，写端点再受 admin-writes 开关）：
   ```bash
   # 建用户（201）：可带 roles + directScopes
   curl -X POST $GW/auth/admin/users -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"username":"dave","password":"secret12","tenant":"globex","roles":["editor"]}'
   # 全量替换角色（幂等 PUT，200）
   curl -X PUT $GW/auth/admin/users/dave/roles -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' -d '{"roles":["viewer"]}'
   # 局部改资料（PATCH：null 字段不改，directScopes:[] 清空；不含 roles）
   curl -X PATCH $GW/auth/admin/users/dave -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' -d '{"enabled":false}'
   # 分页列用户（X-Total-Count 返总数）/ 单查 / 删（204 幂等）
   curl -i "$GW/auth/admin/users?offset=0&limit=50" -H "Authorization: Bearer $TOKEN"
   curl -X DELETE $GW/auth/admin/users/dave -H "Authorization: Bearer $TOKEN"
   # 角色 CRUD：POST 201 / PUT 全量替换 / DELETE 204（被引用返 409，不级联）
   curl -X POST $GW/auth/admin/roles -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' -d '{"name":"support","scopes":["chat"],"description":"客服"}'
   ```
   护栏：不能移除**最后一个启用的 role-admin**（改角色/禁用/删号/改角色 scope 均预检 → 409）；删被引用的角色 → 409；写未知角色 → 400。
2. **自助注册默认角色** —— `POST /auth/register`（**须同时开 `AUTH_RBAC_ENABLED` 与 `AUTH_REGISTRATION_ENABLED`**，否则会建出无有效 scopes 的死账号）：新用户拿 `default-role`（默认 `viewer`）。按 IP 节流（成功/失败都计数）。
3. **按规则自动映射** —— `app.auth.registration.rules` 按邮箱域映射租户+角色（默认**不内置**示例规则，需在部署专用配置里加）：
   ```yaml
   app.auth.registration.rules:
     - email-domain: acme.com   # neo@acme.com 注册 → 租户 acme + editor 角色
       tenant: acme
       roles: [editor]
   ```

### 端点小结

| 端点 | 方法 | 鉴权 | 说明 |
|---|---|---|---|
| `/auth/register` | POST | open | 自助注册（须 rbac+registration 双开），成功即登录 |
| `/auth/admin/users` | GET | `role-admin` | 分页列用户（`X-Total-Count`） |
| `/auth/admin/users/{u}` | GET/PATCH/DELETE | `role-admin`(+writes) | 单查 / 局部改 / 删（204 幂等） |
| `/auth/admin/users` | POST | `role-admin`(+writes) | 建户（201，可带 directScopes） |
| `/auth/admin/users/{u}/roles` | PUT | `role-admin`(+writes) | 幂等全量替换角色 |
| `/auth/admin/roles` | GET/POST | `role-admin`(+writes) | 列 / 建（201，重复 409） |
| `/auth/admin/roles/{n}` | GET/PUT/DELETE | `role-admin`(+writes) | 单查 / 全量替换 / 删（204，被用户/组/租户引用 409） |
| `/auth/admin/users/{u}/groups` | PUT | `role-admin`(+writes) | 幂等全量替换用户所属组 |
| `/auth/admin/users/{u}/effective-permissions` | GET | `role-admin` | 有效权限的分层归因（direct/role/tenant/group） |
| `/auth/admin/tenants` | GET | `role-admin` | 列租户（实际用到 ∪ 已配策略） |
| `/auth/admin/tenants/{t}` | GET | `role-admin` | 单查租户基础角色（无绑定 version=-1） |
| `/auth/admin/tenants/{t}/roles` | PUT/DELETE | `role-admin`(+writes) | 全量替换 / 清空租户基础角色（首绑 If-Match: -1） |
| `/auth/admin/groups` | GET/POST | `role-admin`(+writes) | 列 / 建组（201，重名 409） |
| `/auth/admin/groups/{n}` | GET/PUT/DELETE | `role-admin`(+writes) | 单查 / 全量替换 / 删（有成员 409 `group_in_use`） |
| `/auth/admin/groups/{n}/members` | GET/PUT | `role-admin`(+writes) | 列 / 全量替换组成员 |

> `role-admin`(+writes)：GET 只需 `role-admin`；写端点在 `AUTH_RBAC_ADMIN_WRITES_ENABLED=false` 时返 503（灰度）。整套 admin API 仅在 `AUTH_RBAC_ENABLED=true` 时装配。租户/组绑定是否折进签发的 JWT 由 `AUTH_RBAC_INHERITANCE_ENABLED` 再控（可先配好绑定、核对归因，再翻此开关生效）。

### API-key 不参与角色

RBAC **不给既有 api-key 静默增权**：`dev-key-acme` 保持基线 scopes（`chat/ingest/approve/agent/channel/eval/vision/voice`，不含 `role-admin`/`public-ingest`）。管理面与公共库写统一走"登录会话 → 角色展开"。api-key 老路完整保留（双模）。

### 回滚限制

- 灰度步骤 2–4、尚未产生 role-only 用户时：关 `AUTH_RBAC_ENABLED` 即回 direct scopes，新表无害保留。
- 已产生 role-only 用户后不能直接回滚而宣称权限不变：需先导出各用户 current effective scopes 临时物化进 `USERS.SCOPES` 再关 flag。不 drop 新表/列。
- 继承层同理：关 `AUTH_RBAC_INHERITANCE_ENABLED` 即刻退回两级 token 数学、`last_admin` 守卫也同步退两级，`TENANT_*`/`AUTH_GROUP`/`GROUP_ROLE`/`USER_GROUP` 表无害保留；若已产生"仅经租户/组获权"的用户，先经归因接口物化其 effective scopes 再关，不 drop 新表。
- 撤权最长延迟一个 access TTL（无状态 JWT，默认 60min；灰度期可降到 5–15min）。

## 公共/共享知识库

一个所有租户可读的**保留租户分区** `__public__`（`RAG_PUBLIC_TENANT_ID`）。默认关闭（`RAG_PUBLIC_ENABLED=false`）——关闭时行为与引入前完全一致。

- **写**：`POST /rag/documents` 带 `visibility=public`，需 `public-ingest` scope；文档落 `__public__` 分区（四个 sink 均按此 tenantId 派生）。
- **读**：开启后，任意租户查询在隔离查自己分区的基础上，**并入** `__public__` 分区命中（向量/keyword/ES 三路都并；图谱本期不并）。隔离不破——公共是独立 tenantId，普通用户读不到别的真实租户。
- 保留 id `__public__` 禁止被真实租户/注册用户占用（入库与建户路径校验）。

### 灌公共库 + 验证

```bash
# 1) 把示例文档（含退款政策）灌进公共库（需 public-ingest 的 key）
bash deploy/seed-kb.sh --public

# 2) knowledge-service 开启公共读：RAG_PUBLIC_ENABLED=true 重启
# 3) 任意租户（如 globex/bob）查询即可命中退款政策：
curl -X POST http://127.0.0.1:18080/rag/query -H "X-Api-Key: dev-key-globex" \
  -H 'Content-Type: application/json' -d '{"query":"退款政策怎么退","topK":5}'
```

这正好解决"bob(globex) 查不到只灌在 acme 的退款政策"——把通用文档放公共库，全租户可见，私有数据仍互不可见。

## 相关配置

| 配置 | 默认 | 说明 |
|---|---|---|
| `AUTH_STORE` | in-memory | 账号/角色存储；jdbc 落 MySQL（关系表 USER_ROLE/ROLE_SCOPE） |
| `AUTH_RBAC_ENABLED` | false | RBAC 总开关；关时登录只用直配 scopes（不展开角色） |
| `AUTH_RBAC_ADMIN_WRITES_ENABLED` | false | admin 写端点二级开关；关时写返 503（先只读灰度） |
| `AUTH_RBAC_INHERITANCE_ENABLED` | false | 继承第三段灰度；开后把租户基础角色 + 用户组角色并入有效 scopes（关时退两级、逐字节等价） |
| `AUTH_RBAC_BOOTSTRAP_ADMIN_USERS` | （空，demo=alice） | 确保拥有 admin 角色的用户名单 |
| `AUTH_SEED_ENABLED` | true（生产建议 false） | 空库是否自动灌 demo 账号/角色 |
| `AUTH_PASSWORD_MIN_LENGTH` / `_MAX_LENGTH` | 6 / 128 | 密码策略（注册/建户/改密共用） |
| `AUTH_TRUST_FORWARDED_FOR` | false | 是否信任 X-Forwarded-For 取客户端 IP（仅 edge 已覆盖 XFF 时开） |
| `AUTH_REGISTRATION_ENABLED` | false | 自助注册开关（须与 rbac 同开） |
| `AUTH_REGISTRATION_DEFAULT_TENANT` / `_ROLE` | public / viewer | 未命中规则时的默认 |
| `AUTH_REGISTRATION_MAX_ATTEMPTS` / `_WINDOW` | 10 / PT10M | 按 IP 的注册节流 |
| `RAG_PUBLIC_ENABLED` | false | 公共库读取开关 |
| `RAG_PUBLIC_TENANT_ID` | `__public__` | 公共库保留租户 id |
