# 业务场景落地总览

这份文档是**业务场景层的导航入口**：把平台已经组装成的、可交付的业务场景汇总在一处，
回答「这些底座能力被拼成了哪些业务场景、各自从哪个端点接入、跑到什么程度、深入看哪篇文档」。

它和 `docs/参考/capabilities.md` 是两种视角，不重复：

- **`capabilities.md`** 是**能力目录**——逐条列平台有哪些原子能力、端点、默认开关（横向切片）。
- **本文（`scenarios.md`）** 是**场景装配视图**——这些原子能力被组合成哪些业务闭环（纵向切片）。

> 关联：能力目录 → `capabilities.md`；系统架构 / 两层网关 / DDD 拆分 → `架构文档.md`；
> 运行配置 / 端口 / 起栈 → `operations.md`；接口速查 → `api-reference.md`；事件总线（跨服务原子性）→ `eventbus-guide.md`。

## 访问约定

所有业务端点都经**唯一对外入口 `edge-gateway`（`http://localhost:8080`）** 访问，带 `X-Api-Key`：
网关校验 api-key → 识别租户/scope → 签发短时内部 JWT（`X-Internal-Token`）→ 按路径路由到下游服务。
下游服务只信任内部 JWT，租户身份随之跨每一跳传播并还原进 `TenantContext`（多租户硬隔离）。
服务直连端口（conversation:8081 … voice:8091）仅供本地调试，不建议对外。

**默认开关基线**：绝大多数场景能力**默认关闭 / 内存实现**，dev/test 零外部依赖。
下表「状态」列如实标注每个场景当前跑到哪一步——「核心可跑」= 端点就位、内存/确定性实现可直接验；
「代码就位・默认关」= 逻辑与单测就位，但需显式开开关 + 配真实 provider / 外部应用才能端到端。

---

## 场景一览

| 场景 | 子能力 | 入口端点（经 :8080） | 状态 | 详细文档 |
| --- | --- | --- | --- | --- |
| **① 企业知识库问答（RAG）** | 文档入库 + 带引用多轮问答 + 多租户隔离 | `POST /rag/documents`、`POST /chat` | 核心可跑（内存向量库）；生产后端 / RAG 增强默认关 | `rag-guide.md`、`memory-guide.md`、`semantic-cache.md`、`eval-guide.md` |
| **② 智能客服** · NL2SQL/ChatBI | 自然语言查业务库（6 层 SQL 护栏） | `POST /chat/sql`（别名 `/analytics/sql`） | 代码就位・默认关（需只读 DB + tool-calling 模型） | `nl2sql-guide.md` |
| **② 智能客服** · 工作流审批 | 退款等挂人工审批的长流程（Flowable） | `POST /workflow/refund/start` | 核心可跑（需 MySQL）；终态通知可切事件总线 | `workflow-guide.md` |
| **② 智能客服** · 渠道接入 | 飞书 / 钉钉群内 @机器人 入站事件桥 | `POST /channel/feishu/events`、`POST /channel/dingtalk/events` | 代码就位・默认关（需真应用 + 公网回调联调） | `dingtalk-guide.md` |
| **② 智能客服** · 语音渠道 | 音频 → ASR → 对话 → TTS 语音闭环 | `POST /voice/chat`、`/voice/chat/stream` | 代码就位・默认关（需配 ASR/TTS provider） | `voice-guide.md` |
| **③ 深度 Agent / 多 Agent 编排** | ReAct 单 Agent + DAG 多 Agent 编排 | `POST /agent/run`、`/agent/dag/run`、`/agent/dag/plan-run` | 核心可跑（`AGENT_ENABLED=true` 默认开）；受限动作默认关 | `agent-guide.md`、`workflow-guide.md`、`code-exec.md` |
| **④ 多模态视觉** | 看图对话 + 图片 caption/描述 + 图片 RAG | `POST /chat/vision`、`/vision/caption` | 代码就位・默认关（整服务需 `VISION_ENABLED`+模型） | `vision-guide.md` |
| **⑤ 互操作（A2A / MCP）** | 对外 Agent 协作面（真 A2A + MCP tool surface） | `POST /interop/a2a`、`GET /.well-known/agent-card.json`、`/interop/mcp/**` | 核心可跑（端点常开）；live discovery 默认关 | `a2a-guide.md`、`mcp-guide.md` |

> **说明**：「智能客服」不是单个服务，而是**由 NL2SQL + 工作流审批 + 渠道接入（飞书/钉钉/语音）拼成的闭环**——
> 用户从任一渠道进来 → 意图分流（退款/投诉走工作流，查数走 NL2SQL，其余走 RAG 对话）→ 长流程挂人工审批 → 终态主动回推。
> 飞书、钉钉、语音三个渠道**共用同一套下游大脑**（都最终打 conversation 的 `/chat`，可带 RAG），渠道只负责验签、去重、格式转换。

---

## ① 企业知识库问答（RAG）

**做什么**：把企业文档（PDF / Word / Excel / PPT / HTML / Markdown / 纯文本）按租户入库，
支持带来源引用的多轮问答，按租户硬隔离、可持久化不丢。

**怎么装配**：`knowledge-service`（`:8084`）负责入库与检索，`conversation-service`（`:8081`）负责对话。

- **入库**：`POST /rag/documents`（JSON 文本或 multipart 文件）→ Apache Tika 按内容嗅探类型抽取 → 分块（Markdown header / parent-child / semantic）→ Embedding 向量化 → 写入**按租户隔离的 collection**（`collection-per-tenant` 默认）。可选 GraphRAG 三元组抽取。
- **检索**：`POST /rag/query` 融合 vector + keyword（默认开）+ 可选 graph 命中，加权排序取 topK。
- **对话增强**：`POST /chat` 默认直连 LLM；开 `CONVERSATION_RAG_ENABLED=true` 后先查 `/rag/query`、把检索结果注入 prompt 再作答，回复带 `[doc=文件名#N]` 引用。
- **前置提速**：可选 L1 语义缓存（问题级、按租户分桶、在 RAG 之前，命中即短路 RAG+LLM）与多轮记忆（会话隔离滑窗）。

**状态**：**核心可跑**——开箱即用（in-memory 向量库 + 确定性 hash embedding），无需任何外部基础设施即可上传/问答/验隔离。
生产能力**默认关、按需开**：持久化向量库（`RAG_VECTOR_STORE_PROVIDER=qdrant|pgvector|milvus|chroma|doris`）、
真语义 embedding（`RAG_EMBEDDING_PROVIDER=openai|ollama`）、检索重排（`RAG_RERANK_ENABLED`）、查询扩展（`RAG_QUERY_EXPANSION_ENABLED`）、
上下文增强（`RAG_CONTEXTUAL_ENABLED`）、GraphRAG（`RAG_GRAPH_ENABLED`）、`/chat` RAG 增强（`CONVERSATION_RAG_ENABLED`）均默认关。

**深入看**：接入/向量库选型/GraphRAG/语义缓存联动 → `rag-guide.md`；多轮记忆与长期画像 → `memory-guide.md`；
L1 语义缓存单篇 → `semantic-cache.md`；检索召回评测（Recall@k / MRR）→ `eval-guide.md`。

---

## ② 智能客服

客服场景是**多块拼成的闭环**。下面四个子能力各自独立、可单独开，也可组合成「渠道进 → 意图分流 → 查数/审批 → 回推」的完整链路。

### ②.1 NL2SQL / ChatBI

**做什么**：客服 / 运营用自然语言提问 → LLM 生成 SQL → **只读执行** → LLM 解读结果，让不会写 SQL 的人也能查业务库（订单状态、退款记录等）。
核心是 6 层 SQL 安全护栏：只读账号 → 语句白名单（仅 SELECT）→ 表白名单 → 强制 LIMIT → 执行超时 → **强制注入租户谓词** `tenant_id = ?`。

**入口**：`POST /chat/sql`（别名 `/analytics/sql`，analytics :8083），body `{"message":"..."}` → 返回 `{question, sql, rowCount, rows, answer, ...}`。

**状态**：**代码就位・默认关**。整套 NL2SQL 装配是 `@ConditionalOnProperty(app.nl2sql.enabled)`，默认 `NL2SQL_ENABLED=false` —— 关时端点不注册（零开销）。
开启需：`NL2SQL_ENABLED=true` + 一个可达的只读业务库（`NL2SQL_DB_URL` / `NL2SQL_DB_READONLY_URL` / `NL2SQL_SEED_SCRIPT`）+ 支持 tool-calling 的 chat 模型。

**深入看** → `nl2sql-guide.md`（护栏细节、Schema 注入、few-shot、启用 curl）。

### ②.2 工作流审批（Flowable）

**做什么**：退款 / 改单 / 投诉升级等「挂起等人工审批」的长流程。抽工单 → 高优先级进人工审批（Flowable `UserTask` 挂起，引擎表持久化，期间服务重启不丢）→ 通过/驳回 → 生成答复；低风险自动受理。

**入口**（workflow :8082）：`POST /workflow/refund/start` 发起；`GET /workflow/tasks` 待办（需 `approve` scope）；
`POST /workflow/tasks/{taskId}/complete` 审批（body `{approved, comment}`）；`GET /workflow/instances/{instanceId}` 查状态。
回复生成 / 工单抽取默认经 HTTP 调 conversation-service（`/conversation/workflow/reply`、`/ticket`），不直连本地 ChatModel。

**状态**：**核心可跑**——端点常开，需一个可登录的 MySQL（Flowable 自管其表）；assess/resolve 调模型需可达 chat provider（如本机 Ollama）。
终态通知默认本地投递，可切 `WORKFLOW_TERMINAL_NOTIFICATION_MODE=async-task` 走异步任务中心 outbox，或 Kafka 档下经事件总线原子回推。

**深入看** → `workflow-guide.md`。**注意「workflow」在本平台有两层含义**：
(a) 本节的**业务流程引擎**（Flowable BPMN，人在环审批）；(b) **LLM 编排模式**（chain / DAG / vote / reflexion / routing，见场景 ③）——
两者都在 `workflow-guide.md` 里区分说明，不要混淆。

### ②.3 渠道接入（飞书 / 钉钉入站事件桥）

**做什么**：接群内 @机器人 消息 → 验签 + 按 msgId 去重 → 设租户 → 调 conversation `/chat`（可带 RAG）→ 机器人回消息。
意图分流：退款/投诉类 → 工作流，其余 → 对话。**钉钉侧带「知识库无命中 → 转人工 @人工客服」兜底闸门**（作答前先查 `/rag/query` 判命中，命中不足不调 LLM、直接转人工）。

**入口**（channel :8087）：`POST /channel/feishu/events`、`POST /channel/dingtalk/events`。
edge-gateway **白名单放行这两条回调路径**（免 api-key，靠渠道签名验真：飞书 `SHA-256 + AES-256-CBC + verification token`、钉钉 `HmacSHA256(timestamp+"\n"+appSecret)`）。

**状态**：**代码就位・默认关**——验签 / 去重 / 意图路由 / 转人工兜底为纯逻辑且有单测；
入站事件桥默认关（`FEISHU_*` / `DINGTALK_*` 开），出站发消息 / 卡片回调需接真实飞书/钉钉应用 + 公网回调联调。
企微 / Web 复用同一范式（渠道适配层可插拔）。

**深入看** → `dingtalk-guide.md`（含飞书 & 钉钉两侧验签、事件桥、转人工兜底、edge-gateway 放行配置）。

### ②.4 语音渠道（ASR → 对话 → TTS）

**做什么**：turn-based 语音客服闭环。音频 → ASR（whisper）转文本 → 走与 `/chat` 同一套鉴权链打 conversation → 回复文本 → TTS 合成语音返回，**复用同一套意图路由 / 工作流 / RAG 下游大脑**。

**入口**（voice :8091）：`POST /voice/chat`（multipart `audio`，整轮返回 `transcript` + 回复文本 + base64 语音）；
`POST /voice/chat/stream`（SSE 半流式：先发 `transcript`，再按句切分逐句 TTS 发 `audio-chunk`，最后 `done`）；`POST /voice/transcribe`（仅 ASR）。

**状态**：**代码就位・默认关**——`voice-service` **默认整服务不装配**（`VOICE_ENABLED=false`），编排 + 分句半流式有单测；
开启需配 provider：`VOICE_PROVIDER=openai`（OpenAI 兼容协议，`VOICE_BASE_URL` 可指云 OpenAI / Azure / 本地 whisper+tts 网关，`VOICE_API_KEY`），ASR/TTS 模型可调。

**深入看** → `voice-guide.md`。

---

## ③ 深度 Agent / 多 Agent 编排

**做什么**：把复杂任务交给会「思考 → 调工具 → 观察 → 再思考」的 Agent，或拆成多个子 Agent 并行协作。
`agent-service`（`:8085`）提供五种同级编排模式，共享网关 `ChatModel` + 审计/计量 listener + SSE 进度。

- **深度 Agent（ReAct）**：`POST /agent/run`（同步）、`/agent/run/async`（返回 taskId + SSE）。内置动作 `rag_search`（调 knowledge）、`analytics_sql`（调 analytics）、`current_time`、`delegate`、`finish`。
- **DAG 多 Agent**：`POST /agent/dag/run`（显式传 DAG）、`POST /agent/dag/plan-run`（Planner 自动拆 DAG），各带 `/async`。Kahn 拓扑分层、同层并行、下游看上游、末尾综合；可选 critique+replan 质量闭环。
- 另有链式 `POST /agent/chain`（步间确定性 gate）、投票 `POST /agent/vote`（N 次取共识）、反思 `POST /agent/reflexive[/stream]`（answer→critique→improve 自省环）。

**状态**：**核心可跑**——`AGENT_ENABLED=true`（默认开），ReAct / DAG / reflexion / voting 端点即挂载，内存任务态 + SSE 可直接验；
链式 `steps` 默认空需先配置；DAG replan（`AGENT_DAG_REPLAN_ENABLED`）默认关。
**受限动作全部默认关**：`code_exec`（同/子 JVM 执行，中等隔离非强沙箱）、`mcp_call`、`browser_*`、`browser_see`（视觉，需 browser + vision 双开）。

**深入看**：五种模式 / 动作 / 任务态 → `agent-guide.md`；受限代码执行的隔离边界与坑 → `code-exec.md`；
业务流程引擎 vs LLM 编排模式的辨析 → `workflow-guide.md`。

---

## ④ 多模态视觉

**做什么**：看图对话与图片理解。三个入口，共用经 LiteLLM 的多模态 `ChatModel`（仅逻辑模型名不同）：

- **看图对话**：`POST /chat/vision`（conversation multipart，图片 + 问题）→ 委托 vision-service → 返回 `reply`。
- **图片 caption / 描述**：`POST /vision/caption`（别名 `/vision/describe`，vision :8090，JSON `imageBase64` 或 multipart）。
- **图片 RAG（CLIP 多模态 embedding）**：`POST /rag/image` 入库、`POST /rag/image-search` 文本跨模态检索图片（图片向量存独立 collection，与文本隔离）。
- 还供 agent 的 `browser_see` 动作复用（截图 → 看图）。

**状态**：**代码就位・默认关**。`vision-service` **默认整服务不装配**（`VISION_ENABLED=false`；开启但 `VISION_MODEL` 留空则启动 fail-fast）；
对话入口另需 `CONVERSATION_VISION_ENABLED=true` + `CONVERSATION_VISION_BASE_URL`（默认 `http://localhost:8090`）；
图片 RAG 需 `RAG_MULTIMODAL_ENABLED=true` + CLIP/jina-clip embedding；agent 视觉动作需 `AGENT_VISION_ENABLED=true`。开启后需一个多模态模型（如 `gpt-4o-mini` / `qwen2.5-vl`，经 LiteLLM 路由）。

**深入看** → `vision-guide.md`。

---

## ⑤ 互操作（A2A / MCP）

**做什么**：把本平台作为一个**可被外部 Agent 生态发现和调用的节点**，同时也能作 MCP client 调外部工具。
`interop-service`（`:8088`）是对外互操作面，内部仍走 typed-HTTP 代理到下游。

- **A2A JSON-RPC**：`POST /interop/a2a` 单端点，真 task 协议——`message/send`、`message/stream`（真 SSE：chat 代理 conversation token 流、research 代理 agent 任务流）、`tasks/get`、`tasks/cancel`、`tasks/pushNotificationConfig/set|get`；push 通知按 A2A Task 信封由 interop 中继回推（HMAC + `X-A2A-Notification-Token`）。
- **agent-card**：`GET /.well-known/agent-card.json`（edge-gateway 白名单放行给 A2A 生态发现）、`GET /interop/agent-card`、`GET /interop/a2a/agent-card`。
- **MCP tool surface**：`GET /interop/mcp/tools`、`GET /interop/mcp/tools/{toolName}`、`POST /interop/mcp/call`。

**状态**：**核心可跑**——端点常开，A2A JSON-RPC + agent-card + MCP surface 可直接验；
`message/stream` 已是真 SSE、push 通知默认经 agent webhook 触发（零外部依赖，push 配置默认内存存储，多副本需换 Redis/JDBC）；
live discovery（`INTEROP_DISCOVERY_ENABLED`）默认关，下游不可达时确定性回退静态默认。

**深入看**：A2A 协议 / 流式 / push → `a2a-guide.md`；MCP surface 与 client → `mcp-guide.md`。

---

## 场景开关速查

场景**级**的关键闸门（细粒度子开关见各专题文档的「开关速查」表）。默认值以各服务 `application.yml` 为准。

| 场景 / 子能力 | 关键环境变量 | 默认 | 效果 |
| --- | --- | --- | --- |
| ① RAG `/chat` 增强 | `CONVERSATION_RAG_ENABLED` | `false` | `/chat` 是否先查 RAG 再作答 |
| ① 持久化向量库 | `RAG_VECTOR_STORE_PROVIDER` | `in-memory` | `qdrant`/`pgvector`/`milvus`/`chroma`/`doris` 持久化 |
| ① 真 embedding | `RAG_EMBEDDING_PROVIDER` | `hash` | `openai`/`ollama` 语义向量 |
| ① L1 语义缓存 | `CONVERSATION_SEMANTIC_CACHE_ENABLED` | `false` | 命中即短路 RAG+LLM |
| ② NL2SQL 整装配 | `NL2SQL_ENABLED`（`app.nl2sql.enabled`） | `false` | 关时 `/chat/sql` 端点不注册 |
| ② 工作流审批 | 端点常开 | — | 需可登录 MySQL（Flowable 自管表） |
| ② 工作流终态通知 | `WORKFLOW_TERMINAL_NOTIFICATION_MODE` | 本地 | 可切 `async-task`（中心 outbox） |
| ② 飞书入站事件桥 | `FEISHU_*`（app id/secret/verification/encrypt） | 关 | `/channel/feishu/events` 生效 |
| ② 钉钉入站事件桥 | `DINGTALK_*` | 关 | `/channel/dingtalk/events` 生效 |
| ② 语音闭环整服务 | `VOICE_ENABLED` | `false` | 关时 voice-service 不装配 |
| ② 语音 provider | `VOICE_PROVIDER` / `VOICE_BASE_URL` / `VOICE_API_KEY` | `openai` / — | ASR/TTS 后端 |
| ③ Agent 核心 | `AGENT_ENABLED` | `true` | ReAct/DAG/reflexion/voting/chain 端点 |
| ③ DAG replan 闭环 | `AGENT_DAG_REPLAN_ENABLED` | `false` | critique 低分则修订重跑 |
| ③ 受限代码执行 | `AGENT_CODE_EXEC_ENABLED` | `false` | JShell/子进程，中等隔离非强沙箱 |
| ④ 视觉整服务 | `VISION_ENABLED`（+`VISION_MODEL`） | `false` | 关时 vision-service 不装配 |
| ④ 看图对话入口 | `CONVERSATION_VISION_ENABLED` | `false` | `/chat/vision` 生效 |
| ④ 图片 RAG（CLIP） | `RAG_MULTIMODAL_ENABLED` | `false` | `/rag/image*` 生效 |
| ⑤ 互操作面 | 端点常开 | — | A2A / agent-card / MCP surface |
| ⑤ live discovery | `INTEROP_DISCOVERY_ENABLED` | `false` | 从下游动态发现 skills/tools |

---

## 与其他文档的关系

- **能力目录（横向）** → `capabilities.md`
- **系统架构 / 两层网关 / DDD 拆分** → `架构文档.md`
- **运行配置 / 端口 / 起整栈** → `operations.md`；**接口速查** → `api-reference.md`
- **场景 ①** 知识库 RAG → `rag-guide.md`；记忆 → `memory-guide.md`；语义缓存 → `semantic-cache.md`；模型级联 → `model-cascade.md`；召回评测 → `eval-guide.md`
- **场景 ②** NL2SQL → `nl2sql-guide.md`；工作流 → `workflow-guide.md`；渠道 → `dingtalk-guide.md`；语音 → `voice-guide.md`
- **场景 ③** Agent 编排 → `agent-guide.md`；受限代码执行 → `code-exec.md`
- **场景 ④** 多模态视觉 → `vision-guide.md`
- **场景 ⑤** A2A → `a2a-guide.md`；MCP → `mcp-guide.md`
- **横切**：事件总线（跨服务原子性）→ `eventbus-guide.md`；可观测（trace/OTel/指标）→ `observability-guide.md`；成本归因 → `cost-attribution.md`
</content>
</invoke>
