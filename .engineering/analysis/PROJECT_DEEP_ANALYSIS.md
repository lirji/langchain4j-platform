# 项目深度分析

- workspace: `/Users/liruijun/personal/LLM/langchain4j-platform`
- generated_at: 2026-09-16
- protocol: project-deep-analysis/v1
- source_of_truth: 源码（README 仅线索）
- re_run_policy: REGENERATE_OWNED_ARTIFACT
- 能力状态标注：已实现 / 可选开关 / 规划中 / 推测
- companion:
  - `.engineering/analysis/ARCHITECTURE.md`
  - `.engineering/analysis/BUSINESS_FLOWS.md`
  - `.engineering/analysis/EVIDENCE_INDEX.md`
  - `.engineering/analysis/PROJECT_CAPABILITY_REPORT.md`（同日能力探索 16 节，非本技能必填件）

本文件是分析视图，不是 `BACKEND_ARCHITECTURE` / `CONTRACTS`。未跑 `mvn test` 的结论标 `UNVERIFIED`。

---

## 1. 项目一句话定位

这是一套企业多租户 AI 能力平台：把对话、知识检索、Agent、查数、退款审批、渠道和互操作拆成微服务，统一鉴权后按能力调用，而不是一个会聊天的单体 Demo。

---

## 2. 项目背景与业务目标

原单体 `LangChain4j_project` 冻结为行为基准；本仓按 DDD 限界上下文重写为 Maven 多模块微服务，模型调用下沉到外部 LiteLLM。

**核心用户 / 调用方**

- 能力展示前端（Vite/Vue3）与人工操作员：经 edge 用 Casdoor / 会话 Bearer / `X-Api-Key`
- 内部服务互调：`X-Internal-Token` + `TenantContext`
- 外部 Agent 协议：A2A / MCP surface（interop-service）
- 可选 IM 入站：飞书 / 钉钉（默认关）

**系统负责**

- 入站身份换发与租户传播
- RAG 入库/检索、对话增强、评测、视觉/语音（语音默认关）
- 退款审批工作流与终态通知
- NL2SQL、订单只读、发票规则审查
- 异步任务中心、渠道出站

**系统不负责**

- 模型供应商路由（LiteLLM）
- 文档级 ReBAC 的权威判定（委托外部 auth-platform；默认本仓不 enforce）
- 生产 Agent 编排的默认实现（compose 默认打到仓外 `agentscope-platform`；本仓 Java `agent-service` 是回滚面）
- 资金账本 / 库存超卖（订单只读，退款是审批流不是支付核心）

**上游**：Casdoor、LiteLLM、Qdrant、Elasticsearch、MySQL、Redis、可选 Kafka / S3 / SpiceDB。  
**下游**：各微服务、webhook、渠道、评测目标 HTTP。

**为什么不是普通 CRUD**：主路径是「不确定的 LLM + 多存储召回 + 跨进程租户 + 部分成功的入库/通知」。列表增删只是知识与订单的边角。

---

## 3. 系统总体架构

两层网关 + 共享内核库 + 按上下文拆分的 servlet 服务。

- **edge-gateway**（WebFlux :8080）：Casdoor / session / api-key 换发短时内部 JWT，限流后按 path 路由。
- **LiteLLM**（仓外）：唯一 ChatModel 指向 OpenAI 兼容端点，Java 无 provider `switch`。
- **platform-***：安全、观测、协议、审计、计量、事件总线，经 Spring AutoConfiguration 注入。
- **同步为主**：服务间 RestTemplate + 租户/trace 拦截器；Kafka 事件总线默认关。
- **存储按上下文拆库**：auth / flowable / knowledge_graph / knowledge_ingestion / async_task / order_service / channel / nl2sql_demo；向量与全文不在 MySQL。

详细拓扑、分层、部署见 `ARCHITECTURE.md`。

证据：根 `pom.xml` modules；`edge-gateway/.../application.yml` routes；`GatewayChatModelFactory`；`deploy/mysql/init/001-platform-databases.sql`。

---

## 4. 模块地图

| 模块 | 类型 | 从入口恢复的职责 |
|---|---|---|
| `platform-security` | 库 | `TenantContext`、`InternalToken`、入站 JWT filter、出站转发、限流、自研熔断 |
| `platform-observability` | 库 | `TraceIdFilter`、出站 trace、OTel ChatModel listener |
| `platform-gateway-client` | 库 | 唯一 `ChatModel` → LiteLLM |
| `platform-protocol` | 库 | 跨服务 DTO |
| `platform-audit` / `platform-metering` | 库 | LLM 审计；token 日预算事后累加 |
| `platform-eventbus` | 库 | 内存默认，可选 Kafka |
| `database-migrations` | 库 | Flyway；业务进程禁止 DDL |
| `edge-gateway` | 服务 | 唯一对外入口 |
| `auth-service` | 服务 | 登录会话；RBAC 管理面默认关 |
| `conversation-service` | 服务 | `/chat*`、抽取、记忆 |
| `knowledge-service` | 服务 | `/rag/**` 混排、入库、图谱 |
| `agent-service` | 服务 | Java ReAct/DAG 等；**默认流量不打这里** |
| `workflow-service` | 服务 | Flowable 退款 + outbox |
| `analytics-service` | 服务 | `/chat/sql` NL2SQL |
| `async-task-service` | 服务 | 任务状态、SSE、webhook outbox |
| `channel-service` | 服务 | 出站/入站；Kafka 监听依赖 eventbus |
| `interop-service` | 服务 | A2A SSE、MCP 工具面 |
| `eval` / `vision` / `voice` / `order` / `tax` | 服务 | 评测、图像、语音（默认关）、订单只读、发票风控 |
| `config-server` | 服务 | 可选；`optional:configserver` |
| `capability-showcase-frontend` | 非 Maven | 能力试用控制台 |

依赖方向：服务 → `platform-*`；服务之间 HTTP，不跨库 JOIN。Agent 生产实现在仓外 `agentscope-orchestrator`。

---

## 5. 核心业务链路

细节与时序图见 `BUSINESS_FLOWS.md`。

| # | 名称 | 入口 | 最终结果 |
|---|---|---|---|
| 1 | 边缘鉴权换发 | 任意非 open-path | 下游带合法 `X-Internal-Token` 或 401 |
| 2 | RAG 增强对话 | `POST /chat` | 护栏后检索增强的回复；可走语义缓存 |
| 3 | 混排知识检索 | `POST /rag/query` | 融合 + 可选 ReBAC 过滤后的 hits |
| 4 | 异步知识入库 | `POST /rag/ingestions` | job 多 sink 成功后 registry 提交版本才可被查 |
| 5 | 退款审批 | `POST /workflow/refund/start` | COMPLETED 或 WAITING_APPROVAL，终态 outbox 通知 |
| 6 | Agent 工具编排 | `/agent/**` | 生产打 AgentScope；Java `DeepAgentService` 为回滚 |
| 7 | NL2SQL | `POST /chat/sql` | 只读 SELECT 结果或护栏拒绝 |
| 8 | 异步任务 + SSE | `/async/tasks` + `/stream` | 状态机终态 + 可选 webhook |
| 9 | A2A 真流式 | `POST /interop/a2a` `message/stream` | SSE 代理 conversation `/chat/stream` |
| 10 | 发票风险审查 | `POST /tax/invoices/review` | 规则结论 + 可选 RAG/AI 说明 |

---

## 6. 核心领域模型

本仓不是经典电商聚合根仓库；领域对象按上下文拆开。

| 对象 | 性质 | 不变量 |
|---|---|---|
| `TenantContext.Tenant` | 身份值对象 | tenantId/userId/scopes/可选 department；未设置则 ANONYMOUS |
| `InternalToken` | 安全凭证 | iss/aud/kid/`token_use`/TTL；`sub`=tenant |
| Document + version | 知识聚合 | 查询只认 registry **已提交版本**；ingest 未 commit 不可见 |
| `IngestionJob` | 过程实体 | lease + revision CAS；PARTIAL 可按 stage 重试；耗尽 MANUAL_REVIEW |
| `Hit` / `QueryResult` | 检索结果 | 融合时 `shared` 跨源 AND |
| Flowable ProcessInstance + `WF_*` | 审批 | 租户写进流程变量再过滤；幂等账本绑定 instance |
| `AsyncTask` | 任务 | PENDING→RUNNING→终态；lease epoch 冲突 409 |
| `AgentDecision` / `AgentAction` | 编排 | 高风险动作条件装配；循环有 LOOP/MAX_STEPS |
| Order | 只读实体 | `tenant_id` + orderNo |
| 发票审查批次 | 无持久聚合 | 规则引擎权威，AI 不改判定 |

没有把 Redis 当订单/审批账本。

---

## 7. 核心数据库模型

以 `database-migrations` Flyway 为准。业务进程启动只校验表/列。

**workflow** `V1__workflow_platform_schema.sql`

- `WF_IDEMPOTENCY` PK `(TENANT_ID, OPERATION_NAME, IDEMPOTENCY_KEY_HASH)`，含 `REQUEST_HASH`、`INSTANCE_ID`
- `WF_OUTBOX` PK `INSTANCE_ID`，`STATUS`，`CLAIMED_BY`/`CLAIMED_UNTIL`，`ATTEMPTS`
- `WF_TERMINAL_EVENT_OUTBOX` 同类租约列（Kafka 路径）
- `WF_REPLY` 终态回复
- Flowable `ACT_*` 在独立库 `flowable`

**knowledge-ingestion** `V1` + `V3__ingestion_recovery.sql`

- `KNOWLEDGE_INGESTION_JOB`：状态、sink 进度、`RETRY_COUNT`、`NEXT_RETRY_AT`、`LEASE_OWNER`、`LEASE_UNTIL`、`FAILED_STAGE`

**knowledge-graph** `RAG_GRAPH_TRIPLE`（租户隔离由 store 代码保证）

**async-task** `ASYNC_TASK`（`LEASE_*`、`LEASE_EPOCH`）、`ASYNC_TASK_EVENT`、`ASYNC_TASK_WEBHOOK_OUTBOX`、`ASYNC_TASK_LIFECYCLE_OUTBOX`

**order** `orders` / `customers` + `tenant_id`

**auth** `USERS`、`ROLES`、`ROLE_SCOPE`、`AUTH_SESSION`、组与租户策略表

**乐观锁**：async-task lease epoch；auth 管理写 If-Match；ingest job revision。无通用 `version` 列铺满所有表。

向量点、ES 文档、Redis Hash registry **不在** Flyway。

---

## 8. 技术栈

每项落到调用链。依赖存在 ≠ 核心路径使用。

| 技术 | 用在哪条链 | 解决什么 | 状态 |
|---|---|---|---|
| Java 21 / Spring Boot 3.3.5 / Cloud 2023.0.3 | 全部服务 | 运行时 | 已实现 |
| LangChain4j `OpenAiChatModel` | 所有 LLM | 对接 LiteLLM，无 Java provider 分支 | 已实现 |
| Spring Cloud Gateway | 入站 | 鉴权换发 + 路由 + 限流 | 已实现 |
| JJWT HS256 / 可选 RS256 | 租户传播 | 短时内部令牌 | 已实现 |
| JdbcTemplate | order/auth/workflow/ingest/graph | 无 JPA/MyBatis | 已实现 |
| Flowable | 退款 BPMN | 人工任务 + ServiceTask | 已实现 |
| Qdrant（yml 默认） | RAG 向量 | per-tenant collection | 已实现；embedding 本地默认可 hash |
| Elasticsearch | BM25 腿 | 与向量 RRF | yml 默认开 |
| Redis | registry、语义缓存、限流、token-budget | 加速/计数/元数据，非账本 | 已实现 |
| Kafka | workflow/async 生命周期 | 可选；`platform.eventbus.enabled` 默认 false | 可选开关 |
| 自研熔断/bulkhead | 出站 RestTemplate | 无 Resilience4j | 已实现，默认开 |
| Playwright / MCP / code_exec | Agent 动作 | 高风险 | 可选，默认关 |
| Helm / Compose | 部署 | 本地与 K8s 样例 | 已实现 |

**未在核心路径使用**：Resilience4j（全仓无引用）；`TokenBudgetGuardFilter`（文件不存在）；分库分片中间件。

---

## 9. 核心技术难点

评级标准：S 架构级事故面，A 高级工程，B 基本功，C 不写。

### 9.1 多源 RAG 分数与可见性（S）

```text
Problem: 余弦、BM25、图谱固定分不可比；租户/公开/分享三套可见性。
Why Difficult: 融错会串租户或漏检；enforce 下无 docId 的图谱命中被丢。
Current Solution: ParallelRetrievalExecutor 并行召回；HybridFusionService RRF/加权；registry 版本过滤；ReBAC bulk view。
Key Implementation: KnowledgeQueryService.query；FusionStrategy ES 活跃默认 RRF；shared 跨源 AND。
Trade-off: enforce 图谱召回变差；公开库默认开扩大泄漏面。
Failure Scenario: SpiceDB 宕机时 shadow 放行全集，enforce 变空。
Risk: 误以为默认已做文档级授权。
Possible Evolution: 图谱 hit 带 docId+version；生产关闭无必要 public。
```

### 9.2 多 sink 入库部分成功（S）

```text
Problem: 同一文档要写向量、ES、图谱、授权、registry。
Why Difficult: 不能当一次 HTTP 成功；worker 崩溃会双写或永不可见。
Current Solution: job 状态机 + lease heartbeat + V3 恢复列；REGISTRY 最后 commitVersion。
Key Implementation: IngestionJobWorker.process；RedisDocumentRegistry Lua CAS；IngestionReconciler。
Trade-off: 查询与存储短暂不一致；GC 默认关，旧向量可残留。
Failure Scenario: 向量已写但未 commit → 用户以为失败，重试再写。
Risk: 双协议（legacy sync vs job）发散版本。
Possible Evolution: 打开 GC；拆 query/ingest 角色。
```

### 9.3 审批终态通知 at-least-once（S）

```text
Problem: 流程结束必须通知 webhook/channel，又不能绑死 Kafka。
Why Difficult: DB 提交成功但 HTTP 失败会丢通知；重投会重复。
Current Solution: 引擎事务写 WF_OUTBOX / 事件 outbox；claim 租约；4xx DEAD；5xx 退避最多 6 次。
Key Implementation: WorkflowTerminalOutboxListener；WorkflowOutboxDispatcher；幂等表 WF_IDEMPOTENCY。
Trade-off: 下游必须幂等；Kafka 默认关。
Failure Scenario: 投递成功但 markDelivered 失败 → 重投。
Risk: 人工任务超时 sweeper 与 complete 并发。
Possible Evolution: 统一走 async-task 终态。
```

### 9.4 租户跨 JVM 传播（A/S）

```text
Problem: 单体 ThreadLocal 拆服务后丢失。
Why Difficult: 并行检索用虚拟线程，不能靠 InheritableThreadLocal。
Current Solution: 边缘签发内部 JWT；并行任务快照 TenantContext+MDC 再 set/clear。
Key Implementation: InternalToken；OutboundTenantForwarder；ParallelRetrievalExecutor。
Trade-off: HS256 共享密钥，直连服务端口可伪造（密钥泄漏时）。
Failure Scenario: Casdoor filter 未跑时不剥离入站 X-Internal-Token。
Risk: 默认 dev secret 进仓库。
Possible Evolution: RS256 + NetworkPolicy 只允许 edge。
```

### 9.5 LLM 进入审批 / Agent / SQL（S）

```text
Problem: 模型会幻觉、循环、生成写语句。
Why Difficult: 超时既不能当成功也不能当失败。
Current Solution: 退款 ServiceTask degrade 不炸引擎；Agent LOOP/MAX_STEPS；SqlGuard 只读；税票规则权威。
Key Implementation: ServiceTaskDelegates.withRetry；DeepAgentService；SqlGuard；TaxInvoiceRuleEngine。
Trade-off: degrade 可能留下低质量审批意见。
Failure Scenario: NL2SQL 绕过护栏（若白名单配错）。
Risk: Java Agent 与 AgentScope 双栈行为差。
Possible Evolution: 审批意见人工确认门；删 Java 回滚面。
```

CRUD 列表、普通 Controller、单纯 Redis get/set 不进本节。

---

## 10. 核心架构亮点

1. **双网关分工**：业务鉴权与模型路由分离。`GatewayChatModelFactory` 在 virtual-key 模式禁止回退 master key。
2. **内部令牌契约**：iss/aud/kid/`token_use`/TTL，下游无 api-key 表。
3. **接口 + 条件实现**：内存/Noop 默认，Jdbc/Redis/Kafka/Http 可开，单测不绑基础设施。
4. **检索可见性门闩**：多源写入后 registry 提交才可查。
5. **DB lease Outbox**：不用 Redis 锁做多实例抢占。
6. **税票规则权威、AI 只叙事**：幻觉不能改判定。
7. **高风险 Agent 动作默认关**：code_exec/mcp/browser。
8. **ChatModelListener SPI**：审计、计量、OTel 同一挂载点。

Spring Boot / MySQL /「用了 Redis」本身不是亮点。

---

## 11. 设计模式分析

| 模式 | 变化点 | 证据 |
|---|---|---|
| Strategy | 融合 RRF vs 加权；embedding/vector store 多 provider | `FusionStrategy`；`KnowledgeEmbeddingConfig` |
| Adapter | Http*Client vs Noop* | conversation RAG、agent 各动作客户端 |
| Registry / SPI | `AgentAction` Spring 收集；`RetrievalSource`；`ChatModelListener` | `DeepAgentService` 构造器 |
| Template / Pipeline | ingest prepare → sinks → commit | `DefaultIngestionSinkProcessor` |
| Outbox | 本地事务 + 异步投递 | `WorkflowOutbox` |
| State | ingest / async-task / outbox STATUS | 表状态列 + Java 校验 |
| Decorator | `TenantAwareChatModel` | gateway-client |

禁止项：无空 `BaseXxx` + 单实现充数的必要。

---

## 12. 性能与稳定性分析

无产品 NFR，不编 QPS/TP99。

- **同步扇出**：`/chat` 默认可串 query-expansion、混排、rerank、grounding、LLM。LiteLLM 先饱和。
- **并行召回**：固定池 + `AbortPolicy`，饱和拒绝而非无界队列。
- **语义缓存**：租户 Hash `HVALS` 后应用侧余弦，大租户会扫全桶。
- **超时**：edge 下游 connect 3s；`RequestDeadlineFilter` 绝对 deadline；出站熔断计 5xx/IO。
- **重试**：outbox/ingest 有界；Agent brain 默认少次；无 Resilience4j Retry。
- **降级**：知识库失败空 hits 继续聊；tax AI 失败规则仍出；workflow LLM degrade。
- **恢复**：ingest reconciler；async orphan reaper **默认关**。
- **单点**：HS256 密钥；默认 AgentScope 进程；LiteLLM。

---

## 13. 数据一致性设计

选择的是「能守住不变量的最弱模型」，不是 2PC。

| 场景 | 模型 |
|---|---|
| 退款启动 | 本地事务：幂等账本 + Flowable start |
| 终态通知 | Outbox + 租约 + 有界重试（at-least-once） |
| 知识可见性 | registry 提交为查询权威；存储允许短暂超前 |
| 对话 | 允许 RAG 失败后无检索回答 |
| ReBAC | enforce fail-closed；shadow fail-open |
| Token 预算 | 事后累加，不参与提交 |

没有全链路对账任务。Inbox 仅 channel 事件去重 store（eventbus 默认关）。

---

## 14. 并发与异步设计

- **防重**：`WF_IDEMPOTENCY` 唯一键；ingest idempotency key；async `eventKey`。
- **抢占**：outbox/ingest/async `CLAIMED_*` / `LEASE_*`，不是 Redis 锁。
- **Worker**：`IngestionWorkerLoop` `@Scheduled`；WF dispatcher 定时扫。
- **SSE**：async-task / A2A / agent task stream；`Last-Event-ID` 回放。
- **虚拟线程**：并行检索显式传递租户，`inheritInheritableThreadLocals(false)`。
- **无**经典超卖库存；订单只读。

---

## 15. 可扩展性分析

扩展点真实存在：`AgentAction`、`RetrievalSource`、向量/图谱/job store 多实现、`ChatModelListener`、eventbus Publisher、渠道 dispatcher。

规则引擎仅税票确定性规则，不是 Drools 中台。工作流是单条退款 BPMN，不是通用流程平台。DAG 在 Java Agent 与 AgentScope。

没有扩展点的地方不编：例如订单没有策略插件。

---

## 16. 工程质量分析

- **测试**：约 281 个 `*Test.java`，以 POJO + mock + H2 为主。CI `.github/workflows/supply-chain.yml` 执行 `mvn -B -DskipITs test`。本分析会话 **未跑测试 → UNVERIFIED**。
- **聚焦测试**：workflow outbox 原子性、ingest recovery、ParallelRetrievalExecutor 租户、InternalToken。
- **缺口**：几乎不启 Spring 上下文；开关组合、Casdoor JWKS、真实 Qdrant 不在 PR 主路径。
- **观测**：traceId、actuator、可选 OTel、审计 listener。无独立 alerting 代码。
- **开关**：yml 大量默认开，与 Java 注释「默认关」冲突（质量/运维风险）。
- **供应链**：CycloneDX + Trivy HIGH/CRITICAL。
- **ArchUnit / Golden Set**：coding-agent-eval 工具存在，不覆盖业务不变量。

---

## 17. 当前架构问题

1. **内部 JWT HS256 + 默认可猜测 secret**：绕过 edge 即伪造租户。
2. **ReBAC 默认 disabled + public KB 默认 true**：宣传上的文档级授权不是默认生产行为。
3. **Token 预算名不副实**：只有 listener 记账，无预检 Filter。
4. **Java Agent 与 AgentScope 双栈**：默认流量已切仓外，本仓实现仍完整，行为易分叉。
5. **语义缓存 Redis 全桶扫描**；**内存关键词镜像不持久**。
6. **入站内部头剥离依赖 Casdoor filter 是否运行**。
7. **channel 签名默认关**（入站也默认关，误开时危险）。
8. **ingest GC 默认关**。

---

## 18. 潜在技术风险

事故形态，不是「有风险」。

| 级 | 形态 | 触发 |
|---|---|---|
| P0 | 跨租户读知识/订单/审批 | 直连服务 + 泄漏/默认 JWT secret |
| P0 | 敏感文档进 `__public__` 被他租户检索 | public.enabled 默认 true |
| P1 | LLM 费用打穿 | 预算不拦截 |
| P1 | 对话超时雪崩 | 默认同步 RAG+多路 LLM |
| P1 | shadow 授权失效仍放行 | SpiceDB 宕 |
| P1 | 审批重复通知 | outbox 未 ack |
| P2 | 大租户 chat 变慢 | HVALS 语义缓存 |
| P2 | 重启后内存 BM25 腿空 | DocumentMirror |
| P3 | 按 Javadoc 把 Casdoor 配关 | 字段默认与 yml 相反 |

10 倍流量：先炸 LiteLLM → RestTemplate 扇出 → ingest/outbox 行锁 → Redis 大 Hash → ES/Qdrant。

---

## 19. 架构改进方向

**短期（不改架构）**

- 生产 RS256 + 禁默认 secret + 服务端口不对公网
- `RAG_AUTHZ_MODE=enforce` 或明确接受租户级隔离；关闭无必要 public
- 补 token 预检或改文档口径
- 修正「默认关」Javadoc / compose 注释

**中期**

- registry 是否回 MySQL 做权威（Redis 故障即不可查询）
- 图谱 hit 带文档版本
- 打开并验证 ingest GC
- 语义缓存改为向量索引而非 HVALS

**长期**

- 收敛 Agent 双栈
- 对话 RAG 与 LLM 超时预算产品化
- 不为炫技上分库分表/K8s（Helm 已有，按环境需要）

这些不是已批准设计。

---

## 20. 高级Java简历价值点

**项目描述（约 160 字）**

负责企业多租户 AI 能力平台，覆盖边缘换发身份、知识入库与混排检索、对话增强、退款审批终态通知到渠道回调的完整链路，设计内部短时令牌租户传播、检索版本门闩与审批消息表投递，基于 Java 21 / Spring Cloud Gateway / LangChain4j / Flowable 落地可开关的微服务能力面。

**贡献点**

1. 负责平台身份传播体系，覆盖 Casdoor/会话/api-key 入站到下游服务重建租户的完整链路，设计短时内部令牌与出站转发，基于 JJWT 与 ThreadLocal 上下文实现跨进程租户隔离。
2. 负责知识检索体系，覆盖多路召回、融合、版本过滤到可选文档授权的完整链路，设计并行检索时的租户快照传递与 registry 提交可见性，基于 Qdrant、Elasticsearch 与 Redis 元数据实现混排查询。
3. 负责知识入库作业，覆盖提交、租约抢占、多存储写入到版本提交的完整链路，设计阶段重试与过期租约恢复，基于 MySQL 作业表实现可恢复入库。
4. 负责退款审批通知，覆盖流程终态与 webhook/事件投递的完整链路，设计本地事务写入消息表、租约抢占与有界重试，基于 Flowable 与 outbox 表实现可补偿通知。
5. 负责模型接入面，覆盖业务服务到 LiteLLM 的完整调用链，设计单一 ChatModel 与监听器挂载，基于 OpenAI 兼容客户端落地审计与用量计量。
6. 负责对话安全编排，覆盖注入检查、检索增强、生成与输出脱敏的完整链路，设计拦截后不进模型的阻断档，基于护栏与语义缓存实现可降级问答。
7. 负责分析与财税辅助，覆盖 NL2SQL 只读执行与发票规则审查的完整链路，设计 SQL 护栏和「规则出结论、模型只说明」，基于只读账号与确定性规则实现受控智能。

---

## 21. 面试官深挖问题

**Level 1 项目理解**

- 这套系统和 ChatGPT 套壳差在哪？
- 生产 Agent 为什么不走本仓 `agent-service`？
- 租户是怎么从浏览器到 Qdrant collection 的？

**Level 2 实现细节**

- `InternalToken.verify` 拒绝哪些字段？
- 为什么并行检索要自己 set TenantContext？
- `commitVersion` Lua 失败时查询能看到新切片吗？
- WF 幂等键冲突但 REQUEST_HASH 不同为什么是 409 不是重放？

**Level 3 架构设计**

- 为什么模型路由不放 Java？
- 为什么通知用 DB outbox 而不是发完再写库？
- Redis registry 当查询权威的取舍？
- enforce 丢掉无 docId 图谱是有意 fail-closed 还是模型缺陷？

**Level 4 故障场景**

- LiteLLM 超时，对话算成功还是失败？缓存里有什么？
- SpiceDB 宕了，disabled/shadow/enforce 各怎样？
- outbox 已 POST 200 但进程在 markDelivered 前崩溃？
- 有人直连 :8084 拿一段 HS256 JWT？

**Level 5 规模扩大**

- 单租户十万级语义缓存条目时 HVALS 会怎样？
- ingest worker 水平扩展靠什么避免双写？
- `/chat` 默认同步四路检索 + rerank，10 倍 QPS 谁先炸？

故事写法：先讲不变量（谁能看见哪版文档 / 通知至少一次），再讲失败窗口，再讲为什么不 2PC。

---

## 22. 项目学习地图

1. **业务链路**：`ConversationController.chat` → `KnowledgeQueryService.query` → `WorkflowService.start`  
2. **身份**：`CasdoorTokenExchangeFilter` → `InternalToken` → `InternalTokenAuthFilter`  
3. **一致性/并发**：`WF_IDEMPOTENCY` + `WorkflowOutbox`；`IngestionJobWorker` lease  
4. **扩展点**：`AgentAction`、`RetrievalSource`、`GatewayChatModelFactory` listeners  
5. **风险**：默认开关、HS256、ReBAC disabled、TokenBudget 无预检  
6. **仓外**：`docs/Agent编排/agentscope-full-cutover.md` 与 compose `AGENT_URI`

---

## 23. 未确认事项

- 本会话未执行 `mvn test` / 浏览器验收 → 测试与运行时行为 `UNVERIFIED`
- 某套部署是否真正 `EDGE_CASDOOR_MODE=only`、是否达网 AgentScope、是否挂 ES/Qdrant → `NEEDS_VERIFICATION`
- `agentscope-platform` 仓内实现细节 → `UNCONFIRMED`（不在本 workspace）
- `service_callback` 令牌经 edge 限流 `verify()` 是否 401 → 子扫描提出，本会话未读测试断言全文，标 `PARTIAL`
- 生产流量、租户数、QPS → 未知，不编

---

## 24. 证据索引摘要

完整条目见 `EVIDENCE_INDEX.md`。关键锚点：

- 模块与版本：根 `pom.xml`
- 换发与路由：`edge-gateway/.../application.yml`，`CasdoorTokenExchangeFilter`，`AgentCanaryRoutingFilter`
- 令牌：`platform-security/.../InternalToken.java`
- 对话编排：`ConversationController.chat`
- 检索：`KnowledgeQueryService.query`
- 入库：`IngestionJobWorker`，`V3__ingestion_recovery.sql`
- 审批：`WorkflowService`，`V1__workflow_platform_schema.sql`
- 模型：`GatewayChatModelFactory`
- 预算缺口：不存在 `TokenBudgetGuardFilter.java`
- CI：`.github/workflows/supply-chain.yml`
