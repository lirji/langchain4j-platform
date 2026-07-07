# RAG 接入指南

本指南面向要把知识库检索（RAG）接进平台的开发者，覆盖 `knowledge-service` 的文档上传、
向量库 / embedding provider 选型、混合检索（vector + keyword + 可选 GraphRAG）、GraphRAG 图谱查询，
以及 `conversation-service` 侧的 `/chat` RAG 增强与 L1 语义缓存。

所有能力都遵循平台一贯的「接口 + `@ConditionalOnProperty` 多实现，默认内存/确定性」写法：
**开箱即用（in-memory 向量库 + hash embedding），无需任何外部基础设施；生产能力（Qdrant、OpenAI/Ollama embedding、
GraphRAG JDBC、HTTP 视觉/OCR）都默认关闭，靠环境变量显式开启。**

> 端点约定：业务接口都建议走 `edge-gateway`（`http://localhost:8080`，带 `X-Api-Key`）。
> `knowledge-service` 自身监听 `:8084`，仅供服务间直连或本地调试。下文 curl 示例统一走边缘网关。
> 上传/删除需要调用方 api-key 带 `ingest` scope（示例里的 `dev-key-acme-ingest`）；查询只需该租户任意合法 key。

---

## 1. 组件与数据流

```text
上传： POST /rag/documents ─▶ DocumentService
        ├─ (图片) ImageTextProvider 补 caption/OCR ─▶ MultimodalIngestionText 组装为可索引文本
        ├─ DocumentSplitterFactory 切块 ─▶ EmbeddingModel 向量化
        ├─ EmbeddingStoreRouter.forTenant() 写入按租户隔离的向量 collection
        ├─ DocumentMirror 保存明文分块（供 keyword 检索）
        └─ (可选) GraphIngestor 抽取三元组写入 GraphStore

查询： POST /rag/query ─▶ KnowledgeQueryService
        ├─ vector 检索（按 tenantId + 可选 category 过滤）
        ├─ (默认开) keyword 检索并按权重融合
        ├─ (可选) GraphRAG 命中并入结果
        └─ 加权排序后取 topK 返回
```

租户身份来自内部 JWT 还原出的 `TenantContext`；所有读写都强制按 `tenantId` 隔离，跨租户不可见。

---

## 2. 文档上传

端点：`POST /rag/documents`（经边缘网关，需 `ingest` scope）。支持三种形态，走同一个 `DocumentService.upload`：

- **纯文本（JSON）**：`Content-Type: application/json`，字段 `title`（必填）、`text`（必填）、`category`（可选）、`contentType`（可选，默认 `text/plain`）。
- **文件（multipart）**：`Content-Type: multipart/form-data`，表单字段 `file`（必填），可带 `category`、`caption`、`ocrText`。非图片文件由 `DocumentTextExtractor` 抽取文本。
- **图片多模态**：JSON 用 `imageBase64` + `caption` / `ocrText`；multipart 用同名 `file`（`image/*`）+ 表单 `caption` / `ocrText`。详见第 3 节。

同一租户下 `title`（displayName）相同的文档会**覆盖并递增版本号**（`docId` 由 `tenantId:displayName` 哈希得到，删除旧向量后重建）。

### 文本上传（JSON）

```bash
curl -s -X POST 'http://localhost:8080/rag/documents' \
  -H 'X-Api-Key: dev-key-acme-ingest' \
  -H 'Content-Type: application/json' \
  -d '{"title":"guide.md","text":"这是 acme 的知识库文档。","category":"manual"}'
# 返回 DocumentInfo：{"docId":"...","tenantId":"acme","displayName":"guide.md","segments":1,"version":1,...}
```

### 文件上传（multipart）

```bash
curl -s -X POST 'http://localhost:8080/rag/documents' \
  -H 'X-Api-Key: dev-key-acme-ingest' \
  -F 'file=@./guide.md' \
  -F 'category=manual'
```

### 文档列表 / 详情 / 删除

```bash
# 列出当前租户所有文档
curl -s 'http://localhost:8080/rag/documents' \
  -H 'X-Api-Key: dev-key-acme'

# 查单个文档
curl -s 'http://localhost:8080/rag/documents/{docId}' \
  -H 'X-Api-Key: dev-key-acme'

# 删除（需 ingest scope；同步清理向量、明文镜像与图谱三元组）
curl -s -X DELETE 'http://localhost:8080/rag/documents/{docId}' \
  -H 'X-Api-Key: dev-key-acme-ingest'
```

### 文档注册表存储

文档元数据（`DocumentRegistry`）默认内存（`RAG_REGISTRY_STORE=in-memory`）。多实例部署可切 Redis 共享：

```bash
RAG_REGISTRY_STORE=redis
```

### 切块策略（可选）

切块由 `DocumentSplitterFactory` 决定，默认 `recursive`（按字符，`max-size=300`，`overlap=50`）。可通过属性调整：

- `app.rag.chunking.strategy`：`recursive`（默认）| `markdown-header` | `parent-child` | `semantic`
- `app.rag.chunking.unit`：`chars`（默认）| `tokens`
- `app.rag.chunking.max-size` / `app.rag.chunking.overlap`
- `parent-child`、`semantic` 各有 `app.rag.chunking.parent.*` / `app.rag.chunking.semantic.*` 细项

---

## 3. 图片多模态上传（caption / OCR）

图片上传时**默认不保存图片字节**，只索引调用方提供的 `caption` / `ocrText`（由 `MultimodalIngestionText` 组装成
`Text / Image caption / Image OCR` 分段文本），未提供任何文本则 400。

### JSON 方式

```bash
curl -s -X POST 'http://localhost:8080/rag/documents' \
  -H 'X-Api-Key: dev-key-acme-ingest' \
  -H 'Content-Type: application/json' \
  -d '{
    "title":"screenshot.png",
    "imageBase64":"<base64...>",
    "caption":"退款审批流程图",
    "ocrText":"提交申请 -> 审核 -> 打款",
    "category":"manual"
  }'
```

`imageBase64` 支持带 `data:image/png;base64,` 前缀；`contentType` 不传时图片默认按 `image/png`。

### 用外部视觉/OCR 服务自动补全（默认关闭）

设 `RAG_IMAGE_TEXT_PROVIDER=http`，`knowledge-service` 会把图片字节 POST 给外部视觉/OCR 服务（`HttpImageTextProvider`），
用返回的 `caption` / `ocrText` **与调用方传入的文本合并**后再索引。默认 provider 为 `none`（`NoopImageTextProvider`，只用调用方文本）。

```bash
RAG_IMAGE_TEXT_PROVIDER=http
RAG_IMAGE_TEXT_ENDPOINT_URL=http://localhost:8090/image-text
RAG_IMAGE_TEXT_CONNECT_TIMEOUT=2s
RAG_IMAGE_TEXT_READ_TIMEOUT=30s
RAG_IMAGE_TEXT_MAX_IMAGE_BYTES=10485760   # 超过则跳过远程调用，降级为纯文本
```

外部服务约定：接收 `{filename, contentType, imageBase64}`，返回 `{caption, ocrText}`。
远程调用失败/超时/超大图**不会中断上传**，自动降级为仅用调用方文本。

---

## 4. 向量库 provider 与租户隔离

由 `RAG_VECTOR_STORE_PROVIDER` 选择（`KnowledgeEmbeddingConfig`）：

| provider | 默认 | 说明 |
|---|---|---|
| `in-memory` | ✅ 默认 | 进程内 `InMemoryEmbeddingStore`，零依赖，适合开发/单测 |
| `qdrant` | | 连接 Qdrant（gRPC），持久化向量库 |

### collection-per-tenant 强隔离（默认）

向量读写通过 `EmbeddingStoreRouter` **按租户路由**（`RAG_VECTOR_STORE_ISOLATION=collection-per-tenant`，默认）：

- Qdrant：每租户一个独立 collection，命名 `<base>_<tenantId>`（租户 id 归一化为 `[a-zA-Z0-9_-]`），惰性建、幂等。
- in-memory：每租户一个独立 store。

也可退回 `shared` 模式（单 store + metadata `tenantId` 过滤）：`RAG_VECTOR_STORE_ISOLATION=shared`。

### 维度守卫（dimension guard）

路由器每次取 store 都带上当前 embedding 维度：若目标 collection 已按**别的维度**建立，会 fail-fast 抛
`DimensionMismatchException`。这可挡住「换了 embedding provider 但没重建 collection」导致的静默坏数据——
**换 embedding provider（维度变化）时必须为每个租户重建/清空对应 collection。**

### Qdrant 配置

```bash
RAG_VECTOR_STORE_PROVIDER=qdrant
QDRANT_HOST=localhost
QDRANT_PORT=6334
QDRANT_COLLECTION_NAME=knowledge_segments   # 作为 per-tenant collection 的基名
QDRANT_USE_TLS=false
QDRANT_API_KEY=                             # 可选
QDRANT_TIMEOUT=10s
QDRANT_AUTO_CREATE_PAYLOAD_INDEX=true
RAG_REGISTRY_STORE=redis                    # 多实例时建议共享注册表
```

启用 Qdrant 后会注册 `QdrantHealthIndicator`，纳入 `/actuator/health`。最小冒烟：

```bash
bash deploy/smoke-qdrant-rag.sh
```

---

## 5. Embedding provider

由 `RAG_EMBEDDING_PROVIDER` 选择（`KnowledgeEmbeddingConfig`）：

| provider | 默认 | 维度 | 说明 |
|---|---|---|---|
| `hash` | ✅ 默认 | 64 | 确定性 SHA-256 哈希向量，零依赖，不真调 embedding，适合开发/单测 |
| `openai` | | provider 决定 | OpenAI 兼容 embedding，**经 LiteLLM 网关**（默认复用 `platform.gateway.*`） |
| `ollama` | | 模型决定 | 直连本机/远端 Ollama embedding 模型 |

### OpenAI 兼容（经 LiteLLM）

```bash
RAG_EMBEDDING_PROVIDER=openai
RAG_EMBEDDING_MODEL=embedding-default        # LiteLLM model_list 里的逻辑模型名
GATEWAY_BASE_URL=http://localhost:4000/v1    # 缺省复用 platform.gateway.base-url
GATEWAY_API_KEY=sk-litellm-master            # 缺省复用 platform.gateway.api-key
RAG_EMBEDDING_DIMENSIONS=0                    # >0 时向 provider 请求指定维度
RAG_EMBEDDING_TIMEOUT=60s
RAG_EMBEDDING_MAX_RETRIES=3
RAG_EMBEDDING_MAX_SEGMENTS_PER_BATCH=0        # >0 时分批
```

### Ollama

```bash
RAG_EMBEDDING_PROVIDER=ollama
RAG_EMBEDDING_OLLAMA_BASE_URL=http://localhost:11434
RAG_EMBEDDING_OLLAMA_MODEL=nomic-embed-text
```

> ⚠️ 切换 provider 通常改变向量维度，务必配合第 4 节的维度守卫重建 collection。

---

## 6. 混合检索（vector + keyword + 可选 GraphRAG）

端点：`POST /rag/query`（别名 `POST /knowledge/query`）。请求字段（`KnowledgeQueryRequest`）：

| 字段 | 说明 |
|---|---|
| `query` | 必填，查询文本 |
| `topK` | 可选，返回条数，默认 `RAG_QUERY_TOP_K=5` |
| `minScore` | 可选，分数下限，默认 `RAG_QUERY_MIN_SCORE=0.0` |
| `category` | 可选，按分类过滤 |

```bash
curl -s -X POST 'http://localhost:8080/rag/query' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"query":"知识库文档","topK":3,"category":"manual"}'
```

响应 `KnowledgeQueryReply`：`{query, tenantId, hits:[{id,score,docId,displayName,category,index,text,source}]}`，
其中 `source` 标注命中来源：`vector` / `keyword` / `hybrid` / `graph`。

### 融合与排序

`KnowledgeQueryService` 的逻辑：

1. **vector**：按 `tenantId`（+ 可选 `category`）过滤检索，分数 × `vector-weight`。
2. **keyword**（默认开，`RAG_HYBRID_ENABLED=true`）：基于 `DocumentMirror` 明文分块做 token 重叠打分（当前租户分区内），分数 × `keyword-weight`；与 vector 命中按分块 key 合并，同一分块取两者最大分并标记 `hybrid`。
3. **graph**（可选，见第 7 节）：图谱命中作为补充源并入，固定基分 0.75 × `graph-weight`。
4. 全部命中按最终分数降序取 `topK`。

排序权重可调（默认都为 `1.0`）：

```bash
RAG_HYBRID_ENABLED=true            # 关闭则退化为纯向量检索
RAG_HYBRID_KEYWORD_TOP_K=5         # 默认取 RAG_QUERY_TOP_K
RAG_RANKING_VECTOR_WEIGHT=1.0
RAG_RANKING_KEYWORD_WEIGHT=1.0
RAG_RANKING_GRAPH_WEIGHT=1.0
```

---

## 7. GraphRAG（三元组图谱）

默认关闭。开启后额外提供 `/rag/graph/**` 图谱查询，并可把图谱命中并入 `/rag/query`。

当前抽取为**确定性/受控格式**（`RuleBasedGraphExtractor`）：文本按 `subject|relation|object` 三元组解析，
多条用换行或分号分隔。上传含三元组的文档即会写入图谱（`GraphIngestor`）。

### 开启

```bash
RAG_GRAPH_ENABLED=true                 # 开启 GraphRAG 组件与 /rag/graph/**
RAG_GRAPH_INCLUDE_IN_QUERY=true        # 让 /rag/query 融合图谱命中（默认跟随 RAG_GRAPH_ENABLED）
RAG_GRAPH_MAX_HOPS=2
RAG_GRAPH_MAX_TRIPLES=20
RAG_GRAPH_MAX_TRIPLES_PER_CHUNK=12
RAG_GRAPH_RELATION_WHITELIST=隶属于,使用   # 仅保留白名单关系（留空=不过滤）
RAG_GRAPH_ALIASES=张三经理=张三           # 实体别名归一
RAG_GRAPH_ASYNC=false
```

### 图存储：in-memory / jdbc

```bash
RAG_GRAPH_STORE=in-memory   # 默认，进程内
# 或持久化到 MySQL（表结构由 JdbcGraphStore 内 CREATE TABLE IF NOT EXISTS 维护）
RAG_GRAPH_STORE=jdbc
RAG_GRAPH_DB_URL='jdbc:mysql://mysql:3306/knowledge_graph?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&nullCatalogMeansCurrent=true'
RAG_GRAPH_DB_USER=root
RAG_GRAPH_DB_PASSWORD=root
```

### 查询示例

```bash
# 上传带三元组的文档
curl -s -X POST 'http://localhost:8080/rag/documents' \
  -H 'X-Api-Key: dev-key-acme-ingest' \
  -H 'Content-Type: application/json' \
  -d '{"title":"people.md","text":"张三|隶属于|研发部\n研发部|使用|LangChain4j","category":"org"}'

# 图谱邻居查询（POST /rag/graph/query）
curl -s -X POST 'http://localhost:8080/rag/graph/query' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"query":"张三负责什么团队","maxHops":2,"category":"org"}'

# 列实体（GET /rag/graph/entities，可选 category 过滤）
curl -s 'http://localhost:8080/rag/graph/entities?category=org' \
  -H 'X-Api-Key: dev-key-acme'

# 融合了图谱命中的混合检索
curl -s -X POST 'http://localhost:8080/rag/query' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"query":"张三负责什么团队","topK":5,"category":"org"}'
```

`/rag/graph/query` 请求字段：`query`（必填）、`maxHops`、`maxTriples`、`category`。
`/rag/graph/**` 仅在 `RAG_GRAPH_ENABLED=true`（存在 `GraphSearchService` bean）时注册。

---

## 8. conversation 侧 RAG 增强（`/chat`）

`conversation-service` 可在 `/chat` 调 LLM 前，自动跨服务调用 `knowledge-service` 检索并把命中拼进 prompt
（`RagPromptAugmenter`）。默认关闭。

```bash
CONVERSATION_RAG_ENABLED=true
KNOWLEDGE_BASE_URL=http://knowledge-service:8084
CONVERSATION_RAG_TOP_K=5
CONVERSATION_RAG_MIN_SCORE=0.0
CONVERSATION_RAG_CATEGORY=manual          # 留空则不限分类
CONVERSATION_RAG_MAX_CONTEXT_CHARS=4000   # 拼接上下文的字符上限
CONVERSATION_RAG_CONNECT_TIMEOUT=1s
CONVERSATION_RAG_READ_TIMEOUT=3s
```

开启后用普通 `/chat` 即自动增强（命中会以 `<source id="...">` 块注入，无命中则原样透传）：

```bash
curl -s -X POST 'http://localhost:8080/chat?chatId=u1' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"message":"退款审批规则是什么？"}'
```

租户随内部 JWT 透传到 `knowledge-service`，因此只会命中当前租户自己的知识库。

---

## 9. L1 语义缓存（`SemanticCache`）

`conversation-service` 的应用侧语义缓存，位于 `/chat` 的 **RAG + LLM 之前**（pre-RAG）：把用户**原始问题**向量化，
在**当前租户桶**里做相似度检索，余弦相似度 ≥ 阈值即直接返回缓存回复，短路后续 embedding/检索/LLM；
未命中则跑正常流程并回填缓存。默认关闭，对现有 `/chat` 行为零影响。

```bash
CONVERSATION_SEMANTIC_CACHE_ENABLED=true
CONVERSATION_SEMANTIC_CACHE_THRESHOLD=0.95
CONVERSATION_SEMANTIC_CACHE_MAX_ENTRIES=1000              # 每租户桶上限
CONVERSATION_SEMANTIC_CACHE_STORE=in-memory              # 或 redis
CONVERSATION_SEMANTIC_CACHE_EMBEDDING_PROVIDER=hash      # 默认 hash（确定性），或 gateway（经 LiteLLM）
CONVERSATION_SEMANTIC_CACHE_EMBEDDING_MODEL=embedding-default
CONVERSATION_SEMANTIC_CACHE_REDIS_TTL=0s                 # redis 档，0 表示不过期
```

要点：

- **租户隔离**：缓存严格按 `TenantContext` 的 `tenantId` 分桶，跨租户不共享。
- **pre-RAG**：缓存键是用户原始问题（未经 RAG 增强），命中即整体短路，省下检索与 LLM 调用。
- **失效**：`SemanticCache` 提供 `invalidateTenant(tenantId)`（整桶清空，如该租户知识库整体更新）与
  `invalidate(tenantId, question)`（定向失效单个问题）。
- **embedding 一致性**：语义缓存的 embedder 与 knowledge-service 的 embedding provider 相互独立，
  各自配置、互不影响。

---

## 10. 默认开关速查

| 能力 | 环境变量 | 默认 |
|---|---|---|
| 向量库 provider | `RAG_VECTOR_STORE_PROVIDER` | `in-memory` |
| 租户隔离 | `RAG_VECTOR_STORE_ISOLATION` | `collection-per-tenant` |
| embedding provider | `RAG_EMBEDDING_PROVIDER` | `hash` |
| 文档注册表 | `RAG_REGISTRY_STORE` | `in-memory` |
| 图片视觉/OCR provider | `RAG_IMAGE_TEXT_PROVIDER` | `none` |
| keyword 混合检索 | `RAG_HYBRID_ENABLED` | `true`（开） |
| GraphRAG | `RAG_GRAPH_ENABLED` | `false` |
| GraphRAG 图存储 | `RAG_GRAPH_STORE` | `in-memory` |
| conversation RAG 增强 | `CONVERSATION_RAG_ENABLED` | `false` |
| L1 语义缓存 | `CONVERSATION_SEMANTIC_CACHE_ENABLED` | `false` |
