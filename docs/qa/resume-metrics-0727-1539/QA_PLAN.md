# 简历指标本地评测计划

## 1. 目标

在本机对以下能力建立可复现的测试集、对照组和原始报告，最后据实生成简历描述：

1. 企业级 RAG：Recall@10、引用有效率、缓存命中后的响应时间。
2. 多 Agent DAG：6 节点线性执行与分层并行执行耗时、外部确定性 Rubric 完成率。
3. NL2SQL：最终 SQL 执行成功率、结果正确率、Schema 枚举增强前后差异。

所有数据只打 `localhost`，不访问生产环境。

## 2. 执行前环境

| 项目 | 现场 |
| --- | --- |
| Git commit | `f4b8f14`，工作区有未提交修改 |
| Compose | 当前未运行 |
| 网关 | 计划使用 `http://localhost:18080` |
| Ollama | 已安装且模型已下载，服务当前未启动 |
| 云模型 | shell 中 `DEEPSEEK_API_KEY` 已设置，会产生真实费用 |
| Jina | `JINA_API_KEY` 未设置，本轮不能做 Jina Reranker 实测 |
| 压测工具 | 无 k6；使用 Node.js 基准脚本计算平均值和 P95 |
| 鉴权 | edge 默认 Casdoor `only`；本地基准需临时以 `dual` 启动 edge，使用仓库 dev key |

本次只新增测试数据、基准脚本和报告，不修改业务实现。任何临时配置都通过进程/Compose 环境变量传入，不覆盖用户现有 `.env`。

## 3. 总体执行策略

先跑小样本 pilot，确认端点、模型、数据和断言正确，再跑完整样本，避免在错误配置上消耗大量模型调用。

| 阶段 | RAG | Agent | NL2SQL |
| --- | ---: | ---: | ---: |
| Pilot | 20 条 | 3 个任务 × 2 拓扑 | 15 条 |
| 完整 | 200 条 | 20 个目标，每个 6 节点 | 150 条 |

完整评测预计会触发数百次 DeepSeek 调用。具体次数受 Agent Replan 和 SQL 自修复次数影响，报告中记录 LiteLLM 调用量或应用侧运行次数，不承诺固定费用。

## 4. RAG 评测

### 4.1 测试集来源

从仓库 `docs/` 中选择约 20 份已核对的项目文档作为固定语料，每份构造约 10 个问题，共 200 条：

- 事实型：配置默认值、端点、字段、组件职责。
- 同义改写型：不直接复制标题或原文。
- 多跳型：答案需要同一文档的多个段落。
- 易混淆型：不同文档含相近术语。

每条保留：

```json
{
  "id": "rag-001",
  "question": "...",
  "relevantDocIds": ["rag-guide.md"],
  "source": "docs/对话与检索/rag-guide.md",
  "generation": "deepseek-generated-source-validated"
}
```

测试集来自项目文档，不冒充生产用户问题。生成后做以下机械校验：

- `source` 文件存在。
- `relevantDocIds` 与入库 `displayName` 一致。
- 问题非空、ID 唯一、答案可由对应文档支持。
- 生成结果只做来源、ID 和文件对应关系的机械校验；不能描述成全量人工审核。

### 4.2 对照组

使用相同文档、相同 chunking、相同 `nomic-embed-text` embedding 和相同 Top-K：

| 组 | 配置 | 含义 |
| --- | --- | --- |
| A：向量基线 | vector 开；keyword/ES/graph/query-expansion/rerank 关 | 单路向量召回 |
| B：多路 RRF | vector + keyword + ES BM25 + graph，`fusion=rrf`；query-expansion/rerank 关 | 隔离四路召回和 RRF 的贡献 |
| C：完整增强（可选） | B + LLM query expansion + LLM rerank | 会显著增加调用数；单列结果，不与 Jina 混称 |

由于没有 `JINA_API_KEY`，本轮默认不执行 Jina 对照。若用户提供 Jina 测试凭据，再增加 D 组 `RAG_RERANK_TYPE=jina`。

### 4.3 指标

```text
Recall@10(case) = Top-10 命中的相关文档数 / 该问题标注的相关文档数
Macro Recall@10 = Σ Recall@10(case) / 200
```

同时保存 Precision@10、MRR、Hit@10 和逐 case 耗时。

引用指标在 50 条代表性问题上走 `/chat`：

```text
引用有效率 = 回答中能在同次 `/rag/query` 来源集合找到的 [doc=ID] 数 / 回答引用总数
引用覆盖率 = 至少给出 1 个有效引用的回答数 / 应引用回答数
```

“引用有效率”只证明 ID 未伪造，不等价于引用内容完全支持答案；后者需单列 faithfulness/Judge 口径。

### 4.4 缓存延迟

对 30 个固定问题：

1. `DELETE /chat/cache` 清空测试租户缓存。
2. 第一轮逐条请求，记录 cold/miss 延迟。
3. 第二轮用相同问题请求，记录 warm/hit 延迟。
4. 从 conversation debug 日志确认 `semantic cache hit`，不靠时延猜测。

```text
平均响应时间 = Σ durationMs / N
P95 = 排序后第 ceil(0.95 × N) 个样本
受控命中率 = 已确认 hit 数 / 第二轮有效请求数
```

缓存使用当前 hash embedder时，只对完全相同问题作稳定命中测试，不把它描述成同义问句语义命中。

## 5. 多 Agent DAG 评测

### 5.1 耗时对照

为同一个目标构造 6 个内容相同的 worker 任务：

- A：线性 DAG，`t1 → t2 → ... → t6`。
- B：分层 DAG，第一层 4 个独立调研任务并行，第二层 2 个汇总任务并行，之后由服务统一 synthesis。

每种拓扑先预热 1 次，再重复 5 次；记录端到端平均值、P50、P95。响应中的 `levels` 必须与设计拓扑一致。

这个对照测量的是“强制串行”与“Kahn 分层并行”的墙钟时间差，不能描述成优化前后的同一业务版本，除非有对应历史版本证据。

### 5.2 任务达标率

准备 20 个项目领域复杂目标，每个目标写出明确 rubric，例如：

- 必须覆盖的 3～5 个要点。
- 不允许出现的错误事实。
- 输出格式要求。

每个目标分别运行：

- Replan 关闭：保存首轮 synthesis。
- Replan 开启：默认阈值 `0.75`，最多重规划 1 次。

服务内置 `AgentDagCritic` 从 correctness、completeness、clarity 三维评分：

```text
aggregate = 0.50 × correctness
          + 0.35 × completeness
          + 0.15 × clarity

Critic 达标率 = aggregate >= 0.75 的目标数 / 20
```

同时用确定性 rubric 检查必须要点，报告两个数字：

- Critic 达标率：由项目内 LLM Critic 判断。
- Rubric 完成率：由固定关键词/结构断言判断。

简历如果写“复杂任务完成率”，优先采用 Rubric 完成率，并明确测试集和判定规则；Critic 达标率只作辅助，避免模型自己生成、自己打分造成偏差。

## 6. NL2SQL 评测

### 6.1 测试集来源

基于 `analytics-service/src/main/resources/db/nl2sql-demo.sql` 的固定 MySQL 种子数据构造 150 条问题：

- 数量、求和、平均值。
- 时间范围。
- 中文状态/英文审批状态。
- Top-N、分组聚合、关联查询。
- 空结果。
- 租户隔离。
- 非法写操作与越权表，单列为安全拒绝集，不混入业务正确率。

题目可以有多种自然语言改写，但黄金答案由固定 SQL 在同一数据库快照上计算，不由 LLM 猜测。

### 6.2 指标

```text
首次 SQL 执行成功率 = 首次尝试即成功执行的问题数 / 150
最终 SQL 执行成功率 = 经自修复后 sql != null 的问题数 / 150
结果正确率 = 标准化 rows 与黄金 SQL 结果等价的问题数 / 150
```

当前 HTTP 响应只暴露最后一次成功 SQL 和 `guardBlocked`，不能精确区分“首次失败后因 SQL 错误自修复”与其他拒绝。若不修改业务埋点，本轮可可靠报告：

- 最终 SQL 执行成功率。
- 结果正确率。
- `guardBlocked` 占比。

不能把最终成功率写成“首次执行成功率”。要取得真正的首次成功率，需要增加按 attempt 记录的评测埋点或从结构化执行日志提取。

### 6.3 Schema 对照

当前实现始终使用 `SchemaProvider`，没有“完全关闭 Schema 探测”的等价运行开关，因此不能直接声称“引入 Schema 探测后提升 X 个百分点”。

本轮可做的真实对照是：

| 组 | 配置 |
| --- | --- |
| A | 清空 `enum-columns`，只提供列名/类型/注释 |
| B | 默认 `orders.status`、`refunds.status` distinct 枚举增强 |

重点统计包含中文订单状态和英文退款审批状态的问题，结果只能表述为“加入 Schema 枚举值增强后提升 X 个百分点”。

结果集标准化规则：

- 数值统一为 decimal 字符串后比较，金额保留 2 位。
- 无顺序语义的结果按稳定键排序。
- Top-N 保留行顺序。
- SQL 空结果与黄金空结果算正确。
- SQL 文本不同但结果等价算正确。

## 7. 产物

执行后写入当前目录：

```text
docs/qa/resume-metrics-0727-1539/
├── QA_PLAN.md
├── datasets/
│   ├── rag-corpus-manifest.json
│   ├── rag-golden-200.json
│   ├── agent-goals-20.json
│   └── nl2sql-golden-150.json
├── scripts/
├── raw/
│   ├── rag-vector.json
│   ├── rag-rrf.json
│   ├── agent-linear.jsonl
│   ├── agent-parallel.jsonl
│   ├── nl2sql-schema-basic.jsonl
│   └── nl2sql-schema-enum.jsonl
└── QA_REPORT.md
```

报告包含通过、失败、阻塞项，保留所有失败响应，不静默删除异常样本。

## 8. 执行卡点

执行真实本地测试前需要确认：

1. 是否允许启动/重建本地 Docker Compose 与 Ollama 服务。
2. 是否允许使用当前 `DEEPSEEK_API_KEY` 执行完整评测并产生真实 API 费用。
3. 是否先按推荐策略跑 Pilot，确认数据与成本后自动继续完整测试。

推荐策略：允许本地启动，使用 DeepSeek，先跑 Pilot；Pilot 结构和断言通过后直接继续完整评测。如果希望零云费用，需要改用本地 `qwen3`/`llama3.1`，但 NL2SQL JSON/tool calling 和 Agent 结果可能与当前生产路由不等价，需把模型名称写进最终口径。
