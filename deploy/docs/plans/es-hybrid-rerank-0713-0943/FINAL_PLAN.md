# FINAL PLAN

## 背景、目标与非目标

现有 `knowledge-service` 已有 vector 召回、进程内 keyword 召回、可选 GraphRAG、query expansion 与 rerank。当前“混合检索”不是 ES，而是 `DocumentMirror` + `KeywordSearchService` 的内存关键词重叠匹配。目标是在不破坏现有 vector 后端的前提下，引入 Elasticsearch 作为全文召回源，与 vector 召回融合后再进入现有 `Reranker`。

目标：

- 新增 ES keyword/full-text 召回。
- 保留现有 vector 后端和 `EmbeddingStoreRouter`。
- ES + vector 候选去重、融合、初排后进入 rerank。
- `/rag/query` 协议默认兼容 conversation、agent、eval。
- ES 关闭或不可用时可降级到现有行为。

非目标：

- 不用 ES 替换 Qdrant/PgVector/Milvus/Chroma/Doris。
- 不改造 conversation/agent 的 RAG 使用方式。
- 不引入分布式事务。
- 不承诺无源历史数据自动迁移；当前 registry 不保存全文，向量抽象也没有通用枚举 API。

## 跨模型复核修正（Claude 阶段二，2026-07-13）

对照真实仓库核验：Codex 方案的类/方法/表/调用链均属实（`lifecycle/DocumentService.java`、`DocumentRegistry` + `InMemoryDocumentRegistry`/`RedisDocumentRegistry`、`graph/GraphSearchService`/`GraphIngestor`、`segmentKey=docId#index` 均已 Grep/Read 验证）。以下为发现并已在本文件修正的实质问题：

1. **[已解决待验证] ES 客户端版本**：Spring Boot 3.3.5 的 BOM 已托管 `co.elastic.clients:elasticsearch-java`（版本属性 `${elasticsearch-client.version}`≈8.15.x），`knowledge-service` 直接声明依赖、不写版本即可（见修改清单已更新）。
2. **[新增·关键] ES 中文分析器**：本库是中文语料且已投入 HanLP。ES 默认 `standard` analyzer 对中文按单字切，BM25 召回会和现状 bigram 一样弱，等于白引入 ES。索引 `text` 字段**必须**配中文分析器（内置 `smartcn` 插件，或 IK `analysis-ik`），并在 index mapping 与配置显式声明。已在"ES index"与配置节补充。
3. **[修正·正确性] 融合分数量纲**：ES BM25 分数**无上界**，与向量余弦 [0,1] 不可直接比较。首期默认 `weighted_max` 若直接拿原始 BM25 参与 max，会让 ES 恒压向量。因此 ES 检索源**必须把 BM25 归一到 [0,1]**（按本次查询最高分归一）后再进 `weighted_max`；或当 ES 开启时**默认走 RRF**（只看名次、天然免疫量纲，正是引入 ES 想要的效果）。已在融合策略节明确。
4. **[运维·数据] 历史数据回灌**：开启 ES 后现有文档不在 ES（registry 不存全文），需按上传 API 重灌；本机已有 `reingest.sh` + `kb-backup-acme.json` 可一键回灌。
5. **[护栏·回归] query() 重构**：把 vector/keyword/graph 召回迁出 `KnowledgeQueryService` 是热路径大改，`HybridFusionService` 在 `weighted_max` 下必须**逐字复刻**现有语义——尤其"graph 按 `hit.id()` 独立入表、不与 chunk 去重"、向量多变体 `keepHigher`、keyword `max`→标 `hybrid`。以现有 `KnowledgeQueryServiceTest` 为不回归基线。

## 用户决策（2026-07-13）

- **融合默认策略 = RRF 优先**。语义：`app.rag.es.enabled=true` 时融合**默认 `rrf`**（`app.rag.fusion.strategy` 未显式设置时的有效默认随 ES 开启翻为 `rrf`）；`app.rag.es.enabled=false`（现状）时仍为 `weighted_max`，以**不破坏现有非-ES 测试与行为**。ES 的 docker-compose/helm 开启样例里显式带 `RAG_FUSION_STRATEGY=rrf`。
- 因 RRF 只看名次、免疫量纲，ES BM25 归一（`app.rag.es.normalize-score`）在 `rrf` 下不生效，仅 `weighted_max` 显式选用时才需要。

## Codex 独立审查后修复（round 2，2026-07-13）

`/codex-review` 独立审查后已修 4 项（142 测试全绿，+8）：
- **#5 灰度 RRF 翻转（真 bug）**：有效默认改为只在 ES **真正参与查询**（enabled ∧ query-enabled）时才翻 RRF。`FusionStrategy.effectiveDefault(...)` + `KnowledgeQueryService` 注入 `app.rag.es.query-enabled`。测试 `FusionStrategyTest`。
- **#7 RRF 同源重复计分（真 bug）**：`HybridFusionService.fuseRrf` 源内先按 mergeKey 去重取最佳分再定名次。测试 `HybridFusionServiceTest.rrf_dedupsSameSourceDuplicateChunk`。
- **#3 建索引耦合启动 + 400 全吞**：`ElasticsearchSegmentIndexer` 惰性 ensureIndex（首次写、一次），`EsRagConfig` 去 eager 调用；建索引 400 只吞 `resource_already_exists`，其余带响应体上抛（smartcn 缺失可暴露）。测试 `index_ensuresIndexLazilyOnceAcrossCalls`。
- **#2 死配置**：删 `RAG_ES_FALLBACK_TO_IN_MEMORY`（下方修改清单/灰度里此项已废弃）；降级实际靠"ES 查失败返回空 + 内存关键词独立源"。
- 判断题保留：#1 source=`es`（比 keyword 更可观测，**接受偏离原 plan 那句"映射为 keyword"**）；#4/#6/#10 记为已知权衡。

## 已确认的业务规则

- 租户隔离来自 `TenantContext.current().tenantId()`，vector、ES、graph 都必须过滤 `tenantId`。
- `category` 是现有检索过滤字段，ES 也必须支持。
- blank query 返回 400，保持 `KnowledgeQueryController` 现有行为。
- topK/minScore 继续使用 `KnowledgeQueryService` 现有默认规则。
- rerank 候选池继续按 `topK * reranker.retrieveMultiplier()` 放大。
- rerank 失败降级返回初始排序，不让查询整体失败。
- `KnowledgeHit.source` 继续表达来源：`vector`、`keyword`、`hybrid`、`graph`；ES keyword 首期映射为 `keyword`，与 vector 合并后为 `hybrid`。

## 当前代码与调用链分析

查询入口：

- `knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/KnowledgeQueryController.java`
- `POST /rag/query` -> `KnowledgeQueryService.query` -> `KnowledgeQueryReply`

查询核心：

- `KnowledgeQueryService.query`
  - 构造 vector metadata filter：`tenantId` + 可选 `category`
  - 向量召回：`embeddingModel.embed(variant)` + `storeRouter.forTenant(...).search`
  - keyword 召回：`KeywordSearchService.search(query, keywordLimit, category)`
  - graph 召回：`GraphSearchService.query`
  - 去重融合：`segmentKey(docId#index)`，vector + keyword 合并为 `hybrid`
  - rerank：`reranker.rerank(query, candidates, limit)`

入库入口：

- `knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/DocumentController.java`
- `POST /rag/documents` -> `DocumentService.upload`

入库核心：

- `DocumentService.upload`
  - 计算 `docId`
  - 同名文档先 `deleteInternal(prev)`
  - splitter 切 chunk
  - 可选 contextual enrichment
  - `embeddingModel.embedAll`
  - 写 vector
  - `documentMirror.add`
  - 可选 graph ingest
  - `registry.put`

现有存储：

- vector：`EmbeddingStoreRouter`，支持多后端。
- keyword：`DocumentMirror`，进程内内存。
- registry：`InMemoryDocumentRegistry` 或 `RedisDocumentRegistry`，只存文档元数据。
- graph：`InMemoryGraphStore` 或 `JdbcGraphStore`。

现有部署：

- `deploy/docker-compose.yml` 有 qdrant/redis/mysql/kafka/litellm，无 ES。
- `deploy/helm/platform/values.yaml` 有 RAG vector/hybrid/rerank/graph 配置，无 ES。

## 候选方案对比与评分

| 方案 | 正确性 | 改动风险 | 复杂度 | 可维护性 | 扩展性 | 测试难度 | 回滚成本 | 总分 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A 最小 ES keyword 替换 | 3 | 4 | 4 | 3 | 2 | 4 | 4 | 24 |
| B RetrievalSource + 标准融合 | 4 | 3 | 3 | 5 | 5 | 4 | 4 | 28 |
| C ES 统一承载 text + vector | 3 | 2 | 2 | 3 | 3 | 2 | 2 | 17 |
| D catalog + outbox 异步索引 | 5 | 1 | 1 | 5 | 5 | 2 | 2 | 21 |

## 最终方案及选择原因

选择方案 B 为主体，吸收方案 A 的兼容降级和方案 D 的幂等/补偿思想。

选择原因：

- 能直接满足 ES + vector + rerank。
- 不推翻现有多 vector 后端。
- 不改 `/rag/query` 协议。
- 能把召回源和融合逻辑从 `KnowledgeQueryService` 拆出去，后续可维护。
- ES disabled 时保留当前行为，回滚成本可控。

已知弱点：

- 首期仍是同步多写，不是强一致。
- 历史数据无法从 `DocumentRegistry` 通用恢复 chunk 文本；需要重新导入或做后端专用扫描。
- 融合量纲坑（复核新增）：ES BM25 无上界，`weighted_max` 直接用原始 BM25 会恒压向量。故 `EsKeywordRetrievalSource` **必须先把 BM25 归一到 [0,1]**（按本次查询候选最高分归一）再产出 `RetrievalHit.score`，`weighted_max` 才有意义；否则应把 ES 场景默认切到 `rrf`（名次融合，免疫量纲）。二者选一，不能拿裸 BM25 进 max。

## 精确修改清单

修改：

- `knowledge-service/pom.xml`
  - 增加官方 Elasticsearch Java client：`co.elastic.clients:elasticsearch-java`（**已核实** Spring Boot 3.3.5 BOM 托管，`${elasticsearch-client.version}`≈8.15.x，**不写版本**）。它会传递引入 `elasticsearch-rest-client` + Jackson，均与 Spring Boot 管理版本对齐，无需额外锁版本。
  - 若最终确认目标是 OpenSearch 而非 Elasticsearch，则改用 OpenSearch Java client；该分支需要单独验证，不在首期默认实现里混用。
- `knowledge-service/src/main/resources/application.yml`
  - 新增 `app.rag.es.enabled=false`
  - `app.rag.es.index-enabled=${app.rag.es.enabled:false}`
  - `app.rag.es.query-enabled=${app.rag.es.enabled:false}`
  - `app.rag.es.uris`
  - `app.rag.es.username/password/api-key`
  - `app.rag.es.index-name=knowledge_segments_text`
  - `app.rag.es.connect-timeout/read-timeout`
  - `app.rag.es.fail-fast=false`
  - `app.rag.es.fallback-to-in-memory=true`
  - `app.rag.fusion.strategy=weighted_max`（可选 `rrf`）
  - `app.rag.fusion.rrf-k=60`
  - `app.rag.ranking.es-weight=1.0`
  - `app.rag.es.analyzer=smartcn`（中文分析器，见 ES index 节）
  - `app.rag.es.normalize-score=true`（BM25→[0,1] 归一，`weighted_max` 下必须开；`rrf` 下忽略）
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/KnowledgeQueryService.java`
  - 保留校验、topK/minScore、query expansion、rerank。
  - 删除或迁出直接 vector/keyword/graph 召回代码。
  - 注入 `List<RetrievalSource>` 和 `HybridFusionService`。
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/DocumentService.java`
  - 注入 `SegmentIndexer`。
  - `upload` 中在 contextual enrichment 后、registry put 前调用 `segmentIndexer.index(segments)`，确保 ES 索引文本与写入 vector 的最终 chunk 文本一致。
  - `deleteInternal` 中调用 `segmentIndexer.deleteByDoc(info.tenantId(), info.docId())`。
  - ES 写入失败按配置 fail-fast 或 best-effort。
- `deploy/docker-compose.yml`
  - 新增可选 `elasticsearch` 服务，默认不强制 knowledge 依赖，或用 profile 控制。
  - knowledge-service 增加 ES 环境变量，默认 disabled。
- `deploy/helm/platform/values.yaml`
  - 新增 ES 外部地址和 `RAG_ES_*` 配置，默认 disabled。
- `deploy/helm/platform/templates/external-services.yaml`
  - 若使用 ExternalName，则加入 elasticsearch 外部服务配置。

新增：

- `knowledge-service/src/main/java/com/lrj/platform/knowledge/search/RetrievalSource.java`
  - 建议方法：`String name()`、`boolean enabled()`、`List<RetrievalHit> retrieve(RetrievalRequest request)`。
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/search/RetrievalRequest.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/search/RetrievalHit.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/search/VectorRetrievalSource.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/search/InMemoryKeywordRetrievalSource.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/search/EsKeywordRetrievalSource.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/search/GraphRetrievalSource.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/search/HybridFusionService.java`
  - 建议方法：`List<KnowledgeQueryService.Hit> fuse(List<RetrievalHit> hits, int limit)`，后续如抽离内部 `Hit` 再调整返回类型。
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/es/EsRagProperties.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/es/EsRagConfig.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/es/SegmentIndexer.java`
  - 建议方法：`void index(List<TextSegment> segments)`、`void deleteByDoc(String tenantId, String docId)`。
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/es/NoopSegmentIndexer.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/es/ElasticsearchSegmentIndexer.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/es/EsSegmentDocument.java`
- `knowledge-service/src/test/java/com/lrj/platform/knowledge/search/HybridFusionServiceTest.java`
- `knowledge-service/src/test/java/com/lrj/platform/knowledge/search/EsKeywordRetrievalSourceTest.java`
- `knowledge-service/src/test/java/com/lrj/platform/knowledge/es/ElasticsearchSegmentIndexerTest.java`
- `deploy/smoke-es-hybrid-rag.sh`

默认不修改：

- `platform-protocol/src/main/java/com/lrj/platform/protocol/knowledge/KnowledgeQueryRequest.java`
- `KnowledgeQueryReply.java`
- `KnowledgeHit.java`
- `conversation-service`
- `agent-service`
- `eval-service`

## 数据库、接口、配置、消息结构变更

数据库：

- 不新增 MySQL 表。
- 不新增 Redis 结构。
- 不改 `RAG_GRAPH_TRIPLE`。

ES index：

- index：`knowledge_segments_text`
- document id：稳定由 `tenantId/docId/index` 生成。
- 字段：
  - `tenantId` keyword
  - `docId` keyword
  - `displayName` keyword + 可选 text
  - `category` keyword
  - `index` keyword
  - `version` keyword 或 integer
  - `text` text，**analyzer 必须是中文分析器**（默认 `smartcn`，需 ES 装 `analysis-smartcn` 插件；可选 IK `analysis-ik`）。用 `app.rag.es.analyzer` 配置，缺省 `smartcn`。**不可用 `standard`**（对中文按单字切，BM25 召回等同现状 bigram，白引入 ES）。
  - `createdAt` date 或 long
- mapping 创建策略：`ElasticsearchSegmentIndexer` 首次写入前 `PUT /{index}` 建 mapping（幂等，已存在则跳过）；analyzer 插件缺失时 fail-fast 报清晰错误，不静默退化成 standard。

接口：

- `/rag/query` 请求/响应不变。
- `/rag/documents` 不变。

配置：

- 新增 `RAG_ES_ENABLED`
- `RAG_ES_INDEX_ENABLED`
- `RAG_ES_QUERY_ENABLED`
- `RAG_ES_URIS`
- `RAG_ES_USERNAME`
- `RAG_ES_PASSWORD`
- `RAG_ES_API_KEY`
- `RAG_ES_INDEX_NAME`
- `RAG_ES_FAIL_FAST`
- `RAG_ES_FALLBACK_TO_IN_MEMORY`
- `RAG_FUSION_STRATEGY`
- `RAG_FUSION_RRF_K`
- `RAG_RANKING_ES_WEIGHT`

消息结构：

- 无 Kafka/事件消息变更。

## 分阶段实施步骤及依赖关系

### 阶段 1：数据结构与领域模型

1. 新增 `RetrievalRequest/RetrievalHit/RetrievalSource`。
2. 新增 `EsSegmentDocument`。
3. 新增 `EsRagProperties`。
4. 新增 `SegmentIndexer` 与 `NoopSegmentIndexer`。

完成标准：

- ES disabled 时 Spring 能启动。
- 新模型纯单测通过。
- 未触碰协议 DTO。

### 阶段 2：核心业务逻辑

1. 实现 `VectorRetrievalSource`，迁出当前 vector 召回逻辑。
2. 实现 `InMemoryKeywordRetrievalSource`，包装现有 `KeywordSearchService`。
3. 实现 `GraphRetrievalSource`，迁出当前 graph 召回逻辑。
4. 实现 `HybridFusionService`，首期默认 `weighted_max`，可配置 `rrf`。
5. 改造 `KnowledgeQueryService` 为编排器。

完成标准：

- 现有 `KnowledgeQueryServiceTest` 在 ES disabled 下通过。
- `weighted_max` 与现有 source/source merge 行为等价或差异有测试说明。
- rerank 仍接收融合后候选。

### 阶段 3：接口与适配层

1. 实现 `EsRagConfig` 和 ES client。
2. 实现 `ElasticsearchSegmentIndexer`。
3. 实现 `EsKeywordRetrievalSource`。
4. 在 `DocumentService.upload/deleteInternal` 接入 `SegmentIndexer`。
5. 增加 Compose/Helm 配置，默认 disabled。

完成标准：

- ES enabled 时上传后能写入 index。
- ES query 能按 tenant/category 查 chunk。
- ES down 时按配置降级或失败。
- 删除文档后 ES 不返回该 docId。

### 阶段 4：测试

1. 补 `HybridFusionServiceTest`。
2. 补 ES indexer/source fake-client 单测。
3. 补 `KnowledgeQueryServiceTest` 的 ES + vector + rerank 流程。
4. 补 `TenantIsolationTest` 的 ES source 隔离。
5. 新增可选真实 ES smoke。

完成标准：

- `mvn -pl knowledge-service test` 通过。
- `mvn -pl conversation-service -am test` 通过。
- `mvn -pl agent-service -am test` 通过。
- `mvn -pl eval-service -am test` 通过。
- `bash deploy/smoke-qdrant-rag.sh` 仍通过。

### 阶段 5：文档与最终检查

1. 更新 RAG 文档，说明 ES 配置、降级、迁移。
2. 更新部署文档。
3. 给出灰度开关和回滚步骤。

完成标准：

- 文档包含 ES enabled/disabled 示例。
- 文档明确历史数据需要重新导入或专用扫描，不能承诺自动迁移。
- 最终验收清单全部通过。

## 测试方案

单元：

- fusion 去重、weighted max、RRF。
- ES source tenant/category filter。
- ES indexer idempotent index/delete。
- `KnowledgeQueryService` 编排和 rerank 调用。

集成：

- fake ES client 装配测试。
- 可选真实 ES profile/smoke。

回归：

- knowledge/conversation/agent/eval 模块测试。
- Qdrant smoke。
- ES hybrid smoke。

异常：

- ES down。
- ES 写失败。
- vector 写失败。
- rerank down。
- 并发同名上传。
- category 特殊字符。

## 风险、监控、灰度与回滚方案

风险：

- ES 与 vector 分数不可比：通过 `HybridFusionService` 封装策略，默认 weighted max，RRF 灰度。
- 多存储写入部分失败：ES indexer 支持 fail-fast/best-effort，记录日志和指标。
- 历史数据缺失：明确通过上传 API 重新导入，`deploy/seed-kb.sh` 可作为示例；向量后端扫描能力待验证。
- ES 查询慢：限制 `poolLimit`，配置 timeout，监控 latency。
- 租户串数据：所有 ES 查询和删除必须带 `tenantId` filter，并有测试。

监控：

- ES index success/failure counter。
- ES query latency。
- ES query failure counter。
- fallback counter。
- fusion source hit count：vector/es/graph/hybrid。
- rerank latency/failure。

灰度：

1. 部署 ES，但 `RAG_ES_ENABLED=false`。
2. 单租户开启 `RAG_ES_INDEX_ENABLED=true` 且 `RAG_ES_QUERY_ENABLED=false`，只验证写入和索引状态。
3. 开启 ES 查询但 `RAG_ES_FALLBACK_TO_IN_MEMORY=true`。
4. 小流量打开 `RAG_FUSION_STRATEGY=weighted_max`。
5. eval 指标稳定后灰度 `rrf`。
6. 最后按租户扩大。

回滚：

- 关闭 `RAG_ES_ENABLED`，服务回到 vector + in-memory keyword/graph/rerank。
- 保留 ES index 不删，便于排查。
- 如 ES 写入影响上传，设置 `RAG_ES_FAIL_FAST=false` 或禁用 ES。
- 如排序质量下降，先回退 `RAG_FUSION_STRATEGY=weighted_max`，再关闭 ES 查询。

## 最终验收清单

- [ ] ES disabled 时现有行为和测试不变。
- [ ] ES enabled 时上传文本 chunk 写入 ES。
- [ ] ES 查询按 tenant/category 隔离。
- [ ] vector + ES 同 chunk 去重为单条命中。
- [ ] rerank 接收融合后候选池。
- [ ] ES down 可按配置降级。
- [ ] 删除文档清理 ES chunk。
- [ ] conversation/agent/eval 无协议改造即可工作。
- [ ] 部署配置默认不强依赖 ES。
- [ ] 文档明确历史迁移策略和限制。
