# 接口与集成速查

本文按服务列出**代码里真实存在**的 HTTP 端点，每条给出：方法 + 路径 + 用途 + 关键请求字段 + 是否经网关 + 默认开关状态。字段以 controller / `platform-protocol` DTO 源码为准。

## 访问方式

所有业务接口都从 `edge-gateway` 进：

```text
http://localhost:8080
```

外部调用带**两种凭据之一**：

```text
X-Api-Key: <api-key>                         # 方式一：api-key
Authorization: Bearer <会话 accessToken>      # 方式二：登录会话令牌（先 POST /auth/login）
Content-Type: application/json
```

- 网关按凭据换发内部 JWT：`SessionBearerAuthFilter`（order -110）验会话 Bearer，或 `ApiKeyToInternalTokenFilter`（order -100）校验 `X-Api-Key` → 签发短时内部 JWT（`X-Internal-Token`）→ 按路径路由到下游服务。下游只认内部 JWT，对「会话 vs api-key」无感知，**不建议外部直接构造 `X-Internal-Token`**。
- **第三条凭据（默认关）**：`CasdoorTokenExchangeFilter`（order **-120**，最早跑）接受外部 **Casdoor SSO** 签发的 Bearer JWT，JWKS 验签后换发同形状内部 JWT。`EDGE_CASDOOR_ENABLED=false` 时不装配；开启后 `dual` 档与上两条并存（Casdoor 优先、失败回退）、`only` 档独占（失败 401 不回退）。详见 `operations.md` 第 4.2 节。
- 免鉴权放行的路径（`EdgeOpenPaths.isOpen`，两个 filter 共用）：`/actuator*`、`/.well-known/*`、`/health`、`/auth/login`、`/auth/register`、`/auth/refresh`、`/auth/logout`、`/auth/public-config`、`/channel/feishu/events`、`/channel/dingtalk/events`。其中 `GET /.well-known/agent-card.json` 是对外免鉴权的 A2A 发现端点；`/auth/me` **不在**放行内，仍需会话 Bearer。
- 网关路由前缀（`edge-gateway/application.yml`）：`/auth`、`/auth/**` → auth；`/chat`、`/chat/**`、`/extract`、`/extract/**`、`/memory`、`/memory/**` → conversation；`/chat/sql`、`/analytics`、`/analytics/**` → analytics；`/workflow`、`/workflow/**` → workflow；`/rag`、`/rag/**`、`/knowledge`、`/knowledge/**` → knowledge；`/agent`、`/agent/**` → agent；`/async`、`/async/**` → async-task；`/channel`、`/channel/**` → channel；`/interop`、`/interop/**`、`/.well-known/agent-card.json` → interop；`/eval`、`/eval/**` → eval；`/vision`、`/vision/**` → vision；`/voice`、`/voice/**` → voice。
- 本文所有端点均可**经网关**访问（`是否经网关：是`）。少量端点是给服务间调用/回调设计的（如 `/conversation/workflow/*`、`/channel/callbacks/*`），虽然也在网关路由前缀内，但通常由内部服务而非终端用户调用，下面会单独标注。
- 默认开关：平台大量能力是 feature-flag 化的（`@ConditionalOnProperty` / `@ConditionalOnBean`），**默认关闭**的端点在未开启时不注册（404）。每节标注对应开关。

### 网关内置 api-key（dev 种子）

| api-key | 租户 | 用户 | scopes |
|---|---|---|---|
| `dev-key-acme` | acme | alice | chat, ingest, approve, agent, channel, eval, vision, voice |
| `dev-key-globex` | globex | bob | chat |
| `dev-key-tenantA-admin` | tenantA | analyst-a | chat, analytics |
| `dev-key-acme-ingest` | acme | alice | chat, ingest |

登录账号（auth-service demo 种子，口令 `demo12345`，与上表 api-key 租户/scope 镜像对齐）：`alice`(acme, admin 角色—含 `role-admin`/`public-ingest`) / `bob`(globex, viewer) / `analyst-a`(tenantA, analyst)。

限流分类（`EdgeRateLimitFilter`，默认 `app.rate-limit.enabled=true`，`store=redis`）：`chat=60`、`agent=20`、`stream=20`、`ingest=5`、`eval=5`、`default=120`（每分钟）。

---

## Auth（auth-service）

账号登录 + 会话令牌 + RBAC 管理面（:8092，`/auth/**`）。登录/刷新/登出/注册/public-config 在边缘**免鉴权放行**；`/auth/me` 与整个 `/auth/admin/**` 需鉴权。

### POST `/auth/login`

- 用途：账号密码登录，签发会话令牌。
- 请求：`{ username, password }`。
- 响应：`LoginResponse{ accessToken, expiresInSeconds, user{ userId, tenantId, scopes } }` + `Set-Cookie: refresh_token=...`（httpOnly，path `/auth`）。凭据错误 → 401。
- 经网关：是，免鉴权。会话 accessToken 默认 60min，作后续 `Authorization: Bearer` 用。

```bash
curl -s -X POST 'http://localhost:8080/auth/login' \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"demo12345"}'
```

### POST `/auth/refresh`

- 用途：用刷新 cookie 轮转换新会话（一次性作废旧刷新令牌，防重放）。
- 请求：无 body，带 `refresh_token` cookie。响应同 `/auth/login`（新 accessToken + 新 cookie）。cookie 缺失/失效 → 401。
- 经网关：是，免鉴权。

### POST `/auth/logout`

- 用途：撤销当前刷新会话并清 cookie。响应 204（幂等）。经网关：是，免鉴权。

### GET `/auth/me`

- 用途：查看当前登录用户（身份来自内部 JWT 还原的 `TenantContext`）。
- 响应：`{ userId, tenantId, scopes }`。
- 经网关：是。**需会话 Bearer**（不在免鉴权放行内）。

### GET `/auth/public-config`

- 用途：非敏感最小配置，供前端显隐注册入口。响应 `{ registrationEnabled, passwordMinLength, passwordMaxLength }`（`registrationEnabled = rbac.enabled && registration.enabled`）。经网关：是，免鉴权。

### POST `/auth/register`

- 用途：自助注册（默认关），成功即登录。
- 请求：`{ username, password, ... }`。响应同 `/auth/login`。
- 经网关：是，免鉴权。**默认关闭**——须同时 `AUTH_RBAC_ENABLED=true` 与 `AUTH_REGISTRATION_ENABLED=true`，否则 403；按客户端 IP 节流。

### RBAC 管理面 `/auth/admin/**`

整类 `@ConditionalOnProperty(app.auth.rbac.enabled)`（**RBAC 关时不注册**）。调用方需持 `role-admin` scope（仅 `admin` 角色经登录会话获得）；**写/删端点**再受 `AUTH_RBAC_ADMIN_WRITES_ENABLED` 二级开关（关→**503**）与 `If-Match` 乐观锁（缺失→**428**、版本冲突→**412**）约束；非 role-admin→**403**。列表回 `X-Total-Count`，单查回 `ETag` 作 If-Match 基线。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/auth/admin/users` | 分页列用户（`offset`/`limit`，`limit` capped 1–200） |
| GET | `/auth/admin/users/{username}` | 单查用户（回 `ETag`） |
| POST | `/auth/admin/users` | 建户（201；可带 `roles`/`directScopes`/`enabled`） |
| PATCH | `/auth/admin/users/{username}` | 局部改（tenant/password/directScopes/enabled，null=不改；需 If-Match） |
| PUT | `/auth/admin/users/{username}/roles` | 幂等全量替换角色（需 If-Match） |
| DELETE | `/auth/admin/users/{username}` | 删户（204 幂等；需 If-Match） |
| GET | `/auth/admin/roles` | 列角色 |
| GET | `/auth/admin/roles/{name}` | 单查角色 |
| POST | `/auth/admin/roles` | 建角色（201，重复 409） |
| PUT | `/auth/admin/roles/{name}` | 全量替换 scopes/description（需 If-Match） |
| DELETE | `/auth/admin/roles/{name}` | 删角色（204，被引用 409，需 If-Match） |

> 护栏：不能移除最后一个启用的 role-admin、不能删被引用角色（409）。降权/禁用/删号会即时撤销刷新会话（已签发 access 仍受 TTL 约束）。

---

## Conversation（conversation-service）

### POST `/chat`

- 用途：普通聊天，可选 RAG 上下文增强。
- 请求：`{ "message": "..." }`；可选 query `chatId=u1`。
- 响应核心字段：`reply`、`chatId`、`tenantId`、`userId`。
- 经网关：是。默认开启。RAG 增强默认关闭，需 `CONVERSATION_RAG_ENABLED=true`（另配 `KNOWLEDGE_BASE_URL` 等）。

### POST `/chat/stream`

- 用途：token 级 SSE 流式对话（与同步 `/chat` 同一套记忆键 `<tenantId>::<chatId>`、RAG 增强、注入护栏）。流式不挂语义缓存。
- 请求：`{ "message": "..." }`，可选 `category`；可选 query `chatId=u1`。
- 响应：`text/event-stream`。逐 token 以默认 `data:` 事件下发；注入护栏 block 命中 → 发 `event: blocked` 后 `event: done` 收尾（不进 LLM）；grounding 校验不通过 → 追加 `event: grounding-warning`；正常结束 `event: done`；出错 `event: error`。
- 经网关：是。默认开启（底层流式受 `GATEWAY_STREAMING_ENABLED=true`，默认开）。限流分类 `stream`（20/min）。

```bash
curl -sN -X POST 'http://localhost:8080/chat/stream?chatId=u1' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"message":"用一句话介绍你自己"}'
```

### POST `/chat/auto`

- 用途：LLM-as-Router——先对问题分类，再分派到对应处理后回答。与 `/chat` 共享多轮记忆（键 `<tenantId>::<chatId>`）。
- 请求：`{ "message": "..." }`；可选 query `chatId=u1`。
- 响应：`{ reply, route, reason, classifyMs, answerMs, chatId, tenantId, userId }`。
- 经网关：是。**默认关闭**，需 `CONVERSATION_ROUTER_ENABLED=true`（`app.conversation.router.enabled`）。未启用时 controller 仍在，返回明确禁用提示（非 404）。

### POST `/chat/vision`

- 用途：看图对话——图片 + 问题委托给 vision-service。
- 请求：`multipart/form-data`，表单字段 `image`（必填）+ 可选 `message`（作为 vision-service 的 `instruction`，非空即看图问答）。
- 响应：`{ reply, model, chars, tenantId, userId }`。缺图片 → 返回错误提示。
- 经网关：是。**默认关闭**，需 `CONVERSATION_VISION_ENABLED=true`（另配 `CONVERSATION_VISION_BASE_URL`，默认 `http://localhost:8090`）。未启用时返回明确禁用提示（非 404）。

```bash
curl -s -X POST 'http://localhost:8080/chat/vision' \
  -H 'X-Api-Key: dev-key-acme' \
  -F 'image=@/path/to/pic.png' -F 'message=图里是什么？'
```

### POST `/chat/mcp`

- 用途：工具全部来自 MCP server 的对话（`McpAssistant`）。
- 请求：`{ "message": "..." }`。
- 响应：`{ reply, tenantId, userId }`。
- 经网关：是。**默认关闭**，需 `CONVERSATION_MCP_ENABLED=true`（`app.conversation.mcp.enabled`）。未启用时返回明确禁用提示（非 404）。

### POST `/chat/memory`

- 用途：带长期用户画像记忆的对话（跨会话记住用户偏好/事实）。租户+用户取自内部 JWT，天然只操作自己的画像。
- 请求：`{ "message": "..." }`；可选 query `chatId=u1`。
- 响应：`{ reply, chatId, tenantId, userId }`。
- 经网关：是。**默认关闭**，需 `CONVERSATION_MEMORY_PROFILE_ENABLED=true`。未启用时返回明确禁用提示（非 404）。

### GET `/memory/profile`

- 用途：查看当前用户长期画像条目。
- 响应：`{ count, items, tenantId }`。
- 经网关：是。默认关闭（同上 `CONVERSATION_MEMORY_PROFILE_ENABLED`）。

### DELETE `/memory/profile`

- 用途：清空当前用户长期画像（PII 合规删除）。
- 响应：`{ removed, tenantId }`。
- 经网关：是。默认关闭（同上）。

### DELETE `/chat/cache`

- 用途：失效当前租户的 L1 语义缓存——知识库更新后调用，避免 `/chat` 返回缓存里的旧答案。租户取自内部 JWT，只能清自己的桶。
- 请求：可选 query `question`。不带 → 清空整租户桶，响应 `{ tenantId, scope: "tenant", cleared }`；带 → 只失效该原始问题，响应 `{ tenantId, scope: "question", removed }`。
- 经网关：是。默认开启（语义缓存关闭时为 no-op，清 0 条）。

### POST `/extract`

- 用途：自由文本 → 结构化 POJO（langchain4j structured output）。按 query `type` 分派抽取器，未知类型 → 400。
- 请求：query `type`（默认 `ticket`）+ body `{ "text": "..." }`。
- 响应：抽取结果 POJO；`type=ticket` → `{ title, priority, category, summary, tags[] }`。
- 经网关：是。默认开启（无 feature flag）。

```bash
curl -s -X POST 'http://localhost:8080/extract?type=ticket' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"text":"我上周买的耳机右声道没声音，想退货"}'
```

### POST `/chat/cascade`

- 用途：模型级联——便宜模型先答，低置信才升级强模型。
- 请求：`{ "message": "..." }`。
- 响应核心字段：`answer`、`served`（`cheap`|`strong`）、`cheapConfident`。
- 经网关：是。**默认关闭**，需 `app.chat.cascade.enabled=true`（`@ConditionalOnBean(CascadeService.class)`）。

### POST `/conversation/workflow/reply`

- 用途：workflow → conversation 的跨服务能力：生成给用户的受理/通过答复。
- 请求：`{ "message": "..." }`；响应 `{ "reply": "..." }`。
- 经网关：是（但设计为 workflow-service 内部调用）。默认开启。

### POST `/conversation/workflow/ticket`

- 用途：结构化抽取退款工单。
- 请求：`{ "message": "..." }`；响应 `{ title, priority, category, summary, tags[] }`。
- 经网关：是（内部调用）。默认开启。

---

## Knowledge（knowledge-service）

### POST `/rag/documents`

- 用途：上传知识库文档。需要 `ingest` scope。
- 支持两种 content-type：
  - `application/json`（`Map<String,String>`）：`title`（必填）、`text`、`contentType`（默认 `text/plain`，有 `imageBase64` 时默认 `image/png`）、`category`、图片可传 `imageBase64`。
  - `multipart/form-data`：表单字段 `file`（必填）+ 可选 `category`。
- 图片 ingestion：`image/*` 文件或 `imageBase64` 走 OpenAI 兼容多模态 embedding（存入独立的 `knowledge_images_<tenant>` collection），`RAG_MULTIMODAL_ENABLED`（application.yml 默认关→上传图片 400；compose 默认开，路由在但未配真实端点时调用期报错）。⚠️ 旧的 `caption`/`ocrText` 字段与 `RAG_IMAGE_TEXT_*` 图→文字路径已移除。
- 公共/共享库：可传 `visibility=public|shared` 写入 `__public__` 公共分区，需 `public-ingest` scope（否则 403）；缺省写本租户私有库。
- 文档级授权（仅 `RAG_AUTHZ_MODE=enforce`，**默认 `disabled` 不影响**）：**新建**（非共享）文档时把 `owner`（上传人）+ `home_dept`（上传人部门）关系写入外部 auth-platform；若判不出上传人部门（内部 JWT 无 `dept`）→ **403**「cannot determine uploader's home department」。**同名覆盖**既有非共享文档需对其有 `edit` 权限（否则 403，覆盖不夺原 owner）。disabled/shadow 不触发上述拦截。
- 响应：`DocumentInfo`。
- 经网关：是。默认开启（application.yml 默认向量库 `qdrant` + hash embedding，compose 走 nomic + ES 全文混排）。

JSON 示例：

```json
{
  "title": "guide.md",
  "text": "这是知识库文档。",
  "contentType": "text/markdown",
  "category": "manual"
}
```

### GET `/rag/documents`

- 用途：列出当前租户文档。响应 `DocumentInfo[]`。
- 经网关：是。默认开启。

### GET `/rag/documents/{docId}`

- 用途：查看单个文档元数据。响应 `DocumentInfo`，不存在返回 404。
- 经网关：是。默认开启。

### DELETE `/rag/documents/{docId}`

- 用途：删除文档、向量与关联图谱。需要 `ingest` scope。响应 `{ docId, deleted }`。
- 经网关：是。默认开启。

### POST `/rag/documents/{docId}/share`

- 用途：把某文档共享给另一用户（授予其对该文档的读权限）。
- 请求：`{ "userId": "bob" }`（被授权用户 id）。
- 权限：调用者须对该文档有 `share` 权限（服务层 SDK `@CheckAccess(permission="share", ...)`，`FULLY_CONSISTENT`）；无权 → **403**（`AccessDeniedException`）。
- 经网关：是。**仅 `RAG_AUTHZ_MODE=enforce` 时注册**（`@ConditionalOnProperty(app.rag.authz.mode=enforce)`）；disabled/shadow 下整个 controller 不装配（404）。

### DELETE `/rag/documents/{docId}/share/{userId}`

- 用途：撤销此前对某用户的文档共享。
- 权限：同上，调用者须有 `share` 权限（否则 403）。
- 经网关：是。仅 enforce 时注册（同上）。

### POST `/rag/image`

- 用途：图片入库（原生 CLIP 多模态 embedding，向量存入独立的 `knowledge_images_<tenant>` collection）。需要 `ingest` scope。
- 请求：`multipart/form-data`，表单字段 `image`（`image/*`，必填）。
- 响应：`{ id, fileName, type: "image" }`。
- 经网关：是。**默认关闭**，需 `RAG_MULTIMODAL_ENABLED=true`（关闭时返回 400）。

### POST `/rag/image-search`

- 用途：文本 query 跨模态检索图片。
- 请求：`application/json` `{ query（必填）, topK?, minScore? }`（缺省用 `RAG_MULTIMODAL_TOP_K` / `RAG_MULTIMODAL_MIN_SCORE`）。
- 响应：`{ query, results:[{ id, fileName, score }] }`。严格按当前租户隔离。
- 经网关：是。**默认关闭**，需 `RAG_MULTIMODAL_ENABLED=true`。

### POST `/rag/query`（别名 `/knowledge/query`）

- 用途：RAG 查询，四路混排融合——vector / keyword（内存 BM25）/ es（Elasticsearch 真 BM25）/ 可选 graph；ES 参与时有效默认 RRF 融合。
- 请求：`{ query, topK, minScore, category }`（`KnowledgeQueryRequest`）。
- 响应：`{ query, tenantId, hits[] }`，`hits[]` 含 `id, score, docId, displayName, category, index, text, source, visibility`（`source` 为 `vector|keyword|es|hybrid|graph`；`RAG_PUBLIC_ENABLED` 开启时公共分区命中标 `visibility=public`）。
- 文档级授权（仅 `RAG_AUTHZ_MODE=enforce`，**默认 `disabled` 不过滤**）：融合后、重排前经外部 auth-platform `checkBulk(view)` 按 ReBAC 过滤，只保留调用者有 `view` 权限的命中；**无 `docId` 的命中**（如 GraphRAG 三元组）在 enforce 下被 **fail-closed 丢弃**。`shadow` 档只双算记差异、不过滤。
- 经网关：是。默认开启。融合与权重可调（`RAG_FUSION_STRATEGY`、`RAG_RANKING_*_WEIGHT`）。

### GET `/rag/config`

- 用途：运行时只读回显 RAG 前端相关配置：`{ publicKbEnabled, ... }`，供前端决定是否展示共享库视图/共享图片入口。
- 响应：`KnowledgeRuntimeView`（`contractVersion=2`），仅含公共库开关、共享图片支持位与 RAG 后端形态（embedding / 向量库 / 混排 / graph / 多模态）。**刻意不透出任何文档级授权内部态**（无 `RAG_AUTHZ_MODE`、无权限/候选池 caps）——authz 是判权链路内部实现，不进前端能力协商。
- 经网关：是。**需认证但无需额外 scope**（边缘内部 JWT 把守）。

### POST `/rag/graph/query`

- 用途：GraphRAG 实体邻居查询。
- 请求：`{ query, maxHops, maxTriples, category }`。
- 经网关：是。**默认关闭**，需 GraphRAG 开启（`RAG_GRAPH_ENABLED=true`，`@ConditionalOnBean(GraphSearchService.class)`）。

### GET `/rag/graph/entities`

- 用途：列出当前租户图谱实体。可选 query `category=org`。响应 `{ category, entities, size }`。
- 经网关：是。默认关闭（同上 GraphRAG 开关）。

---

## Analytics（analytics-service）

### POST `/chat/sql`（别名 `/analytics/sql`）

- 用途：NL2SQL / ChatBI，自然语言转 SQL 并执行、解读。
- 请求：`{ "question": "..." }`（`AnalyticsSqlRequest`，裸 JSON 兼容）。
- 响应：`{ question, sql, rowCount, rows, answer, guardBlocked }`（`sql` 刻意一并回传，便于审计/复跑）。
- 经网关：是。**默认关闭**，需 `app.nl2sql.enabled=true`。

---

## Workflow（workflow-service）

整个 controller 需 `app.workflow.enabled=true`（**默认关闭**）。审批相关端点需 `approve` scope。

### POST `/workflow/refund/start`

- 用途：发起退款审批流程。发起方普通用户即可。
- 请求：`{ message, chatId?, dedupeId?, webhookUrl? }`（均 `Map<String,String>`；`chatId` 默认 `default`）。传 `dedupeId` 按 `tenant:chatId:dedupeId` 幂等去重；传 `webhookUrl` 则终态经 outbox 可靠回推。
- 响应：`StartResult`。经网关：是。

### GET `/workflow/tasks`

- 用途：当前租户待审任务列表。需 `approve` scope。响应 `TaskView[]`。经网关：是。

### POST `/workflow/tasks/{taskId}/claim`

- 用途：认领任务（设 assignee=当前用户）。需 `approve`。已被他人领 → 409。响应 `TaskView`。经网关：是。

### POST `/workflow/tasks/{taskId}/unclaim`

- 用途：取消认领，放回待领池。需 `approve`。经网关：是。

### POST `/workflow/tasks/{taskId}/complete`

- 用途：完成审批。需 `approve`。请求 `{ approved: true, comment? }`。并发双重审批 → 409。响应 `CompleteResult`。经网关：是。

### GET `/workflow/instances/{instanceId}`

- 用途：查实例状态 + reply。响应 `InstanceView`。经网关：是。

### DELETE `/workflow/data?chatId=u1`

- 用途：PII 合规删除——清除本租户某 `chatId` 下全部工作流数据。需 `approve`。响应 `{ chatId, purgedInstances }`。经网关：是。

---

## Agent（agent-service）

深度 Agent 相关端点条件装配于 `DeepAgentService`/`AgentDagService`/各 sibling service（bean 存在才注册），部分能力默认关闭需开关。

### POST `/agent/run`

- 用途：同步深度 Agent（ReAct，自主选 `rag_search`/`analytics_sql`/`current_time`/`delegate`/`finish` 等动作）。
- 请求：`{ "goal": "..." }`（`AgentRunRequest`）。响应 `AgentRunReply`。
- 经网关：是。默认开启（`@ConditionalOnBean(DeepAgentService.class)`）。

### POST `/agent/run/async`

- 用途：异步提交 Agent 任务，返回任务快照。
- 请求：`{ goal, webhookUrl? }`。终态时后台 POST 快照到 `webhookUrl`（带 `X-Agent-Task-Id`/`X-Agent-Task-Status`/`X-Tenant-Id`）。
- 经网关：是。默认开启。

### GET `/agent/tasks`

- 用途：列出当前租户 agent 任务。响应 `AgentAsyncTask[]`。经网关：是。默认开启。

### GET `/agent/tasks/{taskId}`

- 用途：查询单个 agent 任务，不存在 404。经网关：是。默认开启。

### DELETE `/agent/tasks/{taskId}`

- 用途：取消 agent 任务。响应 `{ taskId, cancelled }`。经网关：是。默认开启。

### GET `/agent/tasks/{taskId}/stream`

- 用途：SSE 订阅任务状态 + DAG 阶段事件（`text/event-stream`）。经网关：是。默认开启。

### POST `/agent/dag/run`

- 用途：显式多 Agent DAG 编排，按拓扑分层执行。
- 请求：`{ goal, tasks[], webhookUrl? }`，`tasks[]` 元素 `{ id, description, dependsOn[] }`（`AgentDagTask`）。
- 经网关：是。默认开启（`@ConditionalOnBean(AgentDagService.class)`）。

```json
{
  "goal": "分析退款审批规则，并给出运营建议",
  "tasks": [
    {"id": "t1", "description": "从知识库检索退款审批规则要点", "dependsOn": []},
    {"id": "t2", "description": "基于 t1 总结潜在运营风险", "dependsOn": ["t1"]}
  ]
}
```

### POST `/agent/dag/plan-run`

- 用途：只传目标，Planner 自动拆 DAG 后执行。请求 `{ "goal": "..." }`（`Map<String,String>`）。经网关：是。默认开启。

### POST `/agent/dag/run/async`

- 用途：显式 DAG 异步版，返回 `taskId`（用 `/agent/tasks/{taskId}` 查）。请求同 `/agent/dag/run`。经网关：是。默认开启。

### POST `/agent/dag/plan-run/async`

- 用途：自动规划 DAG 异步版。请求 `{ goal, webhookUrl? }`（`Map<String,String>`）。经网关：是。默认开启。

### POST `/agent/reflexive`

- 用途：Reflexion 自省环，同步跑完返回含各轮评分轨迹的 `ReflexionReply`。
- 请求：`{ "question": "..." }`（`ReflexionRequest`）。
- 经网关：是。**默认关闭**（`@ConditionalOnBean(ReflexionService.class)`）。

### POST `/agent/reflexive/stream`

- 用途：Reflexion SSE，分阶段推 `attempt-start`/`answer`/`critique`/`done` 事件。请求同上。
- 经网关：是。默认关闭（同上）。

### POST `/agent/vote`

- 用途：并行跑 N 次 + 聚合投票。请求 `{ question, n? }`（`n` 可选，默认 `app.agent.voting.n`）。响应 `VoteReply`（votes/decision/agreement/confident）。
- 经网关：是。**默认关闭**（`@ConditionalOnBean(VotingService.class)`）。

### POST `/agent/chain`

- 用途：Prompt Chaining，跑 yml 预定义链（`app.agent.chaining.steps`）。请求 `{ "input": "..." }`（`ChainRunRequest`）。未配 steps → 400。
- 经网关：是。**默认关闭**（`@ConditionalOnBean(PromptChainService.class)`）。

### GET `/agent/capabilities`

- 用途：live capability discovery，返回 agent-service 当前暴露的 MCP 工具描述（`McpToolDescriptor[]`：`platform.agent.run`、`platform.agent.run_async`、`platform.agent.dag.plan_run`、`platform.agent.dag.plan_run_async`）。
- 经网关：是。默认开启（`@ConditionalOnBean(DeepAgentService.class)`）。

> 其他 agent 动作开关（默认关闭）：`AGENT_CODE_EXEC_ENABLED`（JShell 非沙箱）、`AGENT_MCP_ENABLED`（`mcp_call`）、`AGENT_BROWSER_ENABLED`（Playwright 浏览器动作）、`AGENT_DAG_REPLAN_ENABLED`（Critic/Replan 闭环）。详见 README。

---

## Async Task（async-task-service）

通用任务中心。默认内存任务表；可选 `ASYNC_TASK_STORE=jdbc` 持久化到 MySQL。全部默认开启。

### POST `/async/tasks`

- 用途：创建通用异步任务。
- 请求：`{ kind, input?, taskId?, webhookUrl? }`（`AsyncTaskCreateRequest`；`kind` 必填，`input` 为任意 JSON，`taskId` 可自带做幂等——已存在返回 409）。
- 响应：`AsyncTask`（202）。经网关：是。

```json
{
  "kind": "agent.run",
  "input": {"goal": "..."},
  "webhookUrl": "http://host.docker.internal:9000/async-task-callback"
}
```

### GET `/async/tasks`

- 用途：列出当前租户任务。响应 `AsyncTask[]`。经网关：是。

### GET `/async/tasks/{taskId}`

- 用途：查询任务（按租户隔离），不存在 404。经网关：是。

### PATCH `/async/tasks/{taskId}/status`

- 用途：worker 更新任务状态。
- 请求：`{ status, result?, error?, workerId? }`（`AsyncTaskStatusUpdateRequest`）。已终态则原样返回；lease 被他 worker 持有 → 409。
- 经网关：是（worker 调用）。

### POST `/async/tasks/{taskId}/lease`

- 用途：worker 认领/续租（`PENDING` → `RUNNING`）。
- 请求：`{ workerId, leaseSeconds? }`（`workerId` 必填）。lease 未过期只允许 owner 更新；冲突 → 409。
- 经网关：是（worker 调用）。

### DELETE `/async/tasks/{taskId}`

- 用途：取消任务。响应 `{ taskId, cancelled }`；已终态/不存在 → 404。经网关：是。

### GET `/async/tasks/{taskId}/stream`

- 用途：SSE 状态流（`text/event-stream`），支持 `Last-Event-ID` header 或 `lastEventId` query 断点续订。经网关：是。

### GET `/async/webhook-outbox/dead`

- 用途：查询当前租户投递耗尽（`DEAD`）的 webhook outbox 记录。可选 query `limit`（默认 50，上限 200）。经网关：是。

---

## Channel（channel-service）

渠道 ACL：出站投递 + 回调转投 + 入站签名校验。全部默认开启。

### GET `/channel/capabilities`

- 用途：查看渠道能力。响应 `{ service, tenantId, channels: [feishu, voice, webhook], status }`。经网关：是。

### POST `/channel/messages`

- 用途：发送出站渠道消息。
- 请求：`{ channel, target, message, metadata? }`（`ChannelMessageRequest`；三者缺一 → 400）。
- 经网关：是。

```json
{
  "channel": "voice",
  "target": "user-1",
  "message": "hello",
  "metadata": {"providerUrl": "http://voice-provider.local/calls"}
}
```

### POST `/channel/callbacks`

- 用途：把外部终态回调转为渠道消息并复用 dispatcher。
- 请求：`ChannelCallbackRequest`（`{ source, sourceId, status, channel, target, message, metadata? }`）。
- 经网关：是（内部/外部回调）。

### POST `/channel/callbacks/async-task`

- 用途：接收 async-task-service 终态 webhook。请求为原始 `Map` payload + 头 `X-Async-Task-Id`/`X-Async-Task-Status`；`channel`/`target`/`message` 可在顶层、`result` 或 `metadata` 中。
- 经网关：是（内部回调）。

### POST `/channel/callbacks/workflow`

- 用途：接收 workflow 终态 webhook，映射规则同 async-task callback；头为 `X-Workflow-Instance-Id`/`X-Workflow-Status`。
- 经网关：是（内部回调）。

### POST `/channel/inbound`

- 用途：接收入站渠道事件。请求 `ChannelInboundEvent`（`{ eventId, channel, source, eventType, payload }`；`channel`/`eventType` 必填）+ 头 `X-Channel-Signature`（签名校验失败 → 401）。响应 `{ eventId, status, receivedAt }`。
- 经网关：是（外部 webhook 入站）。

### POST `/channel/feishu/events`、POST `/channel/dingtalk/events`

- 用途：飞书 / 钉钉群 @机器人 入站客服桥——验签（飞书 SHA-256+AES-256-CBC / 钉钉 HmacSHA256）+ 按 msgId 去重 → 设租户 → 调 conversation `/chat`（可带 RAG）→ 机器人回消息。钉钉侧含「知识库无命中 → 转人工」兜底闸门。
- 经网关：是，且**免鉴权放行**（靠渠道签名验真）。**默认关闭**，需 `FEISHU_*` / `DINGTALK_*` 配置开启。详见 [钉钉知识库客服接入指南](../互操作渠道/dingtalk-guide.md)。

---

## Interop（interop-service）

A2A + MCP surface。全部默认开启。

### GET `/interop/agent-card`

- 用途：平台自有互操作 agent card，capabilities 由 MCP tool registry 生成。响应 `AgentCard`。经网关：是。

### GET `/interop/a2a/agent-card`

- 用途：上者的兼容别名，返回同一 `AgentCard`。经网关：是。

### GET `/interop/mcp/tools`

- 用途：列出 MCP-style tool surface。响应 `McpToolDescriptor[]`。经网关：是。

### GET `/interop/mcp/tools/{toolName}`

- 用途：查询单个 MCP tool 描述 + 输入 schema。未知 tool → 404。经网关：是。

### POST `/interop/mcp/call`

- 用途：调用 MCP-style tool（代理到下游服务）。
- 请求：`{ tool, arguments }`（`McpToolCallRequest`）。响应 `McpToolCallReply`；未知 tool → 404，下游失败 → 502，缺 goal → 400。
- 经网关：是。

```json
{
  "tool": "platform.agent.run",
  "arguments": {"goal": "查询退款规则"}
}
```

### GET `/.well-known/agent-card.json`

- 用途：真 A2A Server 发现端点，返回 `A2aAgentCard`（纯静态元数据）。
- 经网关：是，且**免鉴权**（网关白名单放行，无需 `X-Api-Key`）。

### POST `/interop/a2a`

- 用途：真 A2A Server JSON-RPC 2.0 单端点，代理到 agent-service。**需鉴权**。
- 请求：JSON-RPC 报文 `{ jsonrpc, id, method, params }`；缺/非字符串 `method` → JSON-RPC INVALID_REQUEST。
- 响应：`message/stream` 方法返回 `text/event-stream`（**真流式**，逐事件推任务进展）；其余方法（`message/send`、`tasks/*`、push config）返回 `JsonRpcResponse`（application/json）。
- 经网关：是。流式读超时 `INTEROP_STREAM_READ_TIMEOUT`（默认 120s）。

### POST `/interop/a2a/push-callback`

- 用途：A2A push 通知中继回调——接收 agent-service 的任务终态 webhook（内网直连、**不经 edge-gateway、不带内部 JWT**），据头判断是否把终态回推给已登记 push 的 A2A 客户端。
- 请求：无 body；头 `X-Agent-Task-Id` / `X-Tenant-Id`。未登记 push 的任务被忽略。
- 响应：恒 `200`（webhook 语义：收到即确认）。
- 经网关：否（agent-service → interop-service 内网直连，非终端用户调用）。

---

## Eval（eval-service）

外部回归测试客户端。全部默认开启。

### GET `/eval/capabilities`

- 用途：查看评测能力（支持的断言类型、dualRun 模式/指标、baseline/snapshot 位置）。经网关：是。

### POST `/eval/run`

- 用途：执行一组 HTTP eval case。
- 请求：`EvalRunRequest`（`{ targetBaseUrl?, cases[] }`；`cases` 不能为空，`targetBaseUrl` 缺省用 `app.eval` 配置）。`case` 支持 `expectedContains`/`oracleContains`/`expectedJsonPaths`/`semanticExpected`+`semanticMinScore`/`judgeExpected`/`embeddingExpected` 等断言。
- 响应：`EvalRunReply`（202）。经网关：是（受 `eval` scope + `eval` 限流约束）。

```json
{
  "targetBaseUrl": "http://edge-gateway:8080",
  "cases": [
    {
      "id": "chat-smoke",
      "method": "POST",
      "endpoint": "/chat?chatId=eval",
      "body": {"message": "hello"},
      "expectedContains": "reply"
    }
  ]
}
```

### POST `/eval/suites/{suiteName}/run`

- 用途：执行内置或外部 baseline suite。请求可选 `{ targetBaseUrl }`（`EvalSuiteRunRequest`）。suite 不存在 → 404，无 case → 400。响应 `EvalRunReply`（202）。经网关：是。

### POST `/eval/dual-run`

- 用途：oracle vs candidate 双跑，返回门禁明细，**HTTP 恒 200**（信息化）。
- 请求：`EvalDualRunRequest`。响应 `EvalDualRunReply`。经网关：是。

### POST `/eval/gate`

- 用途：CI 门禁——双跑后有回归返回 **HTTP 422**（body 为 `EvalDualRunReply`，含 regressions 明细），无回归 200。
- 请求：`EvalDualRunRequest`。经网关：是。

---

## Vision（vision-service）

整个 controller 需 `app.vision.enabled=true`（**默认关闭**）。

### POST `/vision/caption`（别名 `/vision/describe`）

- 用途：图像描述 / OCR（caption + 文字转写）。两种入参：
  - `application/json`：`VisionCaptionRequest`（`{ imageBase64, mimeType?, instruction? }`；`imageBase64` 必填，跨服务调用如 agent `browser_see` 走这条）。
  - `multipart/form-data`：表单字段 `file`（必填）+ 可选 `instruction`。
- `instruction` 留空 → 用配置的默认 caption/OCR 指令；`mimeType` 缺省兜底 `image/png`。守卫/入参校验失败 → 400。
- 响应：`VisionCaptionReply`（`{ text, modelName, length }`）。
- 经网关：是（受 `vision` scope 约束）。默认关闭。

---

## Voice（voice-service）

语音闭环：**ASR → conversation-service(`/chat`) → TTS**，对话大脑/RAG/多租户全部在下游复用。整个 controller 需 `app.voice.enabled=true`（**默认关闭**），走与 `/chat` 同一套鉴权链（任意合法 api-key 可用）。默认 provider `openai`（兼容协议），指向 `VOICE_BASE_URL`（默认 `https://api.openai.com/v1`），需配 `VOICE_API_KEY`；`base-url` 也可指向 Azure / 本地 whisper+tts 网关。

### POST `/voice/chat`

- 用途：完整轮次——音频 → ASR → `/chat` → TTS，一次返回转写 + 回复文本 + base64 语音。
- 请求：`multipart/form-data`，表单字段 `audio`（必填）+ 可选 query `chatId`（缺省自动生成 `voice-<uuid>`；同 `/chat` 隔离多轮记忆）。
- 响应：`VoiceReply` `{ transcript, reply, route, audioBase64, audioContentType }`。`route` 为 `CHAT`；转写为空则不进对话（不烧 token），`route=NONE` 并播固定"没听清"提示。回复文本保留 `[doc=...]` 引用标记，TTS 语音已剥离。
- 校验：空音频或超 `VOICE_MAX_AUDIO_BYTES`（默认 25MB）→ 400。
- 经网关：是。默认关闭。

```bash
curl -s -X POST 'http://localhost:8080/voice/chat?chatId=u1' \
  -H 'X-Api-Key: dev-key-acme' \
  -F 'audio=@/path/to/question.mp3'
```

### POST `/voice/chat/stream`

- 用途：SSE 半流式——音频 → ASR → `/chat` → 分句 TTS，边生成边逐句回传语音。
- 请求：同 `/voice/chat`（multipart `audio` + 可选 query `chatId`）。
- 响应：`text/event-stream`。先发 `transcript` 事件，再逐句发 `audio-chunk`（`{ text, audioContentType, audioBase64 }`），最后 `done`。分句最小字数 `VOICE_STREAM_MIN_CHARS`（默认 8，过短句不单独切）。空/超大音频以 SSE error 收尾。
- 经网关：是。默认关闭。

### POST `/voice/transcribe`

- 用途：只做 ASR（调试 / 纯转写需求），不进对话、不合成语音。
- 请求：`multipart/form-data`，表单字段 `audio`（必填）。
- 响应：`{ transcript }`。空/超大音频 → 400。
- 经网关：是。默认关闭。

> 主要开关（默认全关）：`VOICE_ENABLED=false`、`VOICE_PROVIDER=openai`、`VOICE_BASE_URL=https://api.openai.com/v1`、`VOICE_API_KEY`、`VOICE_ASR_MODEL=whisper-1`、`VOICE_TTS_MODEL=tts-1`、`VOICE_TTS_VOICE=alloy`、`VOICE_TTS_FORMAT=mp3`（决定回复音频 content-type）、`VOICE_LANGUAGE`（留空自动检测）、`VOICE_CONVERSATION_BASE_URL=http://localhost:8081`、`VOICE_STREAM_MIN_CHARS=8`、`VOICE_MAX_AUDIO_BYTES=26214400`(25MB)、`VOICE_MAX_UPLOAD=25MB`、`VOICE_TIMEOUT_SECONDS=30`。
