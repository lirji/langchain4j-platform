# 迁移缺口补齐方案（对话/RAG 基础层）

本文件承接 `docs/migration-roadmap.md` 与 `docs/migration-remaining-plan.md`。
后两者聚焦 Agent/编排/基建/部署维度（已完成）；本文件收口**代码级逐文件核对后发现的、对话与 RAG「可控管道」基础层的未覆盖缺口**（约 20 项）。

> 核对方法：两仓 `grep -rl` 权威比对 + 端点清单 + 关键类。legacy = `/Users/liruijun/personal/LLM/LangChain4j_project`。

## 总原则（沿用平台约定）

- 一律「接口 + 内存/Noop 默认实现 + `@ConditionalOnProperty` 开启的变体」，**默认零外部依赖、dev/test 可跑**。
- 确定性单测随代码走（纯 POJO，mock LLM，不连模型/DB/网络）。
- 跨服务 DTO 进 `platform-protocol`；provider/model 路由留在 LiteLLM。
- 租户身份走内部 JWT + `TenantContext`；测试 `@AfterEach` 清理。
- **例外**：Chat Memory 默认开（in-memory），因为当前 `/chat` 忽略 `chatId`、多轮丢上下文是**功能回退**，需恢复。护栏（PII/注入）按平台约定默认关但文档标注为生产建议开。

---

## Phase F1 — conversation-service 对话管道补全（自包含，最高日常价值）

| 项 | 能力 | legacy 源 | 落点 | 开关 |
|---|---|---|---|---|
| F1.1 | **Chat Memory 全套**：接口化 store（内存默认/Redis）+ 三滑窗（messages/tokens/summary）+ `@MemoryId` 接入 | `memory/` `store/redis` | conversation `memory/` | `app.conversation.memory.store` / `.window-mode`（默认 in-memory/messages，**开**） |
| F1.2 | **流式 `/chat/stream`**（逐 token SSE + `event: done`） | `ChatController` | gateway-client 加 `StreamingChatModel` 工厂 + conversation SSE 端点 | 端点常开 |
| F1.3 | **外置化 Prompt + per-provider override**（language/tone/citation/extra + overrides） | `AssistantProperties` `ResolvedAssistantStyle` | conversation `prompt/` | 配置驱动，默认等价现有系统提示 |
| F1.4 | **通用结构化抽取 `/extract`**（→ POJO，复用 `WorkflowAssistant`/`TicketDraft` 泛化） | `ai/extract` | conversation | 端点常开 |

## Phase F2 — 安全护栏（生产合规硬缺口）

| 项 | 能力 | legacy 源 | 落点 | 开关 |
|---|---|---|---|---|
| F2.1 | **PII 输出护栏**（邮箱/中国手机/身份证 → reprompt 重写为 `[REDACTED]`） | `ai/guardrail` | conversation `guardrail/` | `app.conversation.guardrail.pii.enabled`（默认关） |
| F2.2 | **注入输入护栏**（12 条 bilingual 规则 + 可选 LLM 分类，BLOCK/SANITIZE/AUDIT） | `ai/guardrail` | conversation `guardrail/` | `app.conversation.guardrail.injection.enabled`（默认关） |

> 依赖 langchain4j guardrail SPI + Spring bean 实例化（legacy `SpringClassInstanceFactory` 那层，带参构造 guardrail 必需）。

## Phase F3 — RAG 检索增强（knowledge-service）

| 项 | 能力 | legacy 源 | 落点 | 开关 |
|---|---|---|---|---|
| F3.1 | **Reranking**（LLM-as-judge / Jina 云） | `rag/scoring` | knowledge `rerank/` | `app.rag.rerank.enabled`（默认关） |
| F3.2 | **Query Expansion**（1→N 变体 + RRF 多路召回） | `ExpandingQueryTransformer` | knowledge `query/` | `app.rag.query-expansion.enabled`（默认关） |
| F3.3 | **History-aware 检索**（history → 自包含 query） | `CompressingQueryTransformer` | knowledge `query/` | `app.rag.history-aware.enabled`（默认关） |
| F3.4 | **Contextual Retrieval**（入库时 chunk 上下文前缀再 embed） | `rag/contextual` | knowledge `ingest/` | `app.rag.contextual.enabled`（默认关） |
| F3.5 | **Grounding 事后校验**（Layer0 引用核对 + Layer1 faithfulness，warn 模式） | `ai/grounding` | knowledge `grounding/` 或 conversation | `app.rag.grounding.enabled`（默认关） |
| F3.6 | **HanLP 中文分词**（hybrid tokenizer 变体） | `rag/hybrid` | knowledge `hybrid/` | `app.rag.hybrid.tokenizer=hanlp`（默认 simple） |

## Phase F4 — 路由 / 长期记忆 / 其它对话形态

| 项 | 能力 | legacy 源 | 落点 | 开关 |
|---|---|---|---|---|
| F4.1 | **LLM-as-Router `/chat/auto`**（分类→RAG/TOOL/CHAT 分派 + BareAssistant） | `ai/routing` | conversation `routing/` | `app.conversation.router.enabled`（默认关） |
| F4.2 | **长期记忆/用户画像**（跨会话 ProfileExtractor + `/chat/memory` `/memory/profile`） | `memory/profile` | conversation `memory/profile/` | `app.conversation.memory.profile.enabled`（默认关） |
| F4.3 | **MCP 驱动对话 `/chat/mcp`** | `ai/mcp` | conversation 或复用 interop | `app.conversation.mcp.enabled`（默认关） |
| F4.4 | **视觉对话 `/chat/vision`**（conversation → vision-service caption/describe） | `ai/vision` | conversation → vision-service | `app.conversation.vision.enabled`（默认关） |

## Phase F5 — 语音 + 可观测/评测

| 项 | 能力 | legacy 源 | 落点 | 开关 |
|---|---|---|---|---|
| F5.1 | **语音闭环**（ASR→客服脑→TTS，SSE 半流式分句） | `voice` | 新 `voice-service` 或 channel | `app.voice.enabled`（默认关） |
| F5.2 | **检索质量评测 `/eval/retrieval`**（Recall@k/Precision@k/MRR/Hit@k，不经 LLM） | `eval/retrieval` | eval-service | 端点常开 |
| F5.3 | **ChunkMetrics**（切分质量指标 Micrometer 打点） | `rag` | knowledge | 常开（入库打点） |
| F5.4 | **LLM/Embedding Health Indicators**（TCP 探测 base-url，挂 readiness） | `observability` | 各服务/共享 | 常开 |
| F5.5 | **eval harness 富度**（9-type in-process dispatch）—— 架构已换为外部 HTTP runner，评估后决定是否补 | `eval` | eval-service | 评估项 |

---

## 依赖与排期

- **F1、F2 无跨服务依赖**，全在 conversation-service（+gateway-client 一处流式工厂），先做、快见效。
- **F3** 全在 knowledge-service，与 F1/F2 并行。
- **F4.1/F4.2/F4.3** 依赖 F1（记忆/Assistant 形态）稳定后接。
- **F5** 相对独立，最后收口。

关键路径：F1.1（记忆）→ F4.2（长期记忆）；F1.3（prompt）→ F2（护栏挂 Assistant）。

## 进度

- [x] **F1.1 Chat Memory** — `conversation/memory/`：`ChatMemoryConfig`（内存默认/Redis + messages/tokens/summary）、`RedisChatMemoryStore`、`SummarizingChatMemory`；`Assistant` 加 `@MemoryId`+`@V("context")`，`/chat` 按 `<tenantId>::<chatId>` 隔离记忆、RAG 来源与用户消息分离。5 个记忆单测 + controller 测试改写，conversation 33 测试绿。
- [x] **F1.2 流式 `/chat/stream`** — gateway-client 加 `StreamingChatModel` 工厂+Bean（默认开，`platform.gateway.streaming.enabled`）；`StreamingAssistant`（TokenStream，共享记忆+context）+ `StreamingConversationController`（逐 token SSE + `event: done`/`error`）。顺带解锁 reflexion/多 Agent 未来真 token 流式。
- [ ] F1.3 外置化 Prompt
- [ ] F1.4 通用抽取 `/extract`
- [x] **F2.1 PII 护栏** — `conversation/guardrail/PiiRedactor`（email/中国手机/身份证 就地脱敏）；`/chat` 输出脱敏在回填缓存之前。默认关。
- [x] **F2.2 注入护栏** — `PromptInjectionRules`（12 条 bilingual 规则）+ `ConversationGuardrail`（block/sanitize/audit 三档）；`/chat` 与 `/chat/stream` 前置扫描，block 档命中不进 RAG/LLM/记忆。默认关。走 controller 层纯逻辑（非 langchain4j guardrail SPI），9 个护栏单测。
- [x] **F3.1 Reranking** — `knowledge/rerank/`：`Reranker` SPI + `NoopReranker`/`LlmReranker`(网关判官打分)/`JinaReranker`(Jina 云 API)；`KnowledgeQueryService` 融合后重排、候选池放大。默认关。
- [x] **F3.2 Query Expansion** — `knowledge/query/`：`QueryExpander` SPI + `LlmQueryExpander`（1→N 变体，多路召回按 max 分融合）。默认关。
- [ ] F3.3 History-aware 检索（conversation 侧：用记忆历史压缩追问为自包含 query）
- [x] **F3.4 Contextual Retrieval** — `knowledge/ingest/`：`ContextualEnricher` SPI + `LlmContextualEnricher`（入库前逐 chunk 加文档级上下文前缀再嵌入）。默认关。
- [ ] F3.5 Grounding faithfulness（conversation 侧：答案 vs RAG 来源的事后校验）
- [x] **F3.6 HanLP 中文分词** — `HanLpKeywordTokenizer`（portable 内置词典，离线）；`app.rag.hybrid.tokenizer=hanlp` 切换。默认 simple。
- 共享：`KnowledgeChatModelConfig`（温=0 网关模型，供 rerank/expansion/contextual/grounding 复用，默认全关时从不调用）。knowledge-service +19 测试（rerank5/expander4/hanlp3/contextual4+DocSvc1+…），111 全绿。
- [ ] F4.1–F4.4 路由/长期记忆/MCP/视觉对话
- [ ] F5.1–F5.5 语音/评测/可观测
</content>
</invoke>
