# 架构文档

## 架构目标

项目目标是把原单体 `LangChain4j_project` 拆成按限界上下文自治的微服务平台，同时保留统一的安全、协议、模型网关、审计、计量和可观测基础设施。

核心原则：

- 业务 API 统一从 `edge-gateway` 进入。
- 下游服务只信任内部 JWT，不直接信任外部 API key。
- 所有 LLM 调用统一走 LiteLLM/OpenAI-compatible 网关（Java 侧只有唯一一个 `ChatModel`）。
- 跨服务 DTO 放在 `platform-protocol`。
- 可独立演进的状态边界拆成独立服务。
- 共享能力沉淀为 `platform-*` 共享库，而不是重新堆回单体。
- 新能力一律「接口 + `@ConditionalOnProperty` 多实现」，默认内存/Noop、零外部依赖，生产按需开关。

## 总体拓扑

```text
Client
  |
  | X-Api-Key
  v
edge-gateway :8080  (Spring Cloud Gateway / WebFlux)
  |  校验 X-Api-Key → 换发短时内部 JWT(X-Internal-Token) → 按路径路由 → 租户级限流
  |
  +--> conversation-service :8081   (/chat, /chat/**)
  +--> workflow-service     :8082   (/workflow, /workflow/**)
  +--> analytics-service    :8083   (/chat/sql, /analytics, /analytics/**)
  +--> knowledge-service    :8084   (/rag, /rag/**, /knowledge, /knowledge/**)
  +--> agent-service        :8085   (/agent, /agent/**)
  +--> async-task-service   :8086   (/async, /async/**)
  +--> channel-service      :8087   (/channel, /channel/**)
  +--> interop-service      :8088   (/interop, /interop/**, /.well-known/agent-card.json)
  +--> eval-service         :8089   (/eval, /eval/**)
  +--> vision-service       :8090   (/vision, /vision/**)

config-server :8888  (Spring Cloud Config，可选，各服务 optional import)

各服务的 LLM 调用统一 ▼ (OpenAI-compatible API)
LiteLLM :4000
  |
  +--> Ollama / OpenAI / Anthropic / 其它 provider  (provider 路由 + failover + 模型名映射)

各服务的终态/生命周期事件 ▼ (仅 platform.eventbus.enabled=true)
Kafka  ──▶ channel-service KafkaListener ──▶ 渠道回推
```

> 路由前缀、端口、api-key 目录均以 `edge-gateway/src/main/resources/application.yml` 为准；下游端口以各服务 `application.yml` / `docker-compose.yml` 为准。

## 两层网关

平台刻意分成两层网关，职责正交：`edge-gateway` 管**业务流量**，LiteLLM 管**模型流量**。

### edge-gateway（:8080）

Spring Cloud Gateway（WebFlux，唯一 reactive 服务），是唯一对外入口。职责：

- 对外统一暴露业务 API，按 `Path` 谓词路由到各服务（路由表见 `application.yml` 的 `spring.cloud.gateway.routes`）。
- 校验 `X-Api-Key`；命中 `platform.security.api-keys` 目录则解析出 tenant/user/scopes，否则直接 401。
- 用 `platform-security` 的 `InternalToken` 签发短时内部 JWT，写入 `X-Internal-Token` 头转发下游，并**从转发请求里剥掉 `X-Api-Key`**（不把外部凭据泄进内网）。签发端是 `ApiKeyToInternalTokenFilter`（`GlobalFilter`）。
- 租户级限流：`EdgeRateLimitFilter`，由 `app.rate-limit.*` 配置。`enabled=true`（本地默认开），`store=in-memory`（可选 redis，见 `RateLimiterRegistry` 多实现），按动作类别配额（`chat:60 / agent:20 / stream:20 / ingest:5 / eval:5 / default:120`），匿名请求按 `anonymous-multiplier=0.2` 打折。
- 放行免鉴权路径：actuator、A2A 发现别名 `/.well-known/agent-card.json`（见 `ApiKeyToInternalTokenFilter.isOpen`）。

edge-gateway 自身也接入 config-server（`spring.config.import=optional:configserver:${CONFIG_SERVER_URI:http://localhost:8888}`），不可达时不阻断启动。

### LiteLLM（:4000，外部，非 Java 模块）

配置在 `deploy/litellm/config.yaml`。职责：

- 对业务服务暴露统一的 OpenAI-compatible API。
- provider 路由、fallback、模型名映射。

**这就是 Java 代码里没有任何 provider `switch` 的原因**——provider/model 选择整体下沉到 LiteLLM。

## 唯一 ChatModel 与 ChatModelListener 横切

- **全局只有一个 `ChatModel` bean。** `platform-gateway-client` 的 `GatewayChatModelFactory` 构建指向 LiteLLM base-url 的 `OpenAiChatModel`（配置前缀见 `GatewayClientProperties`，如 base-url / api-key / model-name / temperature / timeout / max-retries）。`buildDeterministic()` 是 temp=0 变体，供 Judge / critic / 确定性任务使用。
- **所有横切关注点挂在 `ChatModelListener` SPI 上**，由工厂把 Spring 上下文里发现的所有 `ChatModelListener` 一并灌进模型：
  - `platform-audit` → `AuditChatModelListener`：模型调用审计。
  - `platform-metering` → `CostChatModelListener`（成本归因）+ `TokenBudgetChatModelListener`（按租户 token 预算）。
- 这样审计/计量/成本对业务代码透明——不改一行调用点，只要 listener 在 classpath 就自动生效。
- cascade（cheap→置信门→strong）变体在 `platform-gateway-client/.../cascade/` 内产出，但**不暴露成第二个 `ChatModel` bean**，以避开 langchain4j `@AiService` 的自动发现冲突。
- 应用侧遵循「LLM 始终藏在可 mock 的接口后」：controller/service 里绝不直接调 `ChatModel`，走 `@AiService` 接口或 `AiServices.builder(...)`。

## 安全模型与内部 JWT 跨跳

外部请求：

1. 调用方提供 `X-Api-Key`。
2. `edge-gateway` 校验 key，解析出 tenant/user/scopes。
3. gateway 用 `InternalToken.mint(...)` 签发短时 JWT（claims：`sub=tenantId` / `uid=userId` / `scopes`），放到 `X-Internal-Token`。
4. 下游服务的 `InternalTokenAuthFilter`（`platform-security`）校验签名 + 过期，重建 `TenantContext.Tenant` 写入 `TenantContext`（ThreadLocal；测试须在 `@AfterEach` 中 `TenantContext.clear()`）。
5. 服务内部用 `TenantContext` 做租户隔离和 scope 判断。

内部 JWT 算法（`platform.security.jwt.*`，见 `InternalToken` / `InternalSecurityProperties`）：

- **HS256（默认）**：共享对称密钥 `platform.security.jwt-secret`（≥32 字节，否则 jjwt 快速失败）。签发/验签同一 secret，dev/test 零配置沿用。
- **RS256（可选）**：`platform.security.jwt.algorithm=RS256`。edge-gateway 持 `platform.security.jwt.private-key`（PKCS#8 PEM 或纯 base64）签发，下游只持 `platform.security.jwt.public-key`（X.509）验签——缩小密钥轮转的爆炸半径。纯签发节点可不配公钥，纯验签节点可不配私钥。

其它安全配置：`platform.security.jwt-ttl`（默认 5 分钟，仅覆盖一次调用链）、`internal-header`（默认 `X-Internal-Token`）、`api-key-header`（默认 `X-Api-Key`）、`allow-api-key-fallback`（默认 `true`，下游也接受直连 `X-Api-Key` 便于本地调试；生产可关，只信 JWT）。

跨服务请求：

- 跨服务调用用装了拦截器的 `RestTemplate`（见 `agent-service/.../AgentConfig.java` 等）：
  - `OutboundTenantForwarder`（`platform-security`）：把当前 `X-Internal-Token` 透传到下一跳。
  - `OutboundTraceForwarder`（`platform-observability`）：透传 traceId。
- 于是租户身份与 trace 随 JWT 跨**每一次**网络跳传播，下游各自还原进各自的 `TenantContext` / MDC。

## 事件总线（platform-eventbus）与终态事件流

`platform-eventbus` 是 B1 引入的共享库，承载工作流终态、异步任务生命周期等业务事件的跨服务分发。

### EventPublisher SPI 与装配

- `EventPublisher` 接口：`publish(topic, key, payload)`，约定 `key=tenantId` 保证同租户有序。
- 默认 `NoopEventPublisher`（仅 debug log，零 Kafka 依赖）——`platform.eventbus.enabled=false`（默认）。
- 开 `platform.eventbus.enabled=true` 且 classpath 有 Kafka → 切 `KafkaEventPublisher`，并装配幂等生产者 / DLT 消费容器工厂（`PlatformEventbusAutoConfiguration`）。
- 消费幂等去重存储 `ProcessedEventStore`：`platform.eventbus.processed-event-store=memory`（默认）或 `jdbc`（跨重启去重）。
- topic 常量集中在 `platform-protocol` 的 `EventTopics`，每域一个主 topic + `.DLT` 死信：`platform.workflow.terminal`、`platform.asynctask.lifecycle`、`platform.audit.events`、`platform.metering.usage`。

### 一致性形态：effective exactly-once

跨 DB→Kafka→HTTP 链路，平台采用**事务性 outbox + 幂等 relay + 消费侧 eventId 去重**，达到 effective exactly-once（不是纯 Kafka 原生事务）：

1. **写侧事务性**：业务终态与 outbox 行写在**同一 DB 事务**内（终态提交 ⇔ outbox 有 PENDING 行，消除提交后崩溃丢事件的窗口）。
2. **投侧至少一次**：定时 relay（`@Scheduled`）扫到期 PENDING 行 → `EventPublisher.publish`（同步等 broker ack）→ 成功标 DELIVERED；失败按退避重投，耗尽进 DEAD。
3. **收侧幂等**：消费者「先查 `isProcessed` → 处理 → **成功后**标记」——回推抛异常则不标记、重投再处理（不丢）；重复投递被 `isProcessed` 跳过（去重）。同一 eventId 恒落同一分区、单分区串行消费，故无并发竞态。

平台在 **workflow** 与 **async-task** 两侧对称落地了这套形态。

### workflow 终态事件流（`app.workflow.terminal-notification.mode`）

终态通知三种模式（默认 `local`）：

- `local`（默认）：用 workflow 自己的 `WF_OUTBOX` + `WorkflowOutboxDispatcher` 走 HTTP webhook 投递（旧路径，可回退）。
- `async-task`：终态写入 async-task-service，由共享任务中心的 webhook outbox 负责投递。
- `kafka`（B1b）：走事件总线。链路：
  - `WorkflowTerminalOutboxListener`：BPMN `end` 事件的 `ExecutionListener`，在 **Flowable 引擎命令的同一事务**内把终态事件写入 `WorkflowTerminalEventOutbox`（同一 `workflowDataSource`，与 `ACT_*` 终态 + `WF_REPLY` 原子提交）。始终装配（`app.workflow.enabled=true`），仅 kafka 档落库、其它档 no-op。
  - `WorkflowTerminalEventRelay`（`@Scheduled`，仅 kafka 档）：扫到期行，从 `WorkflowReplyStore` 取 reply 重建 `WorkflowTerminalMessage`，发往 `EventTopics.WORKFLOW_TERMINAL`（key=tenantId），成功标 DELIVERED，失败退避重投 / 进 DEAD，并写审计（`WORKFLOW_PUSH_DELIVERED/FAILED/DEAD`）。eventId 稳定为 `workflow:<instanceId>`。
  - `channel-service` 的 `WorkflowTerminalKafkaListener`（仅 `platform.eventbus.enabled=true`）：`@KafkaListener` 消费 → `ProcessedEventStore` 去重 → `ChannelCallbackService.handleCallback(...)` 回推（与 HTTP `/channel/callbacks/workflow` 同一 accept 逻辑，双通道幂等一致）。

### async-task 生命周期事件流

- JDBC 档（`ASYNC_TASK_STORE=jdbc`）下，`JdbcAsyncTaskStore.update` 在终态更新的**同一事务**内写 `ASYNC_TASK_LIFECYCLE_OUTBOX`（`AsyncTaskLifecycleOutbox`）。
- `AsyncTaskLifecycleRelay`（`@Scheduled`，`store=jdbc + transport=kafka`）relay 到 `EventTopics.ASYNCTASK_LIFECYCLE`（key=tenantId）。
- `AsyncTaskKafkaNotifier`（提交后 `@EventListener` 直发）在存在事务性 outbox 时**让位**（避免双投）；仅 `store=memory` 的 dev/test 退化为提交后 best-effort 直发。
- `channel-service` 的 `AsyncTaskLifecycleKafkaListener` 消费 → 去重 → 回推（与 HTTP `/channel/callbacks/async-task` 同一逻辑）。

> 审计/用量事件（`platform.audit.events` / `platform.metering.usage`）为 fire-and-forget，可丢，不进事务性 outbox。

## 集中配置（config-server）

`config-server`（Spring Cloud Config Server，:8888，可用 `CONFIG_SERVER_PORT` 覆盖）集中下发**非密文**配置：

- 后端 profile：默认 `native`（读打包进 jar 的 `classpath:/config/`，可用 `CONFIG_SERVER_NATIVE_SEARCH_LOCATIONS` 指向本地目录，dev/无 git 即开即用）；`SPRING_PROFILES_ACTIVE=git` 切 git 后端（`SPRING_CLOUD_CONFIG_SERVER_GIT_URI` 等）。
- 各业务服务用 `spring.config.import=optional:configserver:${CONFIG_SERVER_URI:...}` 接入——**optional**：config-server 不可达时不阻断启动，各服务 `${ENV:default}` 兜底继续生效（避免成为启动单点）。
- 密钥不走 config-server：仍由各服务 `${ENV:default}` / Vault / K8s Secret 承载。

## 服务边界

| 服务 | 端口 | 边界职责 | 主要状态 |
|---|---:|---|---|
| `conversation-service` | 8081 | Chat API、可选 RAG prompt 增强、可选语义缓存 L1 | 对话请求本身暂无独立持久化 |
| `workflow-service` | 8082 | Flowable 退款审批流程 | Flowable MySQL、reply store、workflow outbox / 终态事件 outbox；终态通知可切 async-task 或 kafka |
| `analytics-service` | 8083 | NL2SQL / ChatBI | demo SQL DB、只读查询连接 |
| `knowledge-service` | 8084 | RAG 文档、向量、GraphRAG、图片多模态 ingestion | vector store、document registry、graph store |
| `agent-service` | 8085 | Agent / DAG 编排、chaining / voting / reflexion / cascade 等编排器 | 本地 async task store，可镜像 / 权威化到 async-task-service |
| `async-task-service` | 8086 | 通用异步任务中心、SSE 断点续订、webhook outbox | in-memory 或 JDBC task/outbox，delivered outbox retention |
| `channel-service` | 8087 | 渠道 ACL、webhook/Feishu/voice 出站、async-task/workflow callback（HTTP + Kafka 双通道）、入站签名校验 | 当前主要无状态 |
| `interop-service` | 8088 | A2A / MCP 对外互操作面，代理下游 agent/knowledge/analytics 能力 | 当前主要无状态 |
| `eval-service` | 8089 | 回归评测执行、可选 embedding / LLM-judge 比较 | baseline 文件、可选 report 输出 |
| `vision-service` | 8090 | 多模态 caption/describe（`/vision/caption`、`/vision/describe`，JSON + multipart） | 当前主要无状态 |
| `edge-gateway` | 8080 | 边缘路由、安全（api-key→JWT）、租户限流 | api-key 配置、限流计数 |
| `config-server` | 8888 | 集中配置下发（可选） | git / native 配置源 |

## 共享库

| 模块 | 作用 |
|---|---|
| `platform-security` | `TenantContext`、内部 JWT 签发/校验（HS256/RS256）、下游入站 filter、出站 token 透传、限流 registry |
| `platform-observability` | `TraceIdFilter`、跨服务 traceId 透传（`OutboundTraceForwarder`） |
| `platform-gateway-client` | 唯一 `ChatModel` 工厂（指向 LiteLLM）+ listener 灌入 + cascade 变体 |
| `platform-protocol` | 跨服务 DTO / 协议对象、事件契约（`event.*`、`EventTopics`） |
| `platform-audit` | 审计事件、`AuditChatModelListener` |
| `platform-metering` | token budget、cost 归因、`CostChatModelListener` + `TokenBudgetChatModelListener` |
| `platform-eventbus` | `EventPublisher` SPI（Noop 默认 / Kafka 变体）、`ProcessedEventStore` 去重（memory/jdbc）、Kafka 生产/消费基础设施 |

共享库均无主类，通过 `META-INF/spring/...AutoConfiguration.imports` 自注册。

## 接口 + `@ConditionalOnProperty` 多实现惯用法

存储/传输/provider 几乎都是「一个接口 + 内存/Noop 默认实现 + 由属性开启的 Http/Kafka/Redis/Jdbc 变体」，默认零外部依赖：

- 传输/发布：`EventPublisher`（Noop/Kafka）、`ChannelMessageDispatcher`、`ChannelEventPublisher`。
- 存储：`EmbeddingStore` / `EmbeddingModel`（in-memory hash / qdrant / pgvector / milvus / chroma / doris；OpenAI-compat / Ollama embedding）、`MultimodalEmbeddingModel`（图片 CLIP，默认关）、`GraphStore`（内存/JDBC）、`AsyncTaskStore`（内存/JDBC）、`ProcessedEventStore`（内存/JDBC）、`RateLimiterRegistry`（内存/Redis）、文档 registry（内存/Redis）。
- 持久化无 JPA/ORM、无 Flyway/Liquibase：裸 `JdbcTemplate` 直连 MySQL，表结构演进靠 `Jdbc*Store` 里的 `CREATE TABLE IF NOT EXISTS` / `ALTER TABLE ADD COLUMN` 字面量。JDBC 存储可选开启（`ASYNC_TASK_STORE=jdbc`、`RAG_GRAPH_STORE=jdbc` 等），默认内存。Flowable 在同一数据源自管其表。

## 典型调用链

### Chat with RAG

```text
Client
  -> edge-gateway /chat
  -> conversation-service
  -> (可选) knowledge-service /rag/query
  -> conversation-service 组装增强 prompt
  -> LiteLLM
  -> model provider
```

### Agent 访问知识库和数据分析

```text
Client
  -> edge-gateway /agent/run
  -> agent-service
     -> knowledge-service /rag/query       (rag_search)
     -> analytics-service /analytics/sql   (analytics_sql, typed AnalyticsSqlRequest/Reply)
     -> LiteLLM                            (reasoning / synthesis)
```

### Async Agent with Shared Task Center

```text
Client
  -> edge-gateway /agent/run/async
  -> agent-service
     -> async-task-service /async/tasks             (mirror 或 authoritative)
     -> async-task-service /async/tasks/{id}/lease  (authoritative 模式)
     -> async-task-service /async/tasks/{id}/status
     -> async-task-service /async/tasks/{id} DELETE (authoritative 取消)
Client
  -> /agent/tasks/{id}/stream 或 /async/tasks/{id}/stream
```

### 终态事件回推（kafka 档）

```text
workflow-service 终态提交 (Flowable 同事务写 WF_TERMINAL_EVENT_OUTBOX)
  -> WorkflowTerminalEventRelay @Scheduled
  -> EventPublisher.publish(platform.workflow.terminal, key=tenantId)
  -> Kafka
  -> channel-service WorkflowTerminalKafkaListener  (ProcessedEventStore 去重)
  -> ChannelCallbackService 回推渠道

async-task-service 终态更新 (JDBC 同事务写 ASYNC_TASK_LIFECYCLE_OUTBOX)
  -> AsyncTaskLifecycleRelay @Scheduled
  -> EventPublisher.publish(platform.asynctask.lifecycle, key=tenantId)
  -> Kafka
  -> channel-service AsyncTaskLifecycleKafkaListener  (去重)
  -> ChannelCallbackService 回推渠道
```

### Knowledge Ingestion

```text
Client
  -> edge-gateway /rag/documents
  -> knowledge-service
     -> Tika 文本抽取（文本文件）/ CLIP 多模态 embedding（图片，默认关）
     -> splitter
     -> embedding model
     -> embedding store（文本进 knowledge_segments，图片进 knowledge_images）
     -> document registry
     -> 可选 GraphRAG 三元组 ingestion
```

## 数据与存储边界

| 数据 | 当前存储 |
|---|---|
| RAG 向量 | in-memory 或 Qdrant |
| 文档 registry | in-memory 或 Redis |
| GraphRAG triples | in-memory 或 JDBC/MySQL |
| Workflow state | Flowable MySQL |
| Workflow outbox/reply/终态事件 outbox | workflow datasource |
| Async task | in-memory 或 JDBC/MySQL |
| Async webhook outbox / 生命周期 outbox | JDBC 模式下 MySQL |
| 事件消费去重（ProcessedEventStore） | in-memory 或 JDBC/MySQL |
| NL2SQL demo 数据 | MySQL |
| 限流计数 | in-memory 或 Redis |
| Eval reports | 可选本地目录 |

## 部署视图

`deploy/docker-compose.yml` 提供本地一体化环境：`mysql`、`redis`、`qdrant`、`kafka`、`litellm` + 各业务服务。

Phase E 已产出 Helm 伞状 chart（library chart 复用 Deployment/Service/HPA，密钥走 External Secrets、依赖接外部托管、服务发现用 k8s Service DNS）。生产化时把外部依赖拆成托管服务，并明确每个服务的数据库、连接池、密钥和资源限额；多副本部署需把 async-task/agent 从默认内存态切到 `ASYNC_TASK_STORE=jdbc`（+ authoritative）以免丢任务/SSE。

## 当前架构风险与后续演进

- 事件总线为 effective exactly-once（事务性 outbox + at-least-once 投递 + 消费侧幂等去重）；纯 Kafka 原生事务的 `transactional-id-prefix` 开关已预留但默认不开。
- config-server 为 optional import，非启动硬依赖；密钥仍在服务侧 `${ENV:default}` / Secret，未收拢进配置中心。
- `INTERNAL_JWT_SECRET`（HS256 档）是全服务共享对称密钥，一处坏配置会引发全链 401；RS256 档可缩小轮转爆炸半径，但需配发 keypair。
- channel 的 voice 已支持通用 HTTP provider，后续仍可接具体厂商 SDK。
- GraphRAG 当前是确定性三元组，后续可接入 LLM/IE 抽取和图数据库。
- 图片 ingestion 走原生 CLIP 多模态 embedding（默认关，`RAG_MULTIMODAL_ENABLED`），另有独立 vision-service 供 agent 复用；更完整托管视觉/多模态 embedding 仍可继续扩展。
- interop 的 A2A `message/stream` 目前为轮询降级（conversation 无真流式），push 通知中继待接总线。
