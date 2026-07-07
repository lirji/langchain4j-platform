# 能力文档

## 一句话概览

本项目是一个基于 Spring Boot、LangChain4j、LiteLLM 和 DDD 限界上下文拆分的企业级 AI 微服务平台。它把对话、语义缓存、模型级联、知识库 RAG/GraphRAG、NL2SQL、多种 Agent 编排模式（ReAct / DAG / 反思 / 投票 / 链式）、工作流审批、异步任务中心、渠道接入、A2A/MCP 互操作、多模态视觉和回归评测拆成独立服务，并通过两层网关、租户鉴权、事件总线、集中配置、共享协议和可观测能力连接起来。

## 访问方式与端口约定

- **唯一对外入口是 `edge-gateway`（:8080）**：校验 `X-Api-Key` → 签发短时内部 JWT（`X-Internal-Token`）→ 按路径路由到下游。下文所有"经网关"的业务端点都可以用 `http://localhost:8080` + api-key 访问。
- **服务直连端口**（本地调试/服务间调用）：conversation `:8081`、workflow `:8082`、analytics `:8083`、knowledge `:8084`、agent `:8085`、async-task `:8086`、channel `:8087`、interop `:8088`、eval `:8089`、vision `:8090`。
- **不经 edge-gateway 暴露**：`config-server`（:8888，集中配置基础设施，仅服务内部经 `spring.config.import` 拉取）。
- **默认开关基线**：绝大多数新能力**默认关闭 / 内存实现**，dev/test 零外部依赖。下文每项都标注了默认开关状态与开启用的环境变量。

## 核心能力矩阵

| 能力域 | 能力（一句话） | 关键端点 / 载体 | 默认开关 |
|---|---|---|---|
| 边缘网关 | API key 鉴权、租户识别、内部 JWT 签发、服务路由、限流 | `edge-gateway`（:8080） | 常开 |
| 内部 JWT | 跨服务身份令牌，支持 HS256（默认）或 RS256 非对称签发/验签 | `platform-security` | HS256（默认）；RS256 需配 keypair |
| LLM 网关 | 所有模型调用统一走 OpenAI 兼容端点，provider 路由/failover 在 LiteLLM | `platform-gateway-client` + LiteLLM | 常开 |
| 集中配置 | Spring Cloud Config Server，native（默认）/git 后端，`optional:` 接入不阻断启动 | `config-server`（:8888） | native 后端默认可用；git 需 `SPRING_PROFILES_ACTIVE=git` |
| 事件总线 | EventPublisher SPI，Noop 默认 / Kafka 变体，幂等生产 + 消费去重（effective exactly-once） | `platform-eventbus` | `platform.eventbus.enabled=false`（默认 Noop） |
| 对话 | `/chat` 入口，可选 RAG 上下文增强 | `/chat`（conversation :8081） | RAG 增强 `CONVERSATION_RAG_ENABLED=false` |
| 语义缓存 L1 | 问题级 / 租户桶 / pre-RAG 语义缓存，命中即短路 RAG+LLM | `/chat` 内部旁路 | `CONVERSATION_SEMANTIC_CACHE_ENABLED=false` |
| 模型级联 | 便宜模型先答、低置信才升级强模型 | `/chat/cascade`（conversation） | `CHAT_CASCADE_ENABLED=false` |
| 知识库 RAG | 文档上传、Tika 抽取、分块、向量 + keyword hybrid 检索、可配置排序权重 | `/rag/documents/**`、`/rag/query`（knowledge :8084） | 上传/查询常开；hybrid `RAG_HYBRID_ENABLED=true` |
| 多租户隔离 | collection-per-tenant 强隔离（每租户独立 collection/namespace） | knowledge 向量库 | `RAG_VECTOR_STORE_ISOLATION=collection-per-tenant`（默认） |
| 向量存储 | in-memory 默认；Qdrant 可选持久化 | knowledge | `RAG_VECTOR_STORE_PROVIDER=in-memory`（默认） |
| Embedding | 确定性 hash（默认）/ OpenAI-compat（LiteLLM）/ Ollama | knowledge | `RAG_EMBEDDING_PROVIDER=hash`（默认） |
| GraphRAG | 确定性三元组抽取、实体链接、邻居查询、可选融合到 `/rag/query` | `/rag/graph/query`、`/rag/graph/entities` | `RAG_GRAPH_ENABLED=false` |
| 图谱持久化 | in-memory 或 JDBC/MySQL | knowledge | `RAG_GRAPH_STORE=in-memory`（默认） |
| NL2SQL / ChatBI | 自然语言转 SQL、只读查询保护 | `/chat/sql`、`/analytics/sql`（analytics :8083） | 常开 |
| 工作流审批 | Flowable 退款审批流、人工审批、超时、终态通知 | `/workflow/**`（workflow :8082） | 常开；终态通知模式可切 async-task |
| 深度 Agent（ReAct） | 单次/异步 ReAct loop，rag/sql/time/delegate 等动作 | `/agent/run`、`/agent/run/async`（agent :8085） | `AGENT_ENABLED=true` |
| DAG 多 Agent | 显式 DAG / 自动规划 DAG / 异步，拓扑分层并行，可选 critique+replan | `/agent/dag/run[/async]`、`/agent/dag/plan-run[/async]` | 常开；replan `AGENT_DAG_REPLAN_ENABLED=false` |
| 链式 Agent | 顺序步骤 + 步间确定性 gate + 不过即短路 | `/agent/chain` | 端点常开；`steps` 默认空需先配置 |
| 投票 Agent | 同题并行 N 次，majority 或 synthesis 取共识 | `/agent/vote` | 端点常开（N=3，majority） |
| 反思 Agent | 单答案 answer→critique→improve 自省环，加权聚合达阈值即停 | `/agent/reflexive[/stream]` | 端点常开 |
| Agent 工具 | 可选受限代码执行、MCP client、无头浏览器动作、视觉看图 | agent 动作注册表 | 全部默认关闭 |
| 异步任务中心 | 通用任务状态、租户隔离、取消、SSE 断点续订、worker lease、webhook outbox | `/async/tasks/**`、`/async/webhook-outbox/dead`（async-task :8086） | 常开；JDBC 存储 `ASYNC_TASK_STORE=in-memory`（默认） |
| 渠道接入 | 渠道能力、webhook/Feishu/voice 出站、async-task/workflow callback、入站事件、HMAC 签名、可选 Kafka event | `/channel/**`（channel :8087） | 出站/签名/Kafka event 全默认关 |
| 互操作（A2A/MCP） | 真 A2A JSON-RPC task 协议、agent-card、MCP tool surface、可选 live discovery | `/interop/**`、`/interop/a2a`、`/.well-known/agent-card.json`（interop :8088） | 常开；live discovery `INTEROP_DISCOVERY_ENABLED=false` |
| 回归评测 | HTTP case/suite 执行、双跑门禁、contains/JSON-path/semantic/oracle 断言 | `/eval/**`（eval :8089） | 常开；judge/embedding 比较默认关 |
| 多模态视觉 | 图片 caption/描述（多模态 ChatModel 经 LiteLLM），供 knowledge/agent 复用 | `/vision/caption`、`/vision/describe`（vision :8090） | `VISION_ENABLED=false`（默认整服务不装配） |
| 审计与计量 | 审计日志、LLM audit listener、token budget、cost attribution | `platform-audit`、`platform-metering` | audit/budget 常开、cost 默认关 |
| 可观测性 | trace id 生成与跨服务透传 | `platform-observability` | 常开 |

## 平台基建

### 两层网关

- **`edge-gateway`（:8080）** —— 唯一对外入口。校验 `X-Api-Key`，按绑定关系识别租户/用户/scope，签发短时内部 JWT（`X-Internal-Token`），按路径路由。路由表覆盖 conversation/analytics/workflow/knowledge/agent/async-task/channel/interop/eval/vision，并对 `/.well-known/agent-card.json` 放行给 interop。
- **LiteLLM**（外部，非 Java 模块） —— LLM 网关。所有模型调用统一走一个 OpenAI 兼容端点，provider 路由/failover/模型名映射都在 LiteLLM 配置里，Java 侧没有任何 provider `switch`。

### 内部 JWT（HS256 / RS256）

- `platform-security` 的 `InternalToken` 负责签发/校验内部 JWT，租户身份随其跨每一次网络跳传播，下游还原进 `TenantContext`。
- 算法可配（前缀 `platform.security.jwt.*`）：默认 `HS256`（对称，沿用 `INTERNAL_JWT_SECRET`，≥32 字节，保零配置 dev/test）；设 `platform.security.jwt.algorithm=RS256` 时 edge-gateway 用私钥签发、下游用公钥验签（PEM 或纯 base64，私钥 PKCS#8 / 公钥 X.509），缩小密钥轮转爆炸半径。
- 下游是否接受直连 `X-Api-Key`：`allow-api-key-fallback`（默认 true，本地调试用；生产可关只信 JWT）。

### 集中配置（config-server）

- 独立模块 `config-server`（:8888），Spring Cloud Config Server。
- 默认 `native` 后端：读打包进 jar 的 `classpath:/config/`（`config/application.yml` = 全服务共享默认，`config/<service>.yml` = 单服务覆盖），dev/无 git 环境即开即用。
- 可选 `git` 后端：`SPRING_PROFILES_ACTIVE=git` + `SPRING_CLOUD_CONFIG_SERVER_GIT_URI`，GitOps 库承载非密文配置；密钥仍走各服务 `${ENV:default}` / Vault / K8s Secret。
- 各业务服务用 `spring.config.import=optional:configserver:${CONFIG_SERVER_URI:...}` 接入 —— **optional 前缀保证 Config Server 不可达时不阻断启动**，`${ENV:default}` 兜底继续生效。

### 事件总线（platform-eventbus）

- 新共享库，`EventPublisher` SPI：默认 `NoopEventPublisher`（`platform.eventbus.enabled=false`，无任何 Kafka 依赖），开启后走 Kafka 变体。
- 生产侧幂等（`enable.idempotence`，默认开）+ 同步等 broker ack 再 `markDelivered`（供 outbox relay 避免误标已投）；可选事务性生产者（配非空 `platform.eventbus.producer.transactional-id-prefix` 启用事务）。
- 消费侧幂等去重存储：`processed-event-store=memory`（默认，重启失忆）| `jdbc`（跨重启）；失败重试超限进 `<topic>.DLT`。
- 端到端定位为 **effective exactly-once**（写侧事务性 outbox + 投侧 at-least-once + 收侧 eventId 幂等去重）。workflow 终态、async-task 生命周期均以「同事务写 outbox 行 → relay 到 Kafka」落地原子性，channel-service `@KafkaListener` 消费回推并做 eventId 去重。

### 审计、计量、可观测

- `platform-audit`：审计日志 + LLM audit `ChatModelListener`。
- `platform-metering`：按租户 token budget（默认开，内存）+ cost attribution（默认关）。actuator 暴露 `tokenbudget`、`cost` 端点。
- `platform-observability`：`TraceIdFilter` 生成并跨服务透传 traceId。

## 业务能力说明

### 1. 对话、语义缓存与模型级联

`conversation-service`：

- **`POST /chat`**：默认直接调用 LLM。开启 `CONVERSATION_RAG_ENABLED=true` 后先调 `knowledge-service` 的 `/rag/query`，把检索结果注入 prompt 再交给 LLM（`CONVERSATION_RAG_TOP_K`、`CONVERSATION_RAG_CATEGORY`、`CONVERSATION_RAG_MIN_SCORE` 等可调）。
- **L1 语义缓存**（默认关，`CONVERSATION_SEMANTIC_CACHE_ENABLED=true` 开启）：应用侧、问题级、按租户分桶、在 RAG 之前。命中即短路 RAG+LLM 直接返回。相似度阈值 `CONVERSATION_SEMANTIC_CACHE_THRESHOLD`（默认 0.95），embedding provider `hash`（默认）/`openai`，store `in-memory`（默认）/`redis`。
- **`POST /chat/cascade`** 模型级联（默认关，`CHAT_CASCADE_ENABLED=true` 开启，端点仅在开启时装配）：便宜模型先答，低置信才升级强模型。`CHAT_CASCADE_CHEAP_MODEL` / `CHAT_CASCADE_STRONG_MODEL` 为 LiteLLM 里的逻辑模型名（留空退化为网关默认模型），置信门 `CHAT_CASCADE_CONFIDENCE_THRESHOLD`（默认 0.6），可选 `CHAT_CASCADE_SELF_RATING`。返回体含 `served=cheap|strong`。cascade 变体 ChatModel 由 `platform-gateway-client` 产出。
- **`POST /conversation/workflow/reply`**、**`POST /conversation/workflow/ticket`**：给 workflow-service 用的回复生成 + 结构化工单抽取端点，让 workflow 经 HTTP 调用而不直连本地 ChatModel。

### 2. 知识库、RAG 与 GraphRAG

`knowledge-service`（`/rag/**`）：

- **文档 ingestion**：`POST /rag/documents`（JSON 文本或 multipart 文件）、`GET /rag/documents`、`GET /rag/documents/{docId}`、`DELETE /rag/documents/{docId}`。Apache Tika 抽取 PDF/Office/HTML/纯文本；支持 Markdown header、parent-child、semantic 等分块。
- **多模态 ingestion**：上传图片可传 `imageBase64` + `caption`/`ocrText`（multipart 用同名表单字段）；也可设 `RAG_IMAGE_TEXT_PROVIDER=http` + `RAG_IMAGE_TEXT_ENDPOINT_URL` 接外部视觉/OCR provider（可指向 vision-service）补充 caption/OCR。系统只索引文本，不保存图片字节。默认 `none`。
- **检索**：`POST /rag/query`（别名 `/knowledge/query`）融合 vector、keyword、可选 graph 命中；排序权重 `RAG_RANKING_VECTOR_WEIGHT` / `_KEYWORD_WEIGHT` / `_GRAPH_WEIGHT`。keyword hybrid 默认开（`RAG_HYBRID_ENABLED=true`）。
- **多租户强隔离**：`RAG_VECTOR_STORE_ISOLATION=collection-per-tenant`（默认）—— 每租户独立 collection/namespace，EmbeddingStore/Model 按租户路由；可退回 `shared`（单 store + metadata filter）。
- **向量存储**：`RAG_VECTOR_STORE_PROVIDER=in-memory`（默认）| `qdrant`（`QDRANT_HOST`/`QDRANT_PORT`/`QDRANT_COLLECTION_NAME`）。
- **Embedding provider**：`RAG_EMBEDDING_PROVIDER=hash`（默认，确定性本地）| `openai`（走 LiteLLM/OpenAI 兼容）| `ollama`（`RAG_EMBEDDING_OLLAMA_BASE_URL`、`RAG_EMBEDDING_OLLAMA_MODEL`，默认 `nomic-embed-text`）。含维度守卫、超时/重试参数。
- **GraphRAG**（默认关，`RAG_GRAPH_ENABLED=true`）：`POST /rag/graph/query`（实体邻居查询）、`GET /rag/graph/entities`。确定性三元组抽取，受控格式 `subject|relation|object`（换行或分号分隔）；`RAG_GRAPH_INCLUDE_IN_QUERY` 决定是否融合进 `/rag/query`；关系白名单 `RAG_GRAPH_RELATION_WHITELIST`、别名 `RAG_GRAPH_ALIASES`、最大跳数 `RAG_GRAPH_MAX_HOPS`。图谱存储 `RAG_GRAPH_STORE=in-memory`（默认）| `jdbc`（MySQL）。

### 3. Agent 编排（五种模式）

`agent-service`（`/agent/**`，`AGENT_ENABLED=true` 默认开）。所有模式共用 gateway `ChatModel`、审计+计量 listener、SSE 进度 sink。

- **深度 Agent（ReAct）**：`POST /agent/run`（同步）、`POST /agent/run/async`（返回 taskId）。内置动作 `rag_search`（调 knowledge）、`analytics_sql`（调 analytics，`AGENT_ANALYTICS_ENABLED=true`）、`current_time`、`delegate`（受控委派，`AGENT_ALLOW_DELEGATION`/`AGENT_MAX_DEPTH`）、`finish`。步数/时长/token/循环等有护栏参数。
- **DAG 多 Agent**：`POST /agent/dag/run`（显式传 DAG）、`POST /agent/dag/plan-run`（Planner 自动拆 DAG）、二者 `/async` 变体。Kahn 拓扑分层，同层并行、下游看上游结果、末尾综合。可选质量闭环 `AGENT_DAG_REPLAN_ENABLED=true`：每轮综合由 Critic 加权评分，低于 `AGENT_DAG_REPLAN_THRESHOLD`（默认 0.75）则 Replanner 修订后再跑，上限 `AGENT_DAG_REPLAN_MAX_REPLANS`。SSE 除任务状态外还有 `dag-planned`/`dag-level-*`/`dag-worker-*`/`dag-synthesis-*`/`dag-critique`/`dag-replan*` 阶段事件。
- **链式 Agent（chaining）**：`POST /agent/chain`。顺序步骤 + 步间确定性 gate（`gate-min-length`/`gate-must-contain`/`gate-must-match`），不过即短路。`app.agent.chaining.steps` 默认空 —— 需先在配置里定义链，否则端点返回 400。
- **投票 Agent（voting）**：`POST /agent/vote`。同题并行 N 次（`AGENT_VOTING_N`，默认 3）取共识。`AGENT_VOTING_STRATEGY=majority`（默认，确定性多数表决，适合离散/分类题）| `synthesis`（聚合器 LLM 收口，适合自由文本）；`AGENT_VOTING_MIN_AGREEMENT` 阈值。
- **反思 Agent（reflexion）**：`POST /agent/reflexive`、`POST /agent/reflexive/stream`（SSE）。单答案 answer→critique→improve 自省环，评分复用 DAG 的 Critic，加权聚合达 `AGENT_REFLEXION_THRESHOLD`（默认 0.75）或到 `AGENT_REFLEXION_MAX_ATTEMPTS` 即停。

**Agent 任务态**：`GET /agent/tasks`、`GET /agent/tasks/{taskId}`、`GET /agent/tasks/{taskId}/stream`（SSE）、`DELETE /agent/tasks/{taskId}`。`GET /agent/capabilities` 返回能力清单。

**可选 Agent 工具（全部默认关）**：

- `code_exec`（`AGENT_CODE_EXEC_ENABLED=true`）：受限 Java 片段执行。默认 `AGENT_CODE_EXEC_SANDBOX=subprocess`（独立子进程 + `-Xmx`/空 cwd/进程 kill，中等隔离）；仍非强隔离容器，不可信输入慎开。含超时/输出截断/denylist 参数。
- `mcp_call`（`AGENT_MCP_ENABLED=true`）：调外部 MCP server，`AGENT_MCP_TRANSPORT=stdio`（默认）| `http`。
- browser 动作（`AGENT_BROWSER_ENABLED=true`）：`browser_open`/`browser_click`/`browser_click_xy`/`browser_type`/`browser_screenshot`，依赖 Playwright Chromium。
- `browser_see` 视觉动作：**双门控**，需 `AGENT_BROWSER_ENABLED=true` 且 `AGENT_VISION_ENABLED=true`（`VISION_BASE_URL` 指向 vision-service），agent POST 截图字节给 vision-service 看图。

**任务中心迁移**：agent 可选把本地任务生命周期同步到 async-task-service，mirror 模式（`AGENT_ASYNC_EXTERNAL_ENABLED=true`）或 authoritative 模式（再加 `AGENT_ASYNC_EXTERNAL_AUTHORITATIVE=true`，含 worker lease 认领）。终态 webhook 可本地投递或交中心 outbox（`AGENT_ASYNC_EXTERNAL_MIRROR_WEBHOOK`）。

### 4. 异步任务中心

`async-task-service`（`/async/tasks/**`）跨服务通用任务状态中心：

- `POST /async/tasks` 创建、`GET /async/tasks` 列表、`GET /async/tasks/{taskId}` 详情、`PATCH /async/tasks/{taskId}/status` 更新、`DELETE /async/tasks/{taskId}` 取消/删除。
- `GET /async/tasks/{taskId}/stream` SSE，支持 `Last-Event-ID` header 或 `lastEventId` query 断点续订。
- `POST /async/tasks/{taskId}/lease` worker 认领（`PENDING`→`RUNNING`），未过期 lease 只允许 owner worker 更新。
- 终态（`SUCCEEDED`/`FAILED`/`CANCELLED`）webhook 投递（头带 `X-Async-Task-Id`/`X-Async-Task-Status`/`X-Tenant-Id`）。
- 存储 `ASYNC_TASK_STORE=in-memory`（默认）| `jdbc`（MySQL，自动建 `ASYNC_TASK` + `ASYNC_TASK_WEBHOOK_OUTBOX`）。投递耗尽进 `DEAD`，`GET /async/webhook-outbox/dead` 按租户查询。已投递 outbox 默认保留 7 天（`ASYNC_TASK_WEBHOOK_DELIVERED_RETENTION`）。
- kafka 档下终态在 `store.update` 同事务写生命周期 outbox 行，由 relay 投 Kafka（与 workflow 对称，避免两段式丢失）。

### 5. 工作流审批

`workflow-service`（`/workflow/**`）Flowable 退款审批流：

- `POST /workflow/refund/start` 发起、`GET /workflow/tasks` 待办、`POST /workflow/tasks/{taskId}/claim` / `/unclaim` / `/complete`、`GET /workflow/instances/{instanceId}`、`DELETE /workflow/data` 按 chatId 清理。
- 回复生成/工单抽取默认经 HTTP 调 conversation-service（`/conversation/workflow/reply`、`/ticket`），断开本地 ChatModel。
- 终态通知：默认 `WORKFLOW_TERMINAL_NOTIFICATION_MODE` 可切 `async-task`（交中心 outbox，失败回退本地 `WF_OUTBOX`）。kafka 档下 BPMN `end` 监听器在引擎事务内写 `WF_TERMINAL_EVENT_OUTBOX`，由 relay 投 Kafka，保证「终态提交 ⇔ 事件行已写」原子。

### 6. 数据分析（NL2SQL / ChatBI）

`analytics-service`：`POST /chat/sql`（别名 `/analytics/sql`）自然语言转 SQL。内置 SQL guard 和只读连接边界，避免生成写操作直接执行。agent→analytics 已用 protocol DTO（`AnalyticsSqlRequest`/`AnalyticsSqlReply`）typed 契约。

### 7. 渠道接入

`channel-service`（`/channel/**`）：

- `GET /channel/capabilities` 能力、`POST /channel/messages` 出站投递、`POST /channel/inbound` 入站事件。
- callback 转渠道消息：`POST /channel/callbacks`、`POST /channel/callbacks/async-task`、`POST /channel/callbacks/workflow`。
- 出站适配：webhook、Feishu webhook 机器人文本、voice HTTP provider。默认 `CHANNEL_OUTBOUND_ENABLED=false`。
- 签名：出站/入站 HMAC 签名校验默认关（`CHANNEL_OUTBOUND_SIGNATURE_ENABLED` / `CHANNEL_INBOUND_SIGNATURE_ENABLED`）。
- 可选 Kafka channel event 发布（`CHANNEL_EVENTS_ENABLED=false`，topic `platform.channel.events`）；作为 platform-eventbus 生命周期事件消费方，去重存储 `CHANNEL_DEDUP_STORE=memory`（默认）| `jdbc`。

### 8. 互操作（真 A2A / MCP）

`interop-service`（`/interop/**`）对外互操作面，内部仍走 typed-HTTP 代理到下游：

- **A2A JSON-RPC**：`POST /interop/a2a` 单端点，真 task 协议，方法 `message/send`、`tasks/get`、`tasks/cancel`（代理到 agent-service，`message/stream` 以轮询降级）；`GET /.well-known/agent-card.json` A2A 生态 agent-card（edge-gateway 白名单放行）。
- **agent-card / MCP surface**：`GET /interop/agent-card`、`GET /interop/a2a/agent-card`、`GET /interop/mcp/tools`、`GET /interop/mcp/tools/{toolName}`、`POST /interop/mcp/call`。
- **live discovery**（默认关，`INTEROP_DISCOVERY_ENABLED=true`）：agent-card skills / MCP tools 从向下游动态发现（懒加载 + TTL `INTEROP_CAPABILITY_TTL`），下游不可达确定性回退静态默认。

### 9. 回归评测

`eval-service`（`/eval/**`）外部回归测试客户端：

- `GET /eval/capabilities`、`POST /eval/run`（单 case/内联 suite）、`POST /eval/suites/{suiteName}/run`（baseline suite）。
- **双跑门禁**：`POST /eval/dual-run`（同一 suite 分别打 oracle 冻结单体与 candidate 网关）、`POST /eval/gate`（纯函数 Gate，回归返回 HTTP 422 供 CI fail）。
- 断言：HTTP status、response contains、oracle contains、JSON path 等值（如 `$.answer`、`$.items[0].name`）、轻量 semantic-tolerance（`semanticExpected` + `semanticMinScore`），输出 JSON report。
- 可选比较模式：LLM-judge（`EVAL_JUDGE_ENABLED=false`）、embedding 相似度（`EVAL_EMBEDDING_ENABLED=false`），默认均关。

### 10. 多模态视觉

`vision-service`（:8090，`/vision/**`）独立多模态服务，**默认整服务不装配**（`VISION_ENABLED=false`；开启但 `VISION_MODEL` 留空则启动 fail-fast）：

- `POST /vision/caption`（别名 `/vision/describe`）：JSON（`imageBase64`）或 multipart 图片，返回 caption/描述。多模态 ChatModel 与文本共用同一 LiteLLM base-url，仅逻辑模型名不同（`VISION_MODEL`，如 gpt-4o-mini / qwen2.5-vl）。
- 图片字节上限 `VISION_MAX_IMAGE_BYTES`（默认 10MB）、允许 MIME `VISION_ALLOWED_MIME`、caption 结果按内容 SHA-256 缓存（`VISION_CAPTION_CACHE_SIZE`）。vision token 计入 metering。
- 供 knowledge-service（`RAG_IMAGE_TEXT_PROVIDER=http` 指向）和 agent-service（`browser_see`）复用。

## 当前限制

- GraphRAG 抽取是确定性三元组格式，不是开放信息抽取。
- A2A `message/stream` 目前用轮询代替真 SSE（buffered 降级）；push 通知中继待接总线。
- 契约测试当前覆盖 knowledge/agent 两个 P0 provider（analytics/async-task 待补），网关 failover 为独立 smoke 脚本、不进默认 CI。
- `code_exec` 子进程沙箱是中等隔离，非强隔离容器；生产对不可信输入应进一步收紧。
- RS256、Config Server git 后端、vision 模型、Kafka EOS 等生产能力默认关闭或需填真实外部配置。
