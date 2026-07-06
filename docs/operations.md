# 运行与配置手册

## 本地前置条件

- JDK 21
- Maven
- Docker / Docker Compose
- 可用的模型 provider。默认 compose 使用 LiteLLM，LiteLLM 可再接 Ollama/OpenAI/Anthropic 等 provider。

## 常用命令

```bash
# 编译所有模块
mvn -DskipTests package

# 跑全量测试
mvn test

# 启动完整本地环境
docker compose -f deploy/docker-compose.yml up --build

# 校验 compose 展开配置
docker compose -f deploy/docker-compose.yml config

# 跑 Qdrant RAG smoke
bash deploy/smoke-qdrant-rag.sh
```

## 服务端口

| 服务 | 端口 | 说明 |
|---|---:|---|
| edge-gateway | 8080 | 对外统一入口 |
| conversation-service | 8081 | Chat |
| workflow-service | 8082 | Flowable workflow |
| analytics-service | 8083 | NL2SQL |
| knowledge-service | 8084 | RAG / GraphRAG |
| agent-service | 8085 | Agent / DAG |
| async-task-service | 8086 | 通用 async task |
| channel-service | 8087 | Channel |
| interop-service | 8088 | A2A / MCP-style |
| eval-service | 8089 | Evaluation |
| LiteLLM | 4000 | LLM gateway |
| MySQL | 3306 | 本地数据库 |
| Redis | 6379 | registry / metering 可选 |
| Qdrant HTTP | 6333 | 向量库 HTTP |
| Qdrant gRPC | 6334 | 向量库 gRPC |

## 鉴权与租户

外部调用统一带 `X-Api-Key`：

```bash
curl -s -X POST 'http://localhost:8080/chat?chatId=u1' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"message":"你好"}'
```

常用开发 key 以 `edge-gateway` 配置为准。下游服务不应直接接收外部 API key，而是接收 gateway 转发的 `X-Internal-Token`。

## 关键配置

### LLM 网关

| 变量 | 默认值 | 说明 |
|---|---|---|
| `GATEWAY_BASE_URL` | `http://localhost:4000/v1` | LiteLLM/OpenAI-compatible base URL |
| `GATEWAY_API_KEY` | 空或 compose 中 master key | 模型网关 key |
| `GATEWAY_MODEL` | 服务配置决定 | chat model |

### Conversation RAG

| 变量 | 默认值 | 说明 |
|---|---|---|
| `CONVERSATION_RAG_ENABLED` | `false` | 是否在 `/chat` 前调用 RAG |
| `KNOWLEDGE_BASE_URL` | `http://localhost:8084` | knowledge-service 地址 |
| `CONVERSATION_RAG_TOP_K` | `5` | 检索条数 |
| `CONVERSATION_RAG_CATEGORY` | 空 | 限定知识分类 |

### Knowledge

| 变量 | 默认值 | 说明 |
|---|---|---|
| `RAG_VECTOR_STORE_PROVIDER` | `in-memory` | `in-memory` 或 `qdrant` |
| `RAG_REGISTRY_STORE` | `in-memory` | `in-memory` 或 `redis` |
| `RAG_EMBEDDING_PROVIDER` | `hash` | `hash` 或 OpenAI-compatible |
| `RAG_HYBRID_ENABLED` | `true` | keyword hybrid 检索 |
| `RAG_RANKING_VECTOR_WEIGHT` | `1.0` | vector 排序权重 |
| `RAG_RANKING_KEYWORD_WEIGHT` | `1.0` | keyword 排序权重 |
| `RAG_RANKING_GRAPH_WEIGHT` | `1.0` | graph 排序权重 |
| `RAG_GRAPH_ENABLED` | `false` | GraphRAG 抽取和接口 |
| `RAG_GRAPH_INCLUDE_IN_QUERY` | `false` | graph hit 是否融合进 `/rag/query` |
| `RAG_GRAPH_STORE` | `in-memory` | `in-memory` 或 `jdbc` |
| `RAG_GRAPH_DB_URL` | MySQL URL | JDBC graph store URL |

### Agent

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AGENT_ENABLED` | `true` | Agent 服务开关 |
| `AGENT_MAX_STEPS` | `8` | ReAct 最大步数 |
| `AGENT_ANALYTICS_ENABLED` | `true` | 是否允许 SQL action |
| `AGENT_CODE_EXEC_ENABLED` | `false` | 是否启用 code execution |
| `AGENT_MCP_ENABLED` | `false` | 是否启用 MCP client |
| `AGENT_BROWSER_ENABLED` | `false` | 是否启用 browser actions |
| `AGENT_ASYNC_EXTERNAL_ENABLED` | `false` | 是否镜像到 async-task-service |
| `AGENT_ASYNC_EXTERNAL_AUTHORITATIVE` | `false` | 是否以 async-task-service 为权威任务存储 |

### Async Task

| 变量 | 默认值 | 说明 |
|---|---|---|
| `ASYNC_TASK_STORE` | `in-memory` | `in-memory` 或 `jdbc` |
| `ASYNC_TASK_DB_URL` | MySQL URL | JDBC store URL |
| `ASYNC_TASK_WEBHOOK_ENABLED` | `true` | 终态 webhook |
| `ASYNC_TASK_WEBHOOK_MAX_ATTEMPTS` | `3` | 最大投递次数 |
| `ASYNC_TASK_WEBHOOK_BATCH_SIZE` | `50` | outbox batch |
| `ASYNC_TASK_WEBHOOK_DELIVERED_RETENTION` | `P7D` | delivered outbox 保留期，0 或负值关闭清理 |

### Workflow / Analytics / Channel / Eval

| 变量 | 默认值 | 说明 |
|---|---|---|
| `WORKFLOW_ENABLED` | `false` | workflow 服务开关，compose 中通常开启 |
| `WORKFLOW_DB_URL` | MySQL URL | Flowable datasource |
| `WORKFLOW_TERMINAL_NOTIFICATION_MODE` | `local` | `local` 使用 WF_OUTBOX，`async-task` 使用 async-task-service webhook outbox |
| `ASYNC_TASK_BASE_URL` | `http://localhost:8086` | workflow/agent 调用 async-task-service 的服务地址 |
| `NL2SQL_ENABLED` | `false` | analytics NL2SQL 开关，compose 中通常开启 |
| `NL2SQL_DB_URL` | MySQL URL | NL2SQL admin datasource |
| `CHANNEL_OUTBOUND_ENABLED` | `false` | 是否真实 POST webhook |
| `CHANNEL_INBOUND_SIGNATURE_ENABLED` | `false` | 是否校验入站签名 |
| `EVAL_TARGET_BASE_URL` | `http://edge-gateway:8080` | eval 默认目标 |
| `EVAL_API_KEY` | 空 | eval 调用目标时带的 API key |

## 健康检查

各服务暴露 Spring Boot actuator health：

```bash
curl -s http://localhost:8081/actuator/health
curl -s http://localhost:8084/actuator/health
curl -s http://localhost:8086/actuator/health
```

通常业务调用应从 `edge-gateway:8080` 进入；排障时可以直接访问服务端口确认下游是否健康。

## 验证路径

### Chat

```bash
curl -s -X POST 'http://localhost:8080/chat?chatId=u1' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"message":"用一句话介绍你自己"}'
```

### RAG

```bash
curl -s -X POST 'http://localhost:8080/rag/documents' \
  -H 'X-Api-Key: dev-key-acme-ingest' \
  -H 'Content-Type: application/json' \
  -d '{"title":"guide.md","text":"这是 acme 的知识库文档。","category":"manual"}'

curl -s -X POST 'http://localhost:8080/rag/query' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"query":"知识库文档","topK":3,"category":"manual"}'
```

### Async Task SSE

```bash
curl -N 'http://localhost:8080/agent/tasks/{taskId}/stream' \
  -H 'X-Api-Key: dev-key-acme'
```

## 常见问题

### 下游服务返回 401

通常是绕过 `edge-gateway` 直接访问服务但没有 `X-Internal-Token`。开发调试业务 API 时优先从 `localhost:8080` 访问。

### RAG 查询没有结果

检查：

- 上传和查询是否使用同一租户 key。
- `category` 是否一致。
- `RAG_VECTOR_STORE_PROVIDER` 是否和预期一致。
- 如果使用 Qdrant，确认 `QDRANT_HOST`、`QDRANT_PORT` 和 collection 配置。

### GraphRAG 没有图命中

检查：

- `RAG_GRAPH_ENABLED=true`。
- 文档是否包含 `subject|relation|object` 格式。
- 查询是否能被实体 linker 链接到图谱实体。
- 如果希望 `/rag/query` 混入 graph hit，还要设置 `RAG_GRAPH_INCLUDE_IN_QUERY=true`。

### Agent 异步任务没有 webhook

检查：

- `webhookUrl` 是否传入。
- `AGENT_TASK_WEBHOOK_ENABLED` 或 `ASYNC_TASK_WEBHOOK_ENABLED` 是否开启。
- authoritative 模式下是否设置了 `AGENT_ASYNC_EXTERNAL_MIRROR_WEBHOOK=true`，避免误以为 agent 本地也会投递。
