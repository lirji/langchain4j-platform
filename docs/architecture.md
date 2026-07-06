# 架构文档

## 架构目标

项目目标是把原单体 `LangChain4j_project` 拆成按限界上下文自治的微服务平台，同时保留统一的安全、协议、模型网关、审计、计量和可观测基础设施。

核心原则：

- 业务 API 统一从 `edge-gateway` 进入。
- 下游服务只信任内部 JWT，不直接信任外部 API key。
- 所有 LLM 调用统一走 LiteLLM/OpenAI-compatible 网关。
- 跨服务 DTO 放在 `platform-protocol`。
- 可独立演进的状态边界拆成独立服务。
- 共享能力沉淀为 platform shared libraries，而不是重新堆回单体。

## 总体拓扑

```text
Client
  |
  | X-Api-Key
  v
edge-gateway :8080
  | validates API key, mints X-Internal-Token
  |
  +--> conversation-service :8081
  +--> workflow-service     :8082
  +--> analytics-service    :8083
  +--> knowledge-service    :8084
  +--> agent-service        :8085
  +--> async-task-service   :8086
  +--> channel-service      :8087
  +--> interop-service      :8088
  +--> eval-service         :8089

Services
  |
  | OpenAI-compatible API
  v
LiteLLM :4000
  |
  +--> Ollama / OpenAI / Anthropic / other providers
```

## 两层网关

### edge-gateway

职责：

- 对外统一暴露业务 API。
- 校验 `X-Api-Key`。
- 把 api key 映射为租户、用户、scope。
- 签发短时内部 JWT，写入 `X-Internal-Token`。
- 按路径转发到对应服务。
- 执行租户级限流。

### LiteLLM

职责：

- 统一 LLM provider 接口。
- 对业务服务暴露 OpenAI-compatible API。
- provider 路由、fallback、模型名映射。

这两个网关职责不同：`edge-gateway` 管业务流量，LiteLLM 管模型流量。

## 服务边界

| 服务 | 端口 | 边界职责 | 主要状态 |
|---|---:|---|---|
| `conversation-service` | 8081 | Chat API 和 RAG prompt augmentation | 对话请求本身暂无独立持久化 |
| `workflow-service` | 8082 | Flowable 审批流程 | Flowable MySQL、reply store、workflow outbox；终态通知可切到 async-task-service |
| `analytics-service` | 8083 | NL2SQL / ChatBI | demo SQL DB、只读查询连接 |
| `knowledge-service` | 8084 | RAG 文档、向量、GraphRAG | vector store、document registry、graph store |
| `agent-service` | 8085 | Agent/DAG 编排 | 本地 async task store，可镜像到 async-task-service |
| `async-task-service` | 8086 | 通用异步任务中心 | in-memory 或 JDBC task/outbox，delivered outbox retention |
| `channel-service` | 8087 | 渠道 ACL、webhook/Feishu 出站和入站事件 | 当前主要无状态 |
| `interop-service` | 8088 | A2A/MCP-style 对外互操作 | 当前主要无状态 |
| `eval-service` | 8089 | 回归评测执行 | baseline 文件、可选 report 输出 |
| `edge-gateway` | 8080 | 边缘路由、安全和限流 | API key 配置、限流计数 |

## 共享库

| 模块 | 作用 |
|---|---|
| `platform-security` | `TenantContext`、内部 JWT、下游入站 filter、出站 token 透传 |
| `platform-observability` | trace id filter、跨服务 trace 透传 |
| `platform-gateway-client` | LiteLLM/OpenAI-compatible `ChatModel` 工厂 |
| `platform-protocol` | 跨服务 DTO 和协议对象 |
| `platform-audit` | 审计事件和 LLM audit listener |
| `platform-metering` | token budget、cost tracking、Redis/in-memory counter |

## 典型调用链

### Chat with RAG

```text
Client
  -> edge-gateway /chat
  -> conversation-service
  -> knowledge-service /rag/query
  -> conversation-service builds augmented prompt
  -> LiteLLM
  -> model provider
```

### Agent 访问知识库和数据分析

```text
Client
  -> edge-gateway /agent/run
  -> agent-service
     -> knowledge-service /rag/query       (rag_search)
     -> analytics-service /analytics/sql   (analytics_sql)
     -> LiteLLM                            (reasoning / synthesis)
```

### Async Agent with Shared Task Center

```text
Client
  -> edge-gateway /agent/run/async
  -> agent-service
     -> async-task-service /async/tasks             (mirror or authoritative)
     -> async-task-service /async/tasks/{id}/lease  (authoritative mode)
     -> async-task-service /async/tasks/{id}/status
     -> async-task-service /async/tasks/{id} DELETE (authoritative cancellation)
Client
  -> /agent/tasks/{id}/stream or /async/tasks/{id}/stream
```

### Knowledge Ingestion

```text
Client
  -> edge-gateway /rag/documents
  -> knowledge-service
     -> Tika text extraction or caption/OCR text assembly
     -> splitter
     -> embedding model
     -> embedding store
     -> document registry
     -> optional GraphRAG triple ingestion
```

## 安全模型

外部请求：

1. 调用方提供 `X-Api-Key`。
2. `edge-gateway` 校验 key，解析出 tenant/user/scopes。
3. gateway 签发短时内部 JWT，放到 `X-Internal-Token`。
4. 下游服务通过 `platform-security` 解析 JWT 并写入 `TenantContext`。
5. 服务内部用 `TenantContext` 做租户隔离和 scope 判断。

跨服务请求：

- RestTemplate/WebClient 通过共享拦截器转发 `X-Internal-Token`。
- trace id 通过 `platform-observability` 透传。

## 数据与存储边界

| 数据 | 当前存储 |
|---|---|
| RAG 向量 | in-memory 或 Qdrant |
| 文档 registry | in-memory 或 Redis |
| GraphRAG triples | in-memory 或 JDBC/MySQL |
| Workflow state | Flowable MySQL |
| Workflow outbox/reply | workflow datasource |
| Async task | in-memory 或 JDBC/MySQL |
| Async webhook outbox | JDBC 模式下 MySQL |
| NL2SQL demo 数据 | MySQL |
| Eval reports | 可选本地目录 |

## 部署视图

`deploy/docker-compose.yml` 提供本地一体化环境：

- `mysql`
- `redis`
- `qdrant`
- `kafka`
- `litellm`
- 所有业务服务

生产化时建议把外部依赖拆成托管服务，并明确每个服务的数据库、连接池、密钥和资源限额。

## 当前架构风险与后续演进

- channel 的 voice 真实 adapter 尚未落地。
- GraphRAG 当前是确定性三元组，后续可接入 LLM/IE 抽取和图数据库。
- 图片 ingestion 当前由调用方提供 caption/OCR，后续可接 vision/OCR provider。
