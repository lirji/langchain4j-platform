# 02 Codebase Analysis

## 项目事实

仓库是 Java 21、Spring Boot 3.3.5、多模块 Maven。父 POM 在 `pom.xml`，`knowledge-service` 是 RAG 入口，`platform-protocol` 定义跨服务 DTO，`conversation-service`、`agent-service`、`eval-service` 是 `/rag/query` 的主要消费者。

代码中未发现 Elasticsearch/OpenSearch 客户端、依赖或部署资源。`deploy/docker-compose.yml` 当前基础设施包含 LiteLLM、Redis、MySQL、Kafka、Qdrant、config-server；Helm 外部基础设施模板只列 mysql/redis/kafka/qdrant/litellm。

## 当前查询调用链

入口：

- `knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/KnowledgeQueryController.java`
  - `POST /rag/query` 与 `/knowledge/query`
  - 读取 `KnowledgeQueryRequest(query, topK, minScore, category)`
  - 调 `KnowledgeQueryService.query`
  - 转为 `KnowledgeQueryReply` 和 `KnowledgeHit`

核心：

- `knowledge-service/src/main/java/com/lrj/platform/knowledge/KnowledgeQueryService.java`
  - 行为集中在 `query(String query, Integer topK, Double minScore, String category)`
  - 当前租户来自 `TenantContext.current().tenantId()`
  - vector filter 固定包含 `tenantId`，可附加 `category`
  - 根据 `reranker.retrieveMultiplier()` 扩大候选池
  - 通过 `queryExpander.expand(query)` 多变体向量召回
  - `hybridEnabled` 时调用 `KeywordSearchService.search`
  - `graphIncludedInQuery` 时调用 `GraphSearchService.query`
  - 最后对 `merged.values()` 按初始 score 降序，再调用 `reranker.rerank`

当前融合方式：

- vector hit：`score * vectorWeight`，source=`vector`
- keyword hit：`score * keywordWeight`，source=`keyword`
- graph hit：固定 `0.75 * graphWeight`，source=`graph`
- vector 与 keyword 命中相同 `docId#index` 时通过 `mergeHits` 合并，score 取 max，source=`hybrid`
- graph hit 用 graph 自己的 id，当前不与 chunk hit 合并

## 当前入库调用链

入口：

- `knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/DocumentController.java`
  - `POST /rag/documents` 支持 multipart 与 JSON
  - 上传需要 `ingest` scope
  - 文本走 `DocumentService.upload`
  - 图片走 `MultimodalRetrievalService`，本方案不覆盖图片 RAG

核心：

- `knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/DocumentService.java`
  - `computeDocId(tenantId, displayName)` 生成稳定 docId
  - 同名上传时先 `deleteInternal(prev)`，版本号加 1
  - 用 `DocumentSplitterFactory` 切分 chunk
  - 可选 `ContextualEnricher`
  - `embeddingModel.embedAll(segments)`
  - `storeRouter.forTenant(...).addAll(embeddings, segments)` 写向量
  - `documentMirror.add(segments)` 写内存关键词镜像
  - 可选 `graphIngestor.ingest(segments)`
  - 最后 `registry.put(info)`

删除：

- `delete(docId)` 查 registry，调用 `deleteInternal(info)`，再 `registry.remove`
- `deleteInternal` 删除向量、从 `DocumentMirror` 移除、删除 graph source prefix

一致性观察：

- 入库/删除没有事务注解。
- registry 是最后写入；如果向量/ES 成功但 registry 失败，列表与检索会割裂。
- 现有设计偏向“尽力而为 + 可重放导入”，而非强一致。

## 当前数据模型

跨服务 DTO：

- `platform-protocol/src/main/java/com/lrj/platform/protocol/knowledge/KnowledgeQueryRequest.java`
  - `String query`
  - `Integer topK`
  - `Double minScore`
  - `String category`
- `KnowledgeQueryReply.java`
  - `String query`
  - `String tenantId`
  - `List<KnowledgeHit> hits`
- `KnowledgeHit.java`
  - `id/score/docId/displayName/category/index/text/source`

内部 hit：

- `KnowledgeQueryService.Hit`
  - 字段与 `KnowledgeHit` 基本一致。

chunk metadata：

- `DocumentService.upload` 给 `Document` metadata 写入 `tenantId/docId/displayName/file_name/version/category`
- chunk 的 `index` 由 splitter/metadata 产生，`KnowledgeQueryService.segmentKey` 依赖 `docId#index`

文档注册表：

- `DocumentRegistry` 接口只有 `put/get/list/remove/snapshotAll`
- `InMemoryDocumentRegistry` 进程内存，默认本地可用
- `RedisDocumentRegistry` 每租户一个 Redis Hash：`rag:docs:<tenantId>`，field=`docId`
- 不保存原始全文或 chunk 全文

## 当前配置

`knowledge-service/src/main/resources/application.yml` 已有：

- `app.rag.vector-store.provider`: `in-memory|qdrant|pgvector|milvus|chroma|doris`
- `app.rag.vector-store.isolation`: 默认 `collection-per-tenant`
- `app.rag.embedding.provider`: `hash|openai|ollama`
- `app.rag.hybrid.enabled`
- `app.rag.hybrid.keyword-top-k`
- `app.rag.hybrid.tokenizer`: `simple|hanlp`
- `app.rag.ranking.vector-weight`
- `app.rag.ranking.keyword-weight`
- `app.rag.ranking.graph-weight`
- `app.rag.rerank.enabled/type/candidate-multiplier`
- `app.rag.graph.*`

缺失：

- 没有 `app.rag.es.*`
- 没有 ES host/index/analyzer/bulk/retry 配置
- 没有 ES health indicator 或 compose/helm 配置

## 当前测试

相关测试：

- `KnowledgeQueryServiceTest`
  - 租户隔离
  - category 过滤
  - blank query
  - keyword fallback with strict vector minScore
  - keyword ranking weight
  - graph hit include
- `KeywordSearchServiceTest`
  - 关键词搜索的租户和 category 隔离
  - 中文 bigram tokenizer
- `TenantIsolationTest`
  - collection-per-tenant 向量隔离
  - keyword mirror 按租户隔离
  - embedding 维度变化 fail-fast
- `RerankTest`
  - Noop 截断
  - LLM rerank 重排
  - scorer 异常回退
  - multiplier 下限
  - parseScore
- `KnowledgeQueryControllerTest`
  - controller DTO 映射和 bad request

测试风格多为纯 POJO + mock，不依赖 Spring context。新增 ES 测试应延续这个风格，避免默认引入 Testcontainers。

## 可复用代码

- `Reranker` SPI 与 `RerankConfig` 可直接复用。
- `QueryExpander` 可直接复用，ES 和 vector 应共享扩展后的 query variants。
- `KnowledgeQueryService.Hit` 可作为短期候选模型复用，但中长期建议抽出 `RetrievalHit`，避免查询服务过胖。
- `DocumentMirror` 和 `KeywordSearchService` 可作为 ES disabled 或 ES down 的本地降级。
- `EmbeddingStoreRouter` 保持 vector 后端抽象，不应被 ES 改造破坏。
- `DocumentService.upload/deleteInternal` 是 ES 索引写入/删除的自然挂点。
- `deploy/seed-kb.sh` 已能通过上传 API 重放示例知识库，可作为 ES 历史重建的演示路径。

## 受影响文件清单

高概率需要修改：

- `knowledge-service/pom.xml`
- `knowledge-service/src/main/resources/application.yml`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/KnowledgeQueryService.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/DocumentService.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/hybrid/KeywordSearchService.java` 或新增并替代
- `knowledge-service/src/test/java/com/lrj/platform/knowledge/KnowledgeQueryServiceTest.java`
- `knowledge-service/src/test/java/com/lrj/platform/knowledge/hybrid/KeywordSearchServiceTest.java`
- `knowledge-service/src/test/java/com/lrj/platform/knowledge/rerank/RerankTest.java` 仅需要补融合后 rerank 测试时可能触及

建议新增：

- `knowledge-service/src/main/java/com/lrj/platform/knowledge/search/RetrievalSource.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/search/RetrievalHit.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/search/HybridFusionService.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/search/VectorRetrievalSource.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/search/EsKeywordRetrievalSource.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/search/GraphRetrievalSource.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/es/EsRagConfig.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/es/EsSegmentIndexer.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/es/NoopSegmentIndexer.java`
- `knowledge-service/src/main/java/com/lrj/platform/knowledge/es/ElasticsearchSegmentIndexer.java`
- `knowledge-service/src/test/java/com/lrj/platform/knowledge/search/HybridFusionServiceTest.java`
- `knowledge-service/src/test/java/com/lrj/platform/knowledge/es/ElasticsearchSegmentIndexerTest.java` 或 fake-client 单测

部署配置可能需要修改：

- `deploy/docker-compose.yml`
- `deploy/helm/platform/values.yaml`
- `deploy/helm/platform/templates/external-services.yaml`
- `deploy/smoke-qdrant-rag.sh` 或新增 ES smoke 脚本

协议文件默认不需要改：

- `platform-protocol/src/main/java/com/lrj/platform/protocol/knowledge/KnowledgeQueryRequest.java`
- `KnowledgeQueryReply.java`
- `KnowledgeHit.java`

如需 explain/debug，则应新增可选字段，但这会影响所有消费者和契约测试，建议不作为首期目标。
