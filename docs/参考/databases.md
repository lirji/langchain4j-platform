# 数据存储清单（数据库 / 缓存 / 向量库 / 消息）

本文汇总平台用到的各类数据存储中间件：类型、端口、账号密码、所属服务与用途。面向本地起栈、排障和生产改配。

> ⚠️ **凭据全部是本地开发/演示默认值**（源码里的 `root/root`、`dev-only-...` 等），生产必须用 External Secrets / 环境变量覆盖，详见[部署指南](../平台工程/deployment-guide.md)。
>
> ℹ️ **单测零外部依赖**（纯 POJO，不加载 Spring context）。但两套「默认」需分清：application.yml 裸跑默认多为内存/关闭，**唯 knowledge-service 已默认 qdrant + redis + jdbc 图谱 + ES 全文**（真正零依赖单跑需显式退回 in-memory / `RAG_ES_ENABLED=false`）；docker-compose demo 则把 RAG 持久化 + ES + 登录 RBAC 等全打开并自带基础设施。下表是这些外部依赖。配置以各服务 `application.yml` 与 `deploy/docker-compose.yml` 为准。

## 概览

| 类型 | 组件 | 镜像 | 端口 | 默认账号/密码 | 何时用到 |
|---|---|---|---|---|---|
| 关系型 | MySQL | `mysql:8.4` | 3306（脚本重映射 13307） | root / root | 工作流引擎、NL2SQL、GraphRAG、异步任务、**auth 账号/角色/会话**（各自逻辑库） |
| 缓存/状态 | Redis | `redis:7-alpine` | 6379 | 无密码 | 限流、token 预算、成本、语义缓存、RAG 注册表、会话记忆 |
| 向量库 | Qdrant | `qdrant/qdrant:latest` | 6333 REST / **6334 gRPC** | 无 api-key | RAG 默认向量库（yml/compose 默认） |
| 全文检索 | Elasticsearch | 自建（`deploy/es`，`elasticsearch:8.15.3` + smartcn） | 9200 | 无认证（`xpack.security.enabled=false`） | RAG BM25 全文混排索引（compose 默认开） |
| 检索 UI | Kibana | `kibana:8.15.3` | 5601 | 无认证 | 浏览/查询 ES（Dev Tools 跑 DSL） |
| 消息/事件流 | Kafka | `apache/kafka:3.8.0` | 9092（控制器 9093） | PLAINTEXT 无认证 | 渠道出入站事件（默认内存总线，需开关） |
| 测试库 | H2 | 内嵌 | 无 | 无 | 依赖 DB 的单测（内存，无 Testcontainers） |

> Kafka 严格说不是数据库，是跨服务事件总线；一并列出便于对齐端口与凭据。
>
> ℹ️ **外部 IAM 存储不在本 compose 内**：身份侧 **Casdoor（:8000）** 与授权侧 **auth-platform-server（:8200，SpiceDB ReBAC）** 都是本仓库外的独立平台（各自的库自管，如 Casdoor 表在 authz-postgres）。仅在 edge Casdoor 启用 / `RAG_AUTHZ_MODE != disabled` 时才被依赖，详见 §七#6。可观测侧 LiteLLM（:4000）、Jaeger（:16686）亦非数据存储，见[运行与配置手册](operations.md)。

## 一、MySQL 8.4（关系型，:3306）

Docker 单实例 `mysql:8.4`，`MYSQL_ROOT_PASSWORD=root`、初始库 `platform`。各服务按需自建**独立逻辑库**（连接串带 `createDatabaseIfNotExist=true`，无迁移工具，靠 `Jdbc*Store` 类里的 `CREATE TABLE IF NOT EXISTS` / `ALTER TABLE ADD COLUMN` 演进表结构；Flowable 自管其表）。

| 服务 | 逻辑库 | 账号 | 密码 | 落库开关 | 用途 |
|---|---|---|---|---|---|
| workflow-service | `flowable` | root | root | 恒开 | Flowable BPMN 退款审批引擎表 + outbox |
| analytics-service | `nl2sql_demo` | root（admin） | root | `NL2SQL_ENABLED=true` | NL2SQL 演示库（建表/写入） |
| analytics-service | `nl2sql_demo` | `nl2sql_ro`（只读） | `nl2sql_ro` | 同上 | NL2SQL 只读执行连接（六层 SQL 护栏之一） |
| knowledge-service | `knowledge_graph` | root | Docker `root` / 本地默认**空** | `RAG_GRAPH_STORE=jdbc`（yml/compose 默认 jdbc） | GraphRAG 图谱存储 |
| async-task-service | `async_task` | root | Docker `root` / 本地默认**空** | `ASYNC_TASK_STORE=jdbc`（compose 默认 jdbc） | 通用异步任务中心 |
| auth-service | `auth` | root | root | `AUTH_STORE=jdbc`（compose 默认 jdbc） | 登录账号 / 角色 / role-scope / 刷新会话（`USERS`/`USER_ROLE`/`ROLES`/`ROLE_SCOPE`/`AUTH_SESSION`，仅存刷新令牌哈希） |
| order-service | `order_service` | root | root | 恒开（服务本身即 DB 支撑） | 订单只读查询（`orders`/`customers`，`CREATE TABLE IF NOT EXISTS` + 首启种子，按 `tenant_id` 隔离）；`ORDER_SEED_DEMO=false` 关演示种子 |
| channel-service | （去重表，指向 MySQL） | — | — | `CHANNEL_DEDUP_STORE=jdbc` | 事件去重（跨重启幂等）；默认 `memory` |

连接串形如 `jdbc:mysql://localhost:3306/<库名>?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true`（Docker 内主机名为 `mysql`）。

> ⚠️ **本地 vs Docker 密码不一致**：`knowledge_graph` / `async_task` 在各自 `application.yml` 的默认密码是**空串**，而 `docker-compose.yml` 显式注入 `root`。按运行方式区别对待。

相关环境变量：`WORKFLOW_DB_URL/USER/PASSWORD`、`NL2SQL_DB_URL/READONLY_URL/ADMIN_USER/ADMIN_PASSWORD/READONLY_USER/READONLY_PASSWORD`、`RAG_GRAPH_DB_URL/USER/PASSWORD`、`ASYNC_TASK_DB_URL/USER/PASSWORD`、`AUTH_DB_URL/USER/PASSWORD`、`ORDER_DB_URL/USER/PASSWORD`（+ `ORDER_SEED_DEMO`）。

> auth-service 的**刷新会话只存 SHA-256 哈希**在 `AUTH_SESSION`（非明文令牌，非 Redis）；会话 accessToken 本身无状态（JWT，不落库）。RBAC 用户/角色/scope 是权威表 + CSV 影子列双写。

## 二、Redis 7（缓存/状态，:6379）

- `redis:7-alpine`，**无密码**；`--appendonly yes` + 命名卷 `redis-data`，跨重启持久化。
- 主机名：Docker 内 `redis`，本地默认走 Spring Boot 默认 `localhost:6379`（`application.yml` 未显式配，靠 `SPRING_DATA_REDIS_HOST/PORT` 覆盖）。
- 用途：限流（`platform-security` ratelimit）、按租户 token 预算与 USD 成本计数（分布式计数，保证水平扩容正确性）、语义缓存 L1、RAG 文档注册表、多轮会话记忆（`CONVERSATION_MEMORY_STORE=redis` 时）。
- 多数开关默认即为 `redis`（token 预算、成本、语义缓存、RAG 注册表），部分默认内存（会话记忆）。

## 三、Qdrant（向量库，:6333 REST / :6334 gRPC）

- `qdrant/qdrant:latest`，卷 `qdrant-data`；**应用侧走 6334 gRPC**（`QDRANT_PORT=6334`），6333 为 REST/UI。
- 默认**无 api-key**（`QDRANT_API_KEY` 空）、`use-tls=false`；collection 基名 `knowledge_segments`，`collection-per-tenant` 隔离下按 `<base>_<tenant>` 建。
- RAG 默认向量库（`RAG_VECTOR_STORE_PROVIDER=qdrant`）。冒烟测试：`bash deploy/smoke-qdrant-rag.sh`。

### 可选替代向量库（feature-flag，非默认）

以下都是**可用的真实现**（非配置占位），且客户端依赖**已打进 `knowledge-service` 的 jar**——切换不改代码、不加依赖。装配逻辑见 `KnowledgeEmbeddingConfig.java`：单属性 `app.rag.vector-store.provider` + `@ConditionalOnProperty` 分派出对应 `CollectionManager`，上层 `ManagedEmbeddingStoreRouter` 统一复用（含维度守卫）。

| provider | 默认端口 | 账号/密码 | 环境变量前缀 | 实现类 / 底层 |
|---|---|---|---|---|
| `in-memory`（零依赖回退） | — | — | 无外部依赖 | langchain4j `InMemoryEmbeddingStore` |
| `qdrant`（yml/compose 默认） | 6334 gRPC | 无 api-key | `QDRANT_*` | `QdrantClientCollectionManager` |
| `pgvector`（PostgreSQL） | 5432 | postgres / postgres | `RAG_PGVECTOR_*` | `PgVectorCollectionManager`（`langchain4j-pgvector`） |
| `milvus` | 19530 | 空 / 空 | `RAG_MILVUS_*` | `MilvusCollectionManager`（`langchain4j-milvus`） |
| `chroma` | 8000（HTTP） | 无 | `RAG_CHROMA_BASE_URL` | `ChromaCollectionManager`（`langchain4j-chroma`） |
| `doris`（走 mysql 协议 JDBC） | 9030 | root / 空 | `RAG_DORIS_*` | `DorisCollectionManager` + 自研 `DorisEmbeddingStore`（HNSW ANN） |

### 快速切换向量库

核心开关只有一个 `RAG_VECTOR_STORE_PROVIDER`，补上该后端的连接变量后**重启 knowledge-service 即可，无需重新打包**。

```bash
# 切 pgvector
RAG_VECTOR_STORE_PROVIDER=pgvector \
RAG_PGVECTOR_HOST=localhost RAG_PGVECTOR_PORT=5432 \
RAG_PGVECTOR_USER=postgres RAG_PGVECTOR_PASSWORD=postgres \
mvn -pl knowledge-service spring-boot:run

# 切 milvus：RAG_VECTOR_STORE_PROVIDER=milvus + RAG_MILVUS_HOST/PORT(19530)
# 切 doris ：RAG_VECTOR_STORE_PROVIDER=doris + RAG_DORIS_JDBC_URL=jdbc:mysql://localhost:9030/demo + RAG_DORIS_USER/PASSWORD
# 回退零依赖：RAG_VECTOR_STORE_PROVIDER=in-memory
```

**切换必看的三个坑：**

1. **数据不迁移**。换 provider 得到的是空库，原有向量不会跟着过去——需**重新 ingest 文档**。
2. **外部后端要先起来**（in-memory 除外）。pgvector/milvus/chroma/doris 都得有对应服务可达，否则启动即失败。
3. **隔离模式限制**。默认 `collection-per-tenant`（每租户独立 collection/表）四个后端全支持；`RAG_VECTOR_STORE_ISOLATION=shared` **仅 in-memory / qdrant** 支持，其它 provider 用 shared 会直接抛异常。另外换 embedding provider（hash=64 维 vs openai/ollama 维度不同）也需重建库，维度守卫 `DimensionMismatchException` 会拦截不匹配。

## 四、Elasticsearch 8.15 + Kibana（全文检索，:9200 / :5601）

- **自建镜像**（`deploy/es/Dockerfile`）：`elasticsearch:8.15.3` + `elasticsearch-plugin install analysis-smartcn`（中文分析器）。single-node、`xpack.security.enabled=false`（本地无认证）、`ES_JAVA_OPTS=-Xms512m -Xmx512m`，卷 `es-data`，带 healthcheck。
- **Kibana** `kibana:8.15.3`（:5601），`depends_on` ES 健康后启动；较重（~1G+ RAM），不需要可 `docker compose stop kibana`（不影响混排）。
- **用途**：RAG 四路混排中的真 BM25 全文分支。knowledge-service 用自研 `EsGateway`（低层 `RestClient` + 手工 JSON，非 Spring Data ES），索引 `knowledge_segments_text`（`RAG_ES_INDEX_NAME`）；`tenantId/docId/category/...` 为 keyword 精确字段、`text` 用 smartcn 全文。ingest 时同步 upsert、删除按 `tenantId+docId` `_delete_by_query`。
- **默认开启**（`RAG_ES_ENABLED=true`，application.yml + compose 均默认）；knowledge-service 对 ES `service_healthy` 是 compose 硬依赖。刻意排除 Spring 的 ES 自动配置/健康指示器（否则 ES 关闭会拖挂 readiness）。
- **主机名**：Docker 内 `http://elasticsearch:9200`（`RAG_ES_URIS`）；本地默认 `http://localhost:9200`。冒烟：`bash deploy/smoke-es-hybrid-rag.sh`。

## 五、Kafka 3.8（消息/事件流，:9092）

- `apache/kafka:3.8.0`，KRaft 模式（控制器监听 9093），PLAINTEXT 无认证。
- 用途：channel-service 渠道出入站事件（`CHANNEL_EVENTS_ENABLED=true` 才用；默认内存事件总线）。事务性 outbox + relay + 消费侧去重实现 effective exactly-once，详见[事件总线指南](../平台工程/eventbus-guide.md)。
- 主机名：Docker 内 `kafka:9092`（`KAFKA_BOOTSTRAP_SERVERS`）。

## 六、H2（测试，内存）

依赖 DB 的单测统一用内存 H2，无 Testcontainers、不占端口、无账号。测试与被测代码同包，纯 POJO 单测直接 new 组件注入 mock。

## 七、知识入库写入链路（一份文档落到哪几个存储）

前面各节按「单个存储」列凭据；本节反过来，给一张**一次文档入库同时扇出到哪几处**的总表。上传入口是 `knowledge-service` 的
`DocumentService.upload()`（`knowledge-service/.../lifecycle/DocumentService.java`）。**一份文档不是只进一个库**——它按下表并行写入多个后端（默认全量 compose）：

| # | 存储 | 存什么 | 默认后端 | 落库开关 / 门控 | 代码扇出点（`DocumentService`） |
|---|---|---|---|---|---|
| 1 | **向量库** | 每个 chunk 的 embedding + 正文，`collection-per-tenant` 隔离 | **Qdrant**（第三节） | `RAG_VECTOR_STORE_PROVIDER=qdrant` | `storeRouter.forTenant(...).addAll(embeddings, segments)` |
| 2 | 明文镜像（**进程内内存，非外部库**） | 明文分块，供内存 keyword 检索兜底 | `DocumentMirror`（`ConcurrentHashMap`） | 恒开，不可关 | `documentMirror.add(segments)` |
| 3 | **Elasticsearch** | 同一批 chunk 的 BM25 全文倒排，索引 `knowledge_segments_text`，smartcn 中文分析 | **ES :9200**（第四节） | `RAG_ES_ENABLED=true`（默认开）；关时为 `NoopSegmentIndexer` 零副作用 | `segmentIndexer.index(segments)` |
| 4 | **图谱库** | GraphRAG 抽出的实体/关系三元组 | **MySQL** 库 `knowledge_graph`（第一节） | `RAG_GRAPH_ENABLED=true` + `RAG_GRAPH_STORE=jdbc`（yml/compose 默认）；关时 `graphIngestor==null` 跳过 | `graphIngestor.ingest(segments)` |
| 5 | **文档登记 registry** | 文档级元数据/版本目录（`list`/`get`/覆盖判定） | **Redis** key `rag:docs:<tenant>`（第二节） | `RAG_REGISTRY_STORE=redis`（默认）；可退 `in-memory` | `registry.put(info)` |
| 6 | **SpiceDB（授权关系，外部 `auth-platform`）** | 不是内容，是「谁能看」——文档 `owner` + `home_dept`（上传人部门）关系元组 | 外部 `auth-platform-server` :8200 → SpiceDB | 仅 `app.rag.authz.mode=shadow`/`enforce`；且仅**新建**文档写（同名覆盖保留原 owner，不夺权）。默认 `disabled` 不写 | `knowledgeAuthz.onDocumentCreated(...)` |

其中 1–4 是代码注释里说的「**四个 sink**」（向量 + 内存镜像 + ES + 图谱），5、6 另算。embedding provider（`hash` 64 维 / `ollama` nomic 768 维等）决定的是**写进向量库的内容**，本身不是一处存储。

### 全部按 `tenantId` 同源分区

入库时给每个 chunk 打的 metadata 是 `tenantId` / `docId` / `displayName` / `file_name` / `version` / `category`（enforce 下另由 SpiceDB 关系带上 `home_dept` 部门归属）。**上面每一处 sink 的隔离键都从这同一份 `tenantId`（及 category/部门）派生**——也就是说一份文档在 6 处存储里的「位置」是一致的，由入库那一刻的归属元数据统一决定。

### 删除会逐个 sink 撤干净（fail-closed 顺序）

`DocumentService.delete()` 是上传的逆操作，按**先撤权、再删数据**的安全顺序清理每一处（撤关系抛错即中止，宁留数据不留悬空权限）：

1. `knowledgeAuthz.onDocumentDeleted(...)` → 撤 SpiceDB 关系（enforce/shadow 时）；
2. `deleteInternal()` → 向量 `removeAll(tenantId+docId filter)`、`DocumentMirror.removeWhere`、`segmentIndexer.deleteByDoc`、`graphIngestor.removeBySourcePrefix`；
3. `registry.remove(...)` → 删 registry 记录。

### 由此推论：文档「放错位置」怎么修

因为一份文档在这 6 处存储里的位置**同源于入库时的 `tenantId`/`category`/`home_dept`**，一旦归属放错（错租户 / 错分类 / 错部门），是**所有存储一起一致地放错**，不是某一个库的局部问题。所以治本办法是**在源头改归属后重灌**，而不是去调某一路检索（如只改 ES 阈值 / 向量 minScore）打补丁：

```bash
# 1) 找到错放的文档，2) 一键删干净（delete 会逐个 sink 撤：向量/镜像/ES/图谱/registry/SpiceDB）
GET  /rag/documents            # 列当前租户文档
DELETE /rag/documents/{docId}  # 逐 sink 清理
# 3) 用正确的租户/分类/部门身份重新上传
POST /rag/documents
```

在单个 store 上调参只能「别让无关文档冒头」，救不回错误的归属，还会让 6 处存储彼此漂移。

## 八、知识查询读取链路（查了几个库 + 打分 / 排序 / rerank）

§七 是写入扇出；本节是它的镜像——一次 `POST /rag/query` **并行查哪几个存储**，以及召回后**怎么打分、排序、重排**。
编排器是 `KnowledgeQueryService.query()`（`knowledge-service/.../KnowledgeQueryService.java`）。

### 8.1 并行召回：最多查 4 路源（3 个外部库 + 1 处内存）

查询按**固定顺序**（顺序仅对 `weighted_max` 有意义）扇出到各启用的 `RetrievalSource`，各源都强制按 `tenantId`（+可选 `category`）隔离：

| 顺序 | 源 | 查哪个存储 | 召回什么 / 打什么分 | 启用开关 |
|---|---|---|---|---|
| 1 | **vector** (`VectorRetrievalSource`) | **向量库**（Qdrant，§三） | 用**查询扩展后的全部 variants** 多路 ANN 召回；分=归一化相关度 [0,1]（cosine，不相关≈0.5） | 恒开 |
| 2 | **keyword** (`InMemoryKeywordRetrievalSource`) | **进程内 `DocumentMirror`**（非外部库，§七#2） | 内存 BM25 近似；分=BM25 量纲 | `app.rag.hybrid.enabled=true`（默认开） |
| 3 | **es** (`EsKeywordRetrievalSource`) | **Elasticsearch**（§四） | 真 BM25 全文（smartcn 中文分析）；分=ES `_score` | `RAG_ES_ENABLED=true` 且 `es.query-enabled`（默认开） |
| 4 | **graph** (`GraphRetrievalSource`) | **图谱库**（MySQL `knowledge_graph`，§一） | GraphRAG 三元组命中；**多为无 `docId` 命中** | `RAG_GRAPH_INCLUDE_IN_QUERY=true`（默认随 graph.enabled） |

> **registry(Redis) 与 SpiceDB 不在召回路里**：Redis 只服务 `list`/`get` 文档元数据端点；SpiceDB 只在下面第 6 步授权过滤时被 `checkBulk` 查询。rerank 用的是 LLM 网关（DeepSeek）或 Jina 云 API，也不是数据库。

### 8.2 全流程七步

```text
POST /rag/query ─▶ KnowledgeQueryService.query()
 1. 解析 topK(默认 5) / minScore(默认 0.0)；快照身份 TenantContext(tenantId,userId)
 2. 候选池 poolLimit = topK × max(1, rerank 放大倍数)         # 授权开启时改为「有界 overfetch」：倍数取 max 不相乘，封顶 maxCandidates
 3. 查询扩展 QueryExpander.expand → variants(原 query + 变体)   # 默认 Noop=仅原 query
 4. 四路并行召回（8.1）→ 每源一个 hit 列表
 5. HybridFusionService.fuse(groups, strategy, rrfK) → 融合、按分降序、【不截断】
 6. filterReadable(...) 授权过滤（融合后、重排前）              # 默认 disabled 直通
 7. Reranker.rerank(query, candidates, topK) → 截断到 topK 返回
```

### 8.3 融合打分（第 5 步）：两种策略，分的含义完全不同

`FusionStrategy.effectiveDefault`：**ES 真正参与查询 → 默认 `RRF`**；ES 关闭/只写不查 → `weighted_max`。

- **`weighted_max`**（`fuseWeightedMax`）：`LinkedHashMap` 合并——同源同 chunk 取 `keepHigher`，跨源同 chunk 取 **max 分并标 `hybrid`**，图谱按自身 id `putIfAbsent`。**保留各源原始分量纲**（cosine 与 BM25 不可比，靠顺序与 max 兜底）。
- **`RRF`**（`fuseRrf`，`rrfK=60`）：各源先按 `mergeKey` 去重取最高分、再定名次，`score += 1/(k+rank)`，命中多源者标 `hybrid`。**免疫 BM25/余弦量纲差**，但结果 **`Hit.score = Σ 1/(60+rank) ≈ 0.01~0.05` 是「名次分」不是相关度**。

### 8.4 排序 / 重排（第 7 步）与三个反直觉的坑

`Reranker` 默认 `NoopReranker`（不重排，直接取融合序前 topK）。开启（`app.rag.rerank.enabled=true`，`type=llm|jina`）后：先按 `candidate-multiplier`（默认 3）多召回候选，用判官模型给每个候选打 0..1 相关分，**过滤掉 `< min-score` 的**，按分降序 `limit(topK)`。

调检索质量时**极易踩**的三点（详见调参备忘）：

1. **`RAG_QUERY_MIN_SCORE`（第 1 步的 `minScore`）只作用于 vector 源**（见 `RetrievalRequest.minScore` 注释：「仅向量源使用；关键词/图谱/ES 不适用」）。调高它**拦不住** ES/关键词/图谱通道进来的文档。它比的是归一化相关度（不相关≈0.5），故默认 0.0 全放行。
2. **RRF 下的 `Hit.score` 是名次分（0.01~0.05），不能拿它卡阈值**；且**重排不改写这个显示分**（rerank 只影响顺序与截断，`Hit.score` 仍是融合分）。
3. **真正的相关度地板是 `RAG_RERANK_MIN_SCORE`**（`LlmReranker`/`JinaReranker` 截断前 `filter(score >= minScore)`）。**不设时 topK 是硬数量、无相关度地板** → topK 大于相关文档数必被 filler 凑满（「topK=1 出对的、topK=5 冒无关」是设计使然）。设了阈值后 **topK 变「上限」**。compose 默认 0.5，但 DeepSeek 判官偏松，**建议 0.7**。另注意 rerank 单候选打分失败会退回其（很低的）融合分——偏精确度的 fail-closed。

> 另一常被误当阈值问题的根因：**该文档压根不该在这个 tenant 的 KB**（放错库，见 §七末）。调阈值只能压住症状，治本是 `DELETE /rag/documents/{docId}` 后按正确归属重灌。

完整 API、四路混排开关、GraphRAG 与查询扩展/上下文分块见 [RAG 接入指南](../对话与检索/rag-guide.md) 第 1、6 节。

## 相关文档

- [部署指南](../平台工程/deployment-guide.md)：docker-compose、k8s/Helm、External Secrets、Config Server。
- [运行与配置手册](operations.md)：本地启动、环境变量全量、验证与排障。
- [架构文档](架构文档.md)：服务边界、数据边界、事件流。
- [RAG 接入指南](../对话与检索/rag-guide.md)：向量库 provider、collection-per-tenant 隔离、GraphRAG。
- [向量检索：ANN 与 RRF](../对话与检索/向量检索-ann与rrf.md)：各向量库的 ANN 索引/实现（§三配套原理）、RRF 融合的含义与用法（§八配套原理）。
- [NL2SQL 指南](../对话与检索/nl2sql-guide.md)：只读连接、SQL 安全护栏、多租户库隔离。
