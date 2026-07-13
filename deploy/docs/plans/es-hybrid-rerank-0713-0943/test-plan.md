# Test Plan

## 单元测试

### ES 索引写入

目标文件：

- `ElasticsearchSegmentIndexerTest`
- `NoopSegmentIndexerTest`

覆盖：

- `index(segments)` 为空时不调用客户端。
- 写入字段包含 `tenantId/docId/displayName/category/index/text/version`。
- ES document id 稳定：建议 `tenantId + ":" + docId + ":" + index` 后做安全编码或 hash。
- 同一 chunk 重复 index 是覆盖/幂等。
- `deleteByDoc(tenantId, docId)` 带 tenant filter，不能跨租户删。
- ES 异常按配置降级：`fail-fast` 抛出，`best-effort` 记录并继续。

### ES 召回

目标文件：

- `EsKeywordRetrievalSourceTest`

覆盖：

- query 为空返回空。
- tenant filter 必须存在。
- category 非空时必须作为 filter。
- ES `_score` 映射到 `RetrievalHit.score`。
- 返回 `stableKey=docId#index`。
- ES timeout/down 时按配置返回空或抛出。

### 融合

目标文件：

- `HybridFusionServiceTest`

覆盖：

- vector 与 ES 命中同 `docId#index` 时去重。
- 去重后 source 合并为 `hybrid` 或保留 source 列表中的主 source；最终协议若仍单 `source`，建议沿用 `hybrid`。
- weighted max 与现有行为一致。
- RRF 模式下 rank 越靠前分越高。
- 不同 source 分数为 null/NaN/负数时归一化安全。
- topK/poolLimit 边界。

### 查询编排

目标文件：

- `KnowledgeQueryServiceTest`

覆盖：

- `RAG_ES_ENABLED=false` 行为与现有测试一致。
- ES source enabled 时，strict vector minScore 下仍可返回 ES 命中。
- rerank 接收的是融合后的 ES + vector 候选池。
- reranker 异常降级不影响返回。
- query expansion variants 能被 vector source 使用；ES 是否使用 variants 按最终配置测试。

### 租户隔离

目标文件：

- `TenantIsolationTest`

覆盖：

- 租户 A 查询不到租户 B 的 ES chunk。
- 删除租户 A 文档不删除租户 B 同名 docId/index。

## 集成测试

默认不强制 Testcontainers，遵循仓库现有轻量测试风格。可分两层：

1. fake ES client 集成测试：验证 Spring 装配、配置开关、fallback。
2. 可选真实 ES profile：本地/CI 有 ES 时运行，验证 index mapping、match query、delete-by-query。

建议新增 Maven profile：

- `-Pes` 或 `-Pintegration-es`，默认 `mvn test` 不依赖外部 ES。

## 回归测试

必须运行：

- `mvn -pl knowledge-service test`
- `mvn -pl conversation-service -am test`
- `mvn -pl agent-service -am test`
- `mvn -pl eval-service -am test`

建议运行：

- `bash deploy/smoke-qdrant-rag.sh`
- 新增 `bash deploy/smoke-es-hybrid-rag.sh`，在 ES compose 启动后验证：
  - 上传示例文档。
  - vector-only 查询可命中。
  - ES keyword 查询可命中。
  - hybrid source 可出现。
  - rerank enabled 时仍返回结果。

## 异常场景

- ES 不可达：
  - `fail-fast=false` 时查询降级 vector-only。
  - `fail-fast=true` 时启动或查询失败，按配置预期。
- ES 写入成功、vector 写入失败：
  - 当前架构无法事务回滚 ES；需要记录错误并允许删除/重导入修复。
- vector 写入成功、ES 写入失败：
  - 查询仍可 vector 命中；指标暴露 ES index failure。
- 同名文档并发上传：
  - 现有 `DocumentService.upload` 没有显式锁；测试应揭示风险，首期可通过 idempotent ES doc id 和最终覆盖降低影响。
- embedding 维度切换：
  - 现有向量路由 fail-fast；ES 只存文本时不受影响，但最终结果可能只剩 ES 分支，需要监控。
- category 为空/空白/特殊字符：
  - ES filter 构造必须安全，不拼接原始 DSL 字符串。

## 验收标准

- 默认关闭 ES 时，所有现有 knowledge 测试通过。
- 开启 ES fake source 时，`KnowledgeQueryService` 能返回 ES-only、vector-only、hybrid 三种命中。
- 开启 rerank 时，融合候选被重排，topK 截断正确。
- 删除后 ES source 不再返回被删文档。
- 多租户与 category 隔离测试通过。
- 部署配置中 ES 环境变量有默认关闭路径，未部署 ES 不影响平台启动。
