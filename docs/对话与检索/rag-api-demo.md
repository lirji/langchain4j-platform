# RAG 知识库接口演示（可直接调用）

一套**开箱即调**的接口集合，演示本平台的核心能力:**RAG 闭环(上传知识 → 列表 → 语义检索命中 → 查看 → 删除)**,以及 **`/chat` 多轮对话**和 **`/agent/run` ReAct 智能体**。
既可在演示时逐条复制粘贴 `curl`,也可以一键跑通 RAG 闭环(见文末脚本)。所有「预期输出」都是对运行中的栈真实跑出来的结果。

---

## 一、可以对面试官讲的两个设计点

1. **两层网关。**
   - `edge-gateway`(唯一对外入口)校验 `X-Api-Key`,换发一枚**短时内部 JWT**,再按路径路由到下游服务。下游只认这枚内部 JWT。
   - `LiteLLM`(外部 AI 网关)统一所有模型调用,provider 路由/failover/模型名映射都在它的配置里 —— 所以 Java 代码里没有任何 provider `switch`。
2. **租户随 JWT 全链路传播 + 隔离。** api-key 绑定租户(这里 `dev-key-acme-ingest` → 租户 `acme`)。上传的文档、检索的结果都严格按租户隔离:换一个租户的 key 就查不到 `acme` 的数据。检索/上传都在 `TenantContext` 里带着租户走。

> **为什么必须走网关(18080),不能直连 knowledge-service(8084)?**
> `X-Api-Key` 只有网关认识。直连 `:8084` 时这个 header 被忽略,租户退化成 `anonymous` —— 既看不到 `acme` 的数据,也演示不出鉴权和多租户。**演示一律走网关。**

---

## 二、前置与变量

栈已起(`docker compose -f deploy/docker-compose.yml up -d`)。下面所有命令先设这两个变量:

```bash
# 网关地址。注意:当前容器实际映射到宿主机 18080;
# deploy/docker-compose.yml 文件里写的是 8080 —— 若你从该文件重启栈,请改成 8080。
export BASE_URL=http://127.0.0.1:18080

# 这把 key 绑定租户 acme,且带 ingest scope(上传/删除必需)。
export API_KEY=dev-key-acme-ingest
```

可用的开发 api-key(定义在 `edge-gateway/src/main/resources/application.yml`):

| api-key | 租户 | scopes | 能干啥 |
|---|---|---|---|
| `dev-key-acme-ingest` | acme | chat, **ingest** | 本演示全程用它(能上传/删除) |
| `dev-key-acme` | acme | chat, agent, channel… | 能检索/看,但**上传会 403**(没 ingest) |
| `dev-key-tenantA-admin` | tenantA | chat, analytics | 另一个租户,查不到 acme 的数据 |

---

## 三、接口逐条(每条含预期输出)

### 0. 鉴权:不带 key 直接 401
```bash
curl -s -o /dev/null -w "%{http_code}\n" "$BASE_URL/rag/documents"
```
```
401
```
> 证明网关在鉴权。带上 `-H "X-Api-Key: $API_KEY"` 才放行。

### 1. 上传文本知识(JSON)
需要 `ingest` scope。
```bash
curl -s -X POST "$BASE_URL/rag/documents" \
  -H "X-Api-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "退款政策.md",
    "text": "退款政策：商品签收后 7 天内可申请无理由退款，退款将在审核通过后 3-5 个工作日原路退回。",
    "category": "客服"
  }'
```
预期输出(`DocumentInfo`):
```json
{
  "docId": "12cbcde7b722c781",
  "tenantId": "acme",
  "displayName": "退款政策.md",
  "contentType": "text/plain",
  "sizeBytes": 125,
  "segmentCount": 1,
  "version": 1,
  "uploadedAt": "2026-07-08T02:46:38.198614128Z",
  "category": "客服"
}
```
> 记下 `docId`,后面查单个/删除要用。`docId` 由标题决定,重复上传同名文档会覆盖并让 `version` 递增。
> Body 里还支持 `imageBase64`+`contentType` 传图:走原生 CLIP 多模态 embedding,向量入独立的 `knowledge_images_<tenant>` collection,由 `RAG_MULTIMODAL_ENABLED` 控制(默认开,置 `false` 关闭时上传图片返回 400)。⚠️ 旧的 `caption`/`ocrText` 图→文字字段已移除。

### 2. 上传文件(multipart)
```bash
# 先造一个示例文件
printf '配送时效：标准快递 48 小时内送达，偏远地区顺延 1-2 天。' > 配送时效.md

curl -s -X POST "$BASE_URL/rag/documents" \
  -H "X-Api-Key: $API_KEY" \
  -F "file=@配送时效.md;type=text/markdown" \
  -F "category=客服"
```
预期输出:
```json
{
  "docId": "c49a0dbc68418d22",
  "tenantId": "acme",
  "displayName": "配送时效.md",
  "contentType": "text/markdown",
  "sizeBytes": 78,
  "segmentCount": 1,
  "version": 1,
  "uploadedAt": "2026-07-08T02:47:59.308573513Z",
  "category": "客服"
}
```
> 表单可选字段:`category`。pdf/txt/md 等会自动抽取正文;图片(`image/*`)走 CLIP 多模态 embedding(需 `RAG_MULTIMODAL_ENABLED=true`),也可用专用端点 `POST /rag/image`(表单字段名 `image`)。

### 3. 列表
```bash
curl -s "$BASE_URL/rag/documents" -H "X-Api-Key: $API_KEY"
```
预期输出(当前租户 acme 的全部文档,数组):
```json
[
  {
    "docId": "12cbcde7b722c781",
    "tenantId": "acme",
    "displayName": "退款政策.md",
    "contentType": "text/plain",
    "sizeBytes": 125,
    "segmentCount": 1,
    "version": 1,
    "uploadedAt": "2026-07-08T02:46:38.198614128Z",
    "category": "客服"
  }
]
```

### 4. 语义检索(RAG 核心)
```bash
curl -s -X POST "$BASE_URL/rag/query" \
  -H "X-Api-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"query": "退款要几天到账", "topK": 3, "category": "客服"}'
```
预期输出(`KnowledgeQueryReply`,命中刚上传的文档):
```json
{
  "query": "退款要几天到账",
  "tenantId": "acme",
  "hits": [
    {
      "id": "3828428a-d2c2-48be-bd69-2d08e50947df",
      "score": 0.5,
      "docId": "12cbcde7b722c781",
      "displayName": "退款政策.md",
      "category": "客服",
      "index": "0",
      "text": "退款政策：商品签收后 7 天内可申请无理由退款，退款将在审核通过后 3-5 个工作日原路退回。",
      "source": "hybrid"
    }
  ]
}
```
> **请求参数**:`query`(必填)、`topK`(默认 5)、`minScore`(默认 0.0)、`category`(可选,按分类过滤)。
> `source` 表示命中来源:`vector`(向量)/`keyword`(关键词)/`hybrid`(两者都命中)/`graph`(GraphRAG,需开开关)。
> 平台默认**混合检索**:向量(默认 hash embedding,无需真实 embedding 服务)+ 关键词(中文按 bigram 切词)融合排序。

### 5. 查单个
```bash
curl -s "$BASE_URL/rag/documents/12cbcde7b722c781" -H "X-Api-Key: $API_KEY"
```
预期输出:同上传时的 `DocumentInfo`;查不到返回 `404`。

### 6. 删除
```bash
curl -s -X DELETE "$BASE_URL/rag/documents/12cbcde7b722c781" -H "X-Api-Key: $API_KEY"
```
预期输出:
```json
{ "deleted": true, "docId": "12cbcde7b722c781" }
```
> 需要 `ingest` scope;删不到返回 `404`。

---

## 四、反例:scope 生效(可当亮点讲)

用**没有 ingest scope** 的 `dev-key-acme`(同一租户)去上传,会被挡:
```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST "$BASE_URL/rag/documents" \
  -H "X-Api-Key: dev-key-acme" -H "Content-Type: application/json" \
  -d '{"title":"x.md","text":"y"}'
```
```
403
```
> 说明:网关按 api-key 的 scopes 签发 JWT,下游按 scope 授权。检索/列表不需要 ingest,上传/删除才需要。

---

## 五、对话与智能体(走真实 LLM)

> 这两个接口会**真正调用大模型**(经 LiteLLM → 本机 Ollama `llama3.1`),先确保 Ollama 在跑(`ollama pull llama3.1`)、LiteLLM 容器可达。响应耗时数秒到数十秒。

### 7. 多轮对话 `/chat`
```bash
curl -s -X POST "$BASE_URL/chat?chatId=demo" \
  -H "X-Api-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"message": "用一句话介绍你自己"}'
```
预期输出(reply 由模型生成,内容会变):
```json
{
  "reply": "我是企业AI平台的对话助手，负责帮助用户解决问题、提供信息和支持。",
  "chatId": "demo",
  "tenantId": "acme",
  "userId": "alice"
}
```
> - `chatId`(query,默认 `default`):多轮会话隔离键;`message`(body)是用户输入。
> - 响应回显 `tenantId` / `userId` —— 直观验证「租户身份从网关的内部 JWT 一路透传到下游」。
> - 服务端叠加:**RAG 增强**(`CONVERSATION_RAG_ENABLED=true` 时先检索知识库再作答)+ **L1 语义缓存**(命中直接返回),二者均默认开启;把这两个开关置 `false` 即回到纯直连 LLM。
> - 需要 `chat` scope(`dev-key-acme-ingest` 有)。

### 8. ReAct 智能体 `/agent/run`
一个目标进去,智能体自己「思考 → 调工具 → 观察」循环,直到给出答案。可插拔工具:`rag_search`(检索知识库)、`analytics_sql`(NL2SQL)、`current_time` 等。
```bash
curl -s -X POST "$BASE_URL/agent/run" \
  -H "X-Api-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"goal": "用 current_time 工具查询 Asia/Shanghai 的当前时间，然后直接给出最终答案。"}'
```
预期输出(单步命中即收敛,`stopReason=DONE`):
```json
{
  "goal": "用 current_time 工具查询 Asia/Shanghai 的当前时间，然后直接给出最终答案。",
  "steps": [
    {
      "n": 1,
      "thought": "首先需要获取当前时间，因此选择查询当前时间的动作。",
      "action": "current_time",
      "actionInput": "Asia/Shanghai",
      "observation": "2026-07-08 10:57:47 (Asia/Shanghai)"
    }
  ],
  "finalAnswer": "2026-07-08 10:57:47 (Asia/Shanghai)",
  "stopReason": "DONE",
  "depth": 0,
  "tenantId": "acme"
}
```
> **看点:`steps[]` 就是智能体的可见推理轨迹**(每步 thought / action / actionInput / observation),把「Agent 到底怎么想的」摊开给你看。
> `stopReason`:`DONE`(收敛,给出答案)/ `LOOP`(重复动作被截停)/ `MAX_STEPS`(达步数上限)。请求体:`goal`(必填)、`webhookUrl`(可选,异步完成回调)。

**进阶例子:让它自主检索知识库。** 让智能体回答之前上传的退款政策 —— 它会自己调 `rag_search` 命中 `退款政策.md`:
```bash
curl -s -X POST "$BASE_URL/agent/run" -H "X-Api-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"goal": "请检索知识库回答：退款到账一般需要几天？"}'
```
```jsonc
// steps[0] 真实片段:智能体自主决定检索,命中你上传的文档
{
  "n": 1, "action": "rag_search", "actionInput": "退款到账时间",
  "observation": "检索到 1 条片段：[doc=退款政策.md#0] (hybrid) 退款政策：商品签收后 7 天内可申请无理由退款，退款将在审核通过后 3-5 个工作日原路退回。"
}
```
> ⚠️ **模型能力说明(演示前务必知道)**:本地默认小模型 `llama3.1`,**复杂目标常在检索到正确信息后仍反复搜索**,最终 `stopReason=LOOP/MAX_STEPS` 且 `finalAnswer` 为空 —— 这不是接口 bug,是小模型的收敛问题。**换更强的模型只需改 `deploy/litellm/config.yaml`,Java 代码零改动**(这正是 LiteLLM 抽象层的价值)。演示时优先用上面「单步收敛」的 `current_time` 例子,`rag_search` 例子用来讲「Agent 自主用工具 + RAG grounding」的轨迹。
> 关于 scope:`/agent/run` 语义上对应 `agent` scope(见 `dev-key-acme`);本平台未在应用层硬卡该 scope,故 `dev-key-acme-ingest` 也能调,演示可继续用同一把 key。

---

## 六、存储后端与 chunking(数据落在哪)

### 文档到底存哪里 —— 两种模式,别搞混
上传的知识**不是本机文件,也不是 `platform` 库**,而是按 `knowledge-service` 的存储开关落在不同后端:

| 数据 | 默认(零依赖) | 持久化模式(本机当前这套栈) |
|---|---|---|
| 文档元数据(`GET /rag/documents` 列表来源) | 进程内存 `InMemoryDocumentRegistry` | **Redis** key `rag:docs:<tenant>`(`RAG_REGISTRY_STORE=redis`) |
| 切片向量 + 片段文本(检索命中的东西) | 进程内存 `InMemoryEmbeddingStore` | **Qdrant** collection `knowledge_segments_<tenant>`(`RAG_VECTOR_STORE_PROVIDER=qdrant`,卷 `qdrant-data`) |
| GraphRAG 三元组 | 进程内存 | **MySQL** `knowledge_graph.RAG_GRAPH_TRIPLE`(`RAG_GRAPH_STORE=jdbc`) |
| 关键词检索镜像(DocumentMirror,hybrid 的关键词那一路) | 进程内存 | **进程内存(两种模式都在内存)** |

> - **默认全内存**:`knowledge-service` 一重启,登记表 + 向量 + 关键词镜像全清空 → 必须重传才能检索。
> - **持久化模式(qdrant/redis/jdbc)**:文档与向量重启**不丢**(Redis+Qdrant 存着),**唯一会丢的是内存里的关键词镜像** → 重启后向量检索照常命中,只是 hybrid 的关键词那一路要重传才恢复。
> - 查数据:`redis-cli hgetall rag:docs:acme`、Qdrant Dashboard `http://127.0.0.1:6333/dashboard`、`SELECT * FROM knowledge_graph.RAG_GRAPH_TRIPLE`。

### chunking(切片)
上传时服务端自动把正文切成 segment 再 embedding。默认 `recursive` 策略、按**字符** `max-size=300`、`overlap=50`(`app.rag.chunking.*`)。返回的 `segmentCount` 就是切出来的 chunk 数;检索命中里的 `index` 是 chunk 序号(如 `售后保修与常见问题.md#1` = 第 2 个 chunk)。想按标题切可设 `RAG_CHUNKING_STRATEGY=markdown-header`。

### 示例知识库 + 一键导入
`deploy/sample-docs/` 下有 5 篇客服知识(退款/配送/会员/支付/售后),每篇约 1.2KB、切成 2 个 chunk:
```bash
bash deploy/seed-kb.sh           # 导入 5 篇 + 打印每篇 chunk 数 + 跑几条检索验证
bash deploy/seed-kb.sh --purge   # 先删同名旧文档再导入(干净演示)
```

### embedding:hash(默认) vs 语义(本机当前)
- **磁盘默认是确定性 hash**(`RAG_EMBEDDING_PROVIDER=hash`,64 维)—— **不具备语义**,向量分数基本恒为 `~0.50`,排序全靠 hybrid 的关键词那一路。零依赖、稳定,但"排序不聪明"。
- **本机当前已升级为 Ollama 语义 embedding**(`nomic-embed-text`,768 维):向量分数会真实拉开(`0.82~0.90`),多数 query 相关文档排到前列。启用方式(已固化成 override 文件):
  ```bash
  # 前置:ollama pull nomic-embed-text
  docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.rag-full.yml \
    up -d --no-deps knowledge-service
  # 换 embedding=换维度:删旧 collection 再重导
  curl -s -X DELETE http://127.0.0.1:6333/collections/knowledge_segments_acme
  bash deploy/seed-kb.sh
  ```
- **已加 nomic 任务前缀**:代码里给查询/文档分别加了 `search_query:` / `search_document:` 前缀(`PrefixingEmbeddingModel`,由 `RAG_EMBEDDING_QUERY_PREFIX`/`DOCUMENT_PREFIX` 开启),对齐两者向量空间。示例 5 类 query 的 top1 命中从 3/5 提升到 4/5,且前缀只影响向量、**不写入存储原文**。剩下极短 query(如"顺丰隔天送到")相关文档仍可能落到第 2 位 —— 这是 nomic 在短中文上的固有上限,hybrid 关键词那一路仍重要,别关。
- **换 embedding 必须重启 + 重导**:否则新旧维度混入同一 collection 会报 `DimensionMismatch`;而每次重启还会清空内存关键词镜像,所以务必重跑 `seed-kb.sh`。
- **依赖修复**:qdrant client 1.17 需 protobuf ≥3.25,但 grpc-bom 1.59.1 传递的是 3.24 → 建 collection 报 `NoClassDefFoundError`。根 pom 已钉 `protobuf-java:3.25.8`。

---

## 七、一键跑通

```bash
bash deploy/rag-demo.sh                        # RAG 闭环,保留数据便于演示
bash deploy/rag-demo.sh --with-llm             # 额外演示 /chat + /agent/run(需 Ollama+LiteLLM 可达)
bash deploy/rag-demo.sh --cleanup              # 跑完自动删除本次上传的文档
bash deploy/rag-demo.sh --with-llm --cleanup   # 两个开关可组合,顺序不限

# 覆盖默认(比如从 compose 文件重启后网关在 8080):
BASE_URL=http://127.0.0.1:8080 bash deploy/rag-demo.sh
```
默认(RAG 闭环)依次:健康检查(验证 401)→ 上传 → 列表 → 检索(断言命中非空)→ 查单个 →(可选)删除。
加 `--with-llm` 会在删除前再跑 `/chat` 和 `/agent/run`(用「单步收敛」的 `current_time` 目标;LLM 不可达或超时会打印提示而非中断)。每步打印精简结果。依赖 `curl` + `python3`。

---

## 八、排障 FAQ

| 现象 | 原因 / 解法 |
|---|---|
| `401` | 没带 `X-Api-Key`,或 key 拼错。检查 `-H "X-Api-Key: $API_KEY"`。 |
| `403` | 用了没有 `ingest` scope 的 key 去上传/删除。换 `dev-key-acme-ingest`。 |
| 连不上 / `Connection refused` | 网关端口不对。当前容器映射在 **18080**;若从 `docker-compose.yml` 重启则是 **8080**。用 `docker ps` 看 `edge-gateway` 的实际端口。 |
| 检索 `hits` 为空 | 看你的存储模式(见 §六):**默认全内存**时重启清空所有索引,需重传;**qdrant/redis 持久化**时向量/登记表重启不丢,只有内存里的关键词镜像会丢(向量仍能命中,关键词那路需重传)。 |
| 检索能中但**排序不准** | 默认 hash embedding 无语义、分数恒 `~0.50`。换真 embedding(`RAG_EMBEDDING_PROVIDER=ollama` 等)+ 重启 + 重导,见 §六说明。 |
| 列表能看到文档但检索不到 | 内存模式下服务重启后登记还在(或 Redis 持久)、但内存索引没了。重传该文档即可被检索。 |
| 直连 `:8084` 查不到数据 | 直连下游 `X-Api-Key` 被忽略,租户退化成 `anonymous`。**走网关**。 |
| `/chat` 或 `/agent/run` 超时/报错 | 这俩要真调大模型。确认本机 Ollama 在跑(`ollama pull llama3.1`)、LiteLLM 容器可达。`/agent/run` 多步推理,耗时可达 1-2 分钟,`curl` 记得加 `-m 180`。 |
| `/agent/run` 返回 `finalAnswer` 为空、`stopReason=LOOP/MAX_STEPS` | 小模型 `llama3.1` 收敛差,非接口问题。演示用「单步收敛」目标,或在 `deploy/litellm/config.yaml` 换更强模型(Java 零改动)。 |

---

## 附:接口速查

| 方法 | 路径(经网关) | 作用 | 需要的 scope |
|---|---|---|---|
| POST | `/rag/documents`(JSON) | 上传文本/图片 | ingest |
| POST | `/rag/documents`(multipart) | 上传文件 | ingest |
| GET | `/rag/documents` | 列表 | — |
| GET | `/rag/documents/{docId}` | 查单个 | — |
| DELETE | `/rag/documents/{docId}` | 删除 | ingest |
| POST | `/rag/query` | 语义检索 | — |
| POST | `/chat?chatId=` | 多轮对话(可选 RAG 增强) | chat |
| POST | `/agent/run` | ReAct 智能体(多步 + 工具) | agent¹ |

> ¹ `/agent/run` 语义上对应 `agent` scope,但未在应用层硬卡,`dev-key-acme-ingest` 也能调。

控制器源码:
- RAG:`knowledge-service/.../controller/DocumentController.java`、`KnowledgeQueryController.java`
- 对话:`conversation-service/.../ConversationController.java`(`/chat`)
- 智能体:`agent-service/.../AgentController.java`(`/agent/run`)
- 请求契约:`platform-protocol/.../knowledge/KnowledgeQueryRequest.java`、`platform-protocol/.../agent/AgentRunRequest.java`
