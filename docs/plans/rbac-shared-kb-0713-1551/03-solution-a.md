# 03 — architecture-designer：方案 A（延续当前 CSV 聚合存储）

## 1. 核心思路

保留当前未提交实现的数据库形态：`USERS.ROLES` 用 CSV 表示用户角色，`ROLES.SCOPES` 用 CSV 表示角色 scopes。原位扩充已有 AdminController/注册接口，补齐校验、事务和测试；登录/刷新时由 `RoleService` 展开并签入 scopes。edge 的现有 register open path 保留，下游不改。

这是对早期方案最直接的续作，复用率最高。

## 2. 模块职责

- auth-service：角色字典、用户角色 CSV、有效 scopes 计算、注册和管理 API。
- edge-gateway：保留当前已有的 `/auth/register` open path，会话 JWT 到内部 JWT 的逻辑不变；撤销当前非兼容的 API-key 增权。
- platform-security/下游：完全不变。
- JDBC：继续使用当前 ROLES 表和 USERS.ROLES 列；每次更新整列。

## 3. 核心流程

### 登录/刷新

`USERS.ROLES CSV → JdbcUserAccountStore.parseScopes → UserAccount.roles → RoleService逐个查ROLES.SCOPES → union direct scopes → SessionTokenIssuer → edge换发 → TenantContext`

### 用户角色替换

AdminService 校验所有角色存在后，单条 `UPDATE USERS SET ROLES=? WHERE USERNAME=?`；内存实现用 map.compute。

### 角色更新

不再 delete+insert，改为“存在则 UPDATE，不存在则 INSERT”，并让 POST/PUT 分离。删除前在应用层扫描所有 UserAccount.roles，发现引用则 409。

## 4. 数据库演进

- 沿用当前 `ROLES(NAME,SCOPES,DESCRIPTION,CREATED_AT)`。
- 沿用 `ALTER TABLE USERS ADD COLUMN ROLES VARCHAR(1024)`。
- 不新增关系表，也不增加非必要列。
- 修正 `addColumnIfMissing`：只忽略数据库明确的 duplicate-column 错误，其他 DataAccessException 继续抛出。

## 5. 改动范围

主要集中于 auth-service；扩充现有 Controller/DTO并新增缺失测试，保留 edge open path，调整配置和部署。当前 Role/User/Store 类几乎全部保留。

## 6. 扩展性与实施成本

- 实施成本：最低，约 2–3 个开发阶段。
- 查询角色：小规模用户/角色足够。
- 扩展性：CSV 长度受 1024 限制；无法高效查询“哪些用户拥有某角色”；未来 tenant role、审计、批量授权会遇到瓶颈。
- 多实例：单行更新本身原子，但“检查引用后删角色”跨行无数据库约束，存在竞态。

## 7. 风险评审

### 兼容性

最好。与当前未提交 DDL 完全一致；关闭 RBAC 后 USERS.SCOPES 仍可工作。

### 事务、并发与幂等

- 注册必须新增 `createIfAbsent`，JDBC 直接 INSERT 捕获 DuplicateKeyException，内存 putIfAbsent。
- 角色 create/update 可做到幂等，但角色引用检查与删除之间无法用 CSV 做可移植行级约束。
- 用户角色全量替换是一条 UPDATE，结果不会半写；并发时 last-writer-wins。
- CSV 归一化和排序必须确定，否则相同集合可能生成不同字符串。

### 性能

登录 N 个角色执行 N 次角色查询；可一次 `findAll`/batch query 或小缓存优化。用户列表和角色引用检查会全表扫描。

### 安全

最大的隐藏风险是角色删除/绑定竞态：一个事务刚验证角色存在，另一个请求删除角色，最终产生 dangling role。登录会 fail-closed，但管理数据不一致。

### 迁移、灰度、回滚

迁移最简单；关闭 flag 或回滚到 main 都会忽略 ROLES 列。新建的 role-only 用户在回滚后只剩空 direct scopes，这是所有 mint-time RBAC 方案共有的数据回退问题。

## 8. 适用判断

如果明确角色数、用户数都很小，且角色删除极少，方案 A 是快速交付的合理最小实现。但任务要求“完整 RBAC”并强调事务/并发/数据迁移，本方案在引用完整性和查询能力上存在结构性弱点，不宜作为最终长期方案。
