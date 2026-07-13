# 06 Solution D - Async Indexing Read Model With Outbox

## 核心思路

把 RAG 入库改造成“权威 segment catalog + 异步索引投递”模式。`DocumentService` 先把 chunk 元数据和文本写入一个持久化 catalog，再通过 outbox/worker 同步到 vector、ES、graph。查询读取各索引；索引失败可重试、补偿、重放。

## 架构

新增：

- `SegmentCatalog`
  - 保存 `tenantId/docId/index/text/category/displayName/version/contentHash/indexStatus`
- `RagIndexOutbox`
  - 记录待写 vector/ES/graph 的事件。
- `RagIndexWorker`
  - 幂等消费 outbox。
- `IndexStatusController`
  - 查询文档索引状态，便于灰度和排障。

现有改造：

- `DocumentService.upload`
  - 从“同步写所有索引”改为“写 catalog + outbox”。
- `DocumentService.delete`
  - 写 tombstone/outbox，worker 删除各索引。
- `KnowledgeQueryService`
  - 仍查询 vector + ES + graph + rerank，但需要处理索引延迟。

## 核心流程

入库：

1. 切 chunk。
2. 写 catalog。
3. 写 outbox。
4. 返回 accepted 或同步等待 worker 完成。
5. worker 幂等写 vector/ES/graph。

删除：

1. 标记 catalog tombstone。
2. outbox 删除 vector/ES/graph。
3. worker 完成后清理或保留审计。

## 改动范围

非常大。需要新增表、worker、状态模型、补偿机制和接口语义讨论。

## 扩展性

最好。可以系统性解决历史重建、多存储一致性、重试、回滚和可观测。

## 实施成本

最高。

## 优点

- 有权威 segment 源，ES/vector/graph 都可重建。
- 幂等和补偿清晰。
- 多实例并发入库更可控。
- 便于灰度 shadow index。

## 缺点

- 不是“引入 ES 混排”的局部改造，而是 RAG 入库架构升级。
- 当前仓库没有通用迁移工具，表结构演进多在代码 DDL 中完成，实施复杂。
- 需要定义上传接口是同步完成还是异步 accepted，可能影响用户体验和测试。

## 适用场景

适合生产规模知识库、强运维诉求、多索引一致性要求高的场景。可吸收其“幂等、补偿、重放”思想，但不建议作为首期全量实施。
