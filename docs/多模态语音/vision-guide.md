# 视觉 / 多模态接入指南

本指南面向要给平台接入「看图」能力的开发者。在新微服务平台里，视觉/多模态横跨**三个入口面**，
外加一条正交的原生 CLIP 图片 embedding（在 `rag-guide.md` 里单独讲）：

| 入口面 | 服务 / 端点 | 干什么 | 开关（默认） |
| --- | --- | --- | --- |
| ① 独立视觉服务 | `vision-service`（`:8090`）`POST /vision/caption`、`POST /vision/describe` | 一张图 + 可选指令 → 文本（描述 + OCR 转写，或按问题看图作答） | `VISION_ENABLED=true`（须配 `VISION_MODEL`，否则 fail-fast） |
| ② 看图对话 | `conversation-service` `POST /chat/vision` | multipart `image` + `message` 的单轮看图问答，薄委托转发给 ①  | `CONVERSATION_VISION_ENABLED=true` |
| ③ Agent 视觉动作 | `agent-service` `browser_see` 工具 | 深度 Agent 截当前页面图交 ① 理解，「看」文本抽不出的页面（图表/布局/纯图片页） | `AGENT_VISION_ENABLED=true`（`browser_see` 还需 `AGENT_BROWSER_ENABLED=true`，默认关） |
| （正交）原生 CLIP 图片 embedding | `knowledge-service` `POST /rag/image`、`POST /rag/image-search` | 图片直接向量化进跨模态空间，文本 query ↔ 图片互检索 | `RAG_MULTIMODAL_ENABLED=true`（详见 [`rag-guide.md`](../对话与检索/rag-guide.md) 第 3 节） |

**三个入口面默认全关**，关闭时相关 Bean / 路由 / 网络依赖一概不装配，零开销。所有对外调用统一走
边缘网关 `http://localhost:8080` + `-H 'X-Api-Key: dev-key-acme'`（网关校验 api-key → 签发内部 JWT → 路由下游）。
`dev-key-acme` 已带 `vision` scope。

> ⚠️ **破坏性变更（已在新项目落地）**：老单体里「上传图片 → 视觉模型转 caption/OCR 文本 → 入 RAG 文本索引」
> 那条**入库路径已整体移除**。新项目不再有 `RAG_IMAGE_TEXT_*` / `ImageTextProvider` / `MultimodalDocumentExtractor`，
> 也不再从 `/rag/documents` multipart 接收图片走 caption 入库。图片进知识库现在**只**走 `knowledge-service` 的
> **原生 CLIP / jina-clip 多模态 embedding**（`/rag/image`，图片直接向量化、存独立 image collection），见
> [`rag-guide.md`](../对话与检索/rag-guide.md) 第 3 节。本文的 `vision-service` `/vision/caption` 仍产出「描述 + OCR」文本，
> 但那是**独立的看图端点**，不再是 RAG 入库链的一环。

---

## 1. vision-service —— 图像转文本（`:8090`）

### 做什么

把「一张图 + 一段指令」喂给多模态 LLM，拿回文本：

- **指令留空** → 用内置默认 caption/OCR 指令：**一次调用同时产出**「图像语义描述 + 图表数值趋势 + 逐字文字转写（OCR）」。
  避免「先描述再 OCR」两次烧钱的视觉调用。
- **指令非空** → 按该问题看图作答（如「这张图 Q2 营收是多少？」）。

`POST /vision/caption` 与 `POST /vision/describe` 是**同一个处理器的两个别名**（行为完全一致），按语义各取所需即可。

### 为什么单独成服务

- **不注册第二个 `ChatModel` Bean**：LangChain4j 的 `@AiService` 按类型自动发现 `ChatModel` Bean，进程里多于 1 个就冲突。
  所以视觉 `ChatModel` 由 `VisionConfig` 经 `GatewayChatModelFactory.build(...)` **直接构造、塞进 `DefaultVisionModel` 内部持有，
  不暴露成 Bean**；对外只暴露自定义 `VisionModel` 接口。放进独立服务后，天然与主 `ChatModel` 隔离。
- **模型解耦、但走同一 LiteLLM**：视觉模型与主文本模型**共用同一个 LiteLLM base-url**，仅逻辑模型名不同
  （`VISION_MODEL` 指向 LiteLLM `model_list` 里的多模态模型，如 `gpt-4o-mini` / `qwen2.5-vl`）。
  **新项目里没有 `provider` switch**（老单体的 `openai-compat`/`ollama` 分流已收敛进 LiteLLM 配置）——
  换 provider/failover 改 `deploy/litellm/config.yaml`，不改 Java、不改这里的环境变量。
- **租户 token 正确归因**：视觉 `ChatModel` 由 `GatewayChatModelFactory` 自动挂上全部 `ChatModelListener`
  （指标 / 成本 / per-tenant token 预算）。租户身份沿内部 JWT 透传、在 vision-service 还原进 `TenantContext`，
  视觉调用的 token 因此按正确租户计入日预算与成本，不绕过配额。

### 开启并启动

`VISION_ENABLED=true` 时**必须**配 `VISION_MODEL`，否则启动 **fail-fast** 明确报错（绝不静默降级到文本模型）。

```bash
# 单跑视觉服务（LiteLLM 需可达，且其 model_list 里有名为 gpt-4o-mini 的多模态模型）
VISION_ENABLED=true \
VISION_MODEL=gpt-4o-mini \
  mvn -pl vision-service spring-boot:run
# 或整套本地栈：VISION_ENABLED=true VISION_MODEL=gpt-4o-mini docker compose -f deploy/docker-compose.yml up --build
```

### 调用 A —— JSON（跨服务契约 `VisionCaptionRequest`）

请求体：`{ "imageBase64": <必填>, "mimeType": <可选,缺省 image/png>, "instruction": <可选,留空走默认描述+OCR> }`。
`imageBase64` 用标准 Base64（**不能带换行**，故下面 `tr -d '\n'`）。

```bash
curl -s -X POST 'http://localhost:8080/vision/caption' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d "{\"imageBase64\":\"$(base64 -i chart.png | tr -d '\n')\",\"mimeType\":\"image/png\"}"
# 按问题看图作答：加 "instruction":"这张图的 Q2 营收是多少？"
```

响应体 `VisionCaptionReply`：

```json
{ "caption": "图为2024各季度营收柱状图……Q2 为 320 万元……", "model": "gpt-4o-mini", "chars": 128 }
```

`caption` = 描述/转写/回答正文；`model` = 实际视觉模型名（便于审计/观测）；`chars` = 字符数（调用方快速判空）。
这条 JSON 契约就是入口面 ②③ 内部转发时用的形态。

### 调用 B —— multipart（直接传文件）

便于人工/工具直接上传图片文件；表单字段 `file` 必填、`instruction` 可选：

```bash
curl -s -X POST 'http://localhost:8080/vision/caption' \
  -H 'X-Api-Key: dev-key-acme' \
  -F 'file=@chart.png' \
  -F 'instruction=逐字转写图中所有文字'
```

响应同上（`VisionCaptionReply`）。

### 守卫（图片入口硬门）

图片进入昂贵的视觉调用前，`VisionContentGuard` 先过两道硬门，越限一律 **400**（不静默放行）：

| 守卫 | 环境变量（默认） | 行为 |
| --- | --- | --- |
| 字节上限 | `VISION_MAX_IMAGE_BYTES`（`10485760`＝10MB） | 超限 → 400 `image too large`；`0` = 不限 |
| MIME 白名单 | `VISION_ALLOWED_MIME`（`image/png,image/jpeg,image/webp,image/gif`） | 不在名单 → 400 `unsupported image type`；留空 = 不限具体类型，仅按 `image/` 前缀兜底 |
| multipart 上传体 | `VISION_MAX_UPLOAD`（`12MB`，Spring 层） | 超过 → 上传被 Spring 挡下 |

空图、坏 base64、空 `file` 同样 → 400。MIME 缺失或非 `image/` 前缀会被归一化兜底为 `image/png`。

> ⚠️ **与老单体的差异（已知缺口）**：老单体的 `VisionContentGuard` 还含**文本级安全闸**——对 caption/OCR
> 出的文本做「提示注入阻断 + PII 脱敏」（因为图里可藏注入指令、转写可能带 PII）。新项目 vision-service 的守卫
> **目前只移植了字节 + MIME 两道门**；注入/PII 文本闸门依赖尚未迁移的 guardrail 模块，本轮未移植。
> 若把视觉产出的文本再喂给下游 LLM，注意它是**不可信外部输入**。

### caption 缓存

默认指令（入库式描述+OCR）路径带**按图内容 SHA-256 去重的有界 LRU 缓存**：同一张图重复上传直接复用上次结果，
省掉重复且昂贵的视觉调用。带**具体问题**的看图问答**不缓存**（答案随问题变化）。

- `VISION_CAPTION_CACHE_SIZE`（默认 `256`，`0` = 关缓存）
- 温度 `VISION_TEMPERATURE`（默认 `0.2`）：看图转写/描述偏确定性，压低温度减少漂移
- 默认 caption/OCR 指令可用 `app.vision.caption-prompt` 覆盖（yml 配置项）

---

## 2. conversation `/chat/vision` —— 单轮看图对话

### 做什么

`POST /chat/vision`（multipart `image` + `message`）：把图片和问题作为一次**单轮**看图问答，`conversation-service`
把图片 base64 后经 `VisionClient` **薄委托转发**给 vision-service（`message` 作为其 `instruction`）。视觉能力本体在
vision-service，这里只是对话侧的统一入口。

- **单轮、无 ChatMemory**：刻意不带多轮记忆——看图作答语义清晰、可重复。要多轮可自行在外层接记忆。
- 默认实现是 `NoopVisionClient`（未启用），`enabled()=false` → controller 走明确的禁用提示分支，不是 NPE。

### 开启

需要**同时**：conversation 侧开开关，且 vision-service（入口面 ①）已启动可达。

```bash
# conversation-service 侧
CONVERSATION_VISION_ENABLED=true \
CONVERSATION_VISION_BASE_URL=http://localhost:8090 \
  mvn -pl conversation-service spring-boot:run
# 另需按第 1 节把 vision-service 起起来（VISION_ENABLED=true + VISION_MODEL=…）
```

相关旋钮：`CONVERSATION_VISION_CONNECT_TIMEOUT`（默认 `1s`）、`CONVERSATION_VISION_READ_TIMEOUT`（默认 `60s`）。
转发用的 RestTemplate 带 `OutboundTenantForwarder` + `OutboundTraceForwarder`，租户/trace 随内部 JWT 透传，
vision-service 的 token 计量因此按同一租户归因。

### curl

```bash
curl -s -X POST 'http://localhost:8080/chat/vision' \
  -H 'X-Api-Key: dev-key-acme' \
  -F 'image=@chart.png' \
  -F 'message=这张图的 Q2 营收是多少？'
```

响应（未启用时返回带 `error` 的禁用提示）：

```json
{ "reply": "Q2 营收约 320 万元……", "model": "gpt-4o-mini", "chars": 42, "tenantId": "acme", "userId": "alice" }
```

`reply` = 视觉模型答案；`model` = 实际视觉模型名；`tenantId`/`userId` 回填自 `TenantContext`。

---

## 3. Agent 视觉动作 —— `browser_see`

### 做什么

给深度 Agent（ReAct）一个「看页面」的工具：截当前浏览器页面图 → 提交给独立 vision-service 理解，让 Agent 能读到
`browser_open` 纯文本抽不出的内容（图表、布局、验证码、纯图片页）。串联 `browser_screenshot`（出截图）与 vision-service
（看图），闭合「截图 → 理解」回路。`actionInput` 填想问的问题（留空则整体描述页面）。

### 双门控（重要）

`browser_see` 只有在**三个开关同时为 true** 且 `BrowserSession` Bean 存在时才出现在工具清单里：

- `AGENT_ENABLED`（默认 **true**）
- `AGENT_BROWSER_ENABLED`（默认 false）—— 浏览器动作总开关
- `AGENT_VISION_ENABLED`（默认 true）—— 视觉后端开关

视觉后端关闭时，这个动作不装配、也不出现在工具清单。转发同样走带租户/trace 透传的 RestTemplate，视觉 token 按透传租户归因。

### 开启

```bash
AGENT_ENABLED=true \
AGENT_BROWSER_ENABLED=true \
AGENT_VISION_ENABLED=true \
VISION_BASE_URL=http://localhost:8090 \
  mvn -pl agent-service spring-boot:run
# 同样需 vision-service 已按第 1 节启动
```

相关旋钮：`AGENT_VISION_READ_TIMEOUT`（默认 `60s`）。Playwright 浏览器二进制首次需联网安装（见 CLAUDE.md）。
`browser_see` 作为 Agent 工具，通过 `/agent/run` 触发，不是一个独立 HTTP 端点，用法见 [`agent-guide.md`](../Agent编排/agent-guide.md)。

---

## 4. 原生 CLIP 图片 embedding（正交，见 rag-guide）

上面三面都是「图 → 文本」（caption/OCR/问答）。要「以文搜图 / 保留图的原生视觉语义」，用另一条**正交**路径：
`knowledge-service` 的原生 CLIP / jina-clip 多模态 embedding——图片**直接**向量化进跨模态空间，文本 query 用同一模型
embed 后算相似度，命中「红色跑车」这类难以言说的视觉语义。

- `POST /rag/image`（图片入库）、`POST /rag/image-search`（文本搜图）
- 开关 `RAG_MULTIMODAL_ENABLED`（默认开），模型 `jinaai/jina-clip-v2`、维度 1024，向量存**独立 image collection**
  （与文本索引维度隔离）

完整配置、curl、维度安全与租户隔离**不在本文重复**，详见 [`rag-guide.md`](../对话与检索/rag-guide.md) 第 3 节「图片多模态 embedding（CLIP）」。
如前所述，老单体的「图 → caption 文本 → 入文本 RAG」入库路径**已被这条原生 CLIP 路径取代**。

---

## 设计要点与坑（值得记住的）

- **一个进程一个 `ChatModel` Bean**：视觉模型永远藏在自定义 `VisionModel` 接口后、内部持有非 Bean 的 `ChatModel`——
  否则会撞 `@AiService` 的按类型自动发现。跨服务隔离（vision-service）是最干净的落法。
- **fail-fast 而非静默降级**：`VISION_ENABLED=true` 却没配 `VISION_MODEL` → 启动直接报错。视觉答错/乱计量比启动失败更难查。
- **provider 收敛进 LiteLLM**：不要找 `APP_VISION_PROVIDER`——新项目没有。云 OpenAI / vLLM / 本地 Ollama 多模态模型的选择与
  failover 全在 `deploy/litellm/config.yaml` 的 `model_list`，Java 侧只认一个逻辑模型名。
- **token 计量不绕过配额**：视觉 `ChatModel` 经 `GatewayChatModelFactory` 挂全部 `ChatModelListener`；租户在请求线程的
  `TenantContext` 上，归因正确。（注：限流 `ingest` family 不预拦视觉调用，但 token 用量仍被记账可见。）
- **caption 缓存只覆盖默认指令路径**：同图重复入库式描述复用；带问题的看图问答不缓存。
- **看图对话单轮无记忆**：`/chat/vision` 刻意不带 ChatMemory，可重复、语义清晰。
- **已知缺口**：vision-service 守卫目前只有「字节 + MIME」两道门，老单体的「注入阻断 + PII 脱敏」文本闸尚未迁移——
  视觉产出文本按不可信外部输入对待。

---

## 开关速查

| 环境变量 | 默认 | 作用 | 所在 |
| --- | --- | --- | --- |
| `VISION_ENABLED` | `true` | 视觉服务总开关（须配 `VISION_MODEL` 否则 fail-fast）；置 `false` 则整链不装配 | vision-service |
| `VISION_MODEL` | `""` | 视觉逻辑模型名（LiteLLM `model_list` 多模态模型）；开启时**必填**，否则 fail-fast | vision-service |
| `VISION_TEMPERATURE` | `0.2` | 视觉调用温度（偏确定性） | vision-service |
| `VISION_MAX_IMAGE_BYTES` | `10485760`（10MB） | 单图字节上限；`0`=不限 | vision-service |
| `VISION_ALLOWED_MIME` | `image/png,image/jpeg,image/webp,image/gif` | MIME 白名单；留空=仅按 `image/` 前缀兜底 | vision-service |
| `VISION_CAPTION_CACHE_SIZE` | `256` | caption 缓存条数（SHA-256 去重）；`0`=关 | vision-service |
| `VISION_MAX_UPLOAD` | `12MB` | multipart 上传体上限（Spring 层） | vision-service |
| `GATEWAY_BASE_URL` / `GATEWAY_API_KEY` | `http://localhost:4000/v1` / `sk-litellm-master` | 视觉 ChatModel 走的 LiteLLM 端点（与文本模型共用） | vision-service |
| `CONVERSATION_VISION_ENABLED` | `true` | `/chat/vision` 看图对话开关 | conversation-service |
| `CONVERSATION_VISION_BASE_URL` | `http://localhost:8090` | 转发到 vision-service 的地址 | conversation-service |
| `CONVERSATION_VISION_CONNECT_TIMEOUT` | `1s` | 连接超时 | conversation-service |
| `CONVERSATION_VISION_READ_TIMEOUT` | `60s` | 读超时 | conversation-service |
| `AGENT_ENABLED` | `true` | 深度 Agent 总开关（`browser_see` 前置） | agent-service |
| `AGENT_BROWSER_ENABLED` | `false` | 浏览器动作开关（`browser_see` 门控之一） | agent-service |
| `AGENT_VISION_ENABLED` | `true` | Agent 视觉后端开关（`browser_see` 门控之一） | agent-service |
| `VISION_BASE_URL` | `http://localhost:8090` | agent 转发到 vision-service 的地址 | agent-service |
| `AGENT_VISION_READ_TIMEOUT` | `60s` | agent → vision 读超时 | agent-service |
| `RAG_MULTIMODAL_ENABLED` | `true` | 原生 CLIP 图片 embedding（正交路径，详见 rag-guide.md） | knowledge-service |

> 相关文档：[`rag-guide.md`](../对话与检索/rag-guide.md)（CLIP 图片 embedding）、[`agent-guide.md`](../Agent编排/agent-guide.md)（`browser_see` 等 Agent 工具）、
> [`operations.md`](../参考/operations.md)（运行配置）、[`api-reference.md`](../参考/api-reference.md)（接口速查）。
