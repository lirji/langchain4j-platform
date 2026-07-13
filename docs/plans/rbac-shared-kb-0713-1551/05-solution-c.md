# 05 — architecture-designer：方案 C（角色进入会话 JWT，edge 展开并缓存）

## 1. 核心思路

auth-service 签发的会话 JWT 携带 tenant/user/roles/directScopes；edge-gateway 在每次外部请求时从本地缓存或 auth-service 拉取角色定义，展开为 scopes，再签现有内部 JWT。下游仍只看到 scopes，API-key 路径仍直配 scopes。

与 A/B 的真正区别是：权限物化从“登录/刷新时”推迟到“每次经过 edge 时”，可缩短角色变更生效时间。

## 2. 模块职责

- auth-service：用户/角色权威库、角色查询/版本接口、会话 JWT 带 roles/directScopes。
- edge-gateway：新增 RBAC resolver、缓存、失效策略、故障降级；内部 JWT 结构仍不变。
- platform-security/下游：不变。
- eventbus（可选）：角色变更通知 edge 失效缓存。

## 3. 核心流程

1. 登录 JWT 中写 `roles`、`directScopes` 和 `rbacVersion`。
2. edge 验签后按角色名/版本查询本地 cache；miss 时调用 auth-service 内部接口。
3. edge 计算 scopes，签内部 JWT。
4. 角色更新后通过短缓存 TTL或事件失效；下一请求获得新 scopes。

## 4. 改动范围

- 修改会话 JWT claim 契约和 SessionBearerAuthFilter。
- edge 引入 auth client/cache/超时/熔断；多副本缓存一致性需处理。
- auth-service 需内部角色解析接口或快照接口。
- 新增更多集成/故障测试。
- API-key 分支必须继续绕过该解析器。

## 5. 扩展性与实施成本

- 角色变更可较快生效，且 edge 可集中做缓存。
- edge 和 auth-service 形成运行时依赖；auth 短暂不可用可能影响所有 Bearer 业务请求。
- 角色缓存维度和版本管理复杂；角色删除、用户角色变更与旧 session claims 的关系仍需额外 user-version 校验。
- 实施成本高于 B。

## 6. 风险评审

- 兼容性：内部 JWT兼容，但会话 JWT claim 变化；现有 `InternalToken` 只能表达 scopes，需新 token codec 或滥用现有类。
- 事务：auth DB 可关系化；edge cache 和 DB 无分布式事务，事件可能丢失。
- 并发/幂等：缓存 stampede、多 edge 副本失效时序需要保护。
- 性能：每个 request 多一次 cache lookup，miss 多一次网络调用；auth 成为数据面依赖。
- 安全：fail-open 会越权，fail-closed 会在 auth 故障时大面积不可用。
- 灰度/回滚：需要双 claim/双解析阶段，较难保证旧 edge 与新 auth 混跑。

## 7. 测试重点

- 新旧 session token 双读、cache hit/miss、角色版本升级、事件丢失、auth 超时、fail-closed。
- API-key 请求绝不触发 RBAC resolver。
- 多 edge 副本角色撤销时效。

## 8. 适用判断

适合必须把撤权时延从 60 分钟降到秒/分钟级、且愿意让 edge 承担策略缓存的平台。本任务明确强调利用现有 scopes 链路和低破坏兼容，当前仓库也没有 edge→auth 内部客户端/缓存基础，因此不推荐本期采用。

