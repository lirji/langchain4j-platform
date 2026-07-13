# Comparison

评分：5 分最好，1 分最差。复杂度、测试难度、回滚成本按“分数越高越好”处理，即高分代表更简单、更容易测、更容易回滚。

| 方案 | 正确性 | 改动风险 | 复杂度 | 可维护性 | 扩展性 | 测试难度 | 回滚成本 | 总分 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A Minimal ES Keyword Replacement | 3 | 4 | 4 | 3 | 2 | 4 | 4 | 24 |
| B RetrievalSource + Normalized Fusion | 4 | 3 | 3 | 5 | 5 | 4 | 4 | 28 |
| C ES Unified Text + Vector Store | 3 | 2 | 2 | 3 | 3 | 2 | 2 | 17 |
| D Async Indexing Read Model | 5 | 1 | 1 | 5 | 5 | 2 | 2 | 21 |

## 方案 A 评估

最容易快速落地，但它把 ES 塞进现有 keyword 分支，保留了当前分数融合粗糙的问题。适合 MVP，不适合作为长期架构边界。

主要风险：

- ES `_score` 与 vector score 不可比，`max` 融合可能把某一路异常放大。
- 继续依赖 `KnowledgeQueryService` 大方法，后续不好扩展。
- 入库多写一个 ES 后，部分失败更难排查。

## 方案 B 评估

最平衡。它不推翻现有向量后端，也不要求改协议，但能把 ES、vector、graph 拆成清晰 source，并让 fusion 成为可测试纯逻辑。

主要风险：

- 代码移动较多，需要保护现有行为。
- 融合算法如果从 `max` 改为 RRF，会改变排序，需要 eval 数据验证。
- 仍然不是强一致入库架构。

## 方案 C 评估

技术上可行，但与现有 `EmbeddingStoreRouter` 多后端设计方向冲突。除非团队明确决定“ES 也承载向量检索”，否则本次不建议。

主要风险：

- ES dense vector mapping 与 embedding 维度强绑定。
- 性能与召回质量需要实测。
- 回滚到 Qdrant 需要双写期。

## 方案 D 评估

架构正确性最强，能根治“没有权威 segment 源”和“多索引难补偿”的问题。但这已经是 RAG 入库平台化改造，超出本次“引入 ES 混排”的合理范围。

主要风险：

- 工期长。
- 涉及数据结构、worker、状态机和接口语义。
- 当前代码没有统一迁移框架，需要谨慎落地。

## 推荐判断

不机械选择单一方案。最终方案建议以 B 为主体，吸收 A 的低风险兼容策略和 D 的幂等/补偿思路：

- 首期采用 `RetrievalSource` 抽象。
- ES 只作为 keyword/full-text source，不替换现有 vector。
- 保留内存 keyword 作为 disabled/down fallback。
- fusion 默认保持可兼容的 weighted max，新增 RRF 可配置但灰度开启。
- 入库先同步双写 ES，并通过幂等 document id、delete-by-query、日志指标和重导入脚本处理一致性。
- 历史数据以重新导入为主，后端专用扫描标记为待验证扩展。

该组合的弱点是：没有一次性解决多存储强一致和历史无源重建问题。但相对当前代码结构，这是风险和收益最均衡的路径。
