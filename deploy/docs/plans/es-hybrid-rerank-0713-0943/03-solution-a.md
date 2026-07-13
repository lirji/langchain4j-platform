# 03 Solution A - Minimal ES Keyword Replacement

## 核心思路

在现有 `KeywordSearchService` 位置引入 ES 实现，把进程内 `DocumentMirror` 关键词召回替换为 Elasticsearch 全文召回。`KnowledgeQueryService` 仍保持当前结构：vector 召回、keyword 召回、graph 召回在同一个方法内融合，然后调用现有 reranker。

## 架构

- `DocumentService.upload`
  - 继续写 vector、`DocumentMirror`、graph、registry。
  - 新增调用 `SegmentIndexer.index(segments)`，写入 ES。
- `DocumentService.deleteInternal`
  - 新增调用 `SegmentIndexer.deleteByDoc(tenantId, docId)`。
- `KeywordSearchService`
  - 保留接口名和 `KeywordHit` record。
  - 根据配置装配 `ElasticsearchKeywordSearchService` 或现有内存实现。
- `KnowledgeQueryService`
  - 尽量不改融合逻辑。
  - ES 命中仍通过 `toKeywordHit` 转为 source=`keyword`，与 vector 合并后 source=`hybrid`。

## 模块职责

- `SegmentIndexer`
  - 入库、删除 ES 文档。
  - disabled 时为 no-op。
- `ElasticsearchKeywordSearchService`
  - 按 `tenantId` 和 `category` filter。
  - 使用 query text 做 match/multi_match。
  - 返回 `TextSegment` 或等价字段，供现有 `toKeywordHit` 使用。
- `EsRagConfig`
  - ES client、index name、enabled、fallback 策略。

## 核心流程

入库：

1. 文档上传。
2. 切 chunk。
3. 写 vector。
4. 写 ES chunk index。
5. 写 `DocumentMirror` 和 graph。
6. 写 registry。

查询：

1. vector 召回。
2. ES keyword 召回。
3. 可选 graph 召回。
4. 当前 `mergeHits` 去重融合。
5. rerank。

## 改动范围

小到中等。主要改 `DocumentService`、`KeywordSearchService` 装配、配置和测试。不需要大幅拆 `KnowledgeQueryService`。

## 扩展性

一般。短期最快；但 `KnowledgeQueryService` 会继续承担召回、融合、排序、rerank 编排，后续加 BM25/RRF/多 index/explain 会变得拥挤。

## 实施成本

低。适合作为 MVP。

## 优点

- 最少改动，回归风险低。
- 能复用现有权重配置和 rerank。
- 关闭 ES 后可回到原有内存 keyword。

## 缺点

- 融合算法仍是当前 `max(score)`，不同召回源分数不可比的问题没有根治。
- `KeywordSearchService` 名义上变成 ES 服务，职责不清。
- 历史数据迁移仍需要重放上传或后端专用扫描。
- 不解决多存储写入一致性，只是增加一个写入点。

## 适用场景

需要快速验证 ES 对召回质量的提升，且能接受首期不做大规模架构重构。
