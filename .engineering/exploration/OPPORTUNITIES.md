# Opportunities — langchain4j-platform（Java 半）

> 每个候选项统一模板：Capability / Current / Problem / Evidence / Why Needed / Proposed / Value / Complexity / Risk / Dependency / Priority / Trigger / Status。
> 分类：MUST_FIX / NATURAL_EVOLUTION / PLATFORMIZATION / EXPLORATION。Status：`DISCOVERED|RECOMMENDED|NOT_RECOMMENDED_NOW|BLOCKED|NEEDS_MORE_EVIDENCE`。
> 只找 WHAT TO BUILD，不实现、不选型定稿。**所有涉及 Python 侧的项都必须由两仓协同，不在 Java 侧重建编排能力。**

## Must Fix

### O1 跨语言 boundary 契约兼容门禁（P0）
- Capability：Cross-language contract compatibility gate。
- Current：Python 单向持有 `contracts/boundaries/` 17 个 schema + `contracts/legacy/` 17 个 legacy schema；Java 侧只有 `platform-protocol/src/main/resources/contracts/knowledge/ingestion-job.schema.json` 一个资源，`rg schema.json --glob '*Test.java'` 0 命中。
- Problem：Java 是这些契约的**消费方**（interop 代理、async-task worker token、conversation generation、analytics SQL plan、workflow AI draft），但没有任何校验或 CI 门禁能在合并前发现漂移。
- Evidence：`agentscope-platform/contracts/boundaries/*.schema.json`；`.github/workflows/agentscope-cutover-ci.yml`（只有 Java 模块测试 + Compose/Helm `grep` 断言，无契约步骤）；`docs/架构边界/ai-runtime-boundaries.md`「跨语言协议使用 OpenAPI/JSON Schema」。
- Why Needed：边界文档已把 schema 定为唯一跨语言约定，但约定没有机器护栏，等于靠人记。
- Proposed：把 Python 导出的语言中立 schema 作为 Java 构建期资源（vendored + 版本/digest 固定），对 Java 消费侧 DTO 做 schema 一致性测试；在 `agentscope-cutover-ci.yml` 增加契约兼容步骤，不兼容即 fail closed。
- Value：业务=跨语言调用不静默失败；工程=边界可机器验证。
- Complexity：MEDIUM。Risk：LOW（新增校验，不改运行时行为）。
- Dependency：需与 agentscope 仓约定 schema 导出与版本发布方式。
- Priority：P0。Trigger：**已满足**（消费方存在且无门禁）。
- Status：**DONE（2026-09-16，两仓联动实现）** —— 上游 `contracts/manifest.json` 固定 40 个契约 digest；本仓 vendored 18 个消费契约 + `deploy/sync-agent-contracts.sh` + `platform-protocol` 13 项双向校验测试 + `agentscope-cutover-ci.yml` 门禁步骤。门禁有效性已用 3 次注入漂移验证。
  - **残留**：两仓 CI 互相访问不到，「副本 == 上游最新」只在本地/跨仓联动时被证明。彻底闭合需把契约作为版本化制品发布给 Java 构建解析 → 记为后续变更，非本项遗留缺陷。详见 `docs/架构边界/ai-runtime-boundaries.md`。

### O2 跨语言租户配额与成本收口（P0）
- Capability：Cross-runtime tenant token budget + cost accounting。
- Current：`TokenBudgetChatModelListener` / `CostChatModelListener` 挂在 langchain4j `ChatModelListener`，从 `TenantContext` 取租户，写 Redis 日计数；Python 只有 per-run `agent_max_tokens=24000`、`agent_planner_max_tokens`，以及仅用于离线评测的 `agent_*_cost_usd_per_million_tokens`，`rg "tenant.*budget|daily"` 在 Python src 0 命中。
- Problem：`/agent/**` 与 interop Agent proxy 默认指向 `agentscope-orchestrator`，Agent 路径的模型消耗**不进入** Java 租户预算与成本计数。这是编排迁出引入的能力回归。
- Evidence：`platform-metering/.../TokenBudgetChatModelListener.java`、`RedisCostTracker.java`、`RedisDailyCounters.java`；`agentscope-platform/src/agentscope_platform/core/config.py:55,75,206-207`；`docs/架构边界/java-agent-retirement-gate.md`（默认路由）。
- Why Needed：多租户 SaaS 的配额与成本是安全/合规能力，不能因运行时换语言而失效。
- Proposed：选定**单一收口点**（候选：LiteLLM 层按租户配额、或双运行时共写同一 Redis 计数契约），并把「租户配额生效」纳入退役门禁的机器检查。选型属下游 `TECH_SELECTION`，本报告不定稿。
- Value：业务=配额/账单可解释；工程=消除跨语言观测与治理盲区。
- Complexity：MEDIUM。Risk：MEDIUM（涉及计费语义与两仓协同）。
- Dependency：O4（统一指标面用于验证收口是否生效）。
- Priority：P0。Trigger：**已满足**（默认路由已切，预算面已失效）。
- Status：RECOMMENDED。

### O3 Java Agent 退役门禁的两个机器检查（P1）
- Capability：Machine-checkable retirement gate（存量任务排空 + 纯 live discovery）。
- Current：门禁条件 4/5 无 Java 侧实现。`async-task-service` 无 drain / worker 归属盘点（`rg -i drain` 该模块 0 命中）；`InteropToolRegistry` 保留静态 fallback 目录（`PING` + `platform.agent.run/run_async/dag.plan_run/dag.plan_run_async`），discovery 失败即回落。
- Problem：退役与否只能人工判断；静态 fallback 会在 Python 不可用时对外宣称 Java 时代的能力目录，与「只依赖 live discovery」的门禁条款冲突。
- Evidence：`docs/架构边界/java-agent-retirement-gate.md`（条件 4、5）；`interop-service/.../InteropToolRegistry.java:29-33,119-121`；`async-task-service/src/main/java`（无 drain）。
- Why Needed：126 个 Java 文件 + 镜像 + Compose profile 长期悬挂是持续维护成本；门禁不可机器判定就永远不会通过。
- Proposed：①`async-task-service` 增加按 worker 归属/kind 的存量任务盘点（只读查询即可满足门禁），②`interop` 把静态 fallback 降级为显式配置项并默认 fail-closed（不静默宣称能力）。
- Value：业务=能力目录不说谎；工程=退役路径可判定。
- Complexity：LOW-MEDIUM。Risk：MEDIUM（改 fallback 语义会影响 Python 不可用时的降级行为，需与门禁 owner 确认）。
- Dependency：无。Priority：P1。Trigger：**已满足**（门禁条款已写死，检查缺失）。
- Status：**DONE（2026-09-16）** —— `InteropToolDispatcher` 只代理 live discovery 宣告过的 agent 工具（未宣告返回明确原因，本地 PING 不受影响）；新增只读 `GET /async/drain-inventory` 按 kind + 状态 + 租约持有者盘点未完结任务（内存 + JDBC SQL 聚合）。门禁条件 4、5 现可由机器回答。

## Natural Evolution

### O4 统一可观测指标面 + 关键告警（P1）
- Capability：Unified Metrics/Alert（Prometheus + SLO），覆盖 Java↔Python 全链路。
- Current：OTel tracing + `X-Trace-Id` 跨语言透传已 MATURE；但 `micrometer-registry-prometheus` 只在 `async-task-service/pom.xml`（1/16+ 服务）。
- Problem：Outbox backlog / Kafka lag / 限流命中 / 模型成本与失败率无统一监控告警；跨语言链路无法在同一面板对齐。
- Evidence：`platform-observability/.../otel/*`、`async-task-service/.../AsyncTaskMetrics.java`、`workflow-service/.../WorkflowOutboxDispatcher.java`、`platform-eventbus/.../KafkaConsumerConfig.java`；`agentscope-platform/.../observability/runtime_metrics.py`（Python 已有低基数指标）。
- Why Needed：门禁文档多处要求「dashboard/alert/on-call」证据，且 O2 的收口效果必须可观测才能验收。
- Proposed：`platform-observability` 统一 Micrometer→Prometheus 暴露 + 关键 SLO + 告警规则，与 Python 低基数指标命名对齐。
- Value：业务=更快发现事故；工程=Reliability 与配额验收的前置。
- Complexity：MEDIUM。Risk：LOW（叠加式，不改业务）。
- Dependency：无（是 O2/O6 的前置）。Priority：P1。Trigger：**已满足**。
- Status：**DONE（2026-09-16）** —— `micrometer-registry-prometheus` 收进 `platform-observability`，16 个 Java 服务 actuator 迁到独立 management 端口（业务口 +1000），AgentScope `/metrics` 免鉴权；新增 `deploy/prometheus/{prometheus,alerts}.yml`（8 条告警，只引用真实注册的序列，不设无验收目标的延迟门限）与 `deploy/test-observability-config.sh` 防漂移门禁。

### O5 Knowledge graph provenance（解锁 query 角色）（P2）
- Capability：Versioned graph provenance（`documentId/documentVersion`）。
- Current：Vector id 与 ES id 已含版本；graph 命中无版本 provenance，因此 `query` 角色强制关闭 graph 检索。历史 graph source 因无 provenance 只能 fail-safe 保留，版本 GC 也无法清理。
- Problem：一旦按 split 拓扑上生产，GraphRAG 能力相对 `combined` 降级；同时旧版本 graph 派生数据无法回收。
- Evidence：`knowledge-service/.../KnowledgeRuntimeBoundaryConfig.java`（"query role must disable graph retrieval until graph hits carry document version provenance"）；`docs/架构边界/knowledge-runtime-split.md`（GC 章节）。
- Why Needed：这是拆分拓扑上生产的前置，不是新功能。
- Proposed：graph sink 写入时固化 `documentId/documentVersion`，query 端按 Registry 当前版本过滤，GC 纳入带 provenance 的 graph 数据。
- Value：业务=拆分后检索不降级；工程=GC 可收敛存储。
- Complexity：MEDIUM。Risk：MEDIUM（涉及 graph 数据迁移与旧数据兼容）。
- Dependency：无。Priority：P2。
- Trigger：**决定让 Knowledge split 拓扑进入生产 canary 时**（当前 edge 默认仍指向 `knowledge-service`）。
- Status：**DONE（2026-09-16）** —— `GraphSourceId` 单点定义 `<docId>/v<version>/` provenance（写入 / GC 前缀删除 / 查询还原共用），图命中带 `docId`/`version` 后进入版本过滤与 enforce 文档级判权；`RAG_GRAPH_REQUIRE_PROVENANCE` 丢弃无归属的历史三元组，query 角色从「禁用图检索」改为「开则必须要求 provenance」。

### O6 Conversation 决策门禁的剩余证据（P2）
- Capability：`/chat/stream` candidate 事件面 + 多轮 shadow 质量基线。
- Current：非流式 `/chat` 异步 shadow 已实现（默认关），内部 stream envelope `{sequence,type,data}` 已定义；但真实独立进程的断连/上游取消/背压/错误映射未测，多轮 candidate 质量未验证。
- Problem：门禁保持 HOLD，无法判断 conversation 是否值得独立 runtime；LangChain4j `TokenStream` 无 cancel API，Java emitter 关闭不能等同上游取消。
- Evidence：`docs/架构边界/conversation-runtime-decision-gate.md`（前置条件 3、4 自述缺口）；`conversation-service` shadow observer 与指标定义。
- Why Needed：把 HOLD 变成有依据的 GO/NO-GO，而不是无限期挂着。
- Proposed：补流式 candidate 事件契约的独立进程测试与多轮 shadow 离线质量报告，产出决策所需数据。**结论可能是「继续不拆」，这本身就是合格产出。**
- Value：业务=避免盲目拆分；工程=决策可追溯。
- Complexity：MEDIUM-HIGH。Risk：LOW（shadow 默认关，不动 primary）。
- Dependency：O4。Priority：P2。Trigger：**有人再次提出拆 conversation，或流式质量/成本出现业务诉求**。
- Status：DISCOVERED。

### O7 跨域统一幂等 / 限流接入范式（P2）
- Capability：Unified idempotency + rate-limit adoption。
- Current：幂等仅 workflow 域（`WorkflowIdempotencyStore`）；限流有 registry 但接入不统一。
- Problem：channel 回调 / async webhook / interop push 的重复副作用与热点保护语义不一致。
- Evidence：`workflow-service/.../WorkflowIdempotencyStore.java`、`platform-security/.../ratelimit/RateLimiterRegistry.java`。
- Proposed：把幂等/限流下沉为统一切面 + 接入约定，各服务按需接入。
- Value：一致的重复/热点语义。Complexity：MEDIUM。Risk：LOW-MEDIUM。
- Dependency：O4。Priority：P2。
- Trigger：**第二个入站回调渠道出现重复副作用**，或限流热点被指标证实。→ **已满足**（钉钉 + 飞书两个入站桥各自复制了一份进程内 map 去重，且是「先标记再处理」= 失败即静默丢消息；免鉴权入口整体不限流）。
- Status：**DONE（2026-09-16）** —— 幂等：新增 `InboundIdempotency`（抢占 → 异步处理 → 失败/拒收归还），两个 bridge 收口到与 Kafka listener 同一套 `ProcessedEventStore`（`releaseClaim` 新增，内存实现改有界窗口）；限流：`EdgeOpenPaths` 改成「路径 → family」表，免鉴权业务入口按客户端 IP 限桶（`auth=30` / `channel-callback=600`），探针不限，默认不信任 `X-Forwarded-For`。

## Platformization Opportunities

### O8 跨仓统一发布证据门禁（P2）
- Capability：Single release evidence gate across both repos。
- Current：Python 侧有 `agent-production-evidence.v1` 门禁（19 项，缺一即 fail closed，默认模板 `decision=NO_GO`）；Java 侧有 4 组静态配置门禁脚本（`deploy/test-*-config.sh`）与 `eval-service` 只读消费 Shadow v4。两者各自独立。
- Problem：一次发布需要人工把两仓证据拼起来判断，容易出现「工程 PASS」被误读成「可以上生产」。
- Evidence：`eval-service/.../AgentScopeShadowReportReader.java`；`deploy/test-production-cutover-config.sh` 等；`docs/架构边界/evaluation-control-plane.md`（Java 可只读消费但不得重定义 schema）。
- Proposed：让 Java 侧门禁脚本产出与 Python evidence schema 对齐的结构化结果，由**单一** evidence 门禁聚合两仓证据。Java 不重新实现 Agent 评测。
- Value：业务=发布决策唯一口径；工程=消除「PASS vs GO」混写。
- Complexity：MEDIUM。Risk：LOW。
- Dependency：O1（先有跨语言 schema 约定）。Priority：P2。
- Trigger：**首次真实生产发布筹备时**。
- Status：DISCOVERED。

### O9 跨服务 CI 均一化 + 新业务线接入脚手架（P2）
- Capability：Per-service CI template + business-line onboarding scaffold。
- Current：6 个 workflow vs 16+ 服务；`tax-service` 作为新业务线自带独立 CI（`tax-ai-ci.yml`），共享能力靠既有 service 复制装配。
- Problem：跨服务回归靠人工；新业务线重复装配 JWT/租户/事件/审计/计量。
- Evidence：`.github/workflows/`（6 yml）；`tax-service`(29 files)；`platform-*` autoconfig。
- Proposed：模板化「build+test（+SBOM 增量）」CI 作为 PR 必需检查，复用 `mvn -pl <svc> -am test` 约定；在 `platform-*` 沉淀新服务模板与接入 Checklist。
- Value：业务=接入更快；工程=回归护栏 + 减少 copy 漂移。
- Complexity：LOW-MEDIUM。Risk：LOW。
- Dependency：无。Priority：P2。
- Trigger：CI 部分**已满足**；脚手架触发条件为**第 3 个业务线接入，或重复装配达到 3 个模块**。
- Status：RECOMMENDED（CI）/ DISCOVERED（脚手架）。

## Exploration Opportunities

### O10 统一灰度/回滚开关面（P3）
- Capability：Unified canary + rollback switch。
- Current：仅 `AgentCanaryRoutingFilter` + `AgentCanaryProperties`（Agent 域）。
- Problem：Knowledge split、conversation shadow、ReBAC enforce 都需要按租户灰度，但各自用独立环境变量，无集中开关面。
- Evidence：`edge-gateway/.../AgentCanaryRoutingFilter.java`；`docs/架构边界/knowledge-runtime-split.md`、`conversation-runtime-decision-gate.md` 各自的 rollout 段。
- Proposed：在 `edge-gateway` 汇聚按租户能力灰度 + 集中开关。
- Value：发布安全。Complexity：MEDIUM。Risk：MEDIUM。
- Dependency：O4。Priority：P3。Trigger：**同时存在 ≥2 个进行中的按租户灰度**。
- Status：DISCOVERED。

## Not Recommended Now（过度设计检测 — 重要）

### N1 在 Java 侧重建 Agent 编排 / Agent 评测
- Reason：`ai-runtime-boundaries.md` 已于 2026-07-30 批准把推理/编排/轨迹判给 Python，`evaluation-control-plane.md` 明确 Java **不得**重新执行 Agent shadow 或定义第二套 Agent 评测 schema。重建即违反已批准边界。
- Evidence：两份边界文档；`AgentScopeShadowReportReader.java` 的类注释。
- Status：NOT_RECOMMENDED_NOW。

### N2 现在删除 Java `agent-service` 代码
- Reason：退役门禁 6 条中至少 3 条未满足（真实模型 shadow/canary、存量任务排空、回滚演练）。门禁明确「只允许停用默认 workload，禁止删除代码或生产资源」。
- Evidence：`docs/架构边界/java-agent-retirement-gate.md`。
- Status：NOT_RECOMMENDED_NOW。重评触发：O3 两个机器检查落地 + 演练证据齐全。

### N3 把普通 Chat 迁进 `agentscope-orchestrator`
- Reason：决策门禁状态为 HOLD/shadow-only，7 个前置条件未满足，且明确禁止复用 AgentScope 在线进程作为 candidate。
- Evidence：`docs/架构边界/conversation-runtime-decision-gate.md`。
- Status：NOT_RECOMMENDED_NOW。重评触发：O6 产出完整证据。

### N4 通用只读缓存 L1/L2（Caffeine+Redis）
- Reason：未发现 `@Cacheable`/Caffeine 与读热点证据；Redis 目前仅用于 state/限流/幂等。无热点、无 DB 压力证据，一致性/失效成本 > 收益。
- Evidence：`rg @Cacheable|Caffeine` 无命中。
- Status：NOT_RECOMMENDED_NOW。重评触发：O4 指标证实读热点或 DB 回源压力。

### N5 分库分表 / 数据仓库 / CDC / BI
- Reason：按服务分库、按租户隔离已足够；无大数据量与报表业务驱动证据。
- Evidence：`database-migrations/.../V*.sql` 常规 schema；无报表/CDC 代码。
- Status：NOT_RECOMMENDED_NOW。重评触发：明确报表/离线分析业务，或单库写入被证实为瓶颈。

### N6 再造「统一任务中心 / 规则引擎 / 工作流引擎」
- Reason：`async-task-service`（任务中心含 lease/epoch/SSE/webhook）、`workflow-service`（Flowable BPMN + outbox + 幂等账本）已覆盖；再造是重复建设。O3 只需在现有任务中心加只读盘点，不是新平台。
- Status：NOT_RECOMMENDED_NOW。

### N7 为「展示 AI」额外堆叠 AI 能力
- Reason：AI 已是主业且默认多开，`Business First` 已满足；无新业务场景不应叠加。
- Status：NOT_RECOMMENDED_NOW。
