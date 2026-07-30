# 简历指标本地评测报告

## 1. 结论

本轮在本地 Docker Compose 环境完成了 RAG、引用、NL2SQL、语义缓存和多 Agent DAG
评测。下表只列可以由当前原始数据解释的数字。

| 能力 | 样本与口径 | 实测结果 | 是否建议写入简历 |
| --- | --- | --- | --- |
| RAG | 200 条项目文档合成问题，文件级单相关文档，Top-10 | 向量单路 Recall@10 `80.0%`；四路 RRF `98.5%`，提升 `18.5` 个百分点 | 是，但必须说明是项目文档合成集 |
| RAG 排序 | 同一 200 条问题 | MRR `0.534 → 0.880` | 可作为补充 |
| 引用 | 50 个回答；引用 ID 是否存在于同问题 Top-10 候选 | 有效率 `35/39 = 89.7%`；回答覆盖率 `30/50 = 60.0%` | 可写“引用 ID 有效率”，不能写 faithfulness |
| NL2SQL | 150 条固定种子库业务问句；结果等价判定 | 最终执行成功率 `149/150 = 99.3%`；结果正确率 `143/150 = 95.3%` | 是；不能称首次成功率 |
| 语义缓存 | 30 组冷请求与完全相同问题的立即重复请求 | 冷均值 `2463.4 ms`、P95 `2878 ms`；热均值 `6.7 ms`、P95 `10 ms` | 可写“受控精确重复命中延迟” |
| Agent 并行 | 同一 6 节点任务，冷请求各重复 5 次 | 串行均值 `61.7 s`、并行均值 `39.4 s`，下降 `36.1%` | 是；样本较小，注明各 5 次 |
| Agent 质量闭环 | 20 个固定目标，外部确定性 Rubric | Replanner 关闭 `70%`，开启 `75%`；P95 `48.8 s → 140.3 s` | 不建议宣传为显著提升 |
| 支持租户数 | 本轮未做多租户容量压测 | 无可用数字 | 不应填写 |

本轮没有 `JINA_API_KEY`，因此没有实测 Jina Reranker。RAG 的提升只归因于
vector、内存关键词、Elasticsearch BM25、Graph 四路召回及 RRF 融合。

## 2. 环境与证据版本

| 项目 | 值 |
| --- | --- |
| 日期 | 2026-07-27 |
| Git commit | `f4b8f14`；工作区有未提交变更 |
| 机器 | Apple M1 Max，32 GiB，macOS 15.7.7，arm64 |
| 运行方式 | Docker 29.3.0 + 项目 Compose；统一经 edge `localhost:18080` |
| LLM 路由 | LiteLLM `chat-default`，实际使用项目当前 DeepSeek 配置 |
| Embedding | 本机 Ollama `nomic-embed-text` |
| 向量与全文存储 | Qdrant + Elasticsearch |
| 隔离租户 | `resume-bench` |
| Node.js | v24.12.0 |

原始结果位于 [`raw/`](raw/)，固定数据位于 [`datasets/`](datasets/)，执行脚本位于
[`scripts/`](scripts/)。报告没有删除失败样本。

## 3. 数据从哪里来

### 3.1 RAG

- 从仓库 `docs/` 选取 20 份项目文档，清单见
  [`rag-corpus-manifest.json`](datasets/rag-corpus-manifest.json)。
- 每份文档由 DeepSeek 基于正文生成 10 个问题，共 200 条，见
  [`rag-golden-200.json`](datasets/rag-golden-200.json)。
- 每条题目机械校验了问题非空、ID 唯一、源文件存在、标注文档名与入库
  `displayName` 一致，并保留来源与参考答案，见
  [`rag-cases-metadata.json`](datasets/rag-cases-metadata.json)。
- 这不是生产用户查询，也不是 200 条全部人工逐题审核的数据；准确说法是
  “基于项目文档生成并做来源校验的合成评测集”。
- 测试租户外还存在公共知识库文档。A/B 两组看到相同公共库，因此比较变量受控，但公共文档会形成固定噪声。

每题只标注一个相关文件，文件任意 chunk 命中均算成功。因此：

```text
Recall@10(case) = |相关文件 ∩ Top-10 文件| / 1
Macro Recall@10 = Σ Recall@10(case) / 200
```

在当前单相关文件标注下，Macro Recall@10 与 Hit@10 数值相同。它不是 chunk 级多相关
文档召回率。

### 3.2 NL2SQL

- 数据库快照来自
  `analytics-service/src/main/resources/db/nl2sql-demo.sql` 的 `tenantA` 固定种子数据。
- 测试集包含 15 类意图，每类 10 个自然语言改写，共 150 条，见
  [`nl2sql-golden-150.json`](datasets/nl2sql-golden-150.json)。
- 黄金值由固定种子数据计算并写入数据集，不由被测 LLM 判分。
- 结果判定会展开返回值、统一数值类型并允许等价 SQL；不要求生成 SQL 文本与黄金 SQL 相同。

```text
最终执行成功率 = HTTP 2xx 且 sql != null 的问题数 / 150
结果正确率 = 标准化返回结果与黄金结果等价的问题数 / 150
```

当前接口只返回最终 SQL，不暴露每次 attempt，无法可靠统计首次执行成功率。

### 3.3 多 Agent

- 质量集是 20 个项目领域复杂目标，见
  [`agent-goals-20.json`](datasets/agent-goals-20.json)。
- 每题在运行前固定 `required` 与 `forbidden` 词项。
- “Rubric 完成”要求最终答案包含全部必需项且不含禁用项，不使用被测模型自评分。
- 该规则可复现，但对中英文同义词敏感；它是严格的覆盖率代理，不等于人工业务验收。

### 3.4 缓存与引用

- 缓存题取自 RAG 评测问题。每组先删除测试租户 L1，再发冷请求，随后立即发送完全相同问题。
- 引用题取 RAG 元数据前 50 条。解析回答中的 `[doc=displayName#index]`，检查该 ID 是否存在于同问题
  单独执行的 `/rag/query` Top-10 中。
- 引用检查只能证明 ID 可回溯，不证明引用片段在语义上完整支持答案；`/chat` 与验证用
  `/rag/query` 还是两次独立检索，因此该指标是保守代理。

## 4. RAG 结果

### 4.1 对照配置

两组使用相同 20 文档、相同分块、`nomic-embed-text`、Top-K=10，并关闭 contextual
ingestion、query expansion 和 reranker。

| 组 | 召回源与融合 | Recall@10 | Precision@10 | MRR | 未命中 |
| --- | --- | ---: | ---: | ---: | ---: |
| 基线 | vector 单路，`weighted_max` | 80.0% | 26.0% | 0.534 | 40/200 |
| 增强 | vector + keyword + ES BM25 + graph，RRF | 98.5% | 45.8% | 0.880 | 3/200 |

增强组 Recall@10 提升 `18.5` 个百分点，未命中数从 40 降至 3。原始报告：

- [`rag-vector-full.json`](raw/rag-vector-full.json)
- [`rag-rrf-full.json`](raw/rag-rrf-full.json)

增强组仍未命中的用例是 `rag-027`、`rag-097`、`rag-105`。评测服务报告的
`wallDurationMs` 包含 429 限流后的等待时间，不用于接口 P95。

### 4.2 引用

50 个回答共产生 39 个引用标记，其中 35 个存在于验证候选集：

```text
引用 ID 有效率 = 35 / 39 = 89.7%
回答引用覆盖率 = 30 / 50 = 60.0%
```

4 个无效标记集中在 3 个回答，原始明细见
[`citation-validity.json`](raw/citation-validity.json)。不能把 89.7% 写成“引用事实准确率”；
后者需要人工或独立 Judge 对“答案陈述—引用片段”的蕴含关系评分。

## 5. NL2SQL 结果

采用新鲜 prompt 的 enum-schema 完整组作为主结果：

| 指标 | 结果 |
| --- | ---: |
| 最终执行成功 | 149/150 = 99.3% |
| 结果正确 | 143/150 = 95.3% |
| 护栏曾阻断 | 1/150 = 0.7% |
| 顺序请求 P50 | 1980 ms |
| 顺序请求 P95 | 2610 ms |

7 个未正确用例包括 1 个未生成 SQL，以及订单金额口径、Top 客户筛选和返回聚合值方面的错误。
逐题 SQL、返回 rows 与耗时见
[`nl2sql-enum-full.jsonl`](raw/nl2sql-enum-full.jsonl)，汇总见
[`nl2sql-enum-full-summary.json`](raw/nl2sql-enum-full-summary.json)。

basic-schema v2 结果为 150/150 最终执行成功、147/150 正确，但该轮复用了 LiteLLM
精确缓存，而且列注释本身仍含枚举信息，不是独立且干净的 Schema A/B。enum 组没有复现出提升，
所以不能写“引入 Schema 探测后提升 19 个百分点”。

`2610 ms` 是本机通过网关逐条请求的最近秩 P95，不是并发容量压测 P95。若简历强调性能，
应另用固定并发、时长和错误率的 k6/JMeter 测试。

## 6. 多 Agent 结果

### 6.1 Kahn 分层并行

关闭 Replanner，给每次请求附加唯一评测标识以规避 LiteLLM 精确缓存：

| 拓扑 | 样本 | 平均 | P50 | P95 |
| --- | ---: | ---: | ---: | ---: |
| 6 节点强制线性 | 5 | 61.704 s | 64.296 s | 75.901 s |
| 第一层 4 并行、第二层 2 并行 | 5 | 39.415 s | 41.170 s | 43.946 s |

```text
平均耗时下降 = (61.704 - 39.415) / 61.704 = 36.1%
```

原始结果：

- [`agent-linear-cold.jsonl`](raw/agent-linear-cold.jsonl)
- [`agent-parallel-cold.jsonl`](raw/agent-parallel-cold.jsonl)

这是“人为线性依赖”与“可并行 DAG”的拓扑对照，不是历史生产版本前后对比。各组只有 5 次，
适合作为本地工程证据，不适合宣称稳定生产 SLA。

### 6.2 Critic/Replanner 质量闭环

| 配置 | HTTP 成功 | Rubric 完成率 | 重规划 | 平均 | P95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Replanner 关闭 | 20/20 | 14/20 = 70% | 0 | 41.985 s | 48.829 s |
| Replanner 开启 | 19/20 | 15/20 = 75% | 5/20 | 63.290 s | 140.327 s |

开启后仅提升 `5` 个百分点，同时：

- 发生 1 次 HTTP 500，并保留在 20 题分母中；
- 5 个任务触发第二轮；
- P95 增加到 140.3 秒；
- Critic 接受率 85%，与外部 Rubric 75% 不一致。

因此本轮不能支撑“复杂任务完成率由 68% 提升至 82%”。更诚实的结论是：
Replanner 在该小样本上带来轻微严格覆盖率提升，但显著增加尾延迟并暴露一次稳定性失败。

原始结果：

- [`agent-quality-no-replan.jsonl`](raw/agent-quality-no-replan.jsonl)
- [`agent-quality-replan.jsonl`](raw/agent-quality-replan.jsonl)

## 7. 语义缓存结果

30 组请求全部返回 HTTP 200：

| 链路 | 平均 | P50 | P95 |
| --- | ---: | ---: | ---: |
| 冷链路 | 2463.4 ms | 2481 ms | 2878 ms |
| 完全相同问题立即重复 | 6.7 ms | 6 ms | 10 ms |

```text
平均耗时降低 = 1 - 6.7 / 2463.4 = 99.7%
加速倍数 = 2463.4 / 6.7 = 369.5x
```

为避免下游 LiteLLM L2 把冷链路伪装成热链路，执行前删除了本地 Redis 中 425 个
`litellm.cache:*` 临时键；这些是可自动再生的本地测试缓存。本轮容器日志未输出预期的
`semantic cache hit` debug 行，因此不能把 30/30 写成生产观测意义上的“缓存命中率”；
可以写由测试步骤保证的“30 组精确重复请求命中后的时延”。原始报告见
[`cache-latency.json`](raw/cache-latency.json)。

当前 L1 使用 64 维 hash embedder，测试只覆盖完全相同问题，不代表同义改写的语义命中能力。

## 8. 推荐简历表述

推荐使用以下可解释版本：

> **企业级 RAG：**支持多格式文档解析与多策略分块，将内容写入 Qdrant 和 Elasticsearch；
> 基于可插拔 RetrievalSource 实现向量、BM25、关键词和知识图谱四路召回及 RRF 融合。
> 在 200 条基于项目文档生成并做来源校验的评测集上，Recall@10 从 80.0% 提升至 98.5%，
> MRR 从 0.534 提升至 0.880；50 条回答的引用 ID 有效率为 89.7%。

> **多 Agent 编排：**实现基于 Kahn 拓扑分层的 DAG 引擎，支持环检测、同层并行、
> Synthesis 汇总及 Critic/Replanner 质量闭环；在 6 节点冷请求对照中，并行拓扑平均耗时
> 由 61.7 秒降至 39.4 秒，下降 36.1%（每组 5 次）。

> **NL2SQL / ChatBI：**实现自然语言生成 SQL、只读执行和结果解读，并通过 SELECT 校验、
> 表白名单、强制 LIMIT、执行超时和租户条件构建安全护栏；在基于固定种子库构造的
> 150 条业务问数集上，最终 SQL 执行成功率 99.3%，结果正确率 95.3%。

缓存可作为第四条或面试补充：

> **语义缓存：**实现租户隔离的 Redis L1 语义缓存；在 30 组完全相同问题的受控重复测试中，
> 命中后平均响应时间 6.7 ms、P95 10 ms，较冷链路平均 2.46 秒降低 99.7%。

## 9. 不能写的数字与下一步

- 不写 “Jina Reranker 提升”：本轮没有 Jina 凭据，也未启用 reranker。
- 不写 “NL2SQL 首次成功率”：当前接口没有 attempt 级证据。
- 不写 “Schema 探测提升 19 个百分点”：本轮对照未复现，且 basic 组受缓存与列注释干扰。
- 不写 “Agent 完成率 68%→82%”：实测为 70%→75%，且开启组有一次 500。
- 不写 “支持 N 个租户”：没有执行带目标 QPS、活跃比例、隔离负向用例和资源水位的容量测试。
- 不把顺序样本 P95 描述成生产并发 SLA。

下一轮要提高证据强度，应优先：

1. 由项目作者人工盲审 RAG 200 题及答案来源，冻结为 v1 黄金集。
2. 为引用增加片段级蕴含 Judge，并抽样人工复核。
3. 给 NL2SQL 响应或结构化日志增加 attempt 列表，区分首次与修复后成功。
4. Agent 质量集扩展到至少 50～100 题，采用同义词归一化或结构化 Rubric，并重复多随机种子。
5. 使用 k6 在固定并发、时长和错误率约束下补充生产式 P95。
6. 单独设计多租户容量和跨租户负向测试后，再声明“验证支持的租户数”。
