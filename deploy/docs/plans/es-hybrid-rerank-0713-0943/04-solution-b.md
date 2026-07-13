# 04 Solution B - RetrievalSource Abstraction With Normalized Fusion

## 核心思路

把 `KnowledgeQueryService` 中硬编码的 vector/keyword/graph 召回拆成多个 `RetrievalSource`，引入独立 `HybridFusionService` 做候选归一化、去重、融合和初排。ES 是其中一个 `RetrievalSource`，vector 继续使用现有 `EmbeddingStoreRouter`，rerank 继续使用现有 `Reranker`。

## 架构

新增检索层：

- `RetrievalSource`
  - `String name()`
  - `List<RetrievalHit> retrieve(RetrievalRequest request)`
- `RetrievalRequest`
  - `query`
  - `variants`
  - `tenantId`
  - `category`
  - `poolLimit`
  - `minScore`
- `RetrievalHit`
  - `id`
  - `score`
  - `docId`
  - `displayName`
  - `category`
  - `index`
  - `text`
  - `source`
  - `stableKey`
- `HybridFusionService`
  - 按 stable key 去重。
  - 支持 weighted max、weighted sum、RRF 三种策略，默认推荐 RRF 或归一化加权。

召回源：

- `VectorRetrievalSource`
  - 从当前 `KnowledgeQueryService` 的向量逻辑迁出。
- `EsKeywordRetrievalSource`
  - 使用 ES 检索 chunk。
- `InMemoryKeywordRetrievalSource`
  - 复用现有 `KeywordSearchService`，作为 ES disabled/down fallback。
- `GraphRetrievalSource`
  - 从当前 graph 逻辑迁出，可选启用。

`KnowledgeQueryService` 变成编排器：

1. 校验 query。
2. 计算 topK/minScore/poolLimit。
3. query expansion。
4. 调所有 enabled `RetrievalSource`。
5. `HybridFusionService.fuse`。
6. `reranker.rerank`。
7. 返回 `QueryResult`。

## 核心流程

入库：

- 与方案 A 类似，新增 `SegmentIndexer` 写 ES。
- 保留 `DocumentMirror`，用于 fallback 和测试。

查询：

1. 扩展 query variants。
2. vector source 对每个 variant 做 embedding 检索。
3. ES source 可用原 query，必要时也对 variants 做多路 match。
4. graph source 可选。
5. fusion 将各 source 的分数归一化或转 rank。
6. rerank 最终候选。

## 改动范围

中等。需要拆出若干类，但变更边界清晰，测试也更聚焦。

## 扩展性

好。后续可新增：

- ES semantic_text 或 sparse vector source
- Query rewrite source
- 多 index source
- debug explain
- shadow source 对比

## 实施成本

中。需要补较多单元测试，但不需要改协议和上游消费者。

## 优点

- 解决 `KnowledgeQueryService` 过胖问题。
- ES 与 vector 关系清晰，方便灰度开关。
- fusion 算法可单测，避免在 controller/service 大方法里堆规则。
- 支持 ES down 时自动降级到 vector-only 或 in-memory keyword。

## 缺点

- 首期代码改动比方案 A 多。
- 如果融合策略从 `max` 改为 RRF，检索排序会有行为变化，需要 eval 回归。
- 仍不提供强一致事务；入库一致性需要另行补偿。

## 适用场景

适合作为正式落地方案：既能引入 ES，又为后续检索质量迭代留出结构空间。
