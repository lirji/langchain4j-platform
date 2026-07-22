# 业务场景落地总览

这份文档是**业务场景层的导航入口**：把平台已经组装成的、可交付的业务场景汇总在一处，
回答「这些底座能力被拼成了哪些业务场景、各自从哪个端点接入、跑到什么程度、深入看哪篇文档」。

它和 `docs/参考/capabilities.md` 是两种视角，不重复：

- **`capabilities.md`** 是**能力目录**——逐条列平台有哪些原子能力、端点、默认开关（横向切片）。
- **本文（`scenarios.md`）** 是**场景装配视图**——这些原子能力被组合成哪些业务闭环（纵向切片）。

> 关联：能力目录 → `capabilities.md`；系统架构 / 两层网关 / DDD 拆分 → `架构文档.md`；
> 运行配置 / 端口 / 起栈 → `operations.md`；接口速查 → `api-reference.md`；事件总线（跨服务原子性）→ `eventbus-guide.md`。

## 访问约定

所有业务端点都经**唯一对外入口 `edge-gateway`（`http://localhost:8080`）** 访问，带 `X-Api-Key` **或** 登录后的 `Authorization: Bearer <会话 accessToken>`（先 `POST /auth/login`）：
网关校验凭据 → 识别租户/scope → 签发短时内部 JWT（`X-Internal-Token`）→ 按路径路由到下游服务。
下游服务只信任内部 JWT，对「会话 vs api-key」无感知，租户身份随之跨每一跳传播并还原进 `TenantContext`（多租户硬隔离）。
服务直连端口（conversation:8081 … voice:8091、auth:8092）仅供本地调试，不建议对外。另有能力展示前端 `capability-showcase-frontend`（:8093）浏览器跨域直调网关。

**默认开关基线**：单测是纯 POJO、不启动 Spring，**零外部依赖恒成立**。application.yml 层面 **2026-07 起多数行为增强开关默认已开**（与 compose demo 对齐）——RAG 向量库(qdrant)/ES 全文混排/rerank/查询扩展/上下文/GraphRAG/多模态、对话 RAG/路由/视觉/护栏/级联/记忆画像、NL2SQL、agent 内建动作与 DAG replan、interop discovery、eval、vision、Casdoor SSO(`only`) 均默认开，故**完整启动已需相应基础设施**（真正零依赖单跑需显式退回 `in-memory` + 关 ES/Casdoor 等）。仍**默认关**：`conversation.mcp`、agent `code_exec`/`mcp`/`browser`、`voice`、飞书/钉钉、channel-events、文档级 ReBAC(`RAG_AUTHZ_MODE=disabled`)、自助注册、`cost`，以及 yml 层的 RBAC 落库（compose demo 会自带基础设施并开 RBAC 落库）。下表「默认」以 application.yml 为准。
下表「状态」列如实标注每个场景当前跑到哪一步——「核心可跑」= 端点就位、内存/确定性实现可直接验；
「代码就位・默认关」= 逻辑与单测就位，但需显式开开关 + 配真实 provider / 外部应用才能端到端。

---

## 场景一览

| 场景 | 子能力 | 入口端点（经 :8080） | 状态 | 详细文档 |
| --- | --- | --- | --- | --- |
| **① 企业知识库问答（RAG）** | 文档入库 + 四路混排（向量/关键词/ES BM25/图谱）+ 带引用多轮问答 + 多租户隔离 + 公共共享库 + 可选文档/部门级授权(ReBAC) | `POST /rag/documents`、`POST /rag/query`、`POST /chat` | 核心可跑；默认 qdrant + ES 全文混排 + RRF；RAG 增强/公共库**默认开**、文档/部门级授权(ReBAC)默认关 | `rag-guide.md`、`es-hybrid-rerank.md`、`rbac-and-public-kb.md`、`memory-guide.md` |
| **② 智能客服** · NL2SQL/ChatBI | 自然语言查业务库（6 层 SQL 护栏） | `POST /chat/sql`（别名 `/analytics/sql`） | 默认开（`NL2SQL_ENABLED=true`）；端到端需只读 DB + tool-calling 模型 | `nl2sql-guide.md` |
| **② 智能客服** · 工作流审批 | 退款等挂人工审批的长流程（Flowable） | `POST /workflow/refund/start` | 核心可跑（需 MySQL）；终态通知可切事件总线 | `workflow-guide.md` |
| **② 智能客服** · 渠道接入 | 飞书 / 钉钉群内 @机器人 入站事件桥 | `POST /channel/feishu/events`、`POST /channel/dingtalk/events` | 代码就位・默认关（需真应用 + 公网回调联调） | `dingtalk-guide.md` |
| **② 智能客服** · 语音渠道 | 音频 → ASR → 对话 → TTS 语音闭环 | `POST /voice/chat`、`/voice/chat/stream` | 代码就位・默认关（需配 ASR/TTS provider） | `voice-guide.md` |
| **③ 深度 Agent / 多 Agent 编排** · 通用编排 | ReAct 单 Agent + DAG 多 Agent + chain/vote/reflexion | `POST /agent/run`、`/agent/dag/run`、`/agent/dag/plan-run` | 核心可跑（`AGENT_ENABLED=true` 默认开）；受限动作默认关 | `agent-guide.md`、`workflow-guide.md`、`code-exec.md` |
| **③ 深度 Agent** · 数据分析智能体 | 自然语言问数 → DAG 拆「探表→取数→算→解读」+ 按需探表 | `POST /agent/analyst/run`、`GET /analytics/schema/tables` | 核心可跑；真正取数/探表需 analytics 侧 `NL2SQL_ENABLED` | `agent-guide.md`、`nl2sql-guide.md` |
| **③ 深度 Agent** · 业务流程智能体（人在环） | NL 发起/跟进退款审批，审批仍由人在流程外完成 | `POST /agent/process/run` | 默认开（`AGENT_WORKFLOW_ENABLED` + `WORKFLOW_ENABLED` 均默认开、有副作用人在环，需 workflow-service 可达） | `agent-guide.md`、`workflow-guide.md` |
| **④ 多模态视觉** | 看图对话 + 图片 caption/描述 + 图片 RAG | `POST /chat/vision`、`/vision/caption` | 默认开（`VISION_ENABLED=true`，但须配 `VISION_MODEL`，否则启动 fail-fast） | `vision-guide.md` |
| **⑤ 互操作（A2A / MCP）** | 对外 Agent 协作面（真 A2A + MCP tool surface） | `POST /interop/a2a`、`GET /.well-known/agent-card.json`、`/interop/mcp/**` | 核心可跑（端点常开）；live discovery 默认关 | `a2a-guide.md`、`mcp-guide.md` |
| **⑥ 登录与权限（auth / RBAC / SSO）** | 账号登录 → 会话令牌 + 边缘换发内部 JWT；角色/权限管理面 + 公共知识库授权；+ Casdoor OIDC SSO 多租户登录（整栈默认开、`only` 严格）+ SpiceDB 文档级授权（默认关） | `POST /auth/login`、`GET /auth/me`、`/auth/admin/**` | 核心可跑（compose demo 直开 RBAC + 写、Casdoor `only`）；文档级授权代码就位・默认关 | `rbac-and-public-kb.md` |

> **说明**：「智能客服」不是单个服务，而是**由 NL2SQL + 工作流审批 + 渠道接入（飞书/钉钉/语音）拼成的闭环**——
> 用户从任一渠道进来 → 意图分流（退款/投诉走工作流，查数走 NL2SQL，其余走 RAG 对话）→ 长流程挂人工审批 → 终态主动回推。
> 飞书、钉钉、语音三个渠道**共用同一套下游大脑**（都最终打 conversation 的 `/chat`，可带 RAG），渠道只负责验签、去重、格式转换。

---

## ① 企业知识库问答（RAG）

**做什么**：把企业文档（PDF / Word / Excel / PPT / HTML / Markdown / 纯文本）按租户入库，
支持带来源引用的多轮问答，按租户硬隔离、可持久化不丢。

**怎么装配**：`knowledge-service`（`:8084`）负责入库与检索，`conversation-service`（`:8081`）负责对话。

- **入库**：`POST /rag/documents`（JSON 文本或 multipart 文件）→ Apache Tika 按内容嗅探类型抽取 → 分块（Markdown header / parent-child / semantic）→ Embedding 向量化 → 写入**按租户隔离的 collection**（`collection-per-tenant` 默认）。可选 GraphRAG 三元组抽取。
- **检索（四路混排 + RRF）**：`POST /rag/query` 并行召回 vector + keyword（内存 BM25）+ es（Elasticsearch 真 BM25，`RAG_ES_ENABLED` 默认开）+ 可选 graph，交 `HybridFusionService` 融合——ES 参与时有效默认 RRF（免疫量纲差），否则加权 max，取 topK。
- **对话增强**：`POST /chat` 默认直连 LLM；开 `CONVERSATION_RAG_ENABLED=true` 后先查 `/rag/query`、把检索结果注入 prompt 再作答，回复带 `[doc=文件名#N]` 引用。
- **公共/共享库**：`RAG_PUBLIC_ENABLED=true` 后各租户查询并入 `__public__` 公共分区，写共享库需 `public-ingest` scope；前端据 `GET /rag/config` 决定是否展示共享库视图。
- **（默认关）文档/部门级授权**：在租户硬隔离之上可再叠一层**文档级 ReBAC**（接外部 `auth-platform`：Casdoor 身份 + SpiceDB 授权），由 `RAG_AUTHZ_MODE=disabled(默认)/shadow/enforce` 门控——`enforce` 下检索按当前用户 `view` 权过滤命中、新建文档绑定上传人部门（`home_dept`），`disabled` 时逐字等同接入前。详见 ⑥ 与 `rbac-and-public-kb.md`。
- **前置提速**：可选 L1 语义缓存（问题级、按租户分桶、在 RAG 之前，命中即短路 RAG+LLM）与多轮记忆（会话隔离滑窗）。

**状态**：**核心可跑**——单测零依赖；`docker-compose` demo 开箱即是「qdrant 向量 + nomic 语义 embedding + ES(smartcn) 全文混排 + RRF」的完整 RAG 栈（application.yml 裸跑亦默认 qdrant + ES，仅 embedding 默认 hash；真正零依赖单跑需显式退回 in-memory + `RAG_ES_ENABLED=false`）。
检索重排（`RAG_RERANK_ENABLED`）、查询扩展（`RAG_QUERY_EXPANSION_ENABLED`）、上下文增强（`RAG_CONTEXTUAL_ENABLED`）、`/chat` RAG 增强（`CONVERSATION_RAG_ENABLED`）、公共库（`RAG_PUBLIC_ENABLED`）、GraphRAG（`RAG_GRAPH_ENABLED`，`store=jdbc`）**现均默认开**；按需关闭置对应开关为 `false`。

**深入看**：接入/向量库选型/GraphRAG/语义缓存联动 → `rag-guide.md`；多轮记忆与长期画像 → `memory-guide.md`；
L1 语义缓存单篇 → `semantic-cache.md`；检索召回评测（Recall@k / MRR）→ `eval-guide.md`。

---

## ② 智能客服

客服场景是**多块拼成的闭环**。下面四个子能力各自独立、可单独开，也可组合成「渠道进 → 意图分流 → 查数/审批 → 回推」的完整链路。

### ②.1 NL2SQL / ChatBI

**做什么**：客服 / 运营用自然语言提问 → LLM 生成 SQL → **只读执行** → LLM 解读结果，让不会写 SQL 的人也能查业务库（订单状态、退款记录等）。
核心是 6 层 SQL 安全护栏：只读账号 → 语句白名单（仅 SELECT）→ 表白名单 → 强制 LIMIT → 执行超时 → **强制注入租户谓词** `tenant_id = ?`。

**入口**：`POST /chat/sql`（别名 `/analytics/sql`，analytics :8083），body `{"message":"..."}` → 返回 `{question, sql, rowCount, rows, answer, ...}`。

**状态**：**默认开**。整套 NL2SQL 装配是 `@ConditionalOnProperty(app.nl2sql.enabled)`，默认 `NL2SQL_ENABLED=true`（置 `false` 时端点不注册、零开销）。
端到端需：一个可达的只读业务库（`NL2SQL_DB_URL` / `NL2SQL_DB_READONLY_URL` / `NL2SQL_SEED_SCRIPT`）+ 支持 tool-calling 的 chat 模型。

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

`agent-service`（`:8085`）以 DAG 多 Agent 引擎为底座（Kahn 拓扑分层 / 同层并行 worker / 末尾 synthesis 收口 / 可选 critique+replan 质量闭环），
共享网关 `ChatModel` + 审计/计量 listener + SSE 进度。除通用编排（③.1）外，在同一引擎上**只换一个专用 Planner**即特化出两个开箱业务智能体：
数据分析（③.2）、业务流程/人在环（③.3）。这两个智能体请求体都是 `{"goal":"..."}`、响应都是 `AgentDagRunReply`
（`goal / levels / taskResults / synthesis / attempts / acceptedByThreshold`），异步走 `202` + `AgentAsyncTask`，
SSE 复用 `/agent/tasks/{taskId}/stream`（可见 `dag-planned / dag-worker-* / dag-synthesis-*` 阶段事件）。

### ③.1 通用编排（ReAct + DAG + chain/vote/reflexion）

**做什么**：把复杂任务交给会「思考 → 调工具 → 观察 → 再思考」的 Agent，或拆成多个子 Agent 并行协作。提供五种同级编排模式。

- **深度 Agent（ReAct）**：`POST /agent/run`（同步）、`/agent/run/async`（返回 taskId + SSE）。内置动作 `rag_search`（调 knowledge）、`analytics_sql`（调 analytics）、`current_time`、`delegate`、`finish`。
- **DAG 多 Agent**：`POST /agent/dag/run`（显式传 DAG）、`POST /agent/dag/plan-run`（Planner 自动拆 DAG），各带 `/async`。Kahn 拓扑分层、同层并行、下游看上游、末尾综合；可选 critique+replan 质量闭环。
- 另有链式 `POST /agent/chain`（步间确定性 gate）、投票 `POST /agent/vote`（N 次取共识）、反思 `POST /agent/reflexive[/stream]`（answer→critique→improve 自省环）。

**状态**：**核心可跑**——`AGENT_ENABLED=true`（默认开），ReAct / DAG / reflexion / voting 端点即挂载，内存任务态 + SSE 可直接验；
链式 `steps` 默认空需先配置；DAG replan（`AGENT_DAG_REPLAN_ENABLED`）**现默认开**。
**受限动作全部默认关**：`code_exec`（同/子 JVM 执行，中等隔离非强沙箱）、`mcp_call`、`browser_*`、`browser_see`（视觉，需 browser + vision 双开）。

**深入看**：五种模式 / 动作 / 任务态 → `agent-guide.md`；受限代码执行的隔离边界与坑 → `code-exec.md`；
业务流程引擎 vs LLM 编排模式的辨析 → `workflow-guide.md`。

### ③.2 数据分析智能体（DAG + 按需探表）

**做什么**：传一个自然语言数据问题 → `DataAnalystPlanner` 拆成「探表 → 取数 → 计算 → 解读」子任务 → DAG 并行/依赖执行 → synthesis 收口，
给出带 SQL 与数字依据的结论。核心新增能力是**「按需探表」**：每个 worker 先用 `schema_explore` 查真实库结构（列名/类型/中文枚举），
再用 `analytics_sql` 取数，避免模型凭空猜列名、或把中文枚举 `status='已退款'` 猜成英文。

- **只换 Planner**：不改 `AgentDagService` 引擎，仅换数据分析专用 Planner（内置「先探后取、按维度而非实体拆解、1~6 子任务」规则，planner 返回空则兜底单任务）。
- **探表端点**（analytics :8083，只读）：`GET /analytics/schema/tables` 列白名单表名；`GET /analytics/schema/tables/{table}` 返回单表结构文本（含 `enum-columns` 的 distinct 枚举值），非白名单/不存在一律 404。
- **精确计算**：跨行占比/环比等建议开 `AGENT_CODE_EXEC_ENABLED`，否则计算子任务退化为模型估算。

**入口**（agent :8085）：`POST /agent/analyst/run`，body `{"goal":"..."}`（空则 400）→ `AgentDagRunReply`；
`POST /agent/analyst/run/async`（可选 `webhookUrl`）→ `202` + `AgentAsyncTask`。

**状态**：**核心可跑**——agent 侧常开（`app.agent.enabled`、`app.agent.analytics.enabled` 默认 true），DAG 编排 + SSE 可直接验；
真正取数/探表需 analytics 侧 `NL2SQL_ENABLED=true`（否则 `analytics_sql` / `schema_explore` 被 Noop 降级）；精确计算另需 `AGENT_CODE_EXEC_ENABLED=true`（默认关）。

**深入看** → `agent-guide.md`（数据分析智能体 / `schema_explore` 动作）；NL2SQL 护栏底座 → `nl2sql-guide.md`；精确计算隔离边界 → `code-exec.md`。

### ③.3 业务流程智能体（人在环）

**做什么**：用自然语言发起 / 跟进退款审批。`ProcessPlanner` 把「帮我发起退款并跟进」拆成「发起 → 查状态 → 如实汇报」子任务，
worker 调 `refund_start` / `workflow_status` / `workflow_tasks` 驱动 workflow-service。**严格人在环**——审批只能由人在流程外完成。

- **无自动审批动作**：只暴露发起 / 查实例 / 列待办，**不暴露 `workflow_complete`**；approve/reject 只能由具备 `approve` scope 的人走 `POST /workflow/tasks/{taskId}/complete`。
- **高风险如实告知**：`refund_start` 返回 `status`——`COMPLETED`=低风险已自动受理；`WAITING_APPROVAL`=高风险已转人工审批（未批准、带 `taskId`）。Planner 铁律要求「只发起/查询/汇报，绝不声称已批准/驳回、不重复发起同一诉求」。
- **不越权**：内部 JWT 透传调用方 scope，无 `approve` scope 调 `workflow_tasks` → workflow 403 → 中文提示「当前身份无审批权限」。
- **只在流程外编排**：智能体不进 Flowable 同步 ServiceTask，流程内 assess/resolve 的 LLM 决策由 workflow-service 自担。

**入口**（agent :8085）：`POST /agent/process/run`，body `{"goal":"..."}` → `AgentDagRunReply`；
`POST /agent/process/run/async`（可选 `webhookUrl`）→ `202` + `AgentAsyncTask`。

**状态**：**默认开（有副作用·人在环）**——双门控 `AGENT_WORKFLOW_ENABLED` + `WORKFLOW_ENABLED` 均默认开、需 workflow-service 可达：agent 侧 `AGENT_WORKFLOW_ENABLED=true`、
workflow 侧 `WORKFLOW_ENABLED=true`（其 assess/resolve 还调 conversation-service）+ 一个可登录 MySQL（Flowable 自管表）。

**深入看** → `agent-guide.md`（业务流程智能体 / `refund_start`·`workflow_status`·`workflow_tasks` 动作）；底层退款审批引擎 → `workflow-guide.md`。

---

## ④ 多模态视觉

**做什么**：看图对话与图片理解。三个入口，共用经 LiteLLM 的多模态 `ChatModel`（仅逻辑模型名不同）：

- **看图对话**：`POST /chat/vision`（conversation multipart，图片 + 问题）→ 委托 vision-service → 返回 `reply`。
- **图片 caption / 描述**：`POST /vision/caption`（别名 `/vision/describe`，vision :8090，JSON `imageBase64` 或 multipart）。
- **图片 RAG（CLIP 多模态 embedding）**：`POST /rag/image` 入库、`POST /rag/image-search` 文本跨模态检索图片（图片向量存独立 collection，与文本隔离）。
- 还供 agent 的 `browser_see` 动作复用（截图 → 看图）。

**状态**：**核心可跑（需配模型）**。`vision-service` `VISION_ENABLED=true` **默认装配**，但 `VISION_MODEL` 留空则启动 fail-fast；
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
live discovery（`INTEROP_DISCOVERY_ENABLED`）**默认开**，从下游动态发现 skills/tools，下游不可达时确定性回退静态默认。

**深入看**：A2A 协议 / 流式 / push → `a2a-guide.md`；MCP surface 与 client → `mcp-guide.md`。

---

## ⑥ 登录与权限（auth / RBAC）

**做什么**：给平台加对外鉴权路径（与 api-key 并存），并提供角色/权限管理面。有两条并存的登录路径：**(a) 自建会话登录**——`auth-service`（`:8092`）「账号密码 → 会话令牌」，`edge-gateway` 的 `SessionBearerAuthFilter` 验签后换发内部 JWT；**(b) Casdoor OIDC SSO（已落地，整栈默认开、`only` 严格）**——外部 Casdoor 作 IdP，`edge-gateway` 的 `CasdoorTokenExchangeFilter` 验 Casdoor JWT 后换发内部 JWT。下游服务零改动、对登录方式无感知。

- **登录/会话**：`POST /auth/login`（账号密码 → 会话 accessToken 60min + httpOnly 刷新 cookie 7d）、`POST /auth/refresh`（一次性轮转）、`POST /auth/logout`、`GET /auth/me`、`GET /auth/public-config`、`POST /auth/register`（自助注册，默认关）。前 5 个 + register 在边缘免鉴权放行。
- **RBAC**：种子角色 `viewer/editor/analyst/approver/admin`；有效 scopes = 角色展开 ∪ 直配，签发令牌时展开。新增平台 scope `role-admin`（管理面门禁）、`public-ingest`（写公共库），只经 admin 角色获得、不挂 api-key。
- **管理面**：`/auth/admin/{users,roles}/**`（`role-admin` scope），写端点受 `admin-writes-enabled`（关→503）+ `If-Match` 乐观锁（428/412）。护栏禁止移除最后一个 role-admin、删被引用角色（409）。
- **前端**：能力展示前端已移除内置 RBAC 管理控制台（平台管理统一由 Casdoor + auth-platform 承担）；RAG 租户/共享双视图保留（`VITE_SHARED_KB_UI_ENABLED` kill switch）。
- **（已落地，整栈默认开）Casdoor OIDC SSO**：`edge-gateway` 的 `CasdoorTokenExchangeFilter`（order `-120`，最早）用 Casdoor JWKS 验 `Authorization: Bearer` 后换发内部 JWT。由 `EDGE_CASDOOR_ENABLED=true`（**默认开**）门控；`EDGE_CASDOOR_MODE=only(默认)/dual` 两档——`dual` 灰度期 Casdoor 验过即换发、验不过透传给 legacy(session/api-key) filter，`only` 严格 Casdoor-only（非 open path 无有效 token → 401、不回落，tenant 恒取 owner）。
- **（已落地，随 Casdoor 默认开）多租户登录（方案 C：Casdoor Shared Application + 选组织）**：前端 `LoginView` 登录前先选/输租户(=Casdoor org)，`getUserManager(tenant)` 用 shared app 的 `<base>-org-<tenant>` 客户端（base client_id 默认 `ragshared0client00000001`，`VITE_CASDOOR_CLIENT_ID`），每 org token `aud=<base>-org-<org>`、`owner=org`。前端登录模式 `VITE_AUTH_MODE=apikey(源码默认)/oidc(整栈烘焙)/dual`。
- **（默认关）SpiceDB 文档级授权 + 部门身份链**：`RAG_AUTHZ_MODE=enforce` 时 RAG 检索接 `auth-platform`(SpiceDB ReBAC) 按 `view` 权过滤（见场景 ①）。身份链：Casdoor 嵌套 group → edge 推导部门 → `dept` claim → `TenantContext.department`，供文档按上传人部门（`home_dept`）隔离。

**状态**：**核心可跑**——`docker-compose` demo 默认 `AUTH_STORE=jdbc` + `AUTH_RBAC_ENABLED=true` + 写开放 + 种子账号，登录/管理面开箱即验（`bash deploy/smoke-rbac.sh`）；application.yml 裸跑默认 `in-memory` + RBAC 全关（只用直配 scopes 登录）。自助注册 `AUTH_REGISTRATION_ENABLED` 恒默认关。

**深入看** → `rbac-and-public-kb.md`（登录会话链路 + RBAC + 公共知识库 + Casdoor SSO / 文档级 ReBAC）；Casdoor OIDC 公网化落地 → `公网化-OIDC-改造方案.md`；RAG 文档级授权开关与判权链路 → `rag-guide.md`。

---

## 场景开关速查

场景**级**的关键闸门（细粒度子开关见各专题文档的「开关速查」表）。默认值以各服务 `application.yml` 为准。

| 场景 / 子能力 | 关键环境变量 | 默认 | 效果 |
| --- | --- | --- | --- |
| ① RAG `/chat` 增强 | `CONVERSATION_RAG_ENABLED` | `true` | `/chat` 是否先查 RAG 再作答 |
| ① 持久化向量库 | `RAG_VECTOR_STORE_PROVIDER` | `qdrant` | yml/compose 默认 qdrant；可退 `in-memory`/切 `pgvector`/`milvus`/`chroma`/`doris` |
| ① 真 embedding | `RAG_EMBEDDING_PROVIDER` | `hash`（compose `ollama`） | `openai`/`ollama` 语义向量（compose 默认 nomic 768 维） |
| ① ES 全文混排 | `RAG_ES_ENABLED` / `RAG_FUSION_STRATEGY` | `true` / 空→rrf | ES BM25 并入四路混排、多源 RRF 融合 |
| ① 公共/共享知识库 | `RAG_PUBLIC_ENABLED` | `true` | 查询并入 `__public__` 分区；写需 `public-ingest` scope |
| ① L1 语义缓存 | `CONVERSATION_SEMANTIC_CACHE_ENABLED` | `true` | 命中即短路 RAG+LLM |
| ② NL2SQL 整装配 | `NL2SQL_ENABLED`（`app.nl2sql.enabled`） | `true` | 默认注册 `/chat/sql`；端到端需只读 DB + tool-calling 模型（置 `false` 则端点不注册） |
| ② 工作流审批 | 端点常开 | — | 需可登录 MySQL（Flowable 自管表） |
| ② 工作流终态通知 | `WORKFLOW_TERMINAL_NOTIFICATION_MODE` | 本地 | 可切 `async-task`（中心 outbox） |
| ② 飞书入站事件桥 | `FEISHU_*`（app id/secret/verification/encrypt） | 关 | `/channel/feishu/events` 生效 |
| ② 钉钉入站事件桥 | `DINGTALK_*` | 关 | `/channel/dingtalk/events` 生效 |
| ② 语音闭环整服务 | `VOICE_ENABLED` | `false` | 关时 voice-service 不装配 |
| ② 语音 provider | `VOICE_PROVIDER` / `VOICE_BASE_URL` / `VOICE_API_KEY` | `openai` / — | ASR/TTS 后端 |
| ③ Agent 核心 | `AGENT_ENABLED` | `true` | ReAct/DAG/reflexion/voting/chain 端点 |
| ③ DAG replan 闭环 | `AGENT_DAG_REPLAN_ENABLED` | `true` | critique 低分则修订重跑 |
| ③ 受限代码执行 | `AGENT_CODE_EXEC_ENABLED` | `false` | JShell/子进程，中等隔离非强沙箱；数据分析精确计算复用 |
| ③ 数据分析取数/探表 | `NL2SQL_ENABLED`（analytics 侧） | `true` | 置 `false` 时 `analytics_sql` / `schema_explore` 被 Noop 降级 |
| ③ 业务流程智能体（人在环） | `AGENT_WORKFLOW_ENABLED`（+ workflow 侧 `WORKFLOW_ENABLED`） | `true` | `/agent/process/**` 默认生效（有副作用·人在环，需 workflow-service 可达） |
| ④ 视觉整服务 | `VISION_ENABLED`（+`VISION_MODEL`） | `true` | 默认装配 vision-service；`VISION_MODEL` 留空则启动 fail-fast |
| ④ 看图对话入口 | `CONVERSATION_VISION_ENABLED` | `true` | `/chat/vision` 生效 |
| ④ 图片 RAG（CLIP） | `RAG_MULTIMODAL_ENABLED` | `false` | 默认关闭；启用后 `/rag/image*` 生效，且需可达的多模态 embedding 后端 |
| ⑤ 互操作面 | 端点常开 | — | A2A / agent-card / MCP surface |
| ⑤ live discovery | `INTEROP_DISCOVERY_ENABLED` | `true` | 从下游动态发现 skills/tools（下游不可达时回退静态默认） |
| ⑥ RBAC 整套 | `AUTH_RBAC_ENABLED`（compose demo `true`） | `false` | 关时仅用直配 scopes 登录，`/auth/admin/**` 不注册 |
| ⑥ RBAC 管理写 | `AUTH_RBAC_ADMIN_WRITES_ENABLED`（compose demo `true`） | `false` | 关时写端点 503 |
| ⑥ 账号存储 | `AUTH_STORE`（compose `jdbc`） | `in-memory` | jdbc 落 MySQL `auth` 库 |
| ⑥ 自助注册 | `AUTH_REGISTRATION_ENABLED` | `false` | 须与 RBAC 同开，否则 `/auth/register` 403 |
| ①⑥ 文档级授权（SpiceDB ReBAC） | `RAG_AUTHZ_MODE` | `disabled` | `shadow` 只观测 / `enforce` 按 `view` 权过滤检索、绑上传人部门；需 `auth-platform-server`(:8200) |
| ⑥ Casdoor OIDC SSO | `EDGE_CASDOOR_ENABLED` | `true` | edge 验 Casdoor JWT 换发内部 JWT（整栈默认开） |
| ⑥ Casdoor 认证模式 | `EDGE_CASDOOR_MODE` | `only` | `only` 严格（无有效 token→401，恒取 owner）/ `dual` 灰度回滚：验不过回落 legacy |
| ⑥ 前端登录模式 | `VITE_AUTH_MODE` | `apikey`（源码）/`oidc`（整栈烘焙） | `oidc`/`dual` 启用 Casdoor 多租户登录（方案 C shared-app + 选组织） |

---

## 与其他文档的关系

- **能力目录（横向）** → `capabilities.md`
- **系统架构 / 两层网关 / DDD 拆分** → `架构文档.md`
- **运行配置 / 端口 / 起整栈** → `operations.md`；**接口速查** → `api-reference.md`
- **场景 ①** 知识库 RAG → `rag-guide.md`；记忆 → `memory-guide.md`；语义缓存 → `semantic-cache.md`；模型级联 → `model-cascade.md`；召回评测 → `eval-guide.md`
- **场景 ②** NL2SQL → `nl2sql-guide.md`；工作流 → `workflow-guide.md`；渠道 → `dingtalk-guide.md`；语音 → `voice-guide.md`
- **场景 ③** Agent 编排（通用 / 数据分析·按需探表 / 业务流程·人在环）→ `agent-guide.md`；NL2SQL 底座 → `nl2sql-guide.md`；退款审批引擎 → `workflow-guide.md`；受限代码执行 → `code-exec.md`
- **场景 ④** 多模态视觉 → `vision-guide.md`
- **场景 ⑤** A2A → `a2a-guide.md`；MCP → `mcp-guide.md`
- **场景 ⑥** 登录 / RBAC / 公共知识库 → `rbac-and-public-kb.md`
- **横切**：事件总线（跨服务原子性）→ `eventbus-guide.md`；可观测（trace/OTel/指标）→ `observability-guide.md`；成本归因 → `cost-attribution.md`
