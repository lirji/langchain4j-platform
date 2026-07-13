# IMPLEMENTATION PROGRESS — es-hybrid-rerank

方案：B（RetrievalSource SPI + HybridFusionService），融合默认 **RRF（ES 开启时）**，ES 关闭保持 weighted_max/现状。
用户已批准（2026-07-13）。分阶段实施，每阶段编译+测试+更新本文件。

## 阶段总览
- [x] 阶段 1：领域模型（RetrievalSource/Request/Hit、EsRagProperties、SegmentIndexer+Noop、EsSegmentDocument）
- [x] 阶段 2：核心逻辑（四个 RetrievalSource + HybridFusionService + KnowledgeQueryService 编排；非-ES 行为不回归）
- [x] 阶段 3：接口适配（ES client/EsRagConfig + ElasticsearchSegmentIndexer + EsKeywordRetrievalSource + DocumentService 挂点 + pom + application.yml）
- [x] 阶段 4：测试（fusion/es-source/indexer 单测 + KnowledgeQueryService ES 融合集成）
- [x] 阶段 5：部署（docker-compose.es.yml + es/Dockerfile smartcn + smoke + helm）+ 文档

## 进度日志

### 阶段 1（完成）
新增纯类型：`search/{RetrievalRequest,RetrievalHit,RetrievalSource}`、`es/{EsSegmentDocument,SegmentIndexer,NoopSegmentIndexer,EsRagProperties}`。`mvn compile` BUILD SUCCESS，零行为变更。

### 阶段 2（完成）
新增 `search/{Segments,FusionStrategy,VectorRetrievalSource,InMemoryKeywordRetrievalSource,GraphRetrievalSource,HybridFusionService}`；重写 `KnowledgeQueryService` 为编排器（保留全部构造器，内部构建三源；ES/策略经 @Autowired 注入）。**回归门：`mvn -pl knowledge-service test` 115/115 通过** —— 证明 weighted_max 融合与重构前逐字等价。

### 阶段 3（完成）
新增 `es/{EsGateway,EsSearchHit,ElasticsearchEsGateway(低层RestClient),ElasticsearchSegmentIndexer,EsRagConfig}` + `search/EsKeywordRetrievalSource`；`DocumentService` 用 `@Autowired(required=false)` setter 挂 `SegmentIndexer`（零构造器改动），upload/deleteInternal 双挂点；pom 加 `elasticsearch-java`（SB BOM 托管）；application.yml 加 `app.rag.es.*` + `fusion.*` + `ranking.es-weight`。编译 + 115/115 通过。

### 阶段 4（完成）
新增测试：`HybridFusionServiceTest`(6)、`ElasticsearchSegmentIndexerTest`(6)、`EsKeywordRetrievalSourceTest`(5)、`KnowledgeQueryServiceEsFusionTest`(2) + `FakeEsGateway`。**`mvn -pl knowledge-service test` 134/134 通过（+19）。**

### 阶段 5（完成）
`deploy/es/Dockerfile`(ES 8.15.3 + analysis-smartcn)、`deploy/docker-compose.es.yml`(ES overlay，开 ES + rrf)、`deploy/smoke-es-hybrid-rag.sh`、`deploy/helm/platform/values.yaml`(RAG_ES_* 默认关)、`docs/对话与检索/es-hybrid-rerank.md`。`docker compose config` 合并解析 OK。

## Codex 独立审查后修复（round 2，2026-07-13）
Codex（`/codex-review`）抓到 2 个真 bug + 稳健性/清理项，已修 #5/#7/#3/#2 并补测（142 测试全绿，+8）：
- **#5 灰度 RRF 翻转**：`FusionStrategy.effectiveDefault(configured, esEnabled, esQueryEnabled)`——只有 ES 真正参与查询才翻 RRF；`KnowledgeQueryService` 注入 `es.query-enabled`。测试 `FusionStrategyTest`（含"只写不查保持 weighted_max"）。
- **#7 RRF 同源重复计分**：`HybridFusionService.fuseRrf` 源内先按 mergeKey 去重取最佳分再排名。测试 `HybridFusionServiceTest.rrf_dedupsSameSourceDuplicateChunk`。
- **#3 建索引耦合启动 + 400 全吞**：`ElasticsearchSegmentIndexer` 惰性 ensureIndex（首次写、一次）；`EsRagConfig` 去掉 bean 创建时 eager 调用；`ElasticsearchEsGateway` 建索引 400 只吞 `resource_already_exists`，其余带响应体上抛（smartcn 缺失可暴露）。测试 `index_ensuresIndexLazilyOnceAcrossCalls`。
- **#2 死配置**：删 `fallback-to-in-memory`（props/yaml/doc）；降级实际靠"查失败返回空 + 内存关键词独立源"。
- **#9 隔离测试补强**：`FakeEsGateway` 记录 search 的 tenant/category，`retrieve_passesTenantAndCategoryToGateway` 断言下推。
- 判断题保留：#1 source=`es`（更可观测，已在计划注明偏离）；#4 bulk 不强制 refresh（生产吞吐，smoke 用 sleep）；#6 fail-fast 孤儿窗口（默认 best-effort 无风险，设计一致）；#10 依赖 footprint（小事）。

## 真机 ES 验证（进行中）
`docker compose -f base -f rag-full -f es up --build elasticsearch knowledge-service` → **ES 健康、smartcn 分词正确**（`退款政策是什么`→`退款/政策/是/什么`）。遇本机 mysql 3306 端口冲突（已知坑，见 memory local-full-stack-run），knowledge-service 健康等待中。

## 验收（对照 FINAL_PLAN）
- [x] ES disabled 时现有行为和测试不变（134 绿，含原 115）
- [x] fusion 去重/weighted_max/RRF 单测覆盖
- [x] ES source tenant/category、归一、mergeKey 对齐单测
- [x] ES indexer 幂等/跳过/fail-fast/best-effort 单测
- [x] rerank 接收融合后候选（编排未动 rerank 调用）
- [x] 部署默认不强依赖 ES；overlay 一键开启 + 中文 analyzer
- [x] 文档含 enable/gray/rollback/迁移限制
- [ ] 真实 ES smoke（`bash deploy/smoke-es-hybrid-rag.sh`）—— 需 Docker build ES 镜像，留待部署环境执行
- [ ] conversation/agent/eval 回归 —— 协议 DTO 未改，编译不依赖 knowledge 内部，理论无影响；建议部署前各跑一次 `-am test`
