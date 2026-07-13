# 06 — architecture-designer：方案 D（集中 PDP / 每请求授权决策）

## 1. 核心思路

把 auth-service 扩展为 Policy Decision Point（或接入 OPA）：JWT 只证明身份和 tenant，每个下游受保护操作用 user/resource/action 请求 PDP 决策。角色、scope、资源规则可即时生效。

这与前三案本质不同：scope 不再是下游唯一且自足的授权依据，而是策略输入或兼容缓存。

## 2. 模块职责

- auth/PDP：用户、角色、scope、资源/动作策略、决策 API、缓存版本、审计。
- platform-security：新增 Authorizer/PDP client 和 fail policy。
- 每个下游 controller/service：把现有 `hasScope` 改为 PDP 调用，或加统一拦截注解。
- edge：只负责认证和身份 token。
- eventbus/cache：策略分发或失效。

## 3. 核心流程

1. 用户凭证经 edge 形成身份 JWT。
2. 下游恢复 TenantContext。
3. 业务操作调用 `authorize(subject,tenant,action,resource)`。
4. PDP 展开角色并结合资源策略返回 allow/deny/reason/version。

## 4. 改动范围

涉及 platform-security、auth-service、edge 以及所有需要授权的下游端点。要统一定义 action/resource 命名和不可用策略；现有 scope-only 测试需要大面积重构。

## 5. 扩展性与实施成本

- 可扩展到 ABAC、资源级 ACL、角色继承、即时撤权、集中审计。
- 复杂度和运维成本最高；PDP 是请求数据面关键依赖。
- 必须做高可用、本地缓存、策略版本、降级、熔断和一致性设计。

## 6. 风险评审

- 兼容性：直接违背“下游沿用 scope 鉴权机制不做破坏性改动”的核心约束。
- 事务：策略库可事务化，但决策缓存仍有一致性窗口。
- 并发：策略版本、缓存刷新、批量决策需要专门协议。
- 性能：每个操作增加网络/缓存开销，流式和内部多跳请求尤为敏感。
- 安全：PDP 不可用时 fail-open 不安全、fail-closed 影响全平台。
- 迁移/回滚：必须长期双跑 scope 与 PDP 结果并对比，回滚成本最高。

## 7. 测试重点

- 每个下游 action/resource 映射、PDP 高可用、超时、缓存、策略版本、双跑结果差异。
- 所有现有 scope 端点的回归与性能基准。
- fail-open/fail-closed 的灾难演练。

## 8. 适用判断

当平台明确进入资源级授权、外部合规策略、即时全局撤权阶段时才值得立项。它不是本任务的增量 RBAC 解法，应作为独立架构项目，不应混入当前分支。

