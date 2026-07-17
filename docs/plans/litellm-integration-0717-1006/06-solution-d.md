# 06 — 候选方案 D：预算/Key 控制平面与对账闭环

## 1. 核心思想

新增平台控制平面，把租户业务预算作为领域模型，可靠地调 LiteLLM 管理 API创建 user/team/virtual key，并把 key 版本/映射存到平台数据库；后台 reconciliation job 对比 LiteLLM spend 与 `platform-metering`，修复漂移。`TenantAwareChatModel` 只向本地 resolver 取已下发 key。

## 2. 架构与职责

```text
Admin/API -> TenantLlmPolicy (platform DB)
  -> outbox/reconciler -> LiteLLM /user|team|key APIs
  -> Secret store -> application TenantVirtualKeyResolver

Runtime request
  -> TenantAwareChatModel -> virtual key -> LiteLLM hard guard

Reconciliation
  LiteLLM spend + platform-metering snapshot
  -> normalized report / drift alerts
```

- 新控制面：预算策略、key 状态机、重试/outbox、审计、轮换。
- LiteLLM：执行层和 provider spend 权威源。
- platform-metering：业务观测输入。
- gateway-client：纯 runtime adapter。

## 3. 数据模型（仅候选设计，不是仓库现有表）

若采用此方案，需要新领域记录，例如 tenant policy、provisioning state、external key id、version、last reconciled spend。由于任务明确禁止臆造现有表，本文不指定表名/列名；真正采用前必须另做领域设计和迁移评审。

## 4. 事务、并发与幂等

- 平台 DB 写策略与调用 LiteLLM API不能处于一个本地事务，必须 outbox/saga。
- provisioning command 要有稳定 idempotency key；LiteLLM API 是否支持客户端幂等键“待验证”，不支持时需以 alias/external id 查询后合并。
- key rotation 是多阶段状态机：create-new → distribute → observe → revoke-old。
- reconcile job 要分页、限速、可重入，并区分合理差异（cache/retry/fallback）与真实漏记。
- 控制面不可用不能影响已经拿到 key 的运行时请求，但会阻塞新租户/轮换。

## 5. 改动范围

除方案 A 的部署和 wrapper 外，还需要：

- 选定一个 runtime service 承载控制面，或新增服务；
- 平台数据库迁移、实体/仓储/API；
- outbox/eventbus、调度、审计、Secret connector；
- 管理 UI 或至少运维 API；
- 大量集成、恢复和权限测试。

这显著超出本次列出的 `deploy`、LiteLLM config、gateway-client 和 smoke 范围。

## 6. 扩展性

- 最完整地支持大规模租户分层预算、自动 onboarding、轮换和对账。
- 能把 LiteLLM 从手工运营工具变为平台受控执行面。
- 容易扩展到 embedding/image/audio。

## 7. 实施成本与弱点

实施成本：极高。

弱点：

- 新增真正的跨系统分布式一致性问题，而本任务核心能力并不要求自动 provisioning。
- 在没有稳定 LiteLLM 版本/API契约前先建控制面，返工概率很高。
- 数据迁移、灰度、权限、审计和回滚成本远高于其他方案。
- 对小规模初期租户属于过度设计。

## 8. 适用结论

这是规模化后的目标形态，不适合首轮落地。可吸收其“稳定 external id、key 版本、对账指标、轮换状态机”思想进方案 A 的运维约定，但不新增控制面代码和数据库表。
