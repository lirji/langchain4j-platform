# 05 Solution C - ES As Unified Text Retrieval Store

## 核心思路

把 Elasticsearch 作为文本检索主存储，除 BM25 外，还在 ES 中保存 dense vector 或可检索向量字段，使 ES 自身完成 text + vector hybrid search。现有外部向量库仍可保留一段时间作为兼容或回滚路径。

## 架构

- `DocumentService.upload`
  - 生成 embedding 后，把 chunk text、metadata、embedding vector 一起写 ES。
  - 可选择继续写 Qdrant 等现有向量库，用于兼容和回滚。
- `EsHybridRetrievalSource`
  - 单次 ES 查询内组合 BM25 与 kNN。
  - ES 内部完成初步混排。
- `KnowledgeQueryService`
  - 只接入 `EsHybridRetrievalSource`，可选接入现有 vector source 作为 shadow/fallback。
- rerank
  - 继续使用现有 `Reranker`。

## 核心流程

查询：

1. query embedding。
2. ES 执行 BM25 + kNN hybrid 查询。
3. 返回候选。
4. rerank。

## 改动范围

大。需要确认 ES 版本支持的向量能力、索引 mapping、查询 DSL、分数融合方式；同时需要考虑现有 Qdrant/PgVector/Milvus/Chroma/Doris 后端的兼容定位。

## 扩展性

中到好。如果团队决定以 ES 作为统一检索平台，则后续运维和排障更集中。但该方案会弱化现有 `EmbeddingStoreRouter` 的多后端能力。

## 实施成本

高。

## 优点

- 一个查询引擎同时处理 BM25 和向量，延迟链路短。
- ES explain/profile 能力更容易观察文本侧排序。
- 删除和重建索引集中。

## 缺点

- 与当前多向量后端设计冲突较大。
- ES 向量能力、性能、召回质量需要单独压测，不能假设优于 Qdrant。
- 向量维度变更会影响 ES mapping，迁移成本高。
- 现有 `EmbeddingStoreRouter`、collection-per-tenant 设计价值下降。

## 适用场景

适用于团队明确要把 ES 作为主检索引擎，并愿意承担较大迁移与压测成本。不建议作为本次首选。
