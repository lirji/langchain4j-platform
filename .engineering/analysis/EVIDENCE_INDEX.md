# 证据索引

- workspace: `/Users/liruijun/personal/LLM/langchain4j-platform`
- generated_at: 2026-09-16
- protocol: project-deep-analysis/v1
- source_of_truth: 源码（README 仅线索）
- re_run_policy: REGENERATE_OWNED_ARTIFACT
- 可信度: CONFIRMED / HIGH_CONFIDENCE / PARTIAL / UNCONFIRMED

---

## E01 聚合器与运行时版本

| 字段 | 值 |
|---|---|
| 结论 | Java 21、Spring Boot 3.3.5、LangChain4j 1.13.1、25 个 Maven 模块 |
| 证据类型 | 构建文件 |
| 文件路径 | `pom.xml` |
| 配置项 | `java.version`、`langchain4j.version`、`<modules>` |
| 可信度 | CONFIRMED |

---

## E02 边缘三路换发与 Casdoor 默认

| 字段 | 值 |
|---|---|
| 结论 | filter 顺序 -130 service、-120 Casdoor、-110 session、-100 api-key；yml 默认 Casdoor enabled=true mode=only |
| 证据类型 | 配置 + 类 getOrder |
| 文件路径 | `edge-gateway/src/main/resources/application.yml`；`edge-gateway/src/main/java/com/lrj/platform/edge/CasdoorTokenExchangeFilter.java` |
| 配置项 | `EDGE_CASDOOR_ENABLED` `EDGE_CASDOOR_MODE` |
| 可信度 | CONFIRMED |
| 备注 | `CasdoorSecurityProperties` 字段默认 enabled=false/DUAL，与 yml 相反 |

---

## E03 内部 JWT 契约

| 字段 | 值 |
|---|---|
| 结论 | HS256 默认；claims 含 sub/uid/scopes/可选 dept/token_use；下游只验 X-Internal-Token |
| 证据类型 | 类 |
| 文件路径 | `platform-security/src/main/java/com/lrj/platform/security/InternalToken.java` |
| 类名 | `InternalToken` |
| 方法名 | `mint` `verify` |
| 调用链 | edge mint → `InternalTokenAuthFilter` → `TenantContext` |
| 可信度 | CONFIRMED |

---

## E04 出站租户与熔断

| 字段 | 值 |
|---|---|
| 结论 | RestTemplate 挂租户转发；http-resilience 默认开，自研 per-origin bulkhead+熔断，无 Resilience4j |
| 证据类型 | 自动配置 + 拦截器 |
| 文件路径 | `platform-security/.../PlatformSecurityAutoConfiguration.java`；`OutboundHttpResilienceInterceptor.java`；`OutboundTenantForwarder.java` |
| 配置项 | `platform.security.http-resilience.enabled` matchIfMissing true |
| 依赖 | 全仓无 Resilience4j 符号 |
| 可信度 | CONFIRMED |

---

## E05 单一 ChatModel

| 字段 | 值 |
|---|---|
| 结论 | 只构建 OpenAI 兼容客户端指向 LiteLLM；virtual-key 禁止回退 master key |
| 证据类型 | 类 |
| 文件路径 | `platform-gateway-client/.../GatewayChatModelFactory.java` |
| 类名 | `GatewayChatModelFactory` |
| 方法名 | `build` `buildDeterministic` `buildJsonMode` |
| 可信度 | CONFIRMED |

---

## E06 对话编排顺序

| 字段 | 值 |
|---|---|
| 结论 | 护栏 → 历史压缩 → 语义缓存 → RAG → LLM → grounding → 输出脱敏 |
| 证据类型 | Controller |
| 文件路径 | `conversation-service/.../ConversationController.java` |
| 方法名 | `chat` |
| 可信度 | CONFIRMED |

---

## E07 语义缓存 Redis 模型

| 字段 | 值 |
|---|---|
| 结论 | 每租户一个 Hash，HVALS 后应用侧余弦；TTL 默认 0 |
| 证据类型 | 类 + 配置 |
| 文件路径 | `conversation-service/.../cache/RedisSemanticCacheStore.java` |
| 配置项 | `app.conversation.semantic-cache.store=redis`；`redis.ttl` 默认 0s |
| 可信度 | CONFIRMED |

---

## E08 混排检索

| 字段 | 值 |
|---|---|
| 结论 | 向量 + 内存关键词 + ES + 图谱；并行执行器传递 TenantContext；融合后版本过滤与 ReBAC |
| 证据类型 | 编排类 |
| 文件路径 | `knowledge-service/.../KnowledgeQueryService.java`；`search/ParallelRetrievalExecutor.java` |
| 方法名 | `query` `retrieve` |
| 可信度 | CONFIRMED |

---

## E09 入库恢复与版本门闩

| 字段 | 值 |
|---|---|
| 结论 | V3 增加租约与重试列；registry commit 后查询才可见 |
| 证据类型 | SQL + Worker |
| 文件路径 | `database-migrations/.../knowledge-ingestion/V3__ingestion_recovery.sql`；`IngestionJobWorker.java`；`RedisDocumentRegistry` |
| 数据库表 | `KNOWLEDGE_INGESTION_JOB` |
| 可信度 | CONFIRMED |

---

## E10 ReBAC 默认关

| 字段 | 值 |
|---|---|
| 结论 | `RAG_AUTHZ_MODE` 默认 disabled；enforce fail-closed；shadow 依赖失败 fail-open；public KB 默认 true |
| 证据类型 | yml + RealKnowledgeAuthz |
| 文件路径 | `knowledge-service/src/main/resources/application.yml`；`knowledge/.../authz/RealKnowledgeAuthz.java` |
| 配置项 | `app.rag.authz.mode` `app.rag.public.enabled` |
| 可信度 | CONFIRMED |

---

## E11 工作流幂等与 Outbox

| 字段 | 值 |
|---|---|
| 结论 | 幂等 PK；HTTP outbox 与 Kafka 终态 outbox 分表；claim 租约 |
| 证据类型 | SQL + Service |
| 文件路径 | `database-migrations/.../workflow/V1__workflow_platform_schema.sql`；`WorkflowService.java`；`WorkflowOutboxDispatcher.java` |
| 数据库表 | `WF_IDEMPOTENCY` `WF_OUTBOX` `WF_TERMINAL_EVENT_OUTBOX` `WF_REPLY` |
| 可信度 | CONFIRMED |

---

## E12 Kafka 默认不在主路径

| 字段 | 值 |
|---|---|
| 结论 | eventbus 默认 false；workflow 终态默认 local |
| 证据类型 | 配置 |
| 文件路径 | `platform-eventbus/.../EventbusProperties.java`；`workflow-service/.../application.yml` |
| 配置项 | `platform.eventbus.enabled`；`app.workflow.terminal-notification.mode` |
| 类名 | `WorkflowTerminalKafkaListener` 仅 eventbus 开启时有意义 |
| 可信度 | CONFIRMED |

---

## E13 Agent 生产切流

| 字段 | 值 |
|---|---|
| 结论 | compose 默认 AGENT_URI=agentscope-orchestrator:8085；Java agent-service 回滚；canary filter 改写允许租户 |
| 证据类型 | compose + Filter |
| 文件路径 | `deploy/docker-compose.yml`；`edge-gateway/.../AgentCanaryRoutingFilter.java`；`edge-gateway/.../application.yml` `EDGE_AGENT_BASELINE_BACKEND` |
| 可信度 | CONFIRMED（AgentScope 实现 UNCONFIRMED） |

---

## E14 高风险 Agent 动作默认关

| 字段 | 值 |
|---|---|
| 结论 | code_exec / mcp / browser yml 默认 false |
| 证据类型 | 配置 + ConditionalOnProperty |
| 文件路径 | `agent-service/src/main/resources/application.yml` |
| 类名 | `CodeExecAction` `McpToolAction` `BrowserOpenAction` |
| 可信度 | CONFIRMED |

---

## E15 Token 预算无预检 Filter

| 字段 | 值 |
|---|---|
| 结论 | Listener 在 onResponse 记账；不存在 TokenBudgetGuardFilter 源文件 |
| 证据类型 | 类存在性 + Javadoc |
| 文件路径 | `platform-metering/.../TokenBudgetChatModelListener.java`；`TokenBudgetTracker.java`（Javadoc 仍引用 GuardFilter） |
| 可信度 | CONFIRMED |

---

## E16 NL2SQL 护栏与只读库

| 字段 | 值 |
|---|---|
| 结论 | SqlGuard 仅 SELECT；init SQL 只授 nl2sql_ro SELECT |
| 证据类型 | Java + SQL |
| 文件路径 | `analytics-service/.../SqlGuard.java`；`deploy/mysql/init/001-platform-databases.sql` |
| 数据库 | `nl2sql_demo` |
| 可信度 | CONFIRMED |

---

## E17 税票规则权威

| 字段 | 值 |
|---|---|
| 结论 | 规则引擎先审；RAG/AI 失败降级 |
| 证据类型 | Service |
| 文件路径 | `tax-service/.../TaxInvoiceReviewService.java`；`TaxInvoiceRuleEngine.java`；`HttpTaxKnowledgeClient.java` |
| 可信度 | CONFIRMED |

---

## E18 拆库

| 字段 | 值 |
|---|---|
| 结论 | 本地 init 按上下文建库与 app/migrator 用户 |
| 证据类型 | SQL |
| 文件路径 | `deploy/mysql/init/001-platform-databases.sql` |
| 数据库 | auth async_task flowable knowledge_graph knowledge_ingestion order_service channel nl2sql_demo |
| 可信度 | CONFIRMED |

---

## E19 CI 测试范围

| 字段 | 值 |
|---|---|
| 结论 | PR/main 跑 `mvn -B -DskipITs test` 后 package；本分析会话未本地执行 |
| 证据类型 | workflow |
| 文件路径 | `.github/workflows/supply-chain.yml` |
| 可信度 | CI 定义 CONFIRMED；本机结果 UNCONFIRMED |

---

## E20 入站 deadline

| 字段 | 值 |
|---|---|
| 结论 | servlet 注册 RequestDeadlineFilter；出站拦截器取 min(继承, 本地) |
| 证据类型 | Filter + Interceptor |
| 文件路径 | `RequestDeadlineFilter.java`；`OutboundHttpResilienceInterceptor.deadline` |
| 可信度 | CONFIRMED |

---

## E21 开放路径

| 字段 | 值 |
|---|---|
| 结论 | actuator、well-known、auth login 族、飞书/钉钉 events 免业务鉴权；open path 用 _platform/edge-gateway identity |
| 证据类型 | 类 |
| 文件路径 | `edge-gateway/.../EdgeOpenPaths.java`；`ApiKeyToInternalTokenFilter.java` |
| 可信度 | CONFIRMED |

---

## E22 异步任务表

| 字段 | 值 |
|---|---|
| 结论 | ASYNC_TASK 含 lease 列；webhook/lifecycle outbox |
| 证据类型 | SQL |
| 文件路径 | `database-migrations/.../async-task/V1__async_task_schema.sql` |
| 可信度 | CONFIRMED |

---

## 统计

| 可信度 | 条数 |
|---|---|
| CONFIRMED | 21 |
| PARTIAL | 见主报告 service_callback vs 限流 |
| UNCONFIRMED | AgentScope 内部；本机测试 |

高风险结论均落在 E02/E03/E10/E13/E15。
