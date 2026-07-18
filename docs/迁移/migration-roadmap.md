# 迁移路线图

> ⚠️ **历史存档（2026-07-18）**：迁移主体已完成，本路线图**仅作历史记录**保留——下文「当前状态 / 服务迁移顺序」里标为「进行中 / 暂缓」的条目（`knowledge-service` RAG、`async-task-service`、`agent-service` 多 Agent、`interop-service` A2A/MCP、`browser_see`、workflow 终态异步化 等）**均已落地**；且本路线图**未覆盖**后续新增的 `auth-service` 登录 + 继承式 RBAC、Casdoor SSO、SpiceDB 文档级 ReBAC、`order-service`、`voice-service`、能力展示前端 + 移动端适配、LiteLLM spend 记账/failover、增强开关默认全开 等方案变更。
>
> **当前真实状态以这些为准**：[变更记录](../变更记录.md)（近期方案级变更汇总）、[业务场景总览](../scenarios.md)、[能力文档](../参考/capabilities.md)；迁移收尾细节见 [迁移收尾方案与排期](migration-remaining-plan.md) 与 [对话/RAG 基础层迁移收尾](migration-gap-closure-plan.md)（20 项全部完成）。

本项目是对原单体项目的微服务化重写：

`/Users/liruijun/personal/LLM/LangChain4j_project`

历史 ClaudeCode 架构规划文档为：

`/Users/liruijun/.claude/plans/ai-sprightly-feigenbaum.md`

## 当前状态

Phase 0 已作为一个最小端到端链路完成实现：

- `edge-gateway`：业务 API 网关、API Key 鉴权、内部 JWT 签发。
- `conversation-service`：基于 LangChain4j 的 `/chat` 接口，支持可选的跨服务 RAG 提示词增强。
- `platform-security`：租户上下文、内部 JWT、下游入站鉴权过滤器、出站租户信息透传。
- `platform-observability`：trace id 过滤器与出站 trace 透传。
- `platform-gateway-client`：LiteLLM/OpenAI 兼容的 `ChatModel` 工厂。
- `deploy`：LiteLLM、Redis、MySQL、Kafka 以及各服务部署配置。

Phase 1 已开始：

- `platform-protocol`：跨服务 DTO 契约。
- `platform-audit`：审计日志记录器与 LLM 审计监听器。
- `platform-metering`：token 预算与按租户归因成本统计。
- `platform-security.ratelimit`：内存/Redis 限流注册表。
- `edge-gateway`：支持租户感知的限流过滤器。
- `conversation-service`：已依赖 audit 与 metering 监听器；可选的 RAG 提示词增强通过共享协议 DTO 调用 `knowledge-service`。
- `workflow-service`：Flowable 退款审批工作流已迁移为独立服务，并通过 `edge-gateway` 路由 `/workflow/**`。
- `analytics-service`：NL2SQL / ChatBI 已迁移为独立服务，并通过 `edge-gateway` 路由 `/chat/sql` 与 `/analytics/**`。
- `knowledge-service`：已迁移 RAG 基础能力和文档生命周期/查询能力，包括 chunk 切分器、来源注入上下文、文档注册表、Tika 文本抽取、`/rag/documents/**`、`/rag/query`、关键词/向量混合检索、可选 Qdrant 向量存储，以及第一版确定性的 GraphRAG 切片。
- `agent-service`：第一版 deep-agent 微服务切片已完成，包含 ReAct 循环、`rag_search`、`analytics_sql`、`current_time`，并将 `/agent/run` 置于 `edge-gateway` 之后。
- `async-task-service`：第一版通用异步任务骨架已完成，包含任务状态、租户级 listing/get/update/cancel、可恢复 SSE 状态流、worker 租约归属，以及终态 webhook 投递。
- `channel-service`：通道有界上下文已完成，包括 `/channel/capabilities`、出站消息 provider 边界、webhook dry-run/HTTP POST 投递、飞书 webhook 机器人文本投递、语音 HTTP provider 投递、async-task/workflow 回调到 channel 的接口、出站 webhook 签名、入站事件接收、可选 HMAC 签名校验、可选 Kafka channel events、协议 DTO、edge 路由以及 docker-compose 服务。
- `interop-service`：互操作有界上下文已完成，包括 A2A agent-card、MCP 风格 tool-list/call 暴露面，以及 service-net 工具代理到 `agent-service` 的 run/async/DAG API。
- `eval-service`：外部回归客户端能力已完成，包括 HTTP target 执行、baseline suite 加载、响应 contains 断言、JSON-path 断言、冻结单体 oracle contains 断言、状态/错误捕获、耗时/snippet 报告、可选 JSON 报告输出，以及 edge-gateway API-key 接入。

## 验证

当前基线：

```bash
mvn test
mvn -DskipTests package
```

在 Phase 1 共享库集成完成后，以上两个命令均已通过。

## 当前代码步骤：Knowledge Service

`knowledge-service` 已作为 RAG 有界上下文引入。

已实现第一版切片：

- `MarkdownHeaderSplitter`、`ParentChildSplitter`、`SemanticChunkingSplitter`。
- `TaggedSourceContentInjector`、`RetrievedSourcesContext`、`CategoryContext`。
- `DocumentInfo`、`DocumentRegistry`、内存版与 Redis 版 registry 实现。
- 基于 Apache Tika 的 `DocumentTextExtractor`。
- `DocumentSplitterFactory`、可配置的向量存储 provider 边界、内存/Qdrant `EmbeddingStore`、确定性 hash `EmbeddingModel`，以及可配置的 OpenAI 兼容 embedding provider。
- `DocumentService` 与 `DocumentController` 上传/list/get/delete 接口，包括通过 `imageBase64` 或 multipart image 进行原生 CLIP/jina-clip 多模态图片 embedding，并存储在专用的按租户隔离的 `knowledge_images` collection 中。旧版 image-to-text（caption/OCR）摄取路径与 `ImageTextProvider` 已移除。
- 支持租户/category 过滤的 `KnowledgeQueryService` 与 `KnowledgeQueryController`。
- `KeywordSearchService` 混合检索，包含轻量 tokenizer、结果去重、命中 `source` 归因，以及可配置的向量/关键词/图谱排序权重。
- `knowledge.graph`：确定性的 `subject|relation|object` 抽取、基于 `RAG_GRAPH_STORE` 的内存或 JDBC 图存储、token 实体链接、文档生命周期同步、`/rag/graph/query` + `/rag/graph/entities`，以及在 `RAG_GRAPH_ENABLED=true` / `RAG_GRAPH_INCLUDE_IN_QUERY=true` 下将可选图谱命中融合进 `/rag/query`。
- `platform-protocol.knowledge`：`KnowledgeQueryRequest`、`KnowledgeQueryReply`、`KnowledgeHit` 作为共享跨服务 RAG DTO。
- `conversation-service`：`KnowledgeClient`、带租户/trace 透传的 HTTP 实现，以及位于 `CONVERSATION_RAG_ENABLED=true` 之后的 `RagPromptAugmenter`。
- `deploy/smoke-qdrant-rag.sh`：用于最小 Qdrant 支持的上传/查询 smoke。
- 确定性的 splitter、来源注入、registry、文本抽取、service、query、hybrid、graph 与 controller 测试。

后续切片中已迁移：

- 向量存储后端扩展为 6 种 provider，统一位于 `RAG_VECTOR_STORE_PROVIDER` 之后：`in-memory`（默认）、`qdrant`、`pgvector`（可选 `HYBRID` 向量 + PG full-text RRF 融合）、`milvus`、`chroma`，以及自研 JDBC `doris` 实现（基于 MySQL 协议的 HNSW ANN）。所有实现都通过 collection-per-tenant 实现强租户隔离，并共享 `RAG_VECTOR_STORE_BASE_COLLECTION` 作为基础 collection 名称。
- 图片摄取切换为原生 CLIP/jina-clip 多模态 embedding（`RAG_MULTIMODAL_ENABLED`，默认关闭），提供 `/rag/image` 上传与 `/rag/image-search` 跨模态检索，图片存储在按租户隔离的专用 `knowledge_images` collection 中。该方案替代旧的 caption/OCR image-to-text 路径（`RAG_IMAGE_TEXT_*` / `ImageTextProvider`），旧路径已移除。
- 如果 embeddings 不使用 LiteLLM，则支持可选的 Ollama/native embedding provider。

## 当前代码步骤：Agent Service

`agent-service` 已作为 agent 有界上下文引入。

已实现第一版切片：

- `DeepAgentService`：确定性的 ReAct 循环，支持 step 预算、wall-clock/token 软预算、循环检测、scratchpad 压缩以及 delegate 深度控制。
- 基于共享 LiteLLM/OpenAI 兼容 `ChatModel` 构建的 `AgentBrain`。
- `/agent/run` 接口，返回 `platform-protocol.agent` DTO。
- 本地异步任务执行，提供 `/agent/run/async`、`/agent/tasks/**` 与 SSE 状态流。
- 显式多 agent DAG 执行 `/agent/dag/run`：调用方提供 tasks 与 dependencies，agent-service 按拓扑层级并行执行，并综合生成最终答案。
- 自动 DAG 规划 `/agent/dag/plan-run`：调用方仅提供目标，`AgentDagPlanner` 生成 task/dependency 结构，再复用同一套 DAG 执行内核运行。
- DAG 异步执行 `/agent/dag/run/async` 与 `/agent/dag/plan-run/async`，复用相同的本地 task store 与 SSE stream endpoints。
- 可选 DAG 质量循环，位于 `AGENT_DAG_REPLAN_ENABLED=true` 之后：`AgentDagCritic` 对每次综合答案评分，当 aggregate score 低于阈值时，`AgentDagReplanner` 修订 DAG，响应中暴露 `attempts[]`、`critique`、`aggregate` 与 `acceptedByThreshold`。
- 针对 async run 的细粒度 DAG SSE 进度事件：plan、level start/complete、worker start/result、synthesis、critique 与 replan 事件会随任务状态一并发出。
- 可选异步任务完成 webhook：async agent 与 DAG endpoints 接收 `webhookUrl`，随后 POST 终态 task snapshot，并带有重试和审计日志。
- 使用 `platform-protocol.knowledge` 与 `knowledge-service` 的 `rag_search` action。
- 通过带租户/trace 透传的 HTTP 调用 `analytics-service` 的 `analytics_sql` action。
- `current_time` 本地 action。
- 可选 `code_exec` action，默认由 `AGENT_CODE_EXEC_ENABLED=false` 关闭；从单体 JShell runner 迁移而来，支持超时、输出上限、源码上限与 unsafe API denylist。
- 可选 `mcp_call` action，默认由 `AGENT_MCP_ENABLED=false` 关闭；支持 HTTP/stdio MCP client transports 与 JSON tool dispatch。
- 可选 browser-use actions，默认由 `AGENT_BROWSER_ENABLED=false` 关闭：通过 Playwright 支持 open、按链接文本 click、按坐标 click、type 与 screenshot。
- 位于 `AGENT_ASYNC_EXTERNAL_ENABLED=false` 之后的可选 async-task-service mirror：本地 `/agent/tasks/**` 保持兼容，同时 task create/status transitions 可以镜像到 `/async/tasks/{sameTaskId}`，用于阶段性迁移。
- 位于 `AGENT_ASYNC_EXTERNAL_AUTHORITATIVE=true` 之后的可选 async-task-service authoritative 模式：agent 继续执行工作，但 `/agent/tasks/**` 的 read/list/cancel/status update 由 async-task-service 提供支持。Agent worker 在执行前先 claim `/async/tasks/{taskId}/lease`，并在状态更新时携带 `workerId`。取消会通过中心化 task delete API 以及 worker 内 cancellation token 传播，使排队中/运行中的任务在写入 success 前停止。当 `AGENT_ASYNC_EXTERNAL_MIRROR_WEBHOOK=true` 时，webhook 投递归属迁移到 async-task-service outbox，本地 agent notifier 跳过投递。
- Edge gateway 路由与 docker-compose 服务接入。

暂缓的 agent 项：

- 在多模态/视觉协议具备之后再补 browser visual understanding（`browser_see`）、更强的外部代码执行沙箱，以及 A2A/interop 暴露。

## 当前代码步骤：Async Task Service

`async-task-service` 已作为通用异步执行状态有界上下文引入。

已实现第一版切片：

- `platform-protocol.asynctask`：共享 task status、task snapshot、create request 与 status update request DTO。
- `/async/tasks`：创建租户级 pending task，支持可选 `webhookUrl`。
- 支持调用方指定 `taskId`，使现有服务 API 在镜像到共享任务中心时可以保留对外 public task IDs。
- `/async/tasks/{taskId}` 与 `/async/tasks`：租户级查询与列表。
- `/async/tasks/{taskId}/status`：面向 worker 或未来 orchestrator 的状态/结果/错误更新接口。
- `/async/tasks/{taskId}/stream`：使用相同 task snapshot 契约的 SSE 状态流，通过 `Last-Event-ID` 或 `lastEventId` 与内存近期事件窗口实现可恢复 event ids。
- `/async/tasks/{taskId}/lease`：worker 租约 claim/renew 接口，包含 `workerId`、有界 TTL、租约有效期间仅 owner 可更新状态，以及过期租约可重新 claim。
- `/async/tasks/{taskId}` `DELETE`：pending/running task 的取消 API。
- 终态 webhook 投递，支持重试、审计日志，以及 header `X-Async-Task-Id`、`X-Async-Task-Status`、`X-Tenant-Id`。
- 位于 `ASYNC_TASK_STORE=jdbc` 之后的可选 JDBC task store，自动创建 `ASYNC_TASK` 表，并通过原子条件 lease update 支持多副本 worker。
- JDBC 模式下的可选 JDBC webhook outbox，自动创建 `ASYNC_TASK_WEBHOOK_OUTBOX`，支持重启安全的重试状态、DLQ 状态、指数退避、已投递行保留、通过 `/async/webhook-outbox/dead` 进行租户级 DLQ 查询，以及通过 `IN_PROGRESS` claim owner/TTL 字段实现多副本行 claim。
- Edge gateway 路由、docker-compose 服务接入，以及聚焦 controller/webhook 的测试。

暂缓的 async-task 项：

- 将 workflow 终态通知从直接 webhook/outbox bridge 迁移到共享 async backbone。

## 上一个代码步骤：Analytics Service

`analytics-service` 已从单体 `nl2sql` 包脚手架化。

已实现重写点：

- 单体 imports 已替换为 platform 共享库。
- 排除 `DataSourceAutoConfiguration`；NL2SQL 仅在 `app.nl2sql.enabled=true` 时拥有自己的手动 admin/read-only datasource 配置。
- `/chat/sql` 保持兼容，同时提供服务原生路径 `/analytics/sql`。
- 已迁移确定性的 `SqlGuard` 与 `NumberGrounding` 测试。

剩余 analytics 加固项：

- 当 MySQL 与 LiteLLM 运行后，新增端到端 docker smoke 脚本。
- 后续通过 HTTP clients 迁移 MCP/Agent NL2SQL 集成，避免直接进程内注入。

## 上一个代码步骤：Workflow Service

`workflow-service` 已完成脚手架化，并接入本地 stack。

单体中的源文件：

- `src/main/java/com/lrj/langchain4j/workflow/*`
- `src/main/java/com/lrj/langchain4j/controller/WorkflowController.java`
- `src/main/resources/processes/refund-approval.bpmn20.xml`
- `src/test/java/com/lrj/langchain4j/workflow/*Test.java`

已实现重写点：

- 已移除单体 imports。
- Flowable datasource 归属于本地 `workflow-service`。
- 租户传播使用内部 JWT 与 `TenantContext`。
- 审批 endpoints 显式检查 `approve` scope。
- 终态 workflow 通知可通过 `WORKFLOW_TERMINAL_NOTIFICATION_MODE=async-task` 委托给 async-task-service，并回退到本地 `WF_OUTBOX`。
- 已迁移确定性的 workflow 测试。

剩余 workflow 加固项：

- 当 conversation workflows 稳定后，用真实跨服务 conversation 契约替换第一版 `WorkflowAiClient` fallback。
- 当生产发布完成后，全面用共享 async backbone 替换本地终态通知 fallback。

## 服务迁移顺序

1. `workflow-service`：状态边界最清晰，自持 Flowable 与 workflow MySQL。已完成。
2. `analytics-service`：迁移 `nl2sql/*` 与 `Nl2SqlController`。已完成。
3. `knowledge-service`：迁移 RAG、向量存储、图谱、生命周期，并隔离 vector-client/grpc 依赖。进行中。
4. `channel-service`：webhook、飞书机器人与通用语音 HTTP provider 投递已完成。
5. `async-task-service`：迁移 async task/SSE/webhook backbone。进行中。
6. `agent-service`：在 knowledge 与 analytics contract 存在后，迁移 multi-agent/deep-agent/reflexion/cascade/voting/chaining。进行中。
7. `interop-service`：迁移 A2A 与 MCP server。Agent tool proxy 进行中。
8. `eval-service`：外部回归客户端，永不作为业务服务的运行时依赖。HTTP runner、JSON-path assertions 与 oracle contains checks 已完成。

## 当前代码步骤：缺失服务脚手架

剩余规划中的服务模块已引入，因此 monorepo 拓扑现在已经与原服务图一致。

已实现第一版切片：

- `channel-service`：channel capability endpoint、outbound message acceptance endpoint、基于 provider 的 outbound dispatch、webhook dry-run/HTTP POST 投递、飞书 webhook 机器人投递、语音 HTTP provider 投递、async-task/workflow callback-to-channel endpoints、可选 outbound webhook signing、inbound event acceptance endpoint、可选 HMAC signature verification、audit events、可选 Kafka channel events、protocol DTOs、tests、Dockerfile、edge route 与 compose wiring。
- `interop-service`：A2A 风格 agent-card endpoint、基于 registry 派生的 capabilities、MCP 风格 tool listing 与单工具 schema lookup、确定性的 `platform.ping`、真实的 `platform.agent.run`、`platform.agent.run_async`、`platform.agent.dag.plan_run` 与 `platform.agent.dag.plan_run_async` 代理到 `agent-service`、protocol DTOs、tests、Dockerfile、edge route 与 compose wiring。
- `eval-service`：外部回归客户端 API surface，包含 `/eval/run` 中的真实 HTTP target 执行、通过 `/eval/suites/{suiteName}/run` 加载命名 baseline suite、expected-response contains、JSON-path assertions、轻量 semantic-tolerance assertions、冻结单体 oracle contains assertions、status/error/snippet/duration result reporting、tests、Dockerfile、edge route 与 compose wiring。

暂缓的脚手架项：

- `channel-service`：无剩余脚手架项；后续工作是按需补充 provider-specific channel SDK 与 durable delivery。
- `interop-service`：迁移单体 A2A 与 MCP server 实现、扩展当前静态 proxy registry 之外的实时下游 capability discovery，以及更广泛的内部协议复用。
- `eval-service`：补充比确定性 token similarity 更丰富的 oracle comparison modes，例如基于 embedding 或 LLM-judge 的对比。

## 当前代码步骤：Eval Service HTTP Runner

`eval-service` 已不再只是 validation-only scaffold，现在可以针对目标 service network 执行回归 case。

已实现切片：

- `/eval/run` 从请求或 `app.eval.default-target-base-url` 解析 `targetBaseUrl`。
- `/eval/suites/{suiteName}/run` 从 `app.eval.baseline-directory` 或 classpath `eval/baselines/{suiteName}.json` 加载 cases。
- `EvalRunner` 执行 HTTP cases，并支持可配置的 API-key header 注入。
- 空 body case 的 method 默认是 `GET`，非空 body case 的 method 默认是 `POST`。
- Results 现在包含 HTTP status、pass/fail state、error、response snippet 与 duration。
- 每次 run 现在包含 `runId`、`suiteName`、`targetBaseUrl`、`startedAt`、`finishedAt` 与 total duration metadata。
- 当配置 `app.eval.report-directory` / `EVAL_REPORT_DIRECTORY` 时，`EvalReportWriter` 可以写出机器可读的 JSON report。
- 提供 `expectedContains` 时，会检查 response body。
- 当精确文本片段过于脆弱时，`expectedJsonPaths` 会检查简单 JSON path，例如 `$.answer` 与 `$.items[0].name`。
- `semanticExpected` + `semanticMinScore` 会对措辞可能变化的响应进行确定性 token cosine similarity 检查。
- `oracleContains` 允许 baseline case 存储冻结单体响应片段；不匹配时 case result 会以 `oracleMatched=false` 失败，并返回 `oracleExpected`。
- Docker Compose 设置 `EVAL_API_KEY=dev-key-acme`，因此默认 target 可以是 `edge-gateway`。
- 内置 `platform-smoke` suite 提供第一份可复用 baseline 文件。
- 聚焦测试覆盖 suite loading、report writing、successful assertions、JSON-path assertions、semantic-tolerance assertions、oracle drift detection、missing expected text、HTTP 500 capture 与 validation failures。

暂缓的 eval 项：

- 为响应措辞差异较大的场景补充 embedding-based 或 LLM-judge comparison modes。

## 经验原则

从单体迁移代码时：

- 代码迁移时同步迁移确定性测试。
- Provider/model routing 统一放在 LiteLLM 之后，并通过 `platform-gateway-client` 接入。
- API key authentication 保留在 `edge-gateway`。
- 下游服务应信任内部 JWT，而不是外部 API keys。
- 在服务之间接线前，先把 DTOs 与 event shapes 放入 `platform-protocol`。