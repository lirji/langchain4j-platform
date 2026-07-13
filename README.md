# langchain4j-platform

按 DDD 限界上下文把单体 `LangChain4j_project` 拆成的**全微服务**平台 + 外部 AI 网关（LiteLLM）。
原单体**冻结为参考实现 / 行为基准**，逻辑逐步移植过来。设计与阶段见
`~/.claude/plans/ai-sprightly-feigenbaum.md`。

## 目标拓扑

```
client ──X-Api-Key──▶ edge-gateway (Spring Cloud Gateway)
                          │  校验 api-key → 签发短时内部 JWT(X-Internal-Token)
                          ▼
                  ┌───────────────── 各微服务 ─────────────────┐
   conversation / knowledge / agent / analytics / workflow /
   async-task / channel / interop / eval / vision / voice
                          │  所有 LLM 调用统一走 ▼
                     LiteLLM (AI 网关)  ── provider 路由 + failover ──▶ ollama/openai/anthropic/...
```

- **两层网关分工**：`edge-gateway` 管业务 API 路由/鉴权；`LiteLLM` 管 LLM provider 路由/failover。
- **租户跨网络跳**：边缘用 api-key 换发内部 JWT，下游只认 JWT（`platform-security`）。

## 模块

| 模块 | 类型 | 说明 |
|---|---|---|
| `platform-security` | 共享库 | `TenantContext` + `InternalToken`(JWT 签发/校验) + 出站传播拦截器 + 下游入站 filter |
| `platform-observability` | 共享库 | `TraceIdFilter` + 跨服务 traceId 透传 |
| `platform-gateway-client` | 共享库 | 指向 LiteLLM 的 `ChatModel`（OpenAI-compat）+ listener 挂载 |
| `platform-protocol` | 共享库 | 跨服务 DTO 契约 |
| `platform-audit` | 共享库 | 审计日志 + LLM audit listener |
| `platform-metering` | 共享库 | token budget + cost attribution |
| `platform-eventbus` | 共享库 | 跨服务事件总线抽象（内存默认，可选 Kafka）；channel 出入站事件走它 |
| `conversation-service` | 服务 | `/chat`（可选 RAG 增强）+ `/chat/stream` SSE 流式 + `/chat/auto` 意图路由 + `/chat/vision` 视觉对话 + `/chat/mcp` MCP 工具对话 + `/chat/cascade` 级联模型 + `/chat/memory`·`/memory/profile` 长期画像 + `/extract` 结构化抽取；多轮记忆、PII/注入护栏可选开启 |
| `workflow-service` | 服务 | `/workflow/**` Flowable 退款审批流 + outbox |
| `analytics-service` | 服务 | `/chat/sql`、`/analytics/sql` NL2SQL / ChatBI；`/analytics/schema/tables`(+`/{table}`) 按需探表（供数据分析智能体） |
| `knowledge-service` | 服务 | `/rag/documents/**` 文档上传/列表/删除 + `/rag/query` 向量+keyword hybrid（可选 rerank / query-expansion / contextual / HanLP 分词）；`/rag/image*` CLIP 多模态；`/rag/graph/**` GraphRAG；`/rag/obsidian/import` Obsidian 导入 |
| `agent-service` | 服务 | `/agent/run`(+`/async`) 深度 Agent 编排 + `/agent/tasks/**` SSE；`/agent/dag/**` 多 Agent DAG（含自动规划/重规划）；`/agent/analyst/run`(+`/async`) 数据分析智能体（DAG 编排：探表→取数→计算→解读）；`/agent/process/run`(+`/async`) 业务流程智能体（人在环，默认关）；`/agent/chain` 提示词链、`/agent/vote` 投票自一致、`/agent/reflexive`(+`/stream`) Reflexion 自反思；动作跨服务调用 knowledge / analytics / vision |
| `async-task-service` | 服务 | `/async/tasks/**` 通用任务状态、SSE 断点续订、取消与 webhook 通知中心；后续 agent/workflow 会逐步切到该服务 |
| `channel-service` | 服务 | `/channel/**` 渠道 ACL：webhook/Feishu/voice 出站、async-task/workflow callback、出入站签名校验；`/channel/dingtalk/events`·`/channel/feishu/events` 入站客服桥；可选 Kafka event |
| `interop-service` | 服务 | `/interop/**` A2A（`message/send`、`message/stream` 真 SSE、push 通知中继 `/interop/a2a/push-callback`、`/.well-known/agent-card.json`）、MCP tool surface，并可代理 agent run/async/DAG 能力 |
| `eval-service` | 服务 | `/eval/**` 外部回归测试客户端，可执行 HTTP target case、加载 baseline suite、做响应/oracle 断言并输出 JSON report |
| `vision-service` | 服务 | `/vision/caption`·`/vision/describe` 图像描述（多模态，JSON 或 multipart 上传）；默认关（`VISION_ENABLED=false`） |
| `voice-service` | 服务 | `/voice/transcribe` 转写 + `/voice/chat`(+`/stream`) 语音闭环 ASR(whisper)→`/chat`→TTS；默认关（`VOICE_ENABLED=false`） |
| `edge-gateway` | 服务 | 边缘 API 网关 |

> 后续继续加固 `channel`/`interop`/`eval` 的真实适配逻辑，并继续加固 `knowledge`/`async-task` 的持久化和跨服务协议。

## 文档

- [文档入口](docs/README.md)
- [能力文档](docs/参考/capabilities.md)
- [架构文档](docs/参考/架构文档.md)
- [运行与配置手册](docs/参考/operations.md)
- [接口与集成速查](docs/参考/api-reference.md)
- [开发者指南](docs/参考/developer-guide.md)
- [部署指南](docs/平台工程/deployment-guide.md)
- [业务场景总览](docs/scenarios.md)
- 对话与检索：[RAG](docs/对话与检索/rag-guide.md) · [记忆](docs/对话与检索/memory-guide.md) · [语义缓存](docs/对话与检索/semantic-cache.md) · [模型级联](docs/对话与检索/model-cascade.md) · [NL2SQL](docs/对话与检索/nl2sql-guide.md)
- Agent 与编排：[Agent](docs/Agent编排/agent-guide.md) · [工作流](docs/Agent编排/workflow-guide.md) · [Code Interpreter](docs/Agent编排/code-exec.md)
- 多模态与语音：[视觉](docs/多模态语音/vision-guide.md) · [语音](docs/多模态语音/voice-guide.md)
- 互操作与渠道：[A2A](docs/互操作渠道/a2a-guide.md) · [MCP](docs/互操作渠道/mcp-guide.md) · [钉钉桥](docs/互操作渠道/dingtalk-guide.md)
- 平台工程：[RBAC与登录](docs/平台工程/rbac-and-public-kb.md) · [事件总线](docs/平台工程/eventbus-guide.md) · [可观测性](docs/平台工程/observability-guide.md) · [成本归因](docs/平台工程/cost-attribution.md) · [评测](docs/平台工程/eval-guide.md)
- [迁移路线图](docs/迁移/migration-roadmap.md)

## 本地跑（Phase 0）

前置：本机 Ollama（`ollama pull llama3.1`）、Docker、JDK 21、Maven。

```bash
# 1. 构建
mvn -DskipTests package

# 2. 起整网（含 LiteLLM + Redis + MySQL + Kafka + conversation + workflow + edge）
docker compose -f deploy/docker-compose.yml up --build

# 3. 打一条 /chat（走边缘网关，用 api-key，网关内部换 JWT 转发给 conversation-service）
curl -s -X POST 'http://localhost:8080/chat?chatId=u1' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"message":"用一句话介绍你自己"}'
# 期望：{"reply":"...","tenantId":"acme","userId":"alice",...} —— tenantId 由内部 JWT 跨跳还原

# 4. 启动一个退款审批流程（dev-key-acme 带 approve scope）
curl -s -X POST 'http://localhost:8080/workflow/refund/start' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"chatId":"u1","message":"用户申请退款，金额 99 元"}'

# 5. NL2SQL / ChatBI（tenantA demo key 对应种子数据）
curl -s -X POST 'http://localhost:8080/chat/sql' \
  -H 'X-Api-Key: dev-key-tenantA-admin' \
  -H 'Content-Type: application/json' \
  -d '{"question":"2026 年 5 月一共退款了多少钱？"}'

# 6. 上传一篇知识库文档（当前默认 in-memory store，查询默认启用 keyword hybrid）
curl -s -X POST 'http://localhost:8080/rag/documents' \
  -H 'X-Api-Key: dev-key-acme-ingest' \
  -H 'Content-Type: application/json' \
  -d '{"title":"guide.md","text":"这是 acme 的知识库文档。","category":"manual"}'

curl -s -X POST 'http://localhost:8080/rag/query' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"query":"知识库文档","topK":3,"category":"manual"}'
```

`knowledge-service` 默认使用 `RAG_VECTOR_STORE_PROVIDER=in-memory` 和本地 deterministic hash embedding，适合开发和单测。向量库 provider 可选 `in-memory`（默认）| `qdrant` | `pgvector` | `milvus` | `chroma` | `doris`，全部走 collection-per-tenant 强隔离，基名由 `RAG_VECTOR_STORE_BASE_COLLECTION`（默认 `knowledge_segments`）决定。
`/rag/query` 会融合 vector、keyword 和可选 GraphRAG 命中，可通过 `RAG_RANKING_VECTOR_WEIGHT`、`RAG_RANKING_KEYWORD_WEIGHT`、`RAG_RANKING_GRAPH_WEIGHT` 调整排序权重。中文 keyword 检索用 HanLP 分词。

检索质量增强开关（默认全关，可叠加）：

```bash
RAG_RERANK_ENABLED=true            # 召回后重排；RAG_RERANK_TYPE=llm|jina
RAG_RERANK_CANDIDATE_MULTIPLIER=3  # 先取 topK*N 候选再重排回 topK
RAG_QUERY_EXPANSION_ENABLED=true   # 查询改写扩展；RAG_QUERY_EXPANSION_MAX_VARIANTS=4
RAG_CONTEXTUAL_ENABLED=true        # 入库时给分块补文档级上下文；RAG_CONTEXTUAL_MAX_DOC_CHARS=8000
```

Obsidian 知识库可整包导入（multipart 上传 vault 压缩包，自动切分入库）：

```bash
curl -s -X POST 'http://localhost:8080/rag/obsidian/import' \
  -H 'X-Api-Key: dev-key-acme-ingest' \
  -F 'file=@./my-vault.zip' -F 'category=notes'
```

图片走原生 CLIP 多模态 embedding：向量存入独立的 image collection（`knowledge_images_<tenant>`，与文本集合隔离），文本 query 可跨模态检索图片。默认关闭（`RAG_MULTIMODAL_ENABLED=false`），关闭时上传图片返回 400。

> ⚠️ **破坏性变更**：旧的「图 → 文字（caption/OCR）」路径已移除，上传图片不再接受 `caption`/`ocrText` 字段，`RAG_IMAGE_TEXT_*` / `ImageTextProvider` 全部删除。

```bash
RAG_MULTIMODAL_ENABLED=true
RAG_MULTIMODAL_BASE_URL=http://localhost:8000/v1     # OpenAI 兼容 /embeddings（vLLM/TEI/云 jina）
RAG_MULTIMODAL_MODEL=jinaai/jina-clip-v2
RAG_MULTIMODAL_DIMENSION=1024

# 图片入库（multipart 字段名为 image；也可走 /rag/documents 传 image/* 或 imageBase64）
curl -s -X POST 'http://localhost:8080/rag/image' \
  -H 'X-Api-Key: dev-key-acme-ingest' \
  -F 'image=@./screenshot.png'
# 返回 {"id":"...","fileName":"screenshot.png","type":"image"}

# 文本 query 跨模态检索图片
curl -s -X POST 'http://localhost:8080/rag/image-search' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"query":"退款审批流程图","topK":5}'
# 返回 {"query":"...","results":[{"id":"...","fileName":"screenshot.png","score":0.87}]}
```

需要走 LiteLLM/OpenAI-compatible embedding 时，可设置：

```bash
RAG_EMBEDDING_PROVIDER=openai
RAG_EMBEDDING_MODEL=embedding-default
GATEWAY_BASE_URL=http://localhost:4000/v1
GATEWAY_API_KEY=sk-litellm-master
```

需要使用 Qdrant 持久化向量库时，可设置：

```bash
RAG_VECTOR_STORE_PROVIDER=qdrant
QDRANT_HOST=localhost
QDRANT_PORT=6334
QDRANT_COLLECTION_NAME=knowledge_segments
RAG_REGISTRY_STORE=redis
```

也可以直接跑最小 smoke：

```bash
bash deploy/smoke-qdrant-rag.sh
```

需要启用第一阶段 GraphRAG（确定性抽取）时，可设置：

```bash
RAG_GRAPH_ENABLED=true
RAG_GRAPH_INCLUDE_IN_QUERY=true
RAG_GRAPH_RELATION_WHITELIST=隶属于,使用
RAG_GRAPH_ALIASES=张三经理=张三
```

默认图谱存储是内存；需要持久化到 MySQL 时设置：

```bash
RAG_GRAPH_STORE=jdbc
RAG_GRAPH_DB_URL='jdbc:mysql://mysql:3306/knowledge_graph?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&nullCatalogMeansCurrent=true'
RAG_GRAPH_DB_USER=root
RAG_GRAPH_DB_PASSWORD=root
```

当前图谱抽取支持受控文本三元组格式：`subject|relation|object`，多条可用换行或分号分隔。上传文档后可查实体邻居：

```bash
curl -s -X POST 'http://localhost:8080/rag/documents' \
  -H 'X-Api-Key: dev-key-acme-ingest' \
  -H 'Content-Type: application/json' \
  -d '{"title":"people.md","text":"张三|隶属于|研发部\n研发部|使用|LangChain4j","category":"org"}'

curl -s -X POST 'http://localhost:8080/rag/graph/query' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"query":"张三负责什么团队","maxHops":2,"category":"org"}'

curl -s -X POST 'http://localhost:8080/rag/query' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"query":"张三负责什么团队","topK":5,"category":"org"}'
```

需要让 `conversation-service` 在 `/chat` 前自动调用 `knowledge-service` 做 RAG 上下文增强时，可设置：

```bash
CONVERSATION_RAG_ENABLED=true
KNOWLEDGE_BASE_URL=http://knowledge-service:8084
CONVERSATION_RAG_TOP_K=5
CONVERSATION_RAG_CATEGORY=manual
```

### 对话增强：流式 / 记忆 / 护栏 / 路由 / 级联 / 视觉 / 抽取

`/chat/stream` 用 SSE 逐 token 推送（底层 `GATEWAY_STREAMING_ENABLED=true`）：

```bash
curl -N -X POST 'http://localhost:8080/chat/stream?chatId=u1' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"message":"讲个关于向量数据库的冷笑话"}'
```

多轮记忆 + PII/注入护栏（默认全关，按需开启）：

```bash
CONVERSATION_MEMORY_STORE=redis            # 默认 in-memory；窗口 CONVERSATION_MEMORY_MAX_MESSAGES=20
CONVERSATION_MEMORY_WINDOW_MODE=tokens     # 或按 token 截断，CONVERSATION_MEMORY_MAX_TOKENS=2000
CONVERSATION_MEMORY_PROFILE_ENABLED=true   # 长期用户画像（跨会话），/chat/memory + /memory/profile
CONVERSATION_GUARDRAIL_PII_ENABLED=true    # 出入参 PII 脱敏
CONVERSATION_GUARDRAIL_INJECTION_ENABLED=true  # 提示注入检测，_MODE=block|sanitize|audit
```

意图路由 `/chat/auto`（`CONVERSATION_ROUTER_ENABLED=true`）按分类分发；级联 `/chat/cascade`（`CHAT_CASCADE_ENABLED=true`，便宜模型先答、低置信度升级 `CHAT_CASCADE_STRONG_MODEL`）；`/chat/mcp` 让对话直接调用外部 MCP 工具；`/extract?type=ticket` 做结构化抽取：

```bash
curl -s -X POST 'http://localhost:8080/chat/auto?chatId=u1' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"message":"帮我退款"}'

curl -s -X POST 'http://localhost:8080/extract?type=ticket' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"text":"我叫张三，订单 A100 迟迟未发货，请尽快处理"}'
```

### 视觉与语音服务

`vision-service`（:8090，`VISION_ENABLED=true`）做图像描述；conversation 侧 `/chat/vision`（`CONVERSATION_VISION_ENABLED=true`）转发给它：

```bash
# 直接调 vision-service：JSON(imageBase64/imageUrl) 或 multipart 上传
curl -s -X POST 'http://localhost:8080/vision/caption' \
  -H 'X-Api-Key: dev-key-acme' \
  -F 'file=@./chart.png' -F 'instruction=这张图讲了什么'

# 走对话入口，带图提问
curl -s -X POST 'http://localhost:8080/chat/vision' \
  -H 'X-Api-Key: dev-key-acme' \
  -F 'image=@./chart.png' -F 'message=这张图的结论是什么'
```

`voice-service`（:8091，`VOICE_ENABLED=true`）做语音闭环：`/voice/transcribe` 只转写，`/voice/chat` 走 ASR(whisper)→`/chat`→TTS 返回音频，`/voice/chat/stream` 分句流式。默认 provider 走 OpenAI 兼容端点（`VOICE_BASE_URL`、`VOICE_API_KEY`、`VOICE_ASR_MODEL=whisper-1`、`VOICE_TTS_MODEL=tts-1`）。voice-service 已随 `docker compose` 起在 :8091（默认 `VOICE_ENABLED=false` 时空跑占位）：

```bash
curl -s -X POST 'http://localhost:8080/voice/chat?chatId=u1' \
  -H 'X-Api-Key: dev-key-acme' \
  -F 'audio=@./question.mp3' --output reply.mp3
```

深度 Agent 可通过 `/agent/run` 自主选择 `rag_search` / `analytics_sql` / `current_time` / `delegate` / `finish`：

```bash
curl -s -X POST 'http://localhost:8080/agent/run' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"goal":"查一下知识库里退款审批规则，并给出简短结论"}'
```

需要异步执行时，提交任务后可查询任务状态或用 SSE 订阅状态变化：

```bash
curl -s -X POST 'http://localhost:8080/agent/run/async' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{
    "goal":"查一下知识库里退款审批规则，并给出简短结论",
    "webhookUrl":"http://host.docker.internal:9000/agent-task-callback"
  }'

curl -s 'http://localhost:8080/agent/tasks/{taskId}' \
  -H 'X-Api-Key: dev-key-acme'

curl -N 'http://localhost:8080/agent/tasks/{taskId}/stream' \
  -H 'X-Api-Key: dev-key-acme'
```

`webhookUrl` 是可选字段，支持 `/agent/run/async`、`/agent/dag/run/async` 和 `/agent/dag/plan-run/async`。任务进入 `SUCCEEDED` / `FAILED` / `CANCELLED` 终态后，`agent-service` 会后台 POST 一份任务快照到该 URL；投递带 `X-Agent-Task-Id`、`X-Agent-Task-Status`、`X-Tenant-Id` 头，不会转发内部 JWT。重试参数可用 `AGENT_TASK_WEBHOOK_MAX_ATTEMPTS`、`AGENT_TASK_WEBHOOK_BACKOFF`、`AGENT_TASK_WEBHOOK_CONNECT_TIMEOUT`、`AGENT_TASK_WEBHOOK_READ_TIMEOUT` 调整。

如果需要提前验证通用任务中心，`agent-service` 可以把本地 agent 任务生命周期同步到 `async-task-service`：

```bash
AGENT_ASYNC_EXTERNAL_ENABLED=true
ASYNC_TASK_BASE_URL=http://async-task-service:8086
```

默认是 mirror 模式：`/agent/tasks/{taskId}` 仍由 agent 本地 API 提供兼容响应，同时相同 `taskId` 会出现在 `/async/tasks/{taskId}`。

需要把 `/agent/tasks/**` 的任务状态、列表、取消切到 `async-task-service` 作为权威存储时，可开启：

```bash
AGENT_ASYNC_EXTERNAL_ENABLED=true
AGENT_ASYNC_EXTERNAL_AUTHORITATIVE=true
AGENT_ASYNC_WORKER_ID=agent-service-1
AGENT_ASYNC_LEASE_SECONDS=300
ASYNC_TASK_BASE_URL=http://async-task-service:8086
```

authoritative 模式下，agent worker 执行前会调用 `/async/tasks/{taskId}/lease` 认领任务，之后状态更新会携带同一个 `workerId`；如果任务已经被其他活跃 worker 租约持有，本实例会跳过执行。默认 `AGENT_ASYNC_EXTERNAL_MIRROR_WEBHOOK=false`，终态 webhook 仍由 agent-service 本地投递。若同时设置 `AGENT_ASYNC_EXTERNAL_MIRROR_WEBHOOK=true`，webhookUrl 会传给 async-task-service，由中心服务 outbox 投递；agent 本地 notifier 会跳过，避免重复回调。
取消 authoritative 任务时，agent-service 会先调用 async-task-service 的 `DELETE /async/tasks/{taskId}`，再通过本地 cancellation token 阻止 queued/running worker 继续写入成功态。

需要显式多 Agent DAG 编排时，可传入子任务和依赖关系；服务会按拓扑分层执行，同层并行、下游看到上游结果，最后再综合回答：

```bash
curl -s -X POST 'http://localhost:8080/agent/dag/run' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{
    "goal": "分析退款审批规则，并给出运营建议",
    "tasks": [
      {"id": "t1", "description": "从知识库检索退款审批规则要点", "dependsOn": []},
      {"id": "t2", "description": "基于 t1 的规则总结潜在运营风险", "dependsOn": ["t1"]},
      {"id": "t3", "description": "基于 t1 和 t2 给出简短改进建议", "dependsOn": ["t1", "t2"]}
    ]
  }'
```

也可以只传目标，让 Planner 自动拆 DAG 后执行：

```bash
curl -s -X POST 'http://localhost:8080/agent/dag/plan-run' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"goal":"分析退款审批规则，并给出运营建议"}'
```

DAG 也支持异步提交，返回的 `taskId` 继续用 `/agent/tasks/{taskId}` 或 `/agent/tasks/{taskId}/stream` 查询：

```bash
curl -s -X POST 'http://localhost:8080/agent/dag/plan-run/async' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"goal":"分析退款审批规则，并给出运营建议"}'

curl -s -X POST 'http://localhost:8080/agent/dag/run/async' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{
    "goal": "分析退款审批规则，并给出运营建议",
    "tasks": [
      {"id": "t1", "description": "从知识库检索退款审批规则要点", "dependsOn": []},
      {"id": "t2", "description": "基于 t1 的规则总结潜在运营风险", "dependsOn": ["t1"]}
    ]
  }'
```

订阅 DAG 异步任务 SSE 时，除了 `PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED` 状态事件，还会收到 DAG 阶段事件，例如：

```text
dag-planned
dag-levels
dag-level-start
dag-worker-start
dag-worker-result
dag-level-complete
dag-synthesis-start
dag-synthesis-result
dag-critique
dag-replan
dag-replanned
```

需要开启多 Agent 质量闭环时，可让 DAG 每轮综合后由 Critic 评分，低于阈值则 Replanner 修订 DAG 后再跑一轮：

```bash
AGENT_DAG_REPLAN_ENABLED=true
AGENT_DAG_REPLAN_THRESHOLD=0.75
AGENT_DAG_REPLAN_MAX_REPLANS=1
```

响应中的 `attempts[]` 会包含每轮 DAG 执行结果、`critique` 和聚合分；`acceptedByThreshold=false` 表示达到重规划上限后仍未过阈值。

### 数据分析智能体 —— `/agent/analyst/run`

数据分析智能体是 DAG 编排的一个「数据分析人设」入口：传一个自然语言数据问题，专用 Planner 把它拆成「探表 → 取数 → 计算 → 解读」子任务并行/依赖执行，最后综合成带 SQL 与数字依据的结论。它复用同一套 DAG 引擎（拓扑分层 / 并行 worker / synthesis / 可选 critic+replan），每个 worker 可调用 `schema_explore`（按需探表）、`analytics_sql`（只读取数）、`code_exec`（精确二次计算，默认关）动作。响应结构同 DAG（`levels` / `taskResults` / `synthesis` / `attempts`）。

```bash
# 前置：analytics-service 开 NL2SQL（NL2SQL_ENABLED=true），agent-service 常开
curl -s -X POST 'http://localhost:8080/agent/analyst/run' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"goal":"上月退款金额 top5 的客户是谁，各退多少，占总退款额多少？"}'

# 异步变体（返回 taskId，SSE 复用 /agent/tasks/{taskId}/stream，可见 dag-planned/dag-worker-*/dag-synthesis-* 进度）
curl -s -X POST 'http://localhost:8080/agent/analyst/run/async' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"goal":"分析上月各状态退款金额分布"}'
```

要让智能体自主探索库结构（而不是只依赖 NL2SQL 静态注入的全量 schema），analytics-service 额外暴露两个只读探表端点（同受 `NL2SQL_ENABLED` 门控、只暴露白名单表结构，非白名单表 404）：

```bash
curl -s 'http://localhost:8080/analytics/schema/tables' -H 'X-Api-Key: dev-key-acme'
curl -s 'http://localhost:8080/analytics/schema/tables/orders' -H 'X-Api-Key: dev-key-acme'
```

精确的跨行计算（占比 / 环比等）建议再开 `AGENT_CODE_EXEC_ENABLED=true`，否则计算子任务会退化为模型推理估算。

### 业务流程智能体 —— `/agent/process/run`（默认关，人在环）

业务流程智能体同样建在 DAG 编排上：专用 Planner 把「帮我发起退款并跟进」这类诉求拆成「发起 → 查状态 → 如实汇报」子任务，worker 调用 `refund_start`（发起退款审批流）、`workflow_status`（查实例）、`workflow_tasks`（列待办，需审批权限）动作驱动 workflow-service。**人在环治理**：智能体只发起 / 查询 / 汇报，高风险（`WAITING_APPROVAL`）会如实告知「需人工审批、尚未批准」；**不提供自动审批**——approve/reject 仍由具备 `approve` scope 的人走 `POST /workflow/tasks/{id}/complete`。智能体只在流程外编排，不进 Flowable 同步 ServiceTask。

有副作用，默认关，需 workflow-service 与 agent 侧开关同时打开：

```bash
# workflow-service：WORKFLOW_ENABLED=true（其 assess/resolve 环节还会调 conversation-service）
# agent-service：AGENT_WORKFLOW_ENABLED=true（workflow 客户端读超时默认放宽到 60s）
curl -s -X POST 'http://localhost:8080/agent/process/run' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"goal":"客户张三要退订单 O123 的 5000 元，帮我发起并告诉我进展"}'
```

`refund_start` 经内部 JWT 透传调用方的租户与 scope，因此 `workflow_tasks` 等审批相关操作天然受 `approve` scope 约束（无权限返回被翻译成中文提示），智能体不越权。

除 ReAct / DAG 外，agent-service 还提供三种轻量编排模式：`/agent/chain` 提示词链式串联、`/agent/vote` 多候选投票取自一致答案、`/agent/reflexive`（及 `/agent/reflexive/stream` SSE）Reflexion 自我反思后重试：

```bash
curl -s -X POST 'http://localhost:8080/agent/reflexive' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"question":"写一个判断闰年的函数并自检边界"}'

curl -s -X POST 'http://localhost:8080/agent/vote' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"question":"13 是质数吗？给出结论","n":5}'
```

`agent-service` 还迁入了单体里的 `code_exec` 动作，但默认关闭。需要让 Agent 执行受限 Java 片段做精确计算/格式转换时，显式开启：

```bash
AGENT_CODE_EXEC_ENABLED=true
AGENT_CODE_EXEC_TIMEOUT_MS=3000
AGENT_CODE_EXEC_MAX_OUTPUT_CHARS=2000
AGENT_CODE_EXEC_MAX_SOURCE_CHARS=4000
```

注意：默认沙箱为 `AGENT_CODE_EXEC_SANDBOX=subprocess`（独立 JDK 子进程 + 堆上限 + 清空环境/空临时 cwd + 超时强杀，中等隔离；仍共享内核/文件系统/网络命名空间，非容器级），可选降级 `jshell`（同 JVM，隔离更弱）。两种都只做 denylist、超时和输出截断，对不可信输入应保持关闭，生产建议再包独立容器/远端沙箱。详见 [docs/Agent编排/code-exec.md](docs/Agent编排/code-exec.md)。

需要让 Agent 调用外部 MCP server 时，可开启 `mcp_call` 动作：

```bash
AGENT_MCP_ENABLED=true
AGENT_MCP_TRANSPORT=http
AGENT_MCP_HTTP_URL=http://host.docker.internal:3001/mcp
```

stdio transport 也保留，使用 `AGENT_MCP_TRANSPORT=stdio` 和 `AGENT_MCP_STDIO_COMMAND` 配置启动命令。MCP action 默认关闭，避免未配置 server 时影响 agent-service 启动。

需要让 Agent 用无头浏览器访问网页时，可开启 browser-use 动作：

```bash
AGENT_BROWSER_ENABLED=true
```

开启后会暴露 `browser_open`、`browser_click`、`browser_click_xy`、`browser_type`、`browser_screenshot`。它依赖 Playwright Chromium，首次使用前需要安装浏览器二进制，例如在可联网环境运行 `mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"`。当前微服务先迁入非视觉动作，`browser_see` 会等视觉/多模态服务协议稳定后再接。

通用异步任务中心提供 `/async/tasks/**`，用于把 agent/workflow 的本地任务状态、SSE 和 webhook outbox 逐步抽到独立服务。`workflow-service` 可通过 `WORKFLOW_TERMINAL_NOTIFICATION_MODE=async-task` 把终态 webhook 投递交给中心 outbox；失败时默认回退本地 `WF_OUTBOX`。

```bash
curl -s -X POST 'http://localhost:8080/async/tasks' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{
    "kind": "agent.run",
    "input": {"goal": "查一下知识库里的退款审批规则"},
    "webhookUrl": "http://host.docker.internal:9000/async-task-callback"
  }'

curl -s 'http://localhost:8080/async/tasks/{taskId}' \
  -H 'X-Api-Key: dev-key-acme'

curl -N 'http://localhost:8080/async/tasks/{taskId}/stream' \
  -H 'X-Api-Key: dev-key-acme'

curl -N 'http://localhost:8080/async/tasks/{taskId}/stream?lastEventId=12' \
  -H 'X-Api-Key: dev-key-acme'

curl -s -X POST 'http://localhost:8080/async/tasks/{taskId}/lease' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"workerId":"worker-1","leaseSeconds":60}'

curl -s -X PATCH 'http://localhost:8080/async/tasks/{taskId}/status' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"status":"SUCCEEDED","result":{"answer":"done"},"workerId":"worker-1"}'

curl -s 'http://localhost:8080/async/webhook-outbox/dead?limit=50' \
  -H 'X-Api-Key: dev-key-acme'
```

SSE 事件包含可恢复的 event id；客户端断线后可通过标准 `Last-Event-ID` header 或 `lastEventId` query 参数恢复最近事件窗口。
分布式 worker 可先调用 `/lease` 把任务从 `PENDING` claim 到 `RUNNING`；未过期 lease 只允许 owner worker 更新状态，过期后其他 worker 可重新 claim。

任务进入 `SUCCEEDED` / `FAILED` / `CANCELLED` 后，`async-task-service` 会投递任务快照到 `webhookUrl`，请求头包含 `X-Async-Task-Id`、`X-Async-Task-Status`、`X-Tenant-Id`。JDBC outbox 中投递耗尽的记录会进入 `DEAD` 状态，可通过 `/async/webhook-outbox/dead` 按当前租户查询。当前默认是内存任务表，并已支持 agent-service 可选 mirror/authoritative，以及 workflow-service 终态通知迁移。

需要让任务表和 webhook outbox 持久化到 MySQL 时，开启 JDBC store：

```bash
ASYNC_TASK_STORE=jdbc
ASYNC_TASK_DB_URL='jdbc:mysql://mysql:3306/async_task?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&nullCatalogMeansCurrent=true'
ASYNC_TASK_DB_USER=root
ASYNC_TASK_DB_PASSWORD=root
```

JDBC 模式下会自动创建 `ASYNC_TASK` 和 `ASYNC_TASK_WEBHOOK_OUTBOX` 表；终态 webhook 先入 outbox，再由调度器按 `ASYNC_TASK_WEBHOOK_MAX_ATTEMPTS` / `ASYNC_TASK_WEBHOOK_BACKOFF` 重投。已投递成功的 outbox 记录默认保留 7 天，可通过 `ASYNC_TASK_WEBHOOK_DELIVERED_RETENTION` 调整，设为 0 或负值可关闭清理。

不用 Docker 也可本地分别跑（需本机有 LiteLLM 或把 `platform.gateway.base-url` 指向可用的 OpenAI-compat 端点）：

```bash
mvn -pl conversation-service spring-boot:run   # :8081
mvn -pl edge-gateway spring-boot:run           # :8080
```

## 验证要点

- **租户传播**：不带 `X-Api-Key` → 网关 401；带合法 key → 响应里 `tenantId`/`userId` 为该 key 绑定的租户。
- **单测**：`mvn test`（含 `InternalTokenTest` JWT 签发/校验/过期/篡改）。
