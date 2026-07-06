# 能力文档

## 一句话概览

本项目是一个基于 Spring Boot、LangChain4j、LiteLLM 和 DDD 限界上下文拆分的企业级 AI 微服务平台。它把聊天、知识库 RAG、GraphRAG、NL2SQL、Agent 编排、工作流审批、异步任务、渠道接入、互操作协议和回归评测拆成独立服务，并通过统一网关、租户鉴权、共享协议和可观测能力连接起来。

## 核心能力矩阵

| 能力域 | 当前能力 | 主要模块 |
|---|---|---|
| 边缘网关 | API key 鉴权、租户识别、内部 JWT 签发、服务路由、限流 | `edge-gateway`, `platform-security` |
| LLM 网关 | 所有模型调用走 OpenAI-compatible/LiteLLM，支持 provider 路由和失败转移 | `platform-gateway-client`, LiteLLM |
| 对话服务 | `/chat` 对话入口，可选 RAG 上下文增强 | `conversation-service` |
| 知识库 RAG | 文档上传、Tika 文本抽取、分块、向量检索、keyword hybrid、可配置排序权重 | `knowledge-service` |
| 多模态 ingestion | 图片作为文档上传，索引调用方提供的 caption/OCR 文本 | `knowledge-service` |
| 向量存储 | in-memory 默认实现；Qdrant 可选持久化向量库 | `knowledge-service` |
| GraphRAG | 确定性三元组抽取、实体链接、邻居查询、可选图谱融合到 `/rag/query` | `knowledge-service` |
| 图谱持久化 | in-memory 或 JDBC/MySQL 图谱存储 | `knowledge-service` |
| NL2SQL / ChatBI | 自然语言转 SQL、只读查询保护、demo 数据源种子 | `analytics-service` |
| 工作流 | Flowable 退款审批流、人工审批、超时处理、终态 outbox | `workflow-service` |
| 深度 Agent | ReAct loop、RAG/SQL/time/delegate 动作、同步执行 | `agent-service` |
| DAG Agent | 显式 DAG、自动规划 DAG、异步 DAG、可选 critique/replan | `agent-service` |
| Agent 工具 | 可选 code execution、MCP client、browser actions | `agent-service` |
| 异步任务 | 通用任务状态、租户隔离、取消、SSE、worker lease、webhook outbox、delivered retention | `async-task-service` |
| 渠道接入 | 渠道 capability、webhook 出站、入站事件、HMAC 签名校验 | `channel-service` |
| 互操作 | A2A agent-card、MCP-style tools、代理 agent run/async/DAG | `interop-service` |
| 回归评测 | HTTP case 执行、baseline suite、oracle contains、JSON report | `eval-service` |
| 审计与计量 | 审计日志、LLM audit listener、token budget、cost attribution | `platform-audit`, `platform-metering` |
| 可观测性 | trace id 生成和跨服务透传 | `platform-observability` |

## 业务能力说明

### 1. 对话与 RAG 增强

`conversation-service` 提供 `/chat`，默认直接调用 LLM。开启 `CONVERSATION_RAG_ENABLED=true` 后，请求会先调用 `knowledge-service` 的 `/rag/query`，把检索结果注入 prompt，再交给 LLM。

适用场景：

- 企业知识库问答。
- 客服机器人补充内部文档上下文。
- 后续多 Agent 任务中的知识查询动作。

### 2. 知识库与 GraphRAG

`knowledge-service` 支持：

- JSON 文本上传和 multipart 文件上传。
- Apache Tika 文本抽取，覆盖 PDF、Office、HTML、纯文本等格式。
- 图片 ingestion 第一阶段能力：上传图片时传入 caption/OCR，系统索引这些文本，不保存图片字节。
- Markdown header、parent-child、semantic chunking 等分块策略。
- in-memory 或 Qdrant 向量存储。
- in-memory 或 JDBC/MySQL 图谱存储。
- vector、keyword、graph 命中融合排序，权重可配置。

当前 GraphRAG 是确定性三元组抽取，输入格式示例：

```text
张三|隶属于|研发部
研发部|使用|LangChain4j
```

### 3. Agent 编排

`agent-service` 支持同步和异步两类运行方式：

- `/agent/run`：单次同步深度 Agent。
- `/agent/run/async`：异步运行，返回 taskId。
- `/agent/dag/run`：调用方显式传 DAG。
- `/agent/dag/plan-run`：模型自动规划 DAG 后执行。
- `/agent/tasks/{taskId}/stream`：SSE 订阅任务状态。
- async-task-service authoritative 模式下，取消会同步到中心任务服务，并通过 worker cancellation token 阻止 queued/running work 继续写成功态。

当前内置动作包括：

- `rag_search`：调用 knowledge-service。
- `analytics_sql`：调用 analytics-service。
- `current_time`：本地时间工具。
- `delegate`：受控委派。
- `code_exec`：默认关闭。
- `mcp_call`：默认关闭。
- browser actions：默认关闭。

### 4. 异步任务中心

`async-task-service` 是跨服务通用任务状态中心，支持：

- 创建任务。
- 查询任务详情和列表。
- 更新任务状态。
- 删除/取消任务。
- SSE 断点续订。
- worker lease 认领和 owner-only 状态更新。
- 终态 webhook 投递。
- JDBC 存储和 JDBC webhook outbox，包含 delivered outbox 保留期清理。

`agent-service` 可以用 mirror 或 authoritative 模式逐步迁移到该任务中心。

### 5. 工作流审批

`workflow-service` 拆出了 Flowable 退款审批流，支持：

- 发起退款审批。
- 查询待办。
- claim/unclaim。
- 审批完成。
- 查询流程实例。
- 按 chatId 清理数据。
- 终态 outbox 回调。

### 6. 数据分析

`analytics-service` 提供 NL2SQL / ChatBI：

- `/chat/sql`
- `/analytics/sql`

服务内置 SQL guard 和只读连接边界，避免生成写操作直接执行。

### 7. 渠道与互操作

`channel-service` 当前提供 webhook 级别的渠道边界和签名能力，Feishu/voice 真实适配仍是后续项。

`interop-service` 提供 A2A/MCP-style 互操作入口，可把平台能力暴露给外部 Agent 或工具调用方。

### 8. 回归评测

`eval-service` 能执行 HTTP baseline suite，用于微服务改造时对齐原单体行为或锁定平台 smoke case。当前支持：

- HTTP status 检查。
- response contains。
- oracle contains。
- JSON report 输出。

## 当前限制

- 图片 ingestion 目前不调用视觉模型/OCR provider，只索引请求方提供的 caption/OCR 文本。
- GraphRAG 抽取是确定性三元组格式，不是开放信息抽取。
- channel-service 真实 Feishu/voice adapter 还未完成。
- async-task JDBC task lease 的多副本原子领取仍需进一步加固。
