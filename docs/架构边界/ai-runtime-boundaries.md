# AI Runtime 与领域服务边界

## 决策

AgentScope/Python 负责推理、计划、工具选择、多 Agent 编排和轨迹；Java 服务继续拥有数据、
事务、安全和副作用。该方向于 2026-07-30 获得批准。

## 所有权

| 组件 | 保留职责 | 可迁出的纯 AI 能力 |
| --- | --- | --- |
| knowledge | 原文、版本、索引、检索、ReBAC | query expansion/rerank 可通过模型适配器演进 |
| analytics | schema allowlist、SQL guard、只读凭据和执行 | NL→SQL planner |
| workflow | Flowable、人工任务、事务、outbox | 工单抽取和回复生成 |
| order | 订单数据和租户隔离 | 无 |
| async-task | task、lease、SSE journal、cancel、webhook | 无 |
| auth/edge | 登录、令牌交换、角色和限流 | 无 |
| channel | 渠道验签、凭据和投递 | 文案生成可调用外部 runtime |
| interop | A2A/MCP/Agent Card 协议 | Agent capability 来自 AgentScope live discovery |
| eval | Java HTTP/检索 harness（仅离线按需） | Agent shadow schema/runner 的权威实现 |

## 禁止依赖

- Java 领域服务不得把业务数据库凭据或 repository 暴露给 AgentScope。
- AgentScope 不得持久化 Flowable、SQL、订单、Knowledge 索引、登录会话或任务权威状态。
- 跨语言协议使用 OpenAPI/JSON Schema，不传 LangChain4j 或 AgentScope 框架对象。
- 模型产生的 tenant/user/scope/department 不可信。
- 未通过 shadow、契约、安全和回滚门禁前，不删除旧实现或修改生产默认路由。

## 跨语言契约门禁

「跨语言协议使用 JSON Schema」不再只是约定，已有机器门禁。契约权威始终在
`agentscope-platform/contracts/`，本仓只保存消费侧副本：

- 上游 `scripts/export_contracts.py` 生成 `contracts/manifest.json`，逐个 JSON 契约固定
  sha256（含手写的 boundary schema），其 CI 的 `--check` 会拦住未重新导出的改动。
- 本仓 vendored 副本位于 `platform-protocol/src/main/resources/contracts/agentscope/`，只包含
  Java 真正消费或生产的契约，附带同格式的 digest manifest。
- `deploy/sync-agent-contracts.sh` 校验「副本 == 上游」并可 `--write` 重新同步；看不到上游
  worktree 时自行 skip（可用 `AGENTSCOPE_REPO` 指定路径）。
- `platform-protocol` 的 `AgentScopeContractManifestTest` 校验「副本 == manifest 固定 digest」，
  `AgentScopeContractConformanceTest` 双向校验 DTO：生产方向要求 Java 序列化结果通过 schema
  校验，消费方向要求 Java 读取的每个字段仍被契约发布（允许 Java 只读子集）。
- `agentscope-cutover-ci.yml` 的 `Verify agentscope contract compatibility` 步骤执行以上检查。

已知残留缺口：两仓 CI 都无法访问对方仓库，所以「本仓副本 == 上游最新」只在本地或跨仓联动时
被证明。上游改契约后，本仓要靠人跑一次同步脚本才会发现。彻底闭合需要把契约作为版本化制品
发布给 Java 构建解析，属于后续变更。

## 默认运行拓扑

- `/agent/**` 与 interop Agent proxy 默认指向 `agentscope-orchestrator`。
- Java `agent-service` 仅保留为显式 `legacy-agent`/Helm enable 回滚目标，不默认部署。
- Java `eval-service` 仅作为 `evaluation` profile 或临时 Job 运行，edge 不发布 `/eval/**`。
- conversation、Knowledge、Workflow、Analytics、SQL、订单、媒体和任务权威状态继续留在各自
  Java 领域服务；不得因 AgentScope 成为默认 Agent runtime 而迁入 Python 新单体。

退役与评测细节分别见 [Java Agent 退役门禁](java-agent-retirement-gate.md) 和
[评测控制面边界](evaluation-control-plane.md)。普通 Chat 的当前 HOLD 结论见
[Conversation Runtime 决策门禁](conversation-runtime-decision-gate.md)。

## Knowledge 拆分前置

Knowledge query 与 ingest 拆进程前，必须先具备：

1. S3-compatible 不可变原文权威存储；
2. 文档版本和 ingestion job 状态机；
3. vector/ES/graph/registry/authz 逐 sink 状态；
4. 幂等写、失败补偿和 reconcile；
5. query 不依赖 ingest 进程的内存 mirror；
6. 跨租户、授权失败和回滚测试。

满足前置后优先采用同仓不同 profile/Deployment，等团队所有权和发布节奏稳定后再决定是否拆
独立 Git 仓库。
