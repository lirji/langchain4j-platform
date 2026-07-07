# A2A 接入指南

本指南面向想把 **langchain4j-platform 当作一个 A2A（Agent-to-Agent）Agent** 来调用的外部客户端 / 其它 Agent。
它由 `interop-service`（:8088）实现，对外只暴露一个「发现 + JSON-RPC」互操作面，内部经内部 JWT + typed-HTTP 代理到 `agent-service`（:8085）。

> 端口约定：**业务调用一律走 `edge-gateway`（:8080）**，用 `X-Api-Key` 鉴权；网关校验后换发短时内部 JWT 转发给 `interop-service`。
> 下文示例都以 `http://localhost:8080` 为基址。`interop-service` 自身端口 :8088 仅用于服务直连 / 排障，直连时需自带内部 JWT。

---

## 1. 什么是 A2A

A2A 是一套「Agent 之间互相调用」的开放协议，核心是三件事：

1. **发现（Agent Card）**——一份声明「我是谁、在哪调、支持哪些技能、怎么鉴权」的静态元数据，发布在约定路径 `/.well-known/agent-card.json`。
2. **JSON-RPC 2.0 单端点**——所有交互（发消息、查任务、取消任务）都打同一个 URL，靠 `method` 字段区分。
3. **Task（任务）生命周期**——长跑协作被建模成一个有状态的 Task，客户端轮询它的状态直到终态。

平台的落地方式：A2A 只是**对外互操作面**，收到 JSON-RPC 后翻译成 `agent-service` 既有的 typed-HTTP 端点（`/agent/run`、`/agent/run/async`、`/agent/tasks/**`）代理调用。内部服务之间**不走 A2A**，仍用内部 JWT + typed-HTTP。

相关源码：`interop-service/src/main/java/com/lrj/platform/interop/a2a/`（`A2aController`、`A2aService`、`A2aAgentGateway`/`HttpA2aAgentGateway`、`A2aTaskMapper` 等）。

---

## 2. Agent Card 发现

平台并存**两种** agent-card，用途不同，别混用：

| 端点 | 返回类型 | 鉴权 | 用途 |
|---|---|---|---|
| `GET /.well-known/agent-card.json` | 真 A2A Agent Card（`A2aAgentCard`） | **免鉴权** | A2A 生态发现惯例别名，客户端据此决定怎么调 |
| `GET /interop/agent-card` | 平台自有互操作卡（`protocol.interop.AgentCard`） | **需鉴权**（`X-Api-Key`） | 平台自有互操作能力清单（含 MCP surface） |
| `GET /interop/a2a/agent-card` | 同上（兼容别名） | **需鉴权** | 上一条的兼容别名 |

> 注意：只有 `/.well-known/agent-card.json` 免鉴权——它在 `edge-gateway` 的 `ApiKeyToInternalTokenFilter.isOpen()` 里按 `path.startsWith("/.well-known")` 精确放行，同时在网关路由表 `interop` 路由的 `Path` 里显式列出。`/interop/**`（含 `/interop/agent-card`）仍需 `X-Api-Key`。

### 2.1 真 A2A Agent Card

```bash
# 免鉴权，直接 GET
curl -s 'http://localhost:8080/.well-known/agent-card.json'
```

返回结构（字段以 `A2aAgentCard` record 为准）：

```json
{
  "name": "langchain4j-platform",
  "description": "Platform agent exposed over the A2A protocol: sync chat and async deep-research tasks, proxied to agent-service.",
  "url": "http://localhost:8080/interop/a2a",
  "version": "0.1.0",
  "protocolVersion": "0.2.0",
  "capabilities": { "streaming": false, "pushNotifications": false, "stateTransitionHistory": false },
  "defaultInputModes": ["text/plain"],
  "defaultOutputModes": ["text/plain"],
  "skills": [
    {
      "id": "chat",
      "name": "Chat",
      "description": "Single-turn / multi-turn agent run with tools and citation.",
      "tags": ["chat", "agent", "qa"],
      "examples": ["用三句话介绍 LangChain4j"],
      "inputModes": ["text/plain"],
      "outputModes": ["text/plain"]
    },
    {
      "id": "deep-research",
      "name": "Deep research",
      "description": "Long-running deep agent run. Returned as an async A2A Task; poll via tasks/get.",
      "tags": ["research", "async", "deep-agent"],
      "examples": ["对比 PGVector / Milvus / Qdrant 三个向量库"],
      "inputModes": ["text/plain"],
      "outputModes": ["text/plain"]
    }
  ],
  "securitySchemes": {
    "apiKey": { "type": "apiKey", "in": "header", "name": "X-Api-Key", "description": "Per-tenant API key (edge-gateway)." }
  },
  "security": [ { "apiKey": [] } ]
}
```

要点：

- `url` 指向 JSON-RPC 单端点（由 `app.interop.a2a.base-url` + `/interop/a2a` 拼成，默认 `http://localhost:8080/interop/a2a`）。
- `capabilities` 三个开关**当前恒为 `false`**（无 `message/stream` 流式、无原生 push、无状态历史）——不要据此调用不存在的流式方法。
- `securitySchemes.apiKey` 明确告诉客户端：用 `X-Api-Key` 请求头带上边缘 api-key。
- `skills` 是**硬编码的两个**（`chat` / `deep-research`），与下面 live discovery（那影响的是 MCP 工具目录，不是这里的 skills）无关。

卡片元数据可配（`InteropProperties.A2a`，`application.yml` 的 `app.interop.a2a.*`）：

| 属性 | 环境变量 | 默认值 |
|---|---|---|
| `app.interop.a2a.agent-name` | `INTEROP_A2A_AGENT_NAME` | `langchain4j-platform` |
| `app.interop.a2a.base-url` | `INTEROP_A2A_BASE_URL` | `http://localhost:8080` |
| `app.interop.a2a.version` | `INTEROP_A2A_VERSION` | `0.1.0` |

> `agentDescription` 只有代码默认值，`application.yml` 未绑定环境变量（如需改需在配置中扩展）。

### 2.2 平台自有互操作卡

```bash
curl -s 'http://localhost:8080/interop/agent-card' -H 'X-Api-Key: dev-key-acme'
```

返回 `protocol.interop.AgentCard`：`name` / `description` / `version`（`0.1.0`）/ `capabilities`（字符串列表，含 `a2a.agent-card`、`mcp.tools.list`、`mcp.tools.call` + 当前 MCP 工具名）/ `endpoints`（各 interop 端点路径映射）。这张卡服务于平台自有的 MCP surface（`/interop/mcp/**`），与真 A2A 发现是两条线。

---

## 3. JSON-RPC 单端点 `/interop/a2a`

所有 A2A 交互都 `POST` 到这一个端点（**需鉴权**）。请求是 JSON-RPC 2.0 信封，`method` 决定行为。

支持的方法（`A2aService.dispatch`）：

| method | 语义 | 代理到 agent-service |
|---|---|---|
| `message/send` | 发一条消息，触发一次 agent 运行 | `chat` skill → `POST /agent/run`（同步）；`deep-research` skill → `POST /agent/run/async`（异步 Task） |
| `tasks/get` | 按 id 轮询任务状态 | `GET /agent/tasks/{id}` |
| `tasks/cancel` | 取消未终态任务 | `DELETE /agent/tasks/{id}` |

> 未列出的 method（如 `message/stream`）返回 `-32601 Method not found`。

### 3.1 请求信封

```json
{
  "jsonrpc": "2.0",
  "id": "<string | number | null，原样回带>",
  "method": "message/send",
  "params": { ... }
}
```

`A2aController` 解析规则：

- `method` 缺失或非字符串 → `-32600 Invalid Request`。
- `id` 支持 string / number / null，响应原样回带且保持类型。

### 3.2 响应信封

```json
{ "jsonrpc": "2.0", "id": "...", "result": { ... } }   // 成功
{ "jsonrpc": "2.0", "id": "...", "error": { "code": -32001, "message": "...", "data": null } }  // 失败
```

`result` 与 `error` 互斥。错误码（`JsonRpcError`）：

| code | 含义 |
|---|---|
| `-32700` | Parse error |
| `-32600` | Invalid Request（`method` 缺失/非字符串） |
| `-32601` | Method not found |
| `-32602` | Invalid params（如 message 无文本、`tasks/*` 缺 `id`） |
| `-32603` | Internal error（下游 agent-service 调用失败等） |
| `-32001` | Task not found（A2A 扩展） |
| `-32002` | Task not cancelable（已终态 / 取消失败，A2A 扩展） |
| `-32003` | Push notification not supported（A2A 扩展） |

---

## 4. `message/send`

### 4.1 params 结构（`MessageSendParams`）

```json
{
  "message": {
    "role": "user",
    "parts": [ { "kind": "text", "text": "你的问题" } ],
    "messageId": "可选",
    "taskId": "可选",
    "contextId": "可选",
    "metadata": { "skill": "chat | deep-research" }
  },
  "configuration": {
    "pushNotificationConfig": { "url": "https://your/webhook", "token": "可选", "id": "可选" },
    "blocking": true,
    "acceptedOutputModes": ["text/plain"]
  },
  "metadata": {}
}
```

关键点（以 `A2aMessage` / `A2aService` 源码为准）：

- **文本来源**：`message.parts` 里所有 `kind == "text"` 的 `text` 拼接。本实现只支持 `text` part（`Part.kind` 判别字段）。文本为空 → `-32602 Invalid params`。
- **skill 选择**：`message.metadata.skill` 决定走哪个技能——`"deep-research"` 走异步 Task，其它（含缺省）都按 `chat` 同步处理。
- **webhook**：`configuration.pushNotificationConfig.url` 会**原样透传**给 `agent-service /agent/run/async` 作为异步任务完成回调（仅 `deep-research` 路径用得上）。注意 agent card 的 `capabilities.pushNotifications=false`——这里只是把 url 透传成 agent 任务的 webhookUrl，不代表 A2A 原生 push 语义。
- 其余字段（`blocking`、`acceptedOutputModes`、外层 `metadata`、`token`/`id` 等）当前被忽略。

### 4.2 chat（同步）

`chat` skill 同步代理到 `POST /agent/run`，把 `AgentRunReply.finalAnswer` 包成一条 **agent Message** 直接返回（不是 Task）。`contextId` 取入参，缺省则生成 UUID。

请求：

```bash
curl -s -X POST 'http://localhost:8080/interop/a2a' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "message/send",
    "params": {
      "message": {
        "role": "user",
        "parts": [ { "kind": "text", "text": "用三句话介绍 LangChain4j" } ],
        "metadata": { "skill": "chat" }
      }
    }
  }'
```

响应（`result` 是一条 `A2aMessage`，`kind == "message"`）：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "role": "agent",
    "parts": [ { "kind": "text", "text": "……最终回答……" } ],
    "messageId": "<uuid>",
    "contextId": "<uuid 或入参>",
    "kind": "message"
  }
}
```

### 4.3 deep-research（异步 Task）

`deep-research` skill 代理到 `POST /agent/run/async`，返回一个 **A2A Task**（`kind == "task"`，初始状态通常 `submitted` / `working`），随后用 `tasks/get` 轮询。

```bash
curl -s -X POST 'http://localhost:8080/interop/a2a' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{
    "jsonrpc": "2.0",
    "id": "req-2",
    "method": "message/send",
    "params": {
      "message": {
        "role": "user",
        "parts": [ { "kind": "text", "text": "对比 PGVector / Milvus / Qdrant 三个向量库" } ],
        "metadata": { "skill": "deep-research" }
      },
      "configuration": {
        "pushNotificationConfig": { "url": "https://example.com/a2a/callback" }
      }
    }
  }'
```

响应（`result` 是 `A2aTask`）：

```json
{
  "jsonrpc": "2.0",
  "id": "req-2",
  "result": {
    "id": "<taskId>",
    "contextId": "<taskId>",
    "status": { "state": "submitted", "timestamp": "2026-07-07T..." },
    "kind": "task"
  }
}
```

---

## 5. Task 生命周期与状态

### 5.1 A2A 状态与 agent 状态映射

A2A `TaskState`（wire value 用连字符形式）与 `agent-service` 内部状态的映射（`A2aTaskMapper`）：

| agent 状态 | A2A `state` |
|---|---|
| `PENDING` | `submitted` |
| `RUNNING` | `working` |
| `SUCCEEDED` | `completed` |
| `FAILED` | `failed` |
| `CANCELLED` | `canceled` |
| 其它 / null | `unknown` |

`TaskState` 全集：`submitted` / `working` / `input-required` / `completed` / `canceled` / `failed` / `unknown`。

**终态**（`A2aService.isTerminal`，判定是否可取消时用 agent 侧字符串）：`SUCCEEDED` / `FAILED` / `CANCELLED`。

`A2aTask` → status 组装规则：

- `timestamp` 取 agent 的 `updatedAt` → `createdAt` → 当前时间（首个非空）。
- 任务 `SUCCEEDED`：把结果（`AgentRunReply.finalAnswer`，或结果 `toString` 兜底）摊成一个文本 **artifact**（`name = "answer"`）挂到 `artifacts`。
- 任务 `FAILED`：把 `error` 文本挂到 `status.message`（一条 agent Message），方便看失败原因。
- `contextId` 复用 `taskId`（当前无独立会话归并）。

### 5.2 `tasks/get`

params：`{ "id": "<taskId>" }`（`historyLength` / `metadata` 被忽略）；缺 `id` → `-32602`；任务不存在 → `-32001`。

```bash
curl -s -X POST 'http://localhost:8080/interop/a2a' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{ "jsonrpc": "2.0", "id": 3, "method": "tasks/get", "params": { "id": "<taskId>" } }'
```

终态成功响应示例：

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "id": "<taskId>",
    "contextId": "<taskId>",
    "status": { "state": "completed", "timestamp": "2026-07-07T..." },
    "artifacts": [
      { "artifactId": "<uuid>", "name": "answer", "parts": [ { "kind": "text", "text": "……" } ] }
    ],
    "kind": "task"
  }
}
```

### 5.3 `tasks/cancel`

params 同 `tasks/get`（只需 `id`）。行为：

- 任务不存在 → `-32001 Task not found`。
- 任务已终态（`SUCCEEDED`/`FAILED`/`CANCELLED`）→ `-32002 Task is already in a terminal state`。
- 下游取消失败 → `-32002 Task could not be canceled`。
- 成功 → 返回取消后重查的 `A2aTask`（state 通常 `canceled`）。

```bash
curl -s -X POST 'http://localhost:8080/interop/a2a' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{ "jsonrpc": "2.0", "id": 4, "method": "tasks/cancel", "params": { "id": "<taskId>" } }'
```

---

## 6. Live capability discovery（MCP 工具目录）

live discovery 影响的是 **MCP 工具目录**（`/interop/mcp/tools`、平台自有 agent-card 的 `capabilities` 列表），**不影响** `/.well-known/agent-card.json` 的两个 A2A skills（那是硬编码）。

机制（`InteropToolRegistry` + `AgentCapabilityClient`）：

- 默认**关闭**（`app.interop.discovery-enabled=false`）：走静态回退工具集 `STATIC_AGENT_TOOLS`，零下游依赖，dev/test 行为不变。
- 开启后：懒加载 + TTL 缓存，从 `agent-service GET /agent/capabilities` 拉取当前能力（返回 `List<McpToolDescriptor>`）；下游不可达 / 返回空时**确定性回退**到 last-known-good 或静态默认，永不因下游故障抛错或阻塞。

内建工具 `platform.ping` 恒在；agent 工具（静态默认）：`platform.agent.run`、`platform.agent.run_async`、`platform.agent.dag.plan_run`、`platform.agent.dag.plan_run_async`。

配置（`InteropProperties`，`app.interop.*`）：

| 属性 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `app.interop.discovery-enabled` | `INTEROP_DISCOVERY_ENABLED` | `false` | live discovery 总开关（**默认关**） |
| `app.interop.capability-ttl` | `INTEROP_CAPABILITY_TTL` | `60s` | discovery 缓存 TTL，过期触发懒刷新 |
| `app.interop.agent-base-url` | `AGENT_BASE_URL` | `http://localhost:8085` | 代理到的 agent-service 基址 |
| `app.interop.connect-timeout` | `INTEROP_CONNECT_TIMEOUT` | `1s` | 出站连接超时 |
| `app.interop.read-timeout` | `INTEROP_READ_TIMEOUT` | `30s` | 出站读超时 |

> `GET /agent/capabilities` 端点在 agent-service 侧仅当 agent 装配（`DeepAgentService` 存在）时才挂载（`@ConditionalOnBean(DeepAgentService.class)`），其声明的工具集与 interop 静态回退对齐。

---

## 7. 鉴权与租户透传

调用链的身份传播：

```
外部客户端 ──X-Api-Key──▶ edge-gateway(:8080)
                              │ 校验 api-key → 换发短时内部 JWT(X-Internal-Token)
                              ▼
                        interop-service(:8088)
                              │ interopAgentRestTemplate 挂 OutboundTenantForwarder + OutboundTraceForwarder
                              │ （内部 JWT + traceId 透传）
                              ▼
                        agent-service(:8085)  /agent/run · /agent/run/async · /agent/tasks/**
```

- 对外**唯一鉴权凭证是 `X-Api-Key`**（对应租户目录在 `edge-gateway` 的 `application.yml`）。A2A card 的 `securitySchemes` 也如实声明这一点。
- 唯一免鉴权路径：`GET /.well-known/agent-card.json`（纯静态元数据，无租户上下文亦可返回）。`POST /interop/a2a` 及其余 `/interop/**` 都需鉴权。
- 内部 JWT 承载的租户身份，经 `interopAgentRestTemplate`（`InteropConfig`，装了 `OutboundTenantForwarder`/`OutboundTraceForwarder`）透传给 agent-service，在下游还原进 `TenantContext`——A2A 任务因此天然按租户隔离。
- 服务直连 `interop-service`（:8088）时没有 edge-gateway 换发 JWT，需自行携带合法内部 JWT，一般仅用于排障。

---

## 8. 相关端点速查

| 端点 | 方法 | 鉴权 | 说明 |
|---|---|---|---|
| `/.well-known/agent-card.json` | GET | 免鉴权 | 真 A2A Agent Card 发现 |
| `/interop/agent-card` | GET | 需鉴权 | 平台自有互操作卡 |
| `/interop/a2a/agent-card` | GET | 需鉴权 | 平台自有互操作卡（别名） |
| `/interop/a2a` | POST | 需鉴权 | A2A JSON-RPC 单端点（`message/send`、`tasks/get`、`tasks/cancel`） |
| `/interop/mcp/tools` | GET | 需鉴权 | MCP 工具目录（受 live discovery 影响） |
| `/interop/mcp/tools/{toolName}` | GET | 需鉴权 | 单个 MCP 工具 descriptor |
| `/interop/mcp/call` | POST | 需鉴权 | 调用 MCP 工具 |

> 全部经 `edge-gateway`（:8080）访问；`interop-service` 直连端口为 :8088。

## 生产配置

对外暴露前，把 agent-card 元数据配成真实值（`interop-service` 环境变量）：

| 环境变量 | 作用 | 默认 |
|---|---|---|
| `INTEROP_A2A_AGENT_NAME` | agent-card `name` | `langchain4j-platform` |
| `INTEROP_A2A_AGENT_DESCRIPTION` | agent-card `description` | 见默认 |
| `INTEROP_A2A_BASE_URL` | agent-card `url` 前缀（对外可达的 A2A 端点基址） | `http://localhost:8080` |
| `INTEROP_A2A_VERSION` | agent-card `version` | `0.1.0` |
| `INTEROP_DISCOVERY_ENABLED` | live capability discovery（从 agent-service 动态拉能力） | `false` |
| `AGENT_BASE_URL` | interop→agent 内网地址 | `http://localhost:8085` |

鉴权：`/.well-known/agent-card.json` **免鉴权对外**（A2A 发现惯例，已在 edge-gateway 白名单）；`/interop/a2a` JSON-RPC 端点**需 `X-Api-Key`**（agent-card 的 `securitySchemes.apiKey` 已声明）。对外发布时，为外部调用方分配专用 api-key→租户 映射即可按租户隔离。

## 冒烟验证

栈起好后一键验证「发现 → chat → deep-research task 轮询」：

```bash
BASE_URL=http://localhost:8080 API_KEY=dev-key-acme bash deploy/smoke-a2a.sh
```

脚本依次：① 免鉴权拉 `/.well-known/agent-card.json` 并打印 name/version/skills；② `message/send`（chat skill）拿同步回复；③ `message/send`（`metadata.skill=deep-research`）拿 A2A Task → `tasks/get` 轮询到 `completed`。
