# Conversation Runtime 决策门禁

## 当前决定

状态为 **HOLD / shadow-only**：暂不创建独立 conversation runtime，更不把普通 Chat 放进
`agentscope-orchestrator`。

Java `conversation-service` 继续拥有：

- prompt-injection 与 PII guardrail；
- history-aware query、RAG 检索与 grounding；
- ChatMemory、用户画像和语义缓存；
- `/chat` HTTP 契约与 `/chat/stream` SSE 契约；
- 租户、用户、scope、department 和 trace 的可信上下文。

候选 runtime 未来只能拥有一次无状态模型生成。语言中立契约位于
`agentscope-platform/contracts/boundaries/conversation-generation.schema.json`，请求体只有：

- `schema_version`
- 已通过 Java 输入护栏的 `message`
- Java 本轮生成的只读 `context`
- `style`（language、tone、citation policy、extra）
- Java 从权威 memory store 导出的有界只读 `history`

请求体禁止 tenant/user/chatId、内部令牌、memory、profile 和 cache。身份只通过受验证的内部
JWT 请求头传播。

`history` 只包含 `system/user/assistant/tool` 的纯文本消息。Java 在 primary 生成前读取
`<tenantId>::<chatId>`，默认最多 12 条、总计 6000 字符、单条 2000 字符；未知或非文本消息
不导出。无论配置如何放大，契约硬上限仍是 32 条和单条 4000 字符。candidate 只能消费
快照，不能得到 store 地址或写权限。shadow 关闭时装配 no-op reader，不额外读取 Redis。

## 已实现的 shadow seam

`conversation-service` 提供默认关闭的异步 shadow observer：

1. Java 完成输入护栏、history-aware query 和 RAG。
2. Java 在 primary 写入本轮消息前捕获有界只读 history。
3. Java primary `Assistant` 正常生成并维护现有 memory。
4. 仅当 primary 实际生成时，把同轮无状态输入异步发到
   `/internal/conversation/generate`。
5. candidate reply 不进入 primary 返回、grounding、PII、cache、memory 或 profile。
6. candidate 失败、超时或线程池饱和全部吞掉，只记录低基数指标。

语义缓存命中和输入护栏阻断时不会触发 candidate。当前 shadow 只覆盖非流式 `/chat`；这也是
尚不能创建/切换独立 runtime 的明确缺口。

配置：

```text
CONVERSATION_SHADOW_ENABLED=false
CONVERSATION_SHADOW_BASE_URL=
CONVERSATION_SHADOW_CONNECT_TIMEOUT=500ms
CONVERSATION_SHADOW_READ_TIMEOUT=5s
CONVERSATION_SHADOW_HISTORY_MAX_MESSAGES=12
CONVERSATION_SHADOW_HISTORY_MAX_CHARS=6000
CONVERSATION_SHADOW_HISTORY_MAX_MESSAGE_CHARS=2000
```

启用时必须显式提供 candidate base URL，否则启动 fail closed。

指标：

- `conversation.shadow.requests{outcome=success|failure|rejected}`
- `conversation.shadow.latency{outcome=...}`
- `conversation.shadow.comparisons{exact_match=true|false}`

指标和日志不包含 prompt、回复、租户或用户正文。

内部 candidate stream envelope 已定义为 `{sequence,type,data}`，type 仅允许
`token/done/error`：token 与 error 的 data 非空，done 的 data 必须为空。Java 对外的
`blocked` 与 `grounding-warning` 仍由 Java state plane 产生，不下放给 candidate。

## 创建独立 runtime 的前置条件

以下条件全部满足前，结论保持 HOLD：

1. 无状态 request/response schema 通过兼容性门禁，且没有状态或可信身份字段。
2. 单轮 Chat shadow 覆盖质量、完成率、错误率、p95/p99 延迟和成本基线。
3. 有界只读 history snapshot 已实现；仍需在真实多轮 candidate shadow 中验证质量，且不得让
   candidate 直接读写 memory store。
4. `/chat/stream` 已有独立的语言中立 candidate event schema；仍缺真实独立进程的断连、
   上游取消、背压与错误映射测试。现有 LangChain4j `TokenStream` 不提供 cancel API，不能把
   Java emitter 关闭误判为上游模型调用已取消。
5. guardrail、RAG、grounding、cache 和 profile 的所有权测试证明仍留在 Java。
6. candidate 使用独立进程、镜像和扩缩容单元，不复用 AgentScope 在线进程。
7. 完成跨租户、超时、线程池饱和、candidate 空响应和整体回滚演练。

## Rollout 与回滚

- rollout：disabled → 内部单轮 shadow → 离线质量报告 → 测试租户 capability canary。
- 当前不允许把 candidate 设为 primary，也不允许改变 edge `/chat/**` 默认路由。
- 回滚只需设置 `CONVERSATION_SHADOW_ENABLED=false`；Java primary 和所有状态路径始终未变。
