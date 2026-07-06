# 接口与集成速查

所有业务接口建议从 `edge-gateway` 访问：

```text
http://localhost:8080
```

外部调用需要带：

```text
X-Api-Key: <api-key>
Content-Type: application/json
```

下游服务间调用使用 `X-Internal-Token`，不建议外部直接构造。

## Conversation

### POST `/chat`

用途：普通聊天，可选 RAG 增强。

请求：

```json
{
  "message": "用一句话介绍你自己"
}
```

可选 query：

```text
chatId=u1
```

响应核心字段：

```json
{
  "reply": "...",
  "chatId": "u1",
  "tenantId": "acme",
  "userId": "alice"
}
```

## Knowledge

### POST `/rag/documents`

用途：上传知识库文档。需要 `ingest` scope。

JSON 文本：

```json
{
  "title": "guide.md",
  "text": "这是知识库文档。",
  "contentType": "text/markdown",
  "category": "manual"
}
```

JSON 图片第一阶段 ingestion：

```json
{
  "title": "chart.png",
  "contentType": "image/png",
  "imageBase64": "<base64 or data-url>",
  "caption": "退款趋势图",
  "ocrText": "May refund 99",
  "category": "report"
}
```

multipart 文件：

```bash
curl -s -X POST 'http://localhost:8080/rag/documents?category=manual' \
  -H 'X-Api-Key: dev-key-acme-ingest' \
  -F 'file=@guide.pdf'
```

multipart 图片可额外传：

```bash
-F 'caption=退款趋势图' -F 'ocrText=May refund 99'
```

### GET `/rag/documents`

用途：列出租户下文档。

### GET `/rag/documents/{docId}`

用途：查看文档元数据。

### DELETE `/rag/documents/{docId}`

用途：删除文档、向量和关联图谱。需要 `ingest` scope。

### POST `/rag/query`

用途：RAG 查询。

请求：

```json
{
  "query": "退款规则是什么",
  "topK": 5,
  "minScore": 0.0,
  "category": "manual"
}
```

响应命中字段：

```json
{
  "query": "退款规则是什么",
  "tenantId": "acme",
  "hits": [
    {
      "id": "...",
      "score": 0.91,
      "docId": "...",
      "displayName": "guide.md",
      "category": "manual",
      "index": "0",
      "text": "...",
      "source": "vector|keyword|hybrid|graph"
    }
  ]
}
```

### POST `/rag/graph/query`

用途：GraphRAG 邻居查询。

```json
{
  "query": "张三负责什么团队",
  "maxHops": 2,
  "maxTriples": 20,
  "category": "org"
}
```

### GET `/rag/graph/entities`

用途：列出租户图谱实体。

可选 query：

```text
category=org
```

## Analytics

### POST `/chat/sql` 或 `/analytics/sql`

用途：NL2SQL / ChatBI。

```json
{
  "question": "2026 年 5 月一共退款了多少钱？"
}
```

## Workflow

### POST `/workflow/refund/start`

用途：发起退款审批流。

```json
{
  "chatId": "u1",
  "message": "用户申请退款，金额 99 元",
  "webhookUrl": "http://callback.local/workflow"
}
```

### GET `/workflow/tasks`

用途：查询当前租户待办。

### POST `/workflow/tasks/{taskId}/claim`

用途：认领审批任务。

### POST `/workflow/tasks/{taskId}/unclaim`

用途：取消认领。

### POST `/workflow/tasks/{taskId}/complete`

用途：完成审批。

```json
{
  "approved": true,
  "comment": "同意退款"
}
```

### GET `/workflow/instances/{instanceId}`

用途：查询流程实例。

### DELETE `/workflow/data?chatId=u1`

用途：清理指定 chatId 的工作流数据。

## Agent

### POST `/agent/run`

用途：同步深度 Agent。

```json
{
  "goal": "查一下知识库里退款审批规则，并给出简短结论"
}
```

### POST `/agent/run/async`

用途：异步深度 Agent。

```json
{
  "goal": "查一下知识库里退款审批规则，并给出简短结论",
  "webhookUrl": "http://callback.local/agent"
}
```

### POST `/agent/dag/run`

用途：显式 DAG Agent。

```json
{
  "goal": "分析退款规则并给出运营建议",
  "tasks": [
    {
      "id": "rag",
      "goal": "查询退款规则",
      "dependsOn": []
    },
    {
      "id": "summary",
      "goal": "基于上游结果总结建议",
      "dependsOn": ["rag"]
    }
  ]
}
```

### POST `/agent/dag/plan-run`

用途：自动规划 DAG 并执行。

```json
{
  "goal": "分析退款审批规则，并给出运营建议"
}
```

### POST `/agent/dag/run/async`

用途：异步显式 DAG。

### POST `/agent/dag/plan-run/async`

用途：异步自动规划 DAG。

### GET `/agent/tasks`

用途：列出当前租户 agent 任务。

### GET `/agent/tasks/{taskId}`

用途：查询 agent 任务。

### DELETE `/agent/tasks/{taskId}`

用途：取消 agent 任务。

### GET `/agent/tasks/{taskId}/stream`

用途：SSE 订阅 agent 任务状态。

```bash
curl -N 'http://localhost:8080/agent/tasks/{taskId}/stream' \
  -H 'X-Api-Key: dev-key-acme'
```

## Async Task

### POST `/async/tasks`

用途：创建通用异步任务。

```json
{
  "taskId": "optional-client-task-id",
  "type": "agent.run",
  "payload": "{\"goal\":\"...\"}",
  "webhookUrl": "http://callback.local/task"
}
```

### GET `/async/tasks`

用途：列出租户任务。

### GET `/async/tasks/{taskId}`

用途：查询任务。

### PATCH `/async/tasks/{taskId}/status`

用途：worker 更新状态。

```json
{
  "status": "RUNNING",
  "result": null,
  "error": null,
  "workerId": "agent-service-1"
}
```

### POST `/async/tasks/{taskId}/lease`

用途：worker 认领或续租。

```json
{
  "workerId": "agent-service-1",
  "leaseSeconds": 300
}
```

### DELETE `/async/tasks/{taskId}`

用途：取消任务。

### GET `/async/tasks/{taskId}/stream`

用途：SSE 状态流，支持 `Last-Event-ID` 或 `lastEventId`。

### GET `/async/webhook-outbox/dead`

用途：查看 dead webhook outbox rows。

## Channel

### GET `/channel/capabilities`

用途：查看渠道能力。

### POST `/channel/messages`

用途：发送出站渠道消息。

```json
{
  "channel": "webhook",
  "target": "http://callback.local/channel",
  "message": "hello",
  "metadata": {}
}
```

### POST `/channel/inbound`

用途：接收入站渠道事件。

```json
{
  "eventId": "evt-1",
  "channel": "webhook",
  "source": "external",
  "eventType": "message",
  "payload": "{}"
}
```

## Interop

### GET `/interop/agent-card`

用途：A2A-style agent card。

### GET `/interop/mcp/tools`

用途：列出 MCP-style tool surface。

### POST `/interop/mcp/call`

用途：调用 MCP-style tool。

```json
{
  "tool": "platform.agent.run",
  "arguments": {
    "goal": "查询退款规则"
  }
}
```

## Eval

### GET `/eval/capabilities`

用途：查看评测能力。

### POST `/eval/run`

用途：执行一组 HTTP eval case。

```json
{
  "targetBaseUrl": "http://edge-gateway:8080",
  "cases": [
    {
      "id": "chat-smoke",
      "method": "POST",
      "endpoint": "/chat?chatId=eval",
      "body": {"message": "hello"},
      "expectedContains": "reply",
      "expectedJsonPaths": {
        "$.answer": "reply"
      }
    }
  ]
}
```

### POST `/eval/suites/{suiteName}/run`

用途：执行内置或外部 baseline suite。

```json
{
  "targetBaseUrl": "http://edge-gateway:8080"
}
```
