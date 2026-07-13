# 候选方案对比、风险复核与裁决

> 视角：risk-reviewer + plan-judge。评分不是为了证明预设答案；每项均以当前仓库规模、静态 SPA 部署方式和本分支 WIP 状态为基准。

## 1. 统一评分规则

每项 1–5 分，5 分最好：

- 正确性：权限/租户/共享可见性和并发语义是否完整。
- 改动风险：5 表示爆炸半径小、现有链路受影响少。
- 复杂度：5 表示实现与认知复杂度低。
- 可维护性：领域边界、契约稳定性和故障定位能力。
- 扩展性：数据规模、功能增长、组织边界的承载能力。
- 测试难度：5 表示测试面小、环境简单、确定性高。
- 回滚成本：5 表示可快速关闭且不需数据逆迁移。

不使用权重，避免用权重人为放大偏好；总分仅作辅助，安全淘汰项可不受总分影响。

## 2. 评分表

| 方案 | 正确性 | 改动风险 | 复杂度 | 可维护性 | 扩展性 | 测试难度 | 回滚成本 | 总分 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A 现有 SPA 最小增量 | 2 | 5 | 5 | 2 | 2 | 4 | 5 | 25/35 |
| B 同 SPA 领域化 + 契约加固 | 5 | 3 | 3 | 5 | 4 | 3 | 4 | **27/35** |
| C 独立 RBAC 管理 SPA | 5 | 2 | 2 | 4 | 5 | 2 | 4 | 24/35 |
| D Console BFF + 服务端会话 | 5 | 1 | 1 | 4 | 5 | 1 | 2 | 19/35 |

### 评分解释

- A 的高总分来自低复杂度/易回滚，不代表业务正确性足够。静默并发覆盖和共享管理缺口是硬伤。
- B 不是所有维度最高，但在不增加运行时服务的前提下完整解决主要问题。
- C 的长期扩展性最好，但当前会重复前端壳和会话逻辑，跨 SPA refresh 轮转是额外正确性问题。
- D 理论能力最强，但当前仓库没有 BFF，会引入服务端 session/CSRF/Redis 协调；本期收益不足以覆盖风险。

## 3. 多维风险矩阵

| 风险维度 | A | B | C | D |
|---|---|---|---|---|
| 兼容性 | 最好；直接用当前 API，但继承语义歧义 | GET/list加法兼容；未发布mutation首次发布前收敛安全合同 | 后端兼容好；浏览器 cookie 跨站/路径需重验 | 改变请求与会话模型，兼容矩阵最大 |
| 事务 | 不改善 AdminService 多表原子性 | 明确要求 mutation executor 包裹资料/关系/version | 同 B | BFF不能替代底层事务，仍需同 B |
| 并发 | 仅防双击，跨管理员静默覆盖 | VERSION/ETag 冲突检测 | 同 B；另有跨 SPA refresh 并发 | 对象并发可解；多实例 session/refresh 并发更复杂 |
| 幂等 | 主键冲突兜底，网络不明难恢复 | 幂等 PUT + create 后 GET 对账；不自动重发密码 | 同 B | 可加幂等 key，但需要额外存储/清理 |
| 性能 | 全量列表，规模增长差 | 服务端分页、懒加载；需修 findByRole N+1 | bundle隔离好，跨站首跳慢 | 多一跳，可聚合/缓存，但新增延迟与容量点 |
| 安全 | API Key/Bearer 身份混淆风险高 | admin Bearer-only、前端权限仅 UX、public-config 最小披露 | 管理站可收紧 CSP；cookie跨域风险 | 浏览器不见 access token；CSRF和高价值 session store 风险 |
| 数据迁移 | 无，因而无并发保护 | USERS/ROLES 加 VERSION；可加法回滚 | 同 B | 同 B + console session/idempotency 数据生命周期 |
| 灰度 | 静态 flag 简单但需重建 | UI kill switch + 后端只读/写开关 + 分层启用 | 独立发布最灵活 | 可分流，但双会话/双请求栈阶段最复杂 |
| 回滚 | 隐藏路由即可 | 关 UI/写/public flags；VERSION 列可保留 | 下线 admin SPA；还原 cookie/CORS | 需还原会话模型、路由和可能的服务端 session |

## 4. 主动失败推演

### 4.1 权限撤销不即时

所有不改变当前 JWT 模型的方案都存在：角色降权后，旧 access token 仍有效到过期。B/C/D 即使撤销 refresh session，也不能撤销已签 access token。可缓解但无法消除：

- 管理写后撤销目标用户 refresh sessions。
- 对禁用、删除、移除 `role-admin` 等高风险动作缩短 access TTL 或后续引入 token version/denylist（不在本期）。
- 前端收到 403/401 后刷新身份，不坚持本地 scope。
- 管理员修改自身权限后立即退出管理中心并清本地会话。

### 4.2 最后管理员竞争

两个管理员并发删除/禁用对方，单纯“读数量再写”会都通过。必须在同一 `RbacMutationExecutor` 临界区/事务内重查有效 role-admin 数量并写；前端确认框不构成保护。A 未解决，B/C/D 都必须落实后端保护。

### 4.3 角色删除与绑定竞态

删除 role 与为用户分配 role 并发时，无外键的当前 schema 可能产生未知角色关系。需要同一控制面锁或关系完整性约束；删除被引用角色返回 409。前端需刷新绑定计数。B/C/D纳入，A继承风险。

### 4.4 共享知识库部分写

共享文档重传会跨向量、DocumentMirror、ES、graph、registry 多 sink，当前不是事务。任一中途失败可能出现查询源不一致。四个前端方案都不能修复；最终方案要求后端监控/补偿，并在 UI 显示 traceId 和“入库失败，请勿假定已回滚”。

### 4.5 API Key 覆盖身份

当前 `sessionStore.runContext()` 让 API Key 优先于 Bearer。若管理员登录后填入 globex Key，能力请求实际 tenant 是 globex；若 Key 有 role-admin，又可能以服务身份改 RBAC。最终裁决吸收 C 的原则：管理域完全不读取 API Key；能力域显示当前 credential mode，API Key 权限预判降级为 unknown。

### 4.6 注册开关漂移

只用 Vite flag 会在后端关闭注册后仍显示入口，或反之。最终方案采用服务端 `GET /auth/public-config` 为事实源，Vite flag只作强制关闭 kill switch。接口不可用时 fail-closed：隐藏注册，登录仍可用。

### 4.7 前端乐观更新误导

RBAC 写成功不等于所有旧 token 已更新；不对权限对象做先展示成功的 optimistic update。列表可在服务端确认后局部更新，权限影响区域显示“新会话生效”。

## 5. 最终裁决

不机械照搬任何单案，合并如下：

- 以 B 的同一 SPA 领域化管理中心、强类型 API、分页、统一If-Match乐观锁和 visibility 契约为骨架。
- 吸收 C 的安全隔离：管理路由懒加载、Bearer-only、独立 store/API、普通用户不下载/不执行管理代码；但暂不拆第二个 SPA。
- 吸收 A 的低风险回滚：`VITE_RBAC_CONSOLE_ENABLED`、`VITE_SHARED_KB_UI_ENABLED` 和后端只读/写/public flags 可快速关闭；旧管理端点暂时兼容。
- 不采用 D 的 BFF/服务端会话；把 API 层和领域边界设计得可在未来平移到 BFF。

## 6. 所选组合方案的弱点

- 同一静态 SPA 仍是一个发布单元；管理端独立发布能力不如 C。
- 后端合同加固跨 auth、knowledge、edge，实施协调与测试工作不小。
- access token 撤权延迟、共享多 sink 非事务和全局 role-admin 权限过大仍存在。
- scope 说明不是中央 registry；前端只能维护已知 scope 说明并保留未知值。
- Vite kill switch 是构建期变量，真正运行时事实仍需后端 config/capability 接口。

这些弱点可接受的前提是：先完成后端正确性门槛，再启用 UI 写功能；不能为了赶页面而退回 A 的静默覆盖语义。
