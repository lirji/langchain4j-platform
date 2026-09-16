# Capability Gaps — langchain4j-platform（Java 半）

> 统一格式：Capability / Current / Target / Gap / Evidence / Impact / Confidence。
> 口径：langchain4j-platform 与 `agentscope-platform` 是**同一产品的两半**，Agent 编排已迁出，因此缺口分析以「跨语言边界是否闭合」为主线。

## Capability Gaps

| # | Capability | Current | Target | Gap | Impact | Confidence |
|---|---|---|---|---|---|---|
| G1 | 跨语言 boundary 契约校验 | Python 单向持有 17 个 `contracts/boundaries/*.schema.json`；Java 仅 1 个 schema 资源、0 个 schema 测试 | Java 消费侧对语言中立 schema 有校验 + CI 兼容门禁 | 契约漂移只能在运行时暴露 | 跨语言调用静默不兼容 | FACT |
| G2 | 租户级 token/成本计量 | Java `ChatModelListener` 只覆盖 Java 进程模型调用；Python 只有 per-run `agent_max_tokens` 与离线 eval 成本 | 无论 Java 还是 Python 发起，租户日配额/成本统一收口 | `/agent/**` 默认走 Python → 绕过 Redis 日计数与预算 | 配额/成本失控、账单不可解释 | FACT |
| G3 | Java Agent 退役门禁（Java 侧机器检查） | 门禁 6 条中，条件 4（存量任务排空）与条件 5（只依赖 live discovery）在 Java 侧无实现 | 可机器判定「无 Java-worker-only 存量任务」「无静态 fallback 目录」 | 退役只能靠人工判断，126 个 Java 文件长期悬挂 | 双 runtime 并存期延长、回滚语义模糊 | FACT |
| G4 | Knowledge `query` 角色的 graph 检索 | `KnowledgeRuntimeBoundaryConfig` 强制 query 角色关闭 graph | graph 命中携带 `documentId/documentVersion` provenance，可在拆分角色下开启 | GraphRAG 能力在拆分拓扑中丢失 | 拆分后检索质量低于 `combined` | FACT |
| G5 | Conversation runtime 决策所需证据 | shadow 只覆盖非流式 `/chat`（门禁文档自述为「明确缺口」） | `/chat/stream` 有独立 candidate 事件面 + 多轮 shadow 质量基线 | 决策门禁无法推进，永久 HOLD | 无法判定 conversation 是否值得拆 | FACT |
| G6 | 统一 Metrics / 告警 / SLO | `micrometer-registry-prometheus` 仅 `async-task-service`；局部自定义指标 | 关键 SLO（Outbox backlog、Kafka lag、限流命中、模型失败率/成本）统一暴露与告警 | 无统一指标面 | 事故发现靠人工 | FACT |
| G7 | 跨服务 CI 均一化 | 6 个 workflow vs 16+ 服务 | 每服务标准化 build+test 必需检查 | 多数服务无 CI | 改共享库影响面不可控 | FACT |
| G8 | 跨语言回滚演练证据 | runbook 已写（Compose/Helm 整服务切换） | 有一次真实演练记录 + 任务一致性 smoke | 演练未执行 | 回滚时才发现问题 | FACT |

## Current Risks

- **R1 配额绕过（高）**：`/agent/**` 默认路由到 Python 后，租户可通过 Agent 路径消耗模型资源而不计入 Java 侧租户预算/成本计数（G2）。这是能力迁出引入的**回归**，不是新功能需求。
- **R2 契约漂移（中高）**：`agentscope-cutover-ci.yml` 只做 Java 模块测试 + Compose/Helm 静态断言，没有任何步骤校验 Java 消费方与 Python `contracts/boundaries/` 的兼容性（G1）。
- **R3 双 runtime 悬挂（中）**：`agent-service` 126 个 Java 文件、镜像定义与 Compose profile 长期保留，退役门禁缺 Java 侧机器检查（G3）。
- **R4 拆分拓扑能力降级（中）**：Knowledge `query` 角色不能开 graph（G4），若按 split 拓扑上生产，检索能力低于当前 `combined`。
- **R5 观测盲区（中）**：跨语言链路 traceId 已通，但没有统一指标面把 Java↔Python 的失败率/延迟/成本放在一起看（G6）。

## Missing Capabilities（ABSENT）

- 跨语言契约兼容门禁（CI 步骤 + Java 侧 schema 校验资源）。
- 租户级跨语言配额/成本收口点（当前既不在 Java listener，也不在 LiteLLM 层被证实）。
- `async-task-service` 存量任务排空 / worker 归属盘点接口。
- 统一 Prometheus 抓取面与告警规则。
- 报表 / BI / CDC / 数仓（**无业务驱动，属于 Not Recommended Now**）。
- 通用只读缓存（**无热点证据，属于 Not Recommended Now**）。

## Partial Capabilities（PARTIAL）

- `interop-service` capability discovery：已消费 Python 版本化 registry，但 `InteropToolRegistry` 仍带静态 fallback 目录（`PING` + 4 个 `platform.agent.*` 描述符）。
- 幂等：只在 workflow 域有专用 store；channel 回调 / async webhook / interop push 语义不统一。
- 限流：有 registry 与 Redis 实现，接入不统一。
- 灰度：只有 Agent 域 canary filter，无跨服务统一开关面。
- ReBAC 授权：实现完整但 `RAG_AUTHZ_MODE` 默认 disabled。

## Scaling Gaps（Current / 3x / 10x）

不预测具体数字，只列架构变化趋势：

- **Current**：单栈本地/单集群，瓶颈是可观测性与配额，不是吞吐。
- **3x（租户数或 Agent 调用量）**：G2 先爆——没有跨语言配额，单租户可挤占共享 LiteLLM 与下游 Java 只读工具容量；`interop`/`async-task` 的 Redis/DB 租约争用需要指标才能定位。
- **10x（文档量 / ingestion 量）**：Knowledge split 拓扑成为必需（ingest-worker 独立扩缩容），此时 G4 必须先关闭，否则拆分即降级。
- **10x（并发 Agent 异步任务）**：`async-task-service` 的 lease/epoch 已具备语义，但缺 backlog/lag 指标与排空能力（G3/G6），扩容时无法判断是否安全。

## Engineering Gaps

- CI：无 per-service 模板化 gate（G7）；无跨语言契约步骤（G1）；无跨仓联动（两仓 CI 各自独立，PR 无法在一次门禁里证明边界闭合）。
- 打包：`mvn -DskipTests package` 在 `platform-eventbus` testCompile 阶段失败（agentscope 仓 `CODEX_PROGRESS.md` 记录，需在本仓复验）→ 文档化命令与真实可用命令不一致。Confidence: NEEDS_VERIFICATION（本轮未运行构建）。
- 灰度/回滚：只有 Agent 域，无统一开关面（G8 关联）。
- 本轮未运行 `mvn test`，所有测试结论均为 UNVERIFIED。
