# Capability Map — langchain4j-platform（Java 半）

> 由 `project-capability-exploration` 只读生成（2026-09-16 修订：改为「一个产品 / 两个仓库」口径）。
> 仅回答「已有什么 / 成熟度」，作为缺口与机会分析的基线。
> 成熟度：`ABSENT | BASIC | PARTIAL | MATURE`；可信度：`FACT | INFERRED | UNKNOWN | NEEDS_VERIFICATION`。

## Exploration Summary

- **现状一句话**：这是同一个 AI 平台产品的 **Java 数据/安全/业务半边**；Agent 推理与编排已按绞杀者迁移全量切到同产品的 `agentscope-platform`（Python），`/agent/**` 默认指向 `agentscope-orchestrator`，Java `agent-service` 只作为整服务回滚目标保留（126 个 Java 文件，Compose `legacy-agent` profile / Helm `enabled=false`）。
- **最该补的 3 件事**：①跨语言 boundary 契约在 Java 侧无校验、CI 无兼容门禁；②`/agent/**` 切到 Python 后，Java 的**租户级 token 预算与成本计量面被绕过**；③Java Agent 退役门禁还差两个 Java 侧机器检查（interop 静态 fallback 目录、async-task 存量任务排空）。
- **最不该做的 1 件事**：不要在 Java 侧重建第二套 Agent 编排 / Agent 评测 / 通用缓存 —— 边界文档已把编排权威判给 Python，重建即违反已批准边界。

## Current Capability Overview

DDD 拆分的全微服务 AI 平台，24 个 Maven 模块（7 个 `platform-*` 共享库 + 16 个 `*-service` + `edge-gateway` + `database-migrations` + `config-server`），Java 21 / Spring Boot 3.3.5，283 个 `*Test`。两层网关（`edge-gateway` 签发内部 JWT + LiteLLM 统一模型网关）为核心设计。

**跨仓边界（FACT，`docs/架构边界/ai-runtime-boundaries.md`，2026-07-30 批准）**：Python 拥有推理/计划/工具选择/多 Agent 编排/轨迹；Java 拥有数据、事务、安全、副作用。四个显式门禁文档已存在并各自记录未完成前置：

| 门禁文档 | 当前状态 | Java 侧未完成项 |
|---|---|---|
| `java-agent-retirement-gate.md` | 已停默认部署，**禁止删码** | 存量任务排空、interop 只依赖 live discovery、回滚演练 |
| `conversation-runtime-decision-gate.md` | **HOLD / shadow-only** | `/chat/stream` candidate 事件面、多轮 shadow 质量验证 |
| `knowledge-runtime-split.md` | 本地已验证，生产 canary 待批 | graph 命中缺 `documentId/documentVersion` provenance |
| `evaluation-control-plane.md` | 边界已定，Java 只读消费 | 发布门禁证据仍在 Python 侧单向持有 |

整体工程成熟度偏高，短板集中在「跨语言契约门禁」「跨语言计量/可观测统一面」「跨服务 CI 均一化」。

## Business Capabilities — MATURE (FACT)

对话/检索/工作流/多模态/互操作/渠道/税务等业务能力齐全：`conversation-service`(/chat 系列)、`knowledge-service`(/rag 混排+GraphRAG+ReBAC)、`workflow-service`(Flowable BPMN)、`analytics-service`(NL2SQL)、`channel/interop/voice/vision/order/tax-service`。
- 证据：`pom.xml` 24 modules；`knowledge-service/.../authz/`；`tax-service`(29 files，独立 CI `tax-ai-ci.yml`)。
- **Agent 编排已不在本仓**：`agent-service`(126 files) 仅回滚目标；工具面权威在 `agentscope-platform/src/agentscope_platform/infrastructure/agentscope/readonly_tools.py`。
- 工具面 parity 良好（FACT）：Java `agent-service/actions/` 的 16 个 action 与 Python 7 个只读工具 + 受治理 `refund_start` + 远端 sandbox(mcp/browser/code，默认关) 一一对应，未发现 Java-only 业务动作被丢弃。
- 备注：`tax-service` 为较新业务线，是「第二业务线」平台化触发信号。

## Platform Capabilities — MATURE (FACT)

`platform-*` 共享库通过 `AutoConfiguration.imports` 自注册：安全/内部 JWT/限流(`platform-security`)、唯一 `ChatModel` bean(`platform-gateway-client`)、跨服务 DTO(`platform-protocol`)、审计/计量(`platform-audit`/`platform-metering`)、事件总线(`platform-eventbus`)、可观测(`platform-observability`)。
- 证据：`platform-security/.../ratelimit/RateLimiterRegistry.java`、`platform-eventbus/.../KafkaEventPublisher.java`。
- **跨语言协议承载 PARTIAL**：`platform-protocol` 只落盘 1 个 schema（`contracts/knowledge/ingestion-job.schema.json`），Python 侧 `contracts/boundaries/` 有 17 个 boundary schema，Java 无对应校验资源。

## Engineering Capabilities — PARTIAL (FACT)

- 数据库迁移：Flyway 版本化，按服务分目录，统一在 `database-migrations`（expand-contract）→ MATURE。证据：`database-migrations/.../db/migration/{order,auth,workflow,...}/V*.sql`。
- 测试：283 个单测，纯 POJO + H2，覆盖面广 → MATURE。
- 供应链安全：CycloneDX 聚合 SBOM + Trivy 扫描 → MATURE。证据：`.github/workflows/supply-chain.yml`。
- **跨仓 cutover 门禁 CI**：`agentscope-cutover-ci.yml` 覆盖 edge/interop/多模态/迁移 + Compose + Helm 静态断言（含 `grep -q 'http://agentscope-orchestrator:8085'`）→ PARTIAL（**无跨语言契约兼容步骤**）。
- CI 均一化：仅 6 个 workflow，多数 `*-service` 无独立 CI → PARTIAL。
- 灰度：`edge-gateway/.../AgentCanaryRoutingFilter.java` + `AgentCanaryProperties.java` → PARTIAL（仅 Agent 域）。

## Reliability Capabilities — PARTIAL→MATURE (FACT)

- Outbox：`workflow-service/.../WorkflowOutbox.java` + `WorkflowOutboxDispatcher.java`、`async-task-service/.../AsyncTaskLifecycleOutbox.java` → MATURE。
- 幂等：`workflow-service/.../WorkflowIdempotencyStore.java` → PARTIAL（工作流域）。
- 限流：`platform-security/.../ratelimit/{RateLimiterRegistry,RedisRateLimiterRegistry}.java` → PARTIAL。
- DB 租约：`async-task-service/.../JdbcAsyncTaskStore.java`（lease + epoch fencing）→ PARTIAL。
- **跨语言回滚**：整服务级切换已有明确 runbook（`java-agent-retirement-gate.md` 的 Compose/Helm 覆盖命令），禁止 per-request fallback → PARTIAL（缺演练证据）。
- **存量任务排空能力 ABSENT**：`async-task-service` 未见 drain / Java-worker-only 任务盘点接口（`rg -i drain` 在该模块 0 命中）。

## Observability Capabilities — PARTIAL (FACT)

- Tracing：OpenTelemetry + traceId 透传，且 Python 侧沿运行上下文传播 `X-Trace-Id` → MATURE。证据：`platform-observability/.../otel/OtelTracingAutoConfiguration.java`、`TraceIdFilter.java`、`OutboundTraceForwarder.java`。
- Health/Actuator：多服务含 actuator → PARTIAL。
- **Metrics BASIC**：`micrometer-registry-prometheus` 只出现在 `async-task-service/pom.xml`（1/16+ 服务），其余仅局部自定义指标（`AsyncTaskMetrics`、`CascadeMetrics`），无统一抓取/聚合/告警面。

## Data Capabilities — PARTIAL (FACT)

- 持久化：裸 `JdbcTemplate` + MySQL，按服务分库、按租户隔离 → MATURE（写权威清晰，且边界文档明确禁止 Python 持久化业务权威状态）。
- 检索：`knowledge-service` 四路混排（向量+ES BM25+rerank+GraphRAG）→ MATURE（`combined` 角色）。
- Durable ingestion v2：S3 原文 + JDBC job + 逐 sink 状态 + Registry 版本可见性提交 + 版本 GC → MATURE。证据：`knowledge-service/.../ingest/job/*`、`IngestionSinkProcessor`。
- **拆分角色 query 的 graph 检索被封锁**：`KnowledgeRuntimeBoundaryConfig.java` 断言 "query role must disable graph retrieval until graph hits carry document version provenance" → PARTIAL。
- 报表/BI/CDC/冷热分离/数仓：未见 → ABSENT（当前无业务驱动证据）。

## Security Capabilities — PARTIAL→MATURE (FACT)

- 认证：Casdoor OIDC SSO 换发内部 JWT，`only` 严格模式默认开 → MATURE。
- 跨语言身份：Python 严格校验 issuer/单一 audience/kid/token-use/jti/时间窗/最大 TTL；下游/worker/sandbox 使用独立短时服务身份（`downstream-service-token-claims`、`async-task-worker-token-claims`）→ MATURE。证据：`platform-security/src/test/.../AsyncTaskWorkerTokenTest.java`。
- 授权：文档级 ReBAC（SpiceDB / auth-platform），`RAG_AUTHZ_MODE` 默认 disabled → PARTIAL。
- 多租户：`TenantContext` ThreadLocal + JWT 跨跳传播 + 可选 `dept` → MATURE。
- **租户配额面 PARTIAL（跨语言漏洞）**：`platform-metering/TokenBudgetChatModelListener` 与 `CostChatModelListener` 挂在 langchain4j `ChatModelListener` 上，只看得到 Java 进程内模型调用。

## Integration Capabilities — MATURE (FACT)

- 出站/回调/事件：`channel-service`（Kafka + 钉钉/飞书桥）、`interop-service`（A2A 真流式 + MCP surface）→ MATURE。
- Agent capability discovery：`InteropToolRegistry` 消费 Python 版本化 `AgentCapabilityRegistry`（revision = 规范 JSON SHA-256）→ PARTIAL（**仍保留静态 fallback 目录**）。
- 模型网关：LiteLLM 统一 OpenAI 兼容端点（Java 无 provider switch）→ MATURE。

## AI Capabilities — MATURE (FACT)

RAG / Tool Calling / MCP surface / NL2SQL / 多模态 / 语音闭环留在 Java；Agent(ReAct+DAG)/规划/critic/replan/轨迹在 Python。
- 证据：`docs/架构边界/ai-runtime-boundaries.md` 所有权表；`eval-service/.../AgentScopeShadowReportReader.java`（Java 只读消费 Shadow v4，不重跑 Agent 评测）。
- 备注：AI 已是平台主业，**不是**「为展示而引入」的候选。

## Capability Maturity Summary

| 维度 | 成熟度 | 关键短板 |
|---|---|---|
| Business | MATURE | 编排已迁出；第二业务线(tax)平台复用待固化 |
| Platform | MATURE | 跨语言契约在 Java 侧无承载/校验 |
| Engineering | PARTIAL | cutover CI 缺契约兼容步骤；跨服务 CI 不均一 |
| Reliability | PARTIAL→MATURE | 存量任务排空能力缺失；跨语言回滚缺演练 |
| Observability | PARTIAL | Prometheus 仅 1 个服务；无统一告警/SLO |
| Data | PARTIAL | graph provenance 阻塞 query 角色；无报表/CDC（暂无驱动） |
| Security | PARTIAL→MATURE | 租户 token/成本预算被 `/agent/**` 绕过 |
| Integration | MATURE | interop 静态 fallback 未清 |
| AI | MATURE | — |
