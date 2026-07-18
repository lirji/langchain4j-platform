# MCP 接入指南

本文覆盖平台与 MCP（Model Context Protocol）的**两个方向**：

1. **平台 agent 作为 MCP client** —— `agent-service` 在 ReAct 循环里通过 `mcp_call` 动作调用外部 MCP server 暴露的工具。
2. **平台经 interop 暴露 MCP-style tool surface** —— `interop-service` 用 tools/list、tool schema、call 三个 HTTP 端点，把平台内部能力包装成 MCP 风格工具，供外部编排器调用。

两个方向相互独立，可只开其一。**方向 ① agent 作 MCP client 默认关闭**（`AGENT_MCP_ENABLED=false`，不配置外部 server 时对现有行为零影响）；**方向 ② interop 的 MCP surface 恒在**，工具目录默认经 live discovery（`INTEROP_DISCOVERY_ENABLED=true`）从 agent-service 动态发现、下游不可达即确定性回退静态。

> 端点访问约定：业务端点建议走 `edge-gateway`（`http://localhost:8080`，带 `X-Api-Key`，网关内部换发内部 JWT）。服务直连端口仅用于本地调试：`agent-service` :8085、`interop-service` :8088。

---

## 一、平台 agent 作为 MCP client（`mcp_call` 动作）

`agent-service` 内置 langchain4j 的 `McpClient`。开启后，`mcp_call` 会作为一个可插拔 `AgentAction` 出现在 agent 工具清单里，ReAct 大脑可自主选择它调用外部 MCP server 的工具。

### 装配条件

`mcp_call` 动作与 `McpClient` bean 同时受以下门控（见 `agent/mcp/AgentMcpConfig.java`、`agent/actions/McpToolAction.java`）：

- `app.agent.enabled=true`（`AGENT_ENABLED`，**默认 true**）
- `app.agent.mcp.enabled=true`（`AGENT_MCP_ENABLED`，**默认 false**）
- `McpToolAction` 额外 `@ConditionalOnBean(McpClient.class)` —— MCP 未开启时不装配 client bean，动作也不会出现。

MCP 动作默认关闭是刻意的：避免在未配置可达 server 时影响 `agent-service` 启动。

### 配置项

配置前缀 `app.agent.mcp`（`AgentMcpProperties`）。对应环境变量与默认值（来自 `agent-service/src/main/resources/application.yml`）：

| 属性 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `app.agent.mcp.enabled` | `AGENT_MCP_ENABLED` | `false` | 总开关 |
| `app.agent.mcp.transport` | `AGENT_MCP_TRANSPORT` | `stdio` | 传输方式：`stdio` 或 `http` |
| `app.agent.mcp.log-events` | `AGENT_MCP_LOG_EVENTS` | `false` | 是否记录 MCP 请求/响应日志 |
| `app.agent.mcp.stdio.command` | `AGENT_MCP_STDIO_COMMAND` | 空列表 | stdio 模式的启动命令（列表） |
| `app.agent.mcp.http.url` | `AGENT_MCP_HTTP_URL` | `http://localhost:3001/mcp` | http 模式的 MCP endpoint URL |

`transport` 非 `http`（大小写不敏感）时一律走 stdio。http 模式使用 langchain4j 的 `StreamableHttpMcpTransport`，stdio 模式使用 `StdioMcpTransport`。

### 示例：http transport

让 agent 连接一个通过 Streamable HTTP 暴露的外部 MCP server：

```bash
AGENT_MCP_ENABLED=true
AGENT_MCP_TRANSPORT=http
AGENT_MCP_HTTP_URL=http://host.docker.internal:3001/mcp
```

### 示例：stdio transport

让 agent 以子进程方式启动一个本地 MCP server：

```bash
AGENT_MCP_ENABLED=true
AGENT_MCP_TRANSPORT=stdio
AGENT_MCP_STDIO_COMMAND=npx,-y,@modelcontextprotocol/server-everything
```

> `AGENT_MCP_STDIO_COMMAND` 是列表属性，用逗号分隔各参数（Spring 会绑定成 `List<String>`）。

### agent 如何使用 mcp_call

开启后无需额外端点 —— `mcp_call` 自动进入 `/agent/run`、`/agent/run/async`、`/agent/dag/**` 等编排的可用动作集。启动时 `McpToolAction` 会调用 `mcp.listTools()` 把 MCP server 暴露的工具名/描述拼进动作 description，供大脑参考。

大脑选择该动作时，`actionInput` 需为如下 JSON（见 `McpToolAction`）：

```json
{"tool":"工具名","args":{"参数对象":"..."}}
```

- 缺 `tool` 字段、非法 JSON、或 `args` 为空对象等情况都会返回可读的中文错误提示，agent 可据此重试或改走其他动作。
- MCP 工具返回 `isError` 时，动作返回 `MCP 工具 '<tool>' 返回错误：...`；抛异常时返回失败提示，不会中断整个 agent 运行。

触发一次可能用到 MCP 的 agent run（经 edge-gateway）：

```bash
curl -s -X POST 'http://localhost:8080/agent/run' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"goal":"用外部 MCP 工具查询 xxx 并给出结论"}'
```

是否真的调用 `mcp_call` 由大脑按 goal 自主决策；平台不强制路由到 MCP。

---

## 二、平台经 interop 暴露 MCP-style tool surface

`interop-service` 对外提供一组 MCP 风格的工具端点：列目录、查单个工具 schema、发起调用。当前实现是**平台自建的 MCP-style HTTP surface**（`McpTool*` DTO + `InteropController`），把平台内部能力（目前主要是 agent-service 的运行能力）包装成工具，供外部编排器发现与调用。

> 经 edge-gateway 访问时，`/interop/**` 需带 `X-Api-Key`（仅 `/.well-known/agent-card.json` 在网关免鉴权白名单内）。直连 `interop-service` 为 :8088。

### 端点一览

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/interop/mcp/tools` | tools/list：返回工具目录 |
| GET | `/interop/mcp/tools/{toolName}` | 返回单个工具的描述与 inputSchema |
| POST | `/interop/mcp/call` | 调用某个工具 |
| GET | `/interop/agent-card` | agent card（能力清单 + 端点映射） |
| GET | `/interop/a2a/agent-card` | 同上，A2A 别名 |

（`interop-service` 另有 A2A JSON-RPC 端点，不在本文范围。）

### 工具目录

`GET /interop/mcp/tools` 返回 `McpToolDescriptor` 列表，字段：`name`、`description`、`inputSchema`（JSON Schema 对象）。

内建工具（见 `InteropToolRegistry`）：

| 工具名 | 说明 | required 参数 |
|---|---|---|
| `platform.ping` | 确定性 pong，联通性自检 | 无（可选 `message`） |
| `platform.agent.run` | 同步跑一次平台 agent | `goal`（可选 `webhookUrl`） |
| `platform.agent.run_async` | 异步启动 agent run | `goal`（可选 `webhookUrl`） |
| `platform.agent.dag.plan_run` | 自动规划并执行 DAG | `goal` |
| `platform.agent.dag.plan_run_async` | 异步规划并执行 DAG | `goal`（可选 `webhookUrl`） |

`platform.ping` 是 interop 本地内建工具（恒在）；四个 `platform.agent.*` 工具经 live capability discovery（`INTEROP_DISCOVERY_ENABLED`，**默认 true**）懒加载 + 按 TTL（`INTEROP_CAPABILITY_TTL`，默认 60s）从 agent-service 拉取能力；下游不可达时确定性回退到上面的静态默认，永不因下游故障抛错或阻塞。置 `INTEROP_DISCOVERY_ENABLED=false` 则纯取静态目录。

```bash
curl -s 'http://localhost:8080/interop/mcp/tools' \
  -H 'X-Api-Key: dev-key-acme'
```

响应（节选）：

```json
[
  {"name":"platform.ping","description":"Returns a deterministic pong response.","inputSchema":{"type":"object","properties":{"message":{"type":"string"}}}},
  {"name":"platform.agent.run","description":"Runs the platform agent through agent-service.","inputSchema":{"type":"object","required":["goal"],"properties":{"goal":{"type":"string"},"webhookUrl":{"type":"string"}}}}
]
```

### 单个工具 schema

`GET /interop/mcp/tools/{toolName}` 返回该工具的 `McpToolDescriptor`；未知工具返回 404，body 为 `{"error":"unknown tool"}`。

```bash
curl -s 'http://localhost:8080/interop/mcp/tools/platform.agent.run' \
  -H 'X-Api-Key: dev-key-acme'
```

### 调用工具

`POST /interop/mcp/call`，请求体为 `McpToolCallRequest`：

```json
{"tool":"工具名","arguments":{"参数对象":"..."}}
```

响应为 `McpToolCallReply`，字段：`tool`、`success`、`result`、`error`。

ping 自检：

```bash
curl -s -X POST 'http://localhost:8080/interop/mcp/call' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"tool":"platform.ping","arguments":{"message":"hi"}}'
```

响应：

```json
{"tool":"platform.ping","success":true,"result":{"pong":"hi"},"error":null}
```

同步跑一次 agent（interop 内部经 typed-HTTP 代理到 agent-service 的 `/agent/run`，透传租户/trace，`result` 为 `AgentRunReply`）：

```bash
curl -s -X POST 'http://localhost:8080/interop/mcp/call' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"tool":"platform.agent.run","arguments":{"goal":"查一下知识库里退款审批规则，并给出简短结论"}}'
```

响应（`result` 为 agent run 结果）：

```json
{
  "tool": "platform.agent.run",
  "success": true,
  "result": {
    "goal": "查一下知识库里退款审批规则，并给出简短结论",
    "steps": [],
    "finalAnswer": "...",
    "stopReason": "finish",
    "depth": 1,
    "tenantId": "acme"
  },
  "error": null
}
```

异步启动（可带 `webhookUrl`；`result` 为 agent-service 的异步提交响应，含 `taskId`，之后用 `/agent/tasks/{taskId}` 或其 SSE 查询）：

```bash
curl -s -X POST 'http://localhost:8080/interop/mcp/call' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{
    "tool":"platform.agent.run_async",
    "arguments":{
      "goal":"分析退款审批规则，并给出运营建议",
      "webhookUrl":"http://host.docker.internal:9000/interop-callback"
    }
  }'
```

### 错误与状态码

`/interop/mcp/call` 的失败情况（见 `InteropController` / `InteropToolDispatcher`）：

| 情况 | HTTP 状态 | `error` 值 |
|---|---|---|
| `tool` 缺失/空 | 400 | `tool is required` |
| `goal` 缺失/空（agent 类工具） | 400 | `goal is required` |
| 未知工具名 | 404 | `unknown tool` |
| 下游 agent-service 返回错误状态 | 502 | `agent-service returned HTTP <code>` |
| 下游连接/IO 异常 | 502 | 异常 message |

### agent card

`GET /interop/agent-card`（及 A2A 别名 `/interop/a2a/agent-card`）返回 `AgentCard`：`name`、`description`、`version`、`capabilities`、`endpoints`。`capabilities` 含内建 `a2a.agent-card` / `mcp.tools.list` / `mcp.tools.call`，再拼上当前 registry 里全部工具名；`endpoints` 给出各端点路径映射（`mcpTools`、`mcpTool`、`mcpCall` 等）。

```bash
curl -s 'http://localhost:8080/interop/agent-card' \
  -H 'X-Api-Key: dev-key-acme'
```

### 相关配置

interop 侧配置前缀 `app.interop`（`InteropProperties`）：

| 属性 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `app.interop.agent-base-url` | `AGENT_BASE_URL` | `http://localhost:8085` | 代理目标 agent-service 基址 |
| `app.interop.connect-timeout` | `INTEROP_CONNECT_TIMEOUT` | `1s` | 出站连接超时 |
| `app.interop.read-timeout` | `INTEROP_READ_TIMEOUT` | `30s` | 出站读取超时 |
| `app.interop.discovery-enabled` | `INTEROP_DISCOVERY_ENABLED` | `true` | live capability discovery 开关 |
| `app.interop.capability-ttl` | `INTEROP_CAPABILITY_TTL` | `60s` | discovery 缓存 TTL |

---

## 小结

- **方向一（agent 作 MCP client）**：`AGENT_MCP_ENABLED=true` + 选 `AGENT_MCP_TRANSPORT`（http/stdio），agent 大脑通过 `mcp_call` 自主调用外部 MCP server；默认关闭。
- **方向二（interop 暴露 MCP surface）**：无需开关即可用 `/interop/mcp/tools`、`/interop/mcp/tools/{name}`、`/interop/mcp/call`，内建 `platform.ping` + 四个 `platform.agent.*` 工具；工具目录默认经 `INTEROP_DISCOVERY_ENABLED=true` 从 agent-service 动态发现并可回退静态（置 `false` 则纯静态）。
