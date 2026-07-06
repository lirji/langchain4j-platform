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
   async-task / channel / interop / eval
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
| `conversation-service` | 服务 | `/chat`（可选跨服务调用 `knowledge-service` 做 RAG prompt 增强） |
| `workflow-service` | 服务 | `/workflow/**` Flowable 退款审批流 + outbox |
| `analytics-service` | 服务 | `/chat/sql`、`/analytics/sql` NL2SQL / ChatBI |
| `knowledge-service` | 服务 | `/rag/documents/**` 文档上传/列表/删除 + `/rag/query` 向量 + keyword hybrid 检索；可选 `/rag/graph/**` GraphRAG 图谱查询 |
| `agent-service` | 服务 | `/agent/run` 同步深度 Agent 编排；`/agent/run/async` + `/agent/tasks/**` 异步任务/SSE；`/agent/dag/run` 显式多 Agent DAG 编排；`/agent/dag/plan-run` 自动规划 DAG；动作通过跨服务协议调用 knowledge / analytics |
| `async-task-service` | 服务 | `/async/tasks/**` 通用任务状态、SSE 断点续订、取消与 webhook 通知中心；后续 agent/workflow 会逐步切到该服务 |
| `channel-service` | 服务 | `/channel/**` 渠道 ACL：出站 provider 边界、webhook dry-run/POST、出入站签名校验 |
| `interop-service` | 服务 | `/interop/**` A2A agent-card、MCP tool surface，并可代理 agent run/async/DAG 能力 |
| `eval-service` | 服务 | `/eval/**` 外部回归测试客户端，可执行 HTTP target case、加载 baseline suite、做响应/oracle 断言并输出 JSON report |
| `edge-gateway` | 服务 | 边缘 API 网关 |

> 后续继续加固 `channel`/`interop`/`eval` 的真实适配逻辑，并继续加固 `knowledge`/`agent`/`async-task` 的持久化和跨服务协议。

## 文档

- [文档入口](docs/README.md)
- [能力文档](docs/capabilities.md)
- [架构文档](docs/architecture.md)
- [运行与配置手册](docs/operations.md)
- [接口与集成速查](docs/api-reference.md)
- [开发者指南](docs/developer-guide.md)

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

`knowledge-service` 默认使用 `RAG_VECTOR_STORE_PROVIDER=in-memory` 和本地 deterministic hash embedding，适合开发和单测。
`/rag/query` 会融合 vector、keyword 和可选 GraphRAG 命中，可通过 `RAG_RANKING_VECTOR_WEIGHT`、`RAG_RANKING_KEYWORD_WEIGHT`、`RAG_RANKING_GRAPH_WEIGHT` 调整排序权重。
图片可以作为多模态文档上传：JSON 使用 `imageBase64` + `caption`/`ocrText`，multipart 图片使用同名表单字段；当前阶段索引 caption/OCR 文本，不保存图片字节。
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

`agent-service` 还迁入了单体里的 `code_exec` 动作，但默认关闭。需要让 Agent 执行受限 Java 片段做精确计算/格式转换时，显式开启：

```bash
AGENT_CODE_EXEC_ENABLED=true
AGENT_CODE_EXEC_TIMEOUT_MS=3000
AGENT_CODE_EXEC_MAX_OUTPUT_CHARS=2000
AGENT_CODE_EXEC_MAX_SOURCE_CHARS=4000
```

注意：当前实现基于 JDK JShell，同 JVM 执行，不是真正强隔离沙箱；它只做 denylist、超时和输出截断，生产环境应优先改成独立受限进程/容器后再开放给不可信请求。

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

通用异步任务中心提供 `/async/tasks/**`，用于把 agent/workflow 的本地任务状态、SSE 和 webhook outbox 逐步抽到独立服务：

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

任务进入 `SUCCEEDED` / `FAILED` / `CANCELLED` 后，`async-task-service` 会投递任务快照到 `webhookUrl`，请求头包含 `X-Async-Task-Id`、`X-Async-Task-Status`、`X-Tenant-Id`。JDBC outbox 中投递耗尽的记录会进入 `DEAD` 状态，可通过 `/async/webhook-outbox/dead` 按当前租户查询。当前默认是内存任务表，并已支持 agent-service 可选 mirror。

需要让任务表和 webhook outbox 持久化到 MySQL 时，开启 JDBC store：

```bash
ASYNC_TASK_STORE=jdbc
ASYNC_TASK_DB_URL='jdbc:mysql://mysql:3306/async_task?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&nullCatalogMeansCurrent=true'
ASYNC_TASK_DB_USER=root
ASYNC_TASK_DB_PASSWORD=root
```

JDBC 模式下会自动创建 `ASYNC_TASK` 和 `ASYNC_TASK_WEBHOOK_OUTBOX` 表；终态 webhook 先入 outbox，再由调度器按 `ASYNC_TASK_WEBHOOK_MAX_ATTEMPTS` / `ASYNC_TASK_WEBHOOK_BACKOFF` 重投。

不用 Docker 也可本地分别跑（需本机有 LiteLLM 或把 `platform.gateway.base-url` 指向可用的 OpenAI-compat 端点）：

```bash
mvn -pl conversation-service spring-boot:run   # :8081
mvn -pl edge-gateway spring-boot:run           # :8080
```

## 验证要点

- **租户传播**：不带 `X-Api-Key` → 网关 401；带合法 key → 响应里 `tenantId`/`userId` 为该 key 绑定的租户。
- **单测**：`mvn test`（含 `InternalTokenTest` JWT 签发/校验/过期/篡改）。
