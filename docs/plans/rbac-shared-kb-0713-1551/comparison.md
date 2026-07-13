# 候选方案比较 — risk-reviewer + plan-judge

## 1. 统一评分规则

每项 1–5 分，5 分最佳。对“复杂度、测试难度、回滚成本”，5 分表示更简单/更容易/成本更低。七项等权，总分只用于暴露权衡，不替代硬性约束判断。

| 维度 | 5 分含义 |
|---|---|
| 正确性 | 完整满足角色、用户绑定、direct scopes、运行时管理、传播和数据一致性 |
| 改动风险 | 爆炸半径小，与当前代码和未提交实现同构 |
| 复杂度 | 组件少、运行链短、故障模式少 |
| 可维护性 | 数据约束清晰、职责单一、易定位问题 |
| 扩展性 | 支持反查、批量管理、未来角色能力演进 |
| 测试难度 | 可用单元/H2/现有 gateway 测试确定性覆盖 |
| 回滚成本 | flag/加法 schema 可回退，混跑简单 |

## 2. 评分表

| 方案 | 正确性 | 改动风险 | 复杂度 | 可维护性 | 扩展性 | 测试难度 | 回滚成本 | 总分/35 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A CSV 聚合 + mint-time scopes | 3 | 5 | 5 | 3 | 2 | 4 | 5 | 27 |
| B 关系化 auth RBAC + mint-time scopes | 5 | 4 | 3 | 5 | 5 | 4 | 3 | **29** |
| C roles 进会话 JWT、edge 展开缓存 | 4 | 2 | 2 | 3 | 4 | 2 | 2 | 19 |
| D 集中 PDP、下游每请求决策 | 5 | 1 | 1 | 2 | 5 | 1 | 1 | 16 |

## 3. 硬性约束符合性

| 约束 | A | B | C | D |
|---|---|---|---|---|
| 下游继续只按 scope 鉴权 | 满足 | 满足 | 满足 | 不满足 |
| 会话→内部 JWT scopes 可传播 | 满足 | 满足 | 满足，但 session claim 改变 | 身份为主，偏离 |
| API-key 老路零改动 | 满足 | 满足 | 可满足但分支更复杂 | 需双授权，风险高 |
| 内存/JDBC双实现 | 满足 | 满足 | auth 仍需双实现，edge 另加 cache | 需更多实现 |
| 裸 JdbcTemplate + 自建表演进 | 满足 | 满足 | 满足 | 可满足但规模过大 |
| 纳入当前早期实现而非重做 | 最好 | 良好 | 较差 | 最差 |

方案 D 即使正确性理论上高，也因违反硬约束直接淘汰。方案 C 没有违反下游约束，但改变会话 token 和 edge 数据面依赖，不适合本期。

## 4. risk-reviewer：逐维失败场景

### 4.1 兼容性

**A**：数据库最兼容；但若给现有 api-key 追加新 scope，会破坏“零改动”，因此禁止照搬旧计划中给 dev-key 增权的建议。

**B**：JWT/HTTP旧契约兼容；新关系表是加法。最大兼容风险是当前早期 CSV 与关系表双权威，必须通过 schema-state 明确切换并同事务双写，不能靠“关系表为空就 fallback”猜测，因为“空角色集合”本身是合法状态。

**C**：旧 edge 读不懂 roles/directScopes claim，新 auth 与旧 edge 混跑可能丢权限；需要双 claim，复杂。

**D**：每个下游都要迁移，兼容面不可接受。

### 4.2 事务与原子性

**A**：单用户 roles CSV 更新原子；角色引用检查/删除无法数据库约束，存在 TOCTOU。不能彻底修复。

**B**：用户创建、USER_ROLE 写入、角色 scope 替换可在本地数据库事务完成。初始化 DDL 可能隐式提交，必须把“建表”和“回填数据事务”分开。当前 `JdbcUserAccountStore/JdbcRoleStore` 的 delete+insert 必须删除。

**C/D**：DB 事务之外还有缓存/网络状态，必须接受最终一致性。

### 4.3 并发与幂等

- 所有方案都必须把注册改为数据库唯一键/内存 putIfAbsent；现有 find-then-save 会覆盖用户。
- A/B 的 `PUT roles` 使用全量替换并声明 last-writer-wins；并发结果可以是任一完整集合，不允许混合集合。
- B 在角色删除和用户绑定时按固定顺序锁定 role/user，避免死锁；数据库唯一键去重。
- 多实例启动不能继续 `COUNT(*)==0` 再 seed；必须逐行 insert、只吞 DuplicateKeyException。
- C/D 还会遇到缓存击穿、乱序失效事件和版本回退。

### 4.4 性能

- A 登录按 N 角色查询可能 N+1；角色引用查询会扫描全 USERS CSV。
- B 可按 `WHERE ROLE_NAME IN (...)` 批量查 ROLE_SCOPE，USER_ROLE 有反向索引。一次登录通常 2–3 个本地 DB 查询，远低于 edge/PDP 每请求成本。
- C cache hit 尚可，miss 会把 auth-service 带入所有请求关键路径。
- D 的每操作决策最昂贵，且服务间多跳会放大。

### 4.5 安全

- 四案都需防止管理 DTO 泄露 passwordHash。
- A 的 dangling role 会 fail-closed，但管理数据可能腐化。
- B 可做引用约束、最后管理员保护和 role-only 用户迁移检查，安全性最好。
- A/B 的共同弱点：旧 access JWT 在角色撤销后仍有效到 TTL。缩短灰度期 SESSION_ACCESS_TTL、撤销 refresh sessions 只能限制延续，不能撤回已经签出的无状态 JWT。
- C/D 的 fail-open 不可接受；fail-closed 会引入平台级可用性风险。
- 注册域规则不能信任请求传入 tenant；当前实现由服务端配置决定，这是正确方向。

### 4.6 数据迁移

**A**：只加列/表，简单；长期受 CSV 限制。

**B**：需要兼容两种来源：main 基线（无 roles 列）和当前早期实现（CSV）。迁移必须：

1. 先 expand schema；
2. 用迁移状态行串行 backfill；
3. 对未知角色告警，不授予 scope；
4. 标 COMPLETE 后切权威读；
5. 一个版本窗口双写 legacy CSV；
6. 本期绝不 drop 列/表。

**C/D**：除 DB 还需 token/cache/策略迁移，明显更难。

### 4.7 灰度与回滚

- A/B 都可用 `app.auth.rbac.enabled=false` 回到 direct scopes。
- 先让现有用户启用 RBAC：他们的 direct scopes 仍在，即使展开失败也不降权。
- 在 RBAC 稳定前不要开放 registration/admin 创建 role-only 用户；否则回滚到 main 时这些用户无 direct permissions。
- 新写入开始后如必须代码回滚，先导出 `effectiveScopes` 并临时物化进 USERS.SCOPES，再回滚；回滚后保留新表。
- C/D 需要 token 双读/服务双跑，回滚成本更高。

## 5. 方案裁决

选择以 B 为骨架，但吸收 A 的三个优点：

1. 保留当前 `UserAccount.roles`/`Role`/Store/Service，不重构领域边界。
2. 保留 `USERS.ROLES` 与 `ROLES.SCOPES` 一个兼容窗口并同事务双写，降低从早期实现回滚的风险。
3. 继续只在 AuthService.issueFor 物化 scopes，SessionTokenIssuer/edge/downstream 契约不变。

不吸收 C/D 的 edge/PDP 部分，因为会把控制面变化带入请求数据面。

## 6. 所选方案已知弱点

- 比 A 多两个关系表、一个 schema-state 表和迁移初始化器，首个版本测试量更大。
- 兼容期双写有漂移风险，必须保证同事务并用一致性测试/启动检查发现差异。
- 无状态 JWT 导致撤权最多延迟一个 access TTL；本期不能宣称“即时撤权”。
- 全局 role-admin 是平台级高权限，尚无 tenant admin 委派。
- scope 仍是字符串约定，没有中央权限目录；拼写错误靠格式校验、测试和未知 scope 监控发现。
- 若 H2/MySQL 对外键/DDL 方言表现不同，关系约束的具体 DDL需在实现时验证；不能为了测试绿色吞掉所有 SQL 异常。

