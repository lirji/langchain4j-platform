# Evidence Index — langchain4j-platform 能力探索

> 结论 → 证据映射。`confidence`：`FACT | INFERRED | UNKNOWN | NEEDS_VERIFICATION`。
> 跨仓证据以 `agentscope-platform/` 前缀标注（同一产品的另一半）。本轮只读，未运行构建与测试。

| # | Finding | Capability | Evidence Type | File / Class / Method / Config | Confidence |
|---|---|---|---|---|---|
| E1 | 两仓为同一产品，Python 负责推理/编排，Java 负责数据/事务/安全/副作用（2026-07-30 批准） | 边界 | Documentation | `docs/架构边界/ai-runtime-boundaries.md` | FACT |
| E2 | `/agent/**` 与 interop Agent proxy 默认指向 `agentscope-orchestrator`；Java `agent-service` 仅回滚目标 | 边界/部署 | Documentation + Config | `docs/架构边界/java-agent-retirement-gate.md`；`.github/workflows/agentscope-cutover-ci.yml`（`grep -q 'http://agentscope-orchestrator:8085'`） | FACT |
| E3 | Java `agent-service` 源码保留 126 个 Java 文件 | 技术债 | Code Count | `find agent-service/src -name '*.java' \| wc -l` = 126 | FACT |
| E4 | Java agent 动作与 Python 工具面一一对应，无 Java-only 业务动作丢失 | 业务 | Code | `agent-service/src/main/java/com/lrj/platform/agent/actions/`（16 files）vs `agentscope-platform/src/agentscope_platform/infrastructure/agentscope/{readonly_tools,governed_tools}.py` | FACT |
| E5 | Python 单向持有 17 个 boundary schema + 17 个 legacy schema | 契约 | Contract | `agentscope-platform/contracts/boundaries/*.schema.json`、`contracts/legacy/*.schema.json` | FACT |
| E6 | Java 侧仅 1 个 schema 资源，0 个 schema 测试 | 契约缺口(G1/O1) | Code Absence | `find -name '*.schema.json'` → 仅 `platform-protocol/src/main/resources/contracts/knowledge/ingestion-job.schema.json`；`rg schema.json --glob '*Test.java'` → 0 命中 | FACT |
| E7 | cutover CI 无跨语言契约兼容步骤（只有 Java 模块测试 + Compose/Helm 静态断言） | 契约缺口(G1/O1) | CI Config | `.github/workflows/agentscope-cutover-ci.yml` steps | FACT |
| E8 | Java 租户 token 预算/成本挂在 langchain4j `ChatModelListener`，只覆盖 Java 进程内调用 | 配额缺口(G2/O2) | Class + Method | `platform-metering/.../TokenBudgetChatModelListener#onResponse`（从 `TenantContext.current().tenantId()` 取租户）、`CostChatModelListener`、`RedisDailyCounters` | FACT |
| E9 | Python 只有 per-run token 上限与离线评测成本常数，无租户级/日级预算 | 配额缺口(G2/O2) | Config + Code Absence | `agentscope-platform/src/agentscope_platform/core/config.py:55`(`agent_max_tokens`)、`:75`、`:206-207`(cost per million)；`rg "tenant.*budget\|daily"` 于 Python src → 0 命中 | FACT |
| E10 | `/agent/**` 模型消耗绕过 Java 租户预算 | 风险 R1 | 推论（E2+E8+E9） | — | INFERRED |
| E11 | 退役门禁条件 4（存量任务排空）在 Java 侧无实现 | G3/O3 | Code Absence | `rg -i drain` 于 `async-task-service/src/main/java` → 0 命中；门禁条款见 `java-agent-retirement-gate.md` | FACT |
| E12 | `interop` 仍保留静态 fallback 能力目录，discovery 失败即回落 | G3/O3 | Class + Log | `interop-service/.../InteropToolRegistry.java:29-33`（`PING_TOOL`、`AGENT_RUN_TOOL`、`AGENT_RUN_ASYNC_TOOL`、`AGENT_DAG_PLAN_RUN(_ASYNC)_TOOL`）、`:119-121`（"using fallback"） | FACT |
| E13 | interop 已消费 Python 版本化 capability registry（revision = 规范 JSON SHA-256） | 集成 | Record | `platform-protocol/.../interop/AgentCapabilityRegistry.java`；`agentscope-platform/contracts/capabilities/agent-capabilities.v1.json` | FACT |
| E14 | Knowledge `query` 角色被强制关闭 graph 检索，直到命中带版本 provenance | G4/O5 | Assertion Message | `knowledge-service/.../KnowledgeRuntimeBoundaryConfig.java`："query role must disable graph retrieval until graph hits carry document version provenance" | FACT |
| E15 | 历史 graph source 无版本 provenance，版本 GC 只能 fail-safe 保留 | G4/O5 | Documentation | `docs/架构边界/knowledge-runtime-split.md`（版本 GC 段） | FACT |
| E16 | Durable ingestion v2 已具备 S3 原文 + JDBC job + 逐 sink 状态 + Registry 版本可见性提交 | 数据能力 | Code | `knowledge-service/.../ingest/job/{IngestionJob,JdbcIngestionJobStore,DefaultIngestionSinkProcessor,IngestionJobStateMachine}.java` | FACT |
| E17 | Conversation runtime 决策为 HOLD/shadow-only，shadow 只覆盖非流式 `/chat` | G5/O6 | Documentation | `docs/架构边界/conversation-runtime-decision-gate.md`（"当前 shadow 只覆盖非流式 /chat；这也是尚不能创建/切换独立 runtime 的明确缺口"） | FACT |
| E18 | LangChain4j `TokenStream` 无 cancel API，Java emitter 关闭不能等同上游取消 | G5/O6 | Documentation | 同 E17，前置条件 4 | FACT |
| E19 | `micrometer-registry-prometheus` 仅出现在 1 个服务 | G6/O4 | Dependency | `rg micrometer-registry-prometheus --glob 'pom.xml' -l` → 仅 `async-task-service/pom.xml` | FACT |
| E20 | OTel tracing 与 traceId 跨语言透传已具备 | 可观测 | Class | `platform-observability/.../otel/OtelTracingAutoConfiguration.java`、`TraceIdFilter.java`、`OutboundTraceForwarder.java`；`agentscope-platform` README（传播 `X-Trace-Id`） | FACT |
| E21 | 只有 6 个 GitHub workflow，服务却 16+ | G7/O9 | CI Config | `.github/workflows/`：`agentscope-cutover-ci`、`capability-showcase-frontend-ci`、`coding-agent-kit-ci`、`edge-gateway-ci`、`supply-chain`、`tax-ai-ci` | FACT |
| E22 | Java `eval-service` 只读消费 Python Shadow v4，不重跑 Agent 评测 | 评测边界 | Class | `eval-service/.../AgentScopeShadowReportReader.java`（拒绝 v3 / 缺 dataset / 非法 digest）；`docs/架构边界/evaluation-control-plane.md` | FACT |
| E23 | Python 侧存在 19 项 `agent-production-evidence.v1` 发布门禁，默认 `decision=NO_GO` | O8 | Documentation | `agentscope-platform/CODEX_PROGRESS.md`（AC-16）；`scripts/test_production_runbook.py` | FACT |
| E24 | Java 侧有 4 组静态配置门禁脚本 | O8 | Script | `deploy/test-{production-cutover,runtime-hardening,database-migration,supply-chain}-config.sh` | FACT |
| E25 | 生产结论仍为 NO-GO：真实模型 shadow/canary、任务排空、回滚演练、目标环境证据均缺 | 风险 R3/R5 | Documentation | `agentscope-platform/CODEX_PROGRESS.md`「未完成」段 | FACT |
| E26 | `tax-service` 为新业务线（29 files + 独立 CI），是平台化触发信号 | O9 | Code + CI | `tax-service/`、`.github/workflows/tax-ai-ci.yml` | FACT |
| E27 | 无 `@Cacheable`/Caffeine 使用，无读热点证据 | N4 | Code Absence | `rg @Cacheable\|Caffeine` → 0 命中 | FACT |
| E28 | 无报表/CDC/数仓代码，schema 为常规业务表 | N5 | Code Absence | `database-migrations/.../db/migration/**/V*.sql` | FACT |
| E29 | `mvn -DskipTests package` 在 `platform-eventbus` testCompile 失败（他仓记录，本仓未复验） | 工程缺口 | Documentation | `agentscope-platform/CODEX_PROGRESS.md`「当前问题」段 | NEEDS_VERIFICATION |
| E30 | 改 `interop` 静态 fallback 语义会影响 Python 不可用时的降级行为 | O3 风险 | 推论 | 需门禁 owner 确认预期降级语义 | NEEDS_VERIFICATION |
| E31 | 本轮未运行 `mvn test`（283 个 `*Test` 的通过状态未验证） | 验证状态 | — | 无执行记录 | UNVERIFIED |
