# 迁移缺口补齐方案（对话/RAG 基础层）

本文件承接 `docs/迁移/migration-roadmap.md` 与 `docs/迁移/migration-remaining-plan.md`。
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
- [x] **F1.3 外置化 Prompt** — `conversation/prompt/`：`AssistantStyleProperties`（`@ConfigurationProperties(app.conversation.assistant)`，language/tone/citation-policy/extra + `overrides` 按 **LiteLLM 逻辑模型名** `platform.gateway.model-name` 覆盖、字段级 null 回退）+ `ResolvedAssistantStyle`(record) + `AssistantStyleConfig`（启动解析一次）。`Assistant`/`StreamingAssistant` 系统提示换成 6 段模板（Role/Language&Style/Tool Use/Citation/Safety/Extra，共用 `SYSTEM_PROMPT` 常量）+ `@V` 四参；两 controller 注入 style 逐次填入。默认值等价现有行为、citation policy 就位 → **F3.5 Layer0 全量激活**。4 个 resolver 单测 + 两 controller 测试改签名，conversation 71 全绿。
- [x] **F1.4 通用抽取 `/extract`** — `conversation/extract/`：`Ticket`(record + `@Description` 驱动 JSON Schema)、`Extractor`(AiService，迁移单体优先级 rubric + 风格规则 prompt)、`ExtractorConfig`(程序化 `AiServices.builder`，无记忆/检索)、`ExtractController`(`POST /extract?type=ticket` 抽取器注册表分派，未知 type→400)。端点常开。4 个 controller 单测，conversation 75 全绿。
- [x] **F2.1 PII 护栏** — `conversation/guardrail/PiiRedactor`（email/中国手机/身份证 就地脱敏）；`/chat` 输出脱敏在回填缓存之前。默认关。
- [x] **F2.2 注入护栏** — `PromptInjectionRules`（12 条 bilingual 规则）+ `ConversationGuardrail`（block/sanitize/audit 三档）；`/chat` 与 `/chat/stream` 前置扫描，block 档命中不进 RAG/LLM/记忆。默认关。走 controller 层纯逻辑（非 langchain4j guardrail SPI），9 个护栏单测。
- [x] **F3.1 Reranking** — `knowledge/rerank/`：`Reranker` SPI + `NoopReranker`/`LlmReranker`(网关判官打分)/`JinaReranker`(Jina 云 API)；`KnowledgeQueryService` 融合后重排、候选池放大。默认关。
- [x] **F3.2 Query Expansion** — `knowledge/query/`：`QueryExpander` SPI + `LlmQueryExpander`（1→N 变体，多路召回按 max 分融合）。默认关。
- [x] **F3.3 History-aware 检索** — `conversation/history/`：`HistoryAwareQueryCompressor` SPI + `Noop`（直通）/`Llm`（读 `ChatMemoryStore` 会话历史、取最近 N 条渲染，压缩追问为自包含检索 query，藏在 `UnaryOperator<String>` 后）+ `HistoryAwareConfig`（成对 `@ConditionalOnProperty(app.conversation.history-aware.enabled)`）。接线 `/chat` 与 `/chat/stream`：仅检索用压缩 query，用户消息原样进记忆；空历史/异常降级原文。默认关。7 个压缩单测 + 两 controller 测试改注入 Noop，conversation 52 测试绿。
- [x] **F3.4 Contextual Retrieval** — `knowledge/ingest/`：`ContextualEnricher` SPI + `LlmContextualEnricher`（入库前逐 chunk 加文档级上下文前缀再嵌入）。默认关。
- [x] **F3.5 Grounding 事后校验** — `conversation/grounding/`：`GroundingRules`（纯静态：Layer0 伪造引用核对 `[doc=ID]`、abstention 白名单跳过、`parseScore`、来源渲染、warn 后缀）+ `FaithfulnessScorer`(函数式) + `GroundingChecker` SPI + `Noop`/`Llm`（Layer0 确定性 + Layer1 faithfulness 打分 < 阈值 → warn 追加后缀，不改写/不拒答）+ `GroundingConfig`（成对开关，Llm 用 `GatewayChatModelFactory.buildDeterministic()` temp=0 判官）。`RagPromptAugmenter` 加 `contextWithHits→RagContext(context, hits)` 带回结构化来源。接线 `/chat`（RAG 仍在缓存 miss lambda 内）+ `/chat/stream`（累积 token，结束补发 `grounding-warning` 事件）。默认关。15 个单测（rules9+checker6），conversation 67 全绿。注：Layer0 在 F1.3 加 citation policy 后全量激活，Layer1 立即生效。
- [x] **F3.6 HanLP 中文分词** — `HanLpKeywordTokenizer`（portable 内置词典，离线）；`app.rag.hybrid.tokenizer=hanlp` 切换。默认 simple。
- 共享：`KnowledgeChatModelConfig`（温=0 网关模型，供 rerank/expansion/contextual/grounding 复用，默认全关时从不调用）。knowledge-service +19 测试（rerank5/expander4/hanlp3/contextual4+DocSvc1+…），111 全绿。
- [x] **F4.1 LLM-as-Router `/chat/auto`** — `conversation/routing/`：`RouteKind`(RAG/TOOL/CHAT)、`RouteDecision`/`RoutedReply`(record)、`QueryClassifier`(AiService，迁移单体分类 prompt+反例)、`QueryRouterService`(classify→dispatch：RAG 走检索、TOOL/CHAT 裸答、分类异常降级 RAG)、`RoutingConfig`(`@ConditionalOnProperty(app.conversation.router.enabled)`，classifier 用 `buildDeterministic()`)、`ChatAutoController`(`ObjectProvider` 守卫，未启用→禁用提示)。默认关。4 个 router 单测，conversation 79 全绿。
- [x] **F4.2 长期记忆/用户画像** — `conversation/memory/profile/`：`MemoryFact`/`ExtractedMemories`/`MemoryItem`(records)、`ProfileExtractor`(AiService，迁移单体抽取 prompt)、`UserProfileStore`+`InMemoryUserProfileStore`（`(tenant,user)` 键、归一化去重/含子串、容量淘汰、按 key 锁）、`UserProfileService`（recall 渲染 bullet、observe 异步抽取入库、失败静默）、`UserProfileChatService`（画像作 context 新鲜注入、不落记忆）、`MemoryProfileConfig`（成对开关，extractor 用 `buildDeterministic()`，观察线程池）、`MemoryProfileController`（`/chat/memory`、GET/DELETE `/memory/profile`，`ObjectProvider` 守卫）。默认关。12 个单测（store6+service6），conversation 91 全绿。
- [x] **F4.3 MCP 驱动对话 `/chat/mcp`** — conversation pom 加 `langchain4j-mcp`。`conversation/mcp/`：`McpAssistant`(接口)、`McpConfig`（`@ConditionalOnProperty(app.conversation.mcp.enabled)`，stdio/http transport → `DefaultMcpClient` → `McpToolProvider` → `AiServices.toolProvider`，含 `McpProperties`）、`ChatMcpController`（`ObjectProvider` 守卫，未启用→禁用提示）。默认关。2 controller + 2 wiring 单测（不做真实 MCP 连接），conversation 95 全绿。
- [x] **F4.4 视觉对话 `/chat/vision`** — `conversation/vision/`：`VisionClient` SPI（`describe` + `enabled()`）+ `HttpVisionClient`（委托 vision-service `POST /vision/caption`，instruction 非空即看图问答，RestTemplate 带租户/trace 转发）+ `NoopVisionClient`（false+matchIfMissing）+ `ConversationVisionConfig` + `VisionConversationController`（multipart 图+问题 → base64 转发，未启用/空图→提示）。复用 `platform-protocol/vision` DTO（vision-service:8090）。默认关。3 controller + 2 wiring 单测，conversation 100 全绿。
- [x] **F5.1 语音闭环 voice-service** — 新模块 `voice-service`（:8091，注册进根 pom + edge-gateway `/voice` 路由；顺带补 conversation 路由 `/extract`、`/memory`）。迁移 `SpeechService`/`OpenAiSpeechService`（JDK HttpClient ASR/TTS，零新 SDK）/`SentenceChunker`（纯状态机分句）/`VoiceProperties`；单体 `CustomerServiceBrain` 替换为 `ConversationClient`+`HttpConversationClient`（HTTP 调 conversation `/chat`，带租户/trace 转发）；`VoiceConversationService`（空转写兜底、去引用再 TTS）、`VoiceStreamService`（SSE 半流式分句 TTS）、`VoiceConfig`（`@ConditionalOnProperty(app.voice.enabled)`）、`VoiceController`（`/voice/chat`、`/voice/chat/stream`、`/voice/transcribe`）。默认关。9 个单测（chunker6+conv3），voice-service 绿。
- [x] **F5.2 检索质量评测 `/eval/retrieval`** — `platform-protocol/eval`：`RetrievalCase`/`RetrievalCaseResult`/`RetrievalSummary`/`RetrievalRunRequest`。`eval-service/retrieval/`：`RetrievalMetrics`（纯函数迁移，Recall@k/Precision@k/MRR/Hit@k，文件级 vs 精确级 id 匹配对 chunk 漂移鲁棒）、`RetrievalClient`+`HttpRetrievalClient`（经 `/rag/query` 打 knowledge，hit→`displayName#index`，失败空列表）、`RetrievalEvaluator`（逐 case 算指标 + 宏平均）；`EvalController` 加 `POST /eval/retrieval`。端点常开、不经 LLM。11 个单测（metrics9+evaluator2），eval-service 60 全绿。
- [x] **F5.3 ChunkMetrics** — `knowledge/observability/ChunkMetrics`（`@Component`，Micrometer 打点 `rag.ingest.documents`/`rag.chunk.size`(DistributionSummary)/`rag.chunk.total`/`rag.chunk.tiny`/`rag.chunk.oversize`，均带 `strategy` tag，字符计量）。`DocumentService` 加可选 `@Autowired(required=false) setChunkMetrics`（测试委托构造器天然 null→跳过），入库富化后打点。常开；`app.rag.metrics.tiny-chars`(50)/`.oversize-chars`(2000)。4 个 `SimpleMeterRegistry` 单测，knowledge 115 全绿。
- [x] **F5.4 LLM/Embedding Health Indicators** — `platform-observability`：`TcpHealthProbe`（共享静态 TCP 探测，1s 超时、端口推断、不发 LLM 请求）+ `GatewayHealthIndicator`（探 `platform.gateway.base-url`），经 `PlatformObservabilityAutoConfiguration` 的 `@ConditionalOnClass(HealthIndicator)` 注册 bean 名 `gateway`（下游有 actuator 才装配，optional actuator 依赖）。`knowledge/observability/EmbeddingHealthIndicator`（`@Component("embedding")`，按 provider 探 ollama/gateway，hash→UNKNOWN，复用探测）。常开挂 `/actuator/health`。4 个 probe 单测（ServerSocket up / 拒连 down / 解析错误 down），observability 绿。
- [x] **F5.5 eval harness 富度（评估项，结论：不补 in-process dispatch）** — 单体 `EvaluationRunner` 的 9 类 in-process dispatch（chat/graph/grounded/extract/multi-agent/reflexive/sql/a2a/workflow/agent）在 v2 已被**外部 HTTP runner** 取代：每类都对应一个真实 HTTP 端点（extract→`/extract` F1.4、grounded→`/chat` 开 grounding F3.5、retrieval→`/eval/retrieval` F5.2、sql→analytics `/chat/sql`、agent/reflexive/multi-agent→agent-service、workflow→workflow-service、a2a→interop），eval-service 用 `targetBaseUrl` + per-case 请求 + 断言（expectedContains/oracle/jsonPaths/semantic/judge/embedding）+ dual-run/gate 通打。**结论：不重新引入进程内 dispatch**——那会要求 eval-service 跨服务边界直接持有各 AiService，违背 DDD 拆分；外部 HTTP runner 更忠实（连网关/鉴权/租户一起测），且 F5.2 还补了单体所无的召回层指标。无代码改动。

### 补充：端点/包级权威比对后剩余四项（2026-07-08）

对单体 `LangChain4j_project` 端点/包级逐一核对后，确认真正还没迁的只剩这四项，本轮补齐（同分支）：

- [x] **补1 OpenTelemetry 分布式追踪** — `platform-observability/otel/`：`OtelChatModelListener`（面向 micrometer `Tracer`，每次 chat 发 `gen_ai.*` CLIENT span + 租户 tag，移植单体 span 语义）+ `OtelTracingAutoConfiguration`（`@ConditionalOnClass` micrometer+langchain4j，`ObjectProvider<Tracer>` 惰性挂，无 tracing 时 no-op）。走 **Boot 原生 `management.tracing.*` + `micrometer-tracing-bridge-otel`**（比单体自定义 `app.observability.otel.*` 更 Spring 原生；OTLP okhttp sender 不引 grpc，与固定 grpc-bom:1.59.1 无冲突）；依赖一处加在 `platform-gateway-client` 惠及 6 个 LLM 服务（agent/analytics/conversation/eval/vision/workflow），`TracingDefaultsEnvironmentPostProcessor` 以最低优先级默认 `management.tracing.enabled=false` 兜底零回归。3 个 `SimpleTracer` 单测。（knowledge 不走 gateway-client，本轮不覆盖）
- [x] **补2 `/chat/category` 按请求选类目** — `RagPromptAugmenter.contextWithHits(msg, categoryOverride)`：per-request `category`（`/chat` body 字段）非空即限定 `metadata.category` 检索，否则回退配置默认 `app.conversation.rag.category`；`/chat` 与 `/chat/stream` 均透传。knowledge 侧 category 过滤本已就绪（`KnowledgeQueryRequest.category`），仅补 conversation 侧 per-request override。+4 单测（augmenter override 优先级 + 两 controller 透传）。
- [x] **补3 Feishu 意图路由 → 工作流（含终态回推）** — 入站：`channel/feishu/`：`FeishuIntent`（关键词分类，移植单体）+ `HttpWorkflowClient`（带租户/trace 转发器调 workflow `/workflow/refund/start`，响应按 Map 读、不引 workflow 内部类型）+ `FeishuMessageBridge` 分流（命中退款/投诉→起工单 + 回执「已转人工审核（工单…）」，否则 / 起单失败→走 `/chat`）。`app.channel.feishu.intent-routing.enabled` 默认关 → 纯对话桥零回归。终态回推：工作流以 `chatId=feishu:<open_id>` 起单，终态事件经既有 `WorkflowTerminalKafkaListener.toCallback` 解析出 `channel=feishu/target=open_id`；`HttpChannelMessageDispatcher.dispatchFeishu` 补**无 webhook 时走飞书应用 API 直发 open_id**（软注入 `HttpFeishuReplyClient`，feishu 未启用则缺失），把审批结果主动推回原发起人（Kafka + HTTP callback 双通道共用）。chatId→open_id 映射即前缀解析，无需额外存储。+7 单测（intent 3 + bridge 路由/自动受理/降级 + dispatcher open_id 直发）。审批交互卡片留后续。
- [x] **补4 语音真 token 流式** — `voice`：`ConversationClient.chatStream`（默认降级为整段单 token；`HttpConversationClient` 覆盖为 RestTemplate `execute` + SSE `ResponseExtractor` 消费 `/chat/stream`，复用转发拦截器免手搓内部 JWT）+ `VoiceStreamService` 逐 token 喂 `SentenceChunker`、凑句即 TTS 发 `audio-chunk` + 断连取消。首句延迟随生成推进（原为等整段回复再分句）。+3 单测。

四项均沿用「接口/默认实现 + 默认关（追踪/意图路由）/零回归、纯 POJO 确定性单测」约定；全 reactor `mvn test` 20 模块全绿。

### 收口

**全部 20 项完成**（本轮补齐 F3.3/F3.5/F1.3/F1.4/F4.1–F4.4/F5.1–F5.5，共 13 项）。新增 1 个模块（voice-service:8091）+ 约 60 个新类 + 约 90 个确定性单测。全 reactor `mvn test` 21 模块全绿、`mvn -DskipTests package` 全量构建通过。均沿用「接口 + `@ConditionalOnProperty` 默认关（记忆/画像/护栏/RAG 增强）+ 纯 POJO 单测」约定，默认零外部依赖、零行为回退。
