# ES 全文混排 + rerank（es-hybrid-rerank）

在原有「向量 + 内存关键词 + 图谱」混合检索上，新增 **Elasticsearch 全文（BM25）召回**作为一个可插拔检索源，与向量召回融合后进入既有 rerank。**默认开启**（已并入基础 compose、代码默认 `RAG_ES_ENABLED=true`）；未部署/不可达 ES 也不影响启动与查询——ES 客户端惰性连接、索引惰性创建、查询期 ES 源失败自动降级返回空（其余源照常）。要关闭：`RAG_ES_ENABLED=false`。

## 架构

`KnowledgeQueryService` 重构为**编排器**：查询扩展 → 收集各启用 `RetrievalSource`（`vector` / `keyword`(内存) / `es` / `graph`）→ `HybridFusionService` 融合 → `Reranker` 重排。

- 检索源 SPI：`search/RetrievalSource`（`VectorRetrievalSource`、`InMemoryKeywordRetrievalSource`、`EsKeywordRetrievalSource`、`GraphRetrievalSource`）。
- 融合：`search/HybridFusionService`，两种策略见下。
- ES 写入：`DocumentService.upload/deleteInternal` 挂 `es/SegmentIndexer`（默认 `NoopSegmentIndexer`，开启后 `ElasticsearchSegmentIndexer`），与向量写入同一批最终 chunk。
- ES 客户端：`es/ElasticsearchEsGateway`（低层 RestClient），窄端口 `es/EsGateway` 便于 fake 单测。

## 融合策略（关键）

| 策略 | 说明 | 默认 |
|---|---|---|
| `weighted_max` | 同 chunk 跨源取加权分 max，标 `hybrid`；图谱按自身 id 独立。**复刻重构前语义** | ES 关闭时 |
| `rrf` | 倒数排名融合 `Σ 1/(k+rank)`，只看名次、**免疫 BM25/余弦量纲差** | **ES 开启时** |

> BM25 分数无上界，与向量余弦 [0,1] 不可直接比较。所以 ES 开启默认走 `rrf`；若坚持 `weighted_max`，`EsKeywordRetrievalSource` 会按本次查询最高分把 BM25 归一到 [0,1]（`RAG_ES_NORMALIZE_SCORE`）。`app.rag.fusion.strategy` 留空时有效默认由代码计算（ES 开→rrf，关→weighted_max）。

## 配置（`app.rag.*`）

| env | 默认 | 说明 |
|---|---|---|
| `RAG_ES_ENABLED` | `true` | 总开关（默认开）；设 `false` 时索引器为 Noop、不注册 ES 检索源 |
| `RAG_ES_INDEX_ENABLED` / `RAG_ES_QUERY_ENABLED` | `true` | 受总开关门控；灰度可只写不查 |
| `RAG_ES_URIS` | `http://localhost:9200` | 逗号分隔多节点 |
| `RAG_ES_USERNAME`/`PASSWORD` / `RAG_ES_API_KEY` | 空 | 二选一鉴权 |
| `RAG_ES_INDEX_NAME` | `knowledge_segments_text` | 全文索引名 |
| `RAG_ES_ANALYZER` | `smartcn` | 中文分析器；**不可用 standard**（按单字切，召回弱）。可选 `ik_smart`/`ik_max_word`（需装 analysis-ik） |
| `RAG_ES_NORMALIZE_SCORE` | `true` | weighted_max 下归一 BM25；rrf 下忽略 |
| `RAG_ES_FAIL_FAST` | `false` | ES 写失败是否让上传失败（默认 best-effort） |
| `RAG_FUSION_STRATEGY` | 空 | `weighted_max`/`rrf`；空=有效默认 |
| `RAG_FUSION_RRF_K` | `60` | RRF 常数 |
| `RAG_RANKING_ES_WEIGHT` | `1.0` | ES 源权重（weighted_max 下生效） |

## 开启（本地）

ES 混排现为**默认**，无需任何开关——基础 `deploy/docker-compose.yml` 已含 `elasticsearch`+`kibana`
服务，且 knowledge-service 默认 `RAG_ES_ENABLED=true` + nomic 语义 embedding。ES 已装中文分析器插件
（`deploy/es/Dockerfile` 内 `elasticsearch-plugin install analysis-smartcn`）。

```bash
# 一键（推荐）：ES/Kibana + nomic + RRF 已是默认；首次需 --all --build 构建 ES 镜像并重建全栈
./deploy/start-local.sh --all --build
# 前置：宿主机  ollama pull nomic-embed-text

# 或直接 compose（基础文件即含 ES/Kibana，无需再叠加 override）
docker compose -f deploy/docker-compose.yml up -d --build elasticsearch knowledge-service

# 历史数据回灌（registry 不存全文，ES 需重新索引；向量在 qdrant 不受影响）
bash deploy/seed-kb.sh

# 冒烟
bash deploy/smoke-es-hybrid-rag.sh
```

> `--es` 开关已弃用（保留为兼容 no-op）；`docker-compose.es.yml` / `docker-compose.rag-full.yml`
> 两个 override 现已冗余（设置并入基础 compose），仅保留供冒烟脚本显式分层使用。
> 要退回零依赖：`export RAG_ES_ENABLED=false RAG_EMBEDDING_PROVIDER=hash`。

**看 ES 里的数据（Kibana）**：es overlay 含 Kibana，`http://localhost:5601` → **Dev Tools** 跑 DSL、**Discover** 浏览文档：
```
GET knowledge_segments_text/_search
GET knowledge_segments_text/_count
GET knowledge_segments_text/_analyze
{ "analyzer": "smartcn", "text": "退款政策是什么" }
```
Kibana 较重(~1G RAM)，不需要时 `docker compose stop kibana`。

完整的 Kibana DSL + curl 查询语句见 [es-kibana-查询速查.md](es-kibana-查询速查.md)。

## 灰度与回滚

**灰度**：① 部署 ES 但 `RAG_ES_ENABLED=false`；② `RAG_ES_INDEX_ENABLED=true, RAG_ES_QUERY_ENABLED=false` 只验证写入——此阶段**融合仍保持 weighted_max、不翻 RRF**（有效默认只在 ES 真正参与查询时才翻）；③ 开 `RAG_ES_QUERY_ENABLED=true`；④ 观察 `rrf` 检索质量；⑤ 按租户扩大。

> 降级是**自动**的：ES 查询失败时 `EsKeywordRetrievalSource` 返回空，内存关键词源（`RAG_HYBRID_ENABLED`）作为独立源天然兜底，无需额外开关。建索引也是惰性的（首次写时），ES 启动期不可达不会拖垮服务。

**回滚**：`RAG_ES_ENABLED=false` 重启即回到 向量 + 内存关键词 + 图谱 + rerank，与重构前逐字等价（现有 `KnowledgeQueryServiceTest` 为不回归基线）。保留 ES index 便于排查；排序质量下降先回 `RAG_FUSION_STRATEGY=weighted_max` 再关 ES 查询。

## 限制

- **历史数据不自动迁移**：registry 只存元数据，开启 ES 后需按上传 API 重灌（`seed-kb.sh` 或重新上传）。
- ES 内存较重（本地默认给 512m）；生产按需调 `ES_JAVA_OPTS`。
- 图片多模态 RAG 不在本特性范围内。
