# 向量检索原理：各库的 ANN 支持与实现 + RRF 融合

本文讲两件在 `knowledge-service` 检索链路里最容易「知其然不知其所以然」的事：

1. **ANN（Approximate Nearest Neighbor，近似最近邻）**——平台支持的 6 个可插拔向量库各自怎么做近邻检索、建什么索引、暴露哪些旋钮；
2. **RRF（Reciprocal Rank Fusion，倒数名次融合）**——多路召回结果如何合并成一个排序，它到底在算什么、平台哪几层用到它。

> 权威接线以代码为准：向量库装配 `knowledge-service/.../KnowledgeEmbeddingConfig.java` + `store/*CollectionManager`；
> 跨源融合 `store` 无关的 `search/HybridFusionService.java`。存储凭据/端口见[数据存储清单](../参考/databases.md) §三，
> 查询打分/排序/rerank 全流程见该文 §八，本文与它互补（这里讲「原理」，那里讲「链路」）。

---

## 1. 为什么需要 ANN

向量检索的本质：把 query 也编码成一个向量，在向量空间里找与它**距离最近的 topK 个** chunk。

- **精确 kNN**（brute-force）要拿 query 和库里**全部 N 条**逐一算距离，复杂度 `O(N·d)`（d=维度）。几千条无所谓，百万级、每次查询几百毫秒起，扛不住。
- **ANN** 用预建的索引结构（近邻图 / 倒排簇 / 量化码本）**只探一小部分候选**，用**极小的召回率损失**换来数量级的速度提升。
- 三个此消彼长的量，调参就是在它们之间取舍：

  | 量 | 含义 | 主要旋钮 |
  |---|---|---|
  | **召回率 recall** | ANN 结果与「真正 topK」的重合比例 | 查询期「探多少候选」（`ef_search` / `nprobe`） |
  | **延迟 latency** | 单次查询耗时 | 同上，反向 |
  | **内存 / 构建成本** | 索引占用与建索引时间 | 建索引期参数（`M` / `ef_construct` / `lists`）、量化 |

- **距离度量**：常见 cosine（余弦）、inner product（内积）、L2（欧氏）。本平台各库**默认 cosine**，且 embedding 通常已归一化（归一化后 cosine 与内积等价）。换度量要和 embedding 的训练目标匹配，否则「最近邻」在语义上就是错的。

---

## 2. 主流 ANN 索引家族（理解各库前的公共底座）

各家向量库的「实现」几乎都是下面几类算法的组合或封装：

- **FLAT / brute-force（暴力精确，非 ANN）**：不建索引，遍历全部算距离。**100% 召回、`O(N)`**。只适合小库、测试、或对召回零容忍的小规模场景。
- **IVF（倒排文件，IVFFlat / IVF_SQ8 …）**：先用 k-means 把向量聚成 `lists` 个簇（各簇一个质心）；查询时只比对与 query 最近的 `nprobe` 个簇内的向量。
  - `lists` 越大 → 每簇越小、查得越快，但边界样本可能漏（召回略降）；`nprobe` 越大 → 召回越高、越慢。**建索引前需要有一批数据训练质心**。
- **HNSW（Hierarchical Navigable Small World，分层可导航小世界图）**：把向量组织成多层近邻图，查询从稀疏顶层贪心逼近、逐层下探到底层精修。**高召回 + 低延迟，是目前最通用的默认**；代价是**内存占用大、构建慢、增量插入成本高**。三个旋钮：
  - `M`：每个节点的邻边数（图的度），大 → 召回高、内存大；
  - `ef_construction`：建图时的候选宽度，大 → 图质量高、建得慢；
  - `ef_search`：**查询期**的探索宽度，大 → 召回高、慢（唯一能在查询时调召回/延迟的旋钮）。
- **量化（PQ / SQ）/ DiskANN**：把向量压缩（乘积量化、标量量化）以省内存，或把图放磁盘（DiskANN），面向**超大规模**。省内存、略降精度。

**一句话选型**：小库用 FLAT（精确即可）；通用场景用 HNSW；超大规模 + 内存受限用 IVF+量化 / DiskANN。

---

## 3. 各向量库在本平台的 ANN 支持与实现

平台用「一个接口 + `@ConditionalOnProperty` 多实现」把 6 个后端做成**换 provider 不改代码**（`RAG_VECTOR_STORE_PROVIDER` 一个开关，见 [databases.md §三](../参考/databases.md)）。下表是每个 provider **在本平台实际建的索引 / ANN 行为**（不是它「理论上支持什么」，而是我们的 `CollectionManager` 真正接线出来的那一档）：

| provider | 平台建的索引 / ANN | 默认距离 | 平台暴露的旋钮 | 实现类 |
|---|---|---|---|---|
| `in-memory`（默认零依赖回退） | **无索引，暴力精确**（遍历全部算 cosine） | cosine | 无 | langchain4j `InMemoryEmbeddingStore` |
| `qdrant`（**平台默认**） | **HNSW**（Qdrant 原生，用其默认 `m=16 / ef_construct=100`） | Cosine | `tenantId`/`category` 的 payload keyword 索引（过滤加速） | `QdrantClientCollectionManager` + `QdrantEmbeddingStore` |
| `pgvector`（PostgreSQL 扩展） | **IVFFlat**（`use-index=true` 时建，`lists=index-list-size`）；`use-index=false` 退化为顺序扫描（精确） | cosine ops | `use-index`、`index-list-size`（IVF lists，默认 100）、`search-mode`（VECTOR/FULL_TEXT/HYBRID）、`text-search-config`、`rrf-k` | `PgVectorCollectionManager` + `PgVectorEmbeddingStore` |
| `milvus` | **`index-type` 决定；默认 `FLAT`（＝精确暴力，非 ANN！）**，可配 `IVF_FLAT`/`IVF_SQ8`/`HNSW`/`DISKANN` 等 | COSINE | `index-type`（默认 FLAT）、`metric-type`（默认 COSINE） | `MilvusCollectionManager` + `MilvusEmbeddingStore` |
| `chroma` | **HNSW**（Chroma 服务端用 hnswlib，平台不传索引参数、维度自动推导） | Chroma 侧默认 | 无（`base-url`/`tenant`/`database`） | `ChromaCollectionManager` + `ChromaEmbeddingStore` |
| `doris`（走 MySQL 协议 JDBC） | **HNSW**（自研 store 显式建 `INDEX ... USING ANN PROPERTIES("index_type"="hnsw",...)`，检索用 `cosine_distance_approximate`/`l2_distance_approximate` 近似函数） | cosine（可 l2） | `metric`（cosine/l2）、`buckets`（HASH 分桶，默认 4）、`create-table` | 自研 `DorisEmbeddingStore`（HNSW ANN） |

**逐条要点：**

- **`in-memory`**：精确、`O(N)`、进程内。测试与小库最省心；数据不持久，重启即失。
- **`qdrant`（默认）**：HNSW 全托管，平台只设维度 + Cosine，其余走 Qdrant 默认 HNSW。另建 `tenantId`/`category` 的 payload 索引 → 带租户/分类过滤时更快（`collection-per-tenant` 隔离下 collection 名 `<base>_<tenant>`）。
- **`pgvector`**：langchain4j 的 pgvector store 建 **IVFFlat**（`lists=index-list-size`）。⚠️ **两个注意**：① IVFFlat 需要先有数据才能训练质心，空表建索引意义有限——大批量灌完再建/重建索引召回更稳；② pgvector **本身也支持 HNSW**，但当前 langchain4j 集成走的是 IVFFlat 这一档。它还有一个**库内混排**能力（`search-mode=HYBRID` 时用自己的 `rrf-k` 把向量+全文两路合并，见 §5 的「两层 RRF」）。
- **`milvus`**：**默认 `FLAT` 是精确暴力搜索，不是 ANN**——想要 ANN 性能必须显式把 `RAG_MILVUS_INDEX_TYPE` 设成 `HNSW` 或 `IVF_*`。这是最容易「以为开了 ANN 其实在全表扫」的坑。
- **`chroma`**：HNSW 由 Chroma 服务端管，平台只给 collection 名，维度它自己推。托管省事，旋钮暴露少。
- **`doris`**：自研 `DorisEmbeddingStore`，建表时显式声明 HNSW ANN 索引并用 `*_approximate` 距离函数查询；`buckets` 是 Doris 的分布式分桶数。适合已有 Doris 数仓、想把向量检索并进去的场景。

**切换向量库的三个通用坑**（详见 [databases.md §三「快速切换向量库」](../参考/databases.md)）：

1. **数据不迁移**——换 provider 得到空库，必须**重新 ingest**；
2. **外部后端要先起来**（in-memory 除外），否则 knowledge-service 启动即失败；
3. **维度守卫**——换 embedding（hash 64 维 ↔ nomic 768 维等）维度变了必须重建库，`DimensionMismatchException` 会拦不匹配；`shared` 隔离模式仅 `in-memory`/`qdrant` 支持。

---

## 4. RRF（Reciprocal Rank Fusion）：它在算什么

### 4.1 要解决的问题

平台的检索是**多路并行召回**（向量 cosine 相似度、ES/关键词 BM25 `_score`、GraphRAG 三元组命中……，见 [databases.md §八](../参考/databases.md)）。这些分数**量纲完全不可比**：cosine 在 [0,1]、BM25 可能是几到几十、图谱是另一套。若直接「加权相加」或「取 max」，结果会被**量纲最大的那一路主导**，且权重极难调对。

### 4.2 RRF 的思路：只看名次，不看原始分

RRF 把每一路的原始分**丢掉，只保留名次（rank）**。每个文档 `d` 的最终分是它在各路里名次倒数的累加：

```text
score(d) = Σ  1 / (k + rank_s(d))
          s∈命中它的源
```

- `rank_s(d)`：文档 d 在源 s 的结果里排第几（1 起）；某源没召回 d 就不贡献。
- `k`：平滑常数（本平台默认 **60**），**压低头部名次的过度主导**——k 越大，第 1 名和第 2 名的贡献差越小，越「民主」；k 越小越「赢家通吃」。

### 4.3 一个算例

query 召回两路，各自的名次如下（k=60）：

| 文档 | 向量源 rank | ES 源 rank | RRF 分 = Σ 1/(60+rank) |
|---|---|---|---|
| A | 1 | 3 | 1/61 + 1/63 ≈ 0.01639 + 0.01587 = **0.03226** |
| B | 2 | 1 | 1/62 + 1/61 ≈ 0.01613 + 0.01639 = **0.03252** |
| C | 3 | — | 1/63 ≈ **0.01587** |
| D | — | 2 | 1/62 ≈ **0.01613** |

排序：**B > A > D > C**。注意 B 虽然在向量源只排第 2，但两路都命中（**共识加成**）→ 压过只在向量源第 1 的 A。这正是 RRF 想要的：**被多路认可的文档更可信**。

### 4.4 性质小结

- ✅ **量纲无关**：免疫 cosine/BM25 尺度差，无需归一化、无需调权重、无需训练。
- ✅ **鲁棒、共识友好**：多源命中天然加分；单源异常值不易霸榜。
- ⚠️ **丢失绝对相关度**：最终分是「名次分」，范围恒在 `~0.01~0.05`（见下），**不携带「有多相关」的信息**，因此**不能拿它当相关度阈值**。

---

## 5. RRF 在本平台的使用

### 5.1 两层 RRF，别混淆

| 层级 | 在哪 | 融合谁 | 开关 |
|---|---|---|---|
| **跨源 RRF（主路径）** | `HybridFusionService.fuseRrf`，`rrf-k=60` | **向量 / 关键词 / ES / 图谱** 四路之间 | `RAG_FUSION_STRATEGY`，ES 参与查询时**有效默认即 RRF**（`FusionStrategy.effectiveDefault`） |
| **pgvector 库内 RRF** | `PgVectorEmbeddingStore` 内部，平台传 `rrf-k` | pgvector **单库内**的「向量 + 全文」两路 | 仅 `RAG_PGVECTOR_SEARCH_MODE=HYBRID` 时 |

绝大多数情况说的是**第一层**（跨源）。第二层只有用 pgvector 且开了它的 HYBRID 模式才出现，发生在「一个向量库内部」，与跨源那层是两码事。

### 5.2 融合策略：RRF vs weighted_max

`FusionStrategy.effectiveDefault(prop, esEnabled, esQueryEnabled)`：

- **ES 真正参与查询 → `RRF`**：免疫量纲差（因为 ES BM25 与向量 cosine 尺度不可比）。
- **ES 关闭 / 只写不查 → `weighted_max`**：`LinkedHashMap` 合并、跨源同 chunk 取 max 分并标 `hybrid`，**保留原始分量纲**（此时只有同尺度的向量为主，可比）。

### 5.3 关键后果（一定要和调参连起来看）

- **RRF 下 `Hit.score = Σ 1/(60+rank) ≈ 0.01~0.05` 是名次分，不是相关度** → **别拿它卡阈值**；且 **rerank 不改写这个显示分**（rerank 只改顺序与截断）。
- 想设「相关度地板」用 **`RAG_RERANK_MIN_SCORE`**（rerank 层，判官打的 0..1 相关分），**不是** `RAG_QUERY_MIN_SCORE`（那个只作用于向量源、拦不住 ES/关键词/图谱）。这三个阈值的完整反直觉机制见 [databases.md §8.4](../参考/databases.md)。
- 想看「原始相关分」而非名次分：把 ES 关掉退回 `weighted_max`，或直接看 rerank 后的判官分（需开 rerank）。

### 5.4 开关速查

```bash
RAG_FUSION_STRATEGY=rrf        # 或 weighted_max；留空=按 ES 是否参与自动选
RAG_FUSION_RRF_K=60            # RRF 平滑常数，越大越"民主"
# pgvector 库内混排（仅该 provider）：
RAG_PGVECTOR_SEARCH_MODE=HYBRID
RAG_PGVECTOR_RRF_K=60
```

---

## 6. 选型建议（速查）

| 场景 | 建议 provider | 理由 |
|---|---|---|
| 起步 / 单测 / 小库 | `in-memory` | 精确、零依赖；数据不持久 |
| 单机生产默认 | `qdrant`（平台默认） | HNSW 全托管、`collection-per-tenant` 隔离、payload 过滤索引 |
| 已有 PostgreSQL、想少一个组件 | `pgvector` | 复用 PG；IVFFlat；可用库内 HYBRID 混排 |
| 超大规模 / 需要多种索引类型 | `milvus` | 索引类型最全；**记得从默认 FLAT 换成 HNSW/IVF** |
| 想要托管、快速起 | `chroma` | HNSW 服务端托管，接入简单 |
| 已有 Doris 数仓 | `doris` | 把向量检索并进数仓；HNSW ANN |

---

## 相关文档

- [数据存储清单](../参考/databases.md)：§三 向量库凭据与切换、§七 知识入库写入链路、§八 查询读取链路（打分/排序/rerank，与本文 §5 互补）。
- [RAG 接入指南](rag-guide.md)：§4 向量库 provider 与租户隔离、§5 embedding provider、§6 四路混排。
- [ES 混排与重排](es-hybrid-rerank.md)：Elasticsearch BM25 分支与 rerank 的落地细节。
