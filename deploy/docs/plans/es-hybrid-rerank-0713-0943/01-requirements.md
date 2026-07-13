# 01 Requirements

## 需求理解

目标是在现有 RAG 检索中引入 Elasticsearch 关键词/全文召回，与现有 vector 召回结合做混排，然后继续走现有 rerank 能力，提升召回率和最终 topK 相关性。

本需求不是从零建设 RAG。仓库已经有：

- 向量召回：`knowledge-service/src/main/java/com/lrj/platform/knowledge/KnowledgeQueryService.java` 使用 `EmbeddingModel` 生成 query embedding，并通过 `EmbeddingStoreRouter` 查询当前租户向量库。
- 现有混合召回：`KeywordSearchService` 基于进程内 `DocumentMirror` 做关键词召回。
- GraphRAG：`GraphSearchService` 可选加入查询结果。
- rerank：`Reranker` SPI 已存在，默认 `NoopReranker`，可切 `llm` 或 `jina`。

因此“ES 混排，然后 rerank，还需要结合 vector”的最小闭环是：

1. 入库时把每个文本 chunk 同步写入 ES keyword index。
2. 查询时同时召回 vector hits 与 ES keyword hits。
3. 按统一候选 key 去重和融合分数，形成候选池。
4. 候选池按初始分排序后交给现有 `Reranker.rerank(query, candidates, topK)`。
5. `/rag/query` 响应保持兼容，命中来源继续通过 `KnowledgeHit.source` 表示。

## 已确认业务规则

- 多租户隔离必须保持。现有向量路由按 `TenantContext.current().tenantId()` 选择租户 collection，关键词召回也只读 `DocumentMirror.all(tenantId)`。
- `category` 是现有检索过滤条件。`KnowledgeQueryRequest` 只有 `query/topK/minScore/category`，ES 查询必须同样支持 `category` 过滤。
- 空 query 必须拒绝。现有 `KnowledgeQueryService.query` 对 blank query 抛 `IllegalArgumentException`，controller 转 400。
- `topK` 小于等于 0 或为空时使用配置默认值；`minScore` 为空或负数时使用配置默认值。
- rerank 开启后，上游候选池按 `topK * reranker.retrieveMultiplier()` 放大。
- 外部 rerank 失败不能导致 RAG 查询整体失败。现有 `LlmReranker`/`JinaReranker` 均有降级逻辑。
- conversation、agent、eval 通过 `/rag/query` 与 `platform-protocol` 交互，默认应保持协议兼容。

## 边界条件

- 当前 `DocumentRegistry` 只保存文档级元数据，不保存原始全文或 chunk 全文。
- 当前 `EmbeddingStoreRouter` 只提供 `forTenant`，没有通用“枚举已有 segment”能力。
- 当前 `DocumentMirror` 是进程内内存镜像，重启后丢失；ES 不能依赖它作为历史数据源。
- 当前入库没有跨向量库、mirror、graph、registry 的事务边界。引入 ES 后会进一步扩大多存储一致性风险。
- `deleteInternal` 对向量删除捕获 `UnsupportedOperationException` 后继续执行；ES 删除也应按“尽力而为 + 可观测 + 可补偿”设计。
- GraphRAG 可以继续作为第三召回源，但本任务核心是 ES + vector + rerank，不应把 GraphRAG 作为必选依赖。

## 非目标

- 不改造对话生成、agent 推理、eval 评分核心逻辑。
- 不替换已有 Qdrant/PgVector/Milvus/Chroma/Doris 向量后端。
- 不改变 `/rag/query` 的基础请求字段。
- 不引入强一致分布式事务。
- 不默认要求所有历史向量数据自动无损迁移到 ES；缺少统一源数据时只能通过重新导入或后端专用扫描补齐。

## 歧义与待确认点

- “ES”具体指 Elasticsearch 还是 OpenSearch：待验证。
- ES 版本、部署方式、认证方式、证书策略：待验证。
- 中文检索是否要求 IK、smartcn、自定义同义词，还是先用标准 analyzer：待验证。
- 混排算法期望：权重加权、RRF、归一化加权、还是 ES 内部 `_score` 与 vector score 线性融合：待验证。
- 是否需要查询返回 explain/debug 字段：目前协议不含该字段，建议初期不加或通过内部日志/指标观测。
- 历史数据迁移范围：是只保证新上传文档进入 ES，还是必须补齐现有 Qdrant/Redis 中历史文档：待验证。

## 验收标准

- `RAG_ES_ENABLED=false` 时，现有测试与行为保持不变。
- `RAG_ES_ENABLED=true` 时，上传文本文件后 ES 能按 `tenantId/docId/index/category` 查询到对应 chunk。
- ES keyword 命中能与 vector 命中在同一候选池去重融合，同一 `docId#index` 不重复返回。
- `category` 过滤在 vector 与 ES 两路均生效。
- rerank 开启时，ES + vector 融合后的候选进入 reranker；rerank 失败时回退初始排序并返回结果。
- 删除文档后，ES 中同 `tenantId/docId` chunk 被删除；删除失败有日志/指标，并可通过重新导入或补偿脚本恢复一致性。
- 多租户检索不串租户。
- conversation、agent、eval 调用 `/rag/query` 无需改请求即可继续工作。
