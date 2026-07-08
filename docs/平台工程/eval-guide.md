# 评测（Eval）指南

本指南面向要给平台做**回归评测**的开发者，覆盖 `eval-service`（`:8089`，路由前缀 `/eval/**`）的两条主线：

1. **检索质量评测**（`/eval/retrieval`）—— 纯 IR 指标 **Recall@k / Precision@k / MRR / Hit@k**，不经 LLM，只量向量检索器把相关文档捞回来了没。
2. **通用回归 harness**（`/eval/run`、`/eval/suites/{name}/run`、`/eval/dual-run`、`/eval/gate`）—— 一个**外部回归测试客户端**：按 case 发真实 HTTP 请求打平台端点，做 contains / JSON-path / 语义 / oracle / 可选 LLM-Judge 与 embedding 断言，输出 JSON 报告；并支持**对照冻结单体（oracle）的双跑门禁**。

> **这是哪个服务、默认什么状态**：`eval-service`，所有 `/eval/**` 端点**常开**（无需 enable 开关）。可选的 **LLM-Judge 断言**（`EVAL_JUDGE_ENABLED`）与 **embedding 相似度断言**（`EVAL_EMBEDDING_ENABLED`）**默认关**。检索评测与通用 harness 本身开箱可用。

> **端点约定**：所有 curl 走 `edge-gateway`（`http://localhost:8080`，带 `X-Api-Key`）。网关校验 api-key → 签发短时内部 JWT → 路由到 `eval-service`。示例统一用 `dev-key-acme`（其 scope 含 `eval`）。`eval-service` 自身监听 `:8089`，仅供服务间直连 / 本地调试。
>
> **一个关键区别（必读）**：`/eval/**` 由**调用方 api-key** 授权（要 `eval` scope + 走 `eval` 限流桶，5/min）。但 eval-service **回打平台目标端点**（检索评测的 `/rag/query`、通用 harness 的 case endpoint）用的是**自己配置的 `EVAL_API_KEY`** —— 那是一次全新的网关跳，租户身份由 `EVAL_API_KEY` 决定，**与 `/eval/**` 调用方的租户无关**。`EVAL_API_KEY` 默认空，不设的话目标调用会被网关 401 挡下（检索评测会静默变成全 0 召回，见 §2.5 排错）。

相关文档：[RAG 接入指南](../对话与检索/rag-guide.md)（入库 / 切分 / 检索）、[接口速查](../参考/api-reference.md)、[运维配置](../参考/operations.md)、[能力总览](../参考/capabilities.md)、[架构文档](../参考/架构文档.md)。

---

## 1. 两类评测的分工

老单体的 `eval-cases.json`（passRate）一直**没覆盖召回层**：passRate 混了「检索 + 生成」两层，调 chunking / embedding / rerank 后分数漂了也**分不清是召回变了还是生成变了**。本服务把两者拆成两个端点，各测一层：

| | 通用 harness（passRate） | 检索评测（Recall@k 等） |
| --- | --- | --- |
| 端点 | `/eval/run`、`/eval/suites/{name}/run`、`/eval/dual-run`、`/eval/gate` | `/eval/retrieval` |
| 测什么 | 答对没（**端到端生成质量**，混检索 + LLM 两层） | 相关文档召回没（**纯检索质量**） |
| 经 LLM | 可选（`judgeExpected` + `EVAL_JUDGE_ENABLED`）；默认只跑确定性断言 | **否**（只跑 retriever，不经 LLM） |
| ground truth | `expectedContains` / `expectedJsonPaths` / `oracleContains` / `semanticExpected` … | 人工标注的 `relevantDocIds` |
| 调 chunking 后 | 分数漂了，但不知是召回还是生成变了 | **直接看 Recall 漂动**，归因干净 |
| 结果粒度 | case 二元 pass/fail → 汇总 passRate（0..1） | 每 case 四个 0..1 指标 → 宏平均汇总 |

**归因心法**：case fail 时先看**本轮检索到了什么**——相关 chunk 根本没进 context 是**召回层**问题（切碎 / embedding 不行 / top-k 太小）；相关 chunk 在 context 里但答案还漏是**生成层**问题（prompt / 模型没利用上）。`/eval/retrieval` 就是把召回层单独钉出来量的工具。

---

## 2. 检索质量评测 `/eval/retrieval`

**做什么**：逐 case 把 `question` 经 `{targetBaseUrl}/rag/query` 打到 `knowledge-service` 取有序检索命中，映射成 `displayName#index` 形式的 id 列表（与对话侧 grounding 的 `[doc=ID]` 引用**同源同口径**），再用纯函数算 Recall@k / Precision@k / MRR / Hit@k，最后**宏平均**（每个 case 权重相同）汇总。全程不经 LLM。

**为什么用 `/rag/query` 而非整条对话增强链**：量的是**向量召回本身**（rerank 之前那一层）——这正是调 chunking / embedding 时最该看的信号；rerank 是召回**之后**的精排，另算（见 §2.6 剩余项）。

### 2.1 四个指标的定义

设某 query 标注了 `R` 个相关文档，检索器返回 top-k 有序列表，其中命中相关的有 `h` 个，首个相关命中的 1-based 排名为 `rank`：

```
                被召回的相关文档数            命中相关的召回数            1
Recall@k = ─────────────────      Precision@k = ─────────────   MRR = ────    Hit@k = (h > 0)
                相关文档总数 R                    召回总数 k                rank
```

- **Recall@k**：该召回的漏没漏 —— **调 chunking / embedding 时最该盯的召回指标**。取值 0..1，越高越好。
- **Precision@k**：召回里掺没掺噪声（分母是召回条数）。召回率管漏、精度管噪。
- **MRR**（Mean Reciprocal Rank）：第一个相关文档排多靠前 —— 反映**排序质量**（rank 敏感，依赖检索器返回顺序）。首命中排第 1 则 1.0、排第 2 则 0.5，全没命中记 0。
- **Hit@k**：top-k 是否**至少命中一个**相关文档（二元，最宽松的可用底线）。

`RetrievalSummary` 的汇总字段是各 case 的**宏平均**：`avgRecall` / `avgPrecision` / `meanMrr` / `hitRate`。

**worked example**：某 query 标注了 4 个相关文档；检索 top-5 里命中其中 3 个 → `Recall@5 = 3/4 = 0.75`。若这 3 个里第一个出现在第 2 位 → `MRR = 1/2 = 0.5`；`Precision@5 = 3/5 = 0.6`；`Hit@5 = true`。整个测试集的指标 = 各 query 取平均。

> 补充指标（本端点未内建，扩展时可加）：**NDCG@k**（带位置折扣的相关性增益，最常用的排序综合指标）。

### 2.2 id 匹配：对 chunk 切分漂移鲁棒（黄金集设计的核心）

标注 id（`relevantDocIds`）支持两级粒度：

| 粒度 | 写法 | 命中规则 | 何时用 |
| --- | --- | --- | --- |
| **文件级**（推荐默认） | `project-faq.md`（不含 `#`） | 比检索 id 去掉 `#…` 后的文件部分，相等即命中 | 换 chunk 策略片段号会变、但「来自哪个文件」不变——对切分漂移鲁棒 |
| **精确级** | `project-faq.md#2`（含 `#`） | 与检索 id **全等**才算 | 钉住具体 section，验证细粒度定位 |

**为什么默认文件级**：调 chunking 时片段边界会漂（`recursive(300)` 和 `markdown-header(600)` 切出的 `#index` 完全不同），文件级标注让「召回变化」的信号不被片段号噪声淹没。

### 2.3 黄金集在哪：请求体里带，不是服务端文件

> ⚠️ **与老单体的关键差异**：单体是 `POST /eval/retrieval?set=default&ingest=true`，黄金集是服务端 `resources/eval/retrieval-cases.json`、还能顺带自动入库。**新微服务不是这样**——`/eval/retrieval` 收 **请求体里的 `cases` 数组**（`RetrievalRunRequest`），服务端**没有内置黄金集文件、也不自动 ingest**。入库是独立的 `/rag/documents` 调用（见 [RAG 指南](../对话与检索/rag-guide.md)）。

请求契约 `RetrievalRunRequest`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `cases` | `[{ id, question, relevantDocIds[] }]` | **必填**，空则 400 |
| `topK` | `int?` | 传给 `/rag/query` 的 top-k；留空用 knowledge 默认 |
| `category` | `string?` | 检索按 category 过滤（对应 `/rag/query` 的 `category`） |
| `targetBaseUrl` | `string?` | 目标地址，留空用服务端默认（`EVAL_TARGET_BASE_URL`，即网关） |

把黄金集**当数据维护**（版本库里存一个 JSON、CI 里 `curl -d @gold.json`），加 case = 往数组里加一条 `{id, question, relevantDocIds}`。

### 2.4 怎么跑

**前提**：目标文档必须**已入库**，且 `tenantId` 与检索侧一致 —— 即入库时用的 api-key 的租户，要与 **`EVAL_API_KEY`** 的租户**相同**（检索评测的 `/rag/query` 由 `EVAL_API_KEY` 授权，不是 `/eval/retrieval` 调用方）。示例统一让两者都用 `acme` 租户（`dev-key-acme-ingest` 入库、`EVAL_API_KEY=dev-key-acme` 检索，同租户 `acme`）。

```bash
# 1) 先把文档入库到 knowledge-service（需 ingest scope 的 key，租户 = acme）
curl -s -X POST 'http://localhost:8080/rag/documents' \
  -H 'X-Api-Key: dev-key-acme-ingest' \
  -H 'Content-Type: application/json' \
  -d '{"displayName":"project-faq.md","text":"## 支持的 LLM Provider\nollama / openai / anthropic / gemini / deepseek ...","category":"faq"}'

# 2) 跑检索评测（黄金集在请求体里；调用方 key 需 eval scope）
curl -s -X POST 'http://localhost:8080/eval/retrieval' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{
        "topK": 5,
        "cases": [
          { "id": "faq-providers",
            "question": "本项目支持哪些 chat provider？",
            "relevantDocIds": ["project-faq.md"] },
          { "id": "eval-judge-temp",
            "question": "Judge 用的 temperature 是多少？",
            "relevantDocIds": ["eval-spec.md#3"] }
        ]
      }' | jq
```

> `/rag/documents` 的完整字段（分类、来源、图片多模态等）见 [RAG 指南](../对话与检索/rag-guide.md)；这里只演示最小入库。

响应 `RetrievalSummary`（HTTP 200）：

```json
{
  "cases": 2,
  "avgRecall": 1.0,
  "avgPrecision": 0.42,
  "meanMrr": 0.83,
  "hitRate": 1.0,
  "totalDurationMs": 210,
  "results": [
    {
      "id": "faq-providers",
      "question": "本项目支持哪些 chat provider？",
      "retrievedIds": ["project-faq.md#1", "project-faq.md#0", "eval-spec.md#2"],
      "relevantIds": ["project-faq.md"],
      "recall": 1.0, "precision": 0.4, "mrr": 1.0, "hit": true,
      "durationMs": 120
    }
  ]
}
```

`results[]` 逐 case 给回 `retrievedIds` / `relevantIds` + 四个指标，方便人肉定位哪条召回差。

### 2.5 用它把「换 chunking 后召回退化」拎出来回归

老单体最经典的探针是 `documents/project-faq.md` 把 **5 个 provider 列在同一 section**（`ollama / openai / anthropic / gemini / deepseek`）——这是对 chunking 切分边界**最敏感**的内容：切分保住整段 → 一个 chunk 含全部 5 个 → 召回完整；切碎（如 token 模式 `max-size` 设太小）→ 命中 chunk 只含 2~3 个 → 召回不全。单体里实测过：`recursive(300)` 只召回 2 个 provider，`markdown-header(600)` 召回完整 5 个。

在新平台，切分是 `knowledge-service` 的入库期配置（`app.rag.chunking.*`，relaxed-binding 环境变量如 `APP_RAG_CHUNKING_STRATEGY` / `APP_RAG_CHUNKING_MAX_SIZE` / `APP_RAG_CHUNKING_UNIT`，详见 [RAG 指南](../对话与检索/rag-guide.md)）。回归动作：

```bash
# 对照组：改切分策略/单位重启 knowledge-service → 重新入库同一批文档 → 再跑同一份检索黄金集
# 只比 avgRecall / hitRate 的漂动即可判定「切分改动是否伤了召回」
APP_RAG_CHUNKING_STRATEGY=recursive   APP_RAG_CHUNKING_MAX_SIZE=300 mvn -pl knowledge-service spring-boot:run   # A 组
APP_RAG_CHUNKING_STRATEGY=markdown-header APP_RAG_CHUNKING_MAX_SIZE=600 mvn -pl knowledge-service spring-boot:run # B 组
# 每组：重新 /rag/documents 入库 → curl /eval/retrieval（同一 cases） → 比 avgRecall
```

**预期**：切碎导致「5-provider」类整段内容漏召回时，对应 case 的 `recall` 会从 1.0 掉下来，`avgRecall` / `hitRate` 随之下滑——比 passRate 更早、更干净地暴露问题。

> **排错**：`avgRecall` / `hitRate` **全为 0** 且 `retrievedIds` 全空 → 十有八九是 **`EVAL_API_KEY` 没设 / 设错租户**：`/rag/query` 被网关 401 挡下，`HttpRetrievalClient` 吞掉异常返回空列表（不中断整跑），于是每条 recall=0。核对 `EVAL_API_KEY` 是有效网关 key、且其租户与入库租户一致。

### 2.6 剩余（按信号强弱）

- **检索层门禁**：`/eval/retrieval` 现在只出报告，未接阈值门禁。真要卡召回回归，可在 CI 里对 `avgRecall` / `hitRate` 设下限自行判定（或参考 §4 的 `/eval/gate` 思路给检索指标加一层）。
- **rerank 后的指标**：本端点只测召回层（rerank 之前）。要量 rerank 精排收益，另跑一条走整条对话增强链的对照。
- **大规模标注集**：几条够 smoke，真做 embedding 选型对比建议标到 30~50 条，覆盖多语言 / 模糊 query。

---

## 3. 通用回归 harness `/eval/run` 与 suite

**做什么**：`eval-service` 作为**外部回归客户端**，逐 case 按 `EvalCase` 描述发真实 HTTP 请求打平台端点，跑一组确定性断言，输出报告 `EvalRunReply`。

### 3.1 EvalCase 断言类型

| 字段 | 断言 | 判定 |
| --- | --- | --- |
| `endpoint` / `method` / `body` | 请求描述 | `method` 缺省：`body` 空则 GET，否则 POST；HTTP 非 2xx 直接 fail |
| `expectedContains` | 子串包含 | 响应体不含该串 → fail |
| `expectedJsonPaths` | JSON 路径等值 | `{ "$.a.b": 值 }`，任一不匹配 → fail（响应非 JSON 也 fail）|
| `semanticExpected` (+`semanticMinScore`) | 确定性语义相似度 | 响应对该参考的 token 余弦 < 阈值（默认 0.75）→ fail |
| `judgeExpected` (+`judgeMinScore`) | **可选** LLM-Judge | 需 `EVAL_JUDGE_ENABLED=true`；temp=0 判分 < 阈值（默认 `EVAL_JUDGE_MIN_SCORE`=0.7）→ fail |
| `embeddingExpected` (+`embeddingMinScore`) | **可选** embedding 相似度 | 需 `EVAL_EMBEDDING_ENABLED=true`；余弦 < 阈值（默认 `EVAL_EMBEDDING_MIN_SCORE`=0.75）→ fail |
| `oracleContains` | 对齐冻结单体 | 响应体不含该串 → fail（错误标 `response did not match monolith oracle`）|

未带的断言直接跳过——所以 `judge` / `embedding` 未开启时不影响现有确定性断言。**passRate = 通过 case 数 / 总 case 数**（单 case 内部是 `2xx && 各断言全过` 的二元结果）。

### 3.2 即席跑一组 case `/eval/run`

```bash
curl -s -X POST 'http://localhost:8080/eval/run' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{
        "targetBaseUrl": "http://edge-gateway:8080",
        "cases": [
          { "id": "caps-smoke",
            "endpoint": "/eval/capabilities",
            "method": "GET",
            "expectedContains": "eval-service" },
          { "id": "chat-json",
            "endpoint": "/chat?chatId=eval",
            "method": "POST",
            "body": { "message": "你好" },
            "expectedJsonPaths": { "$.reply": null } }
        ]
      }' | jq
```

响应 `EvalRunReply`（**HTTP 202 Accepted**）：

```json
{
  "total": 2, "passed": 2, "passRate": 1.0,
  "runId": "8f3c…", "suiteName": null,
  "targetBaseUrl": "http://edge-gateway:8080",
  "startedAt": "2026-07-09T…", "durationMs": 84, "finishedAt": "2026-07-09T…",
  "reportPath": null,
  "results": [
    { "id": "caps-smoke", "passed": true, "status": 200,
      "error": null, "responseSnippet": "{\"service\":\"eval-service\"…}", "durationMs": 20,
      "oracleMatched": true, "oracleExpected": null }
  ]
}
```

> `targetBaseUrl` 留空则用 `EVAL_TARGET_BASE_URL`。案例里的 `expectedJsonPaths` 值用 `null` 表示「该路径存在即可」的占位，实际写期望值做等值断言。`reportPath` 仅在设了 `EVAL_REPORT_DIRECTORY` 时非空（把整份报告落盘成 `{runId}.json`）。

### 3.3 跑内置 / 外部 baseline suite `/eval/suites/{name}/run`

把常跑的 case 固化成**命名 suite**（`EvalSuiteDefinition`：`{ name, cases[] }`）：

- **内置**：classpath `eval/baselines/{name}.json`。仓库自带 `platform-smoke`（单 case 打 `/eval/capabilities`）。
- **外部**：设 `EVAL_BASELINE_DIRECTORY` 指向目录，`{name}.json` 优先于 classpath（suite 名限 `[A-Za-z0-9._-]`，防路径穿越）。

```bash
curl -s -X POST 'http://localhost:8080/eval/suites/platform-smoke/run' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{ "targetBaseUrl": "http://edge-gateway:8080" }' | jq
```

请求体 `EvalSuiteRunRequest`（`{ targetBaseUrl? }`）可省。响应同 `/eval/run`（`EvalRunReply`，202，`suiteName` 回填）。suite 不存在 → 404；suite 无 case → 400。

---

## 4. 双跑门禁：对齐冻结单体（oracle）

**做什么**：把**同一 suite** 分别打两个目标 —— **candidate**（新平台 edge-gateway）与 **oracle**（冻结单体，行为基准）—— 各自聚合 `passRate` / `averageScore`，再算**跨目标语义一致性 `agreement`**，交给纯函数门禁判有没有**行为回归**。这是迁移期「新平台没跑偏出单体行为」的护栏。

两种形态（由请求里给 `oracleSnapshot` 还是 `oracleBaseUrl` 推断）：

| 模式 | 触发 | oracle 来源 | 场景 |
| --- | --- | --- | --- |
| **snapshot（PR）** | 给 `oracleSnapshot`（快照名） | 读预存快照（`EvalOracleSnapshot`），**只实跑 candidate** | 快 / 稳，PR 门禁 |
| **live（nightly）** | 给 `oracleBaseUrl` | 现场起冻结单体实打 | oracle/candidate 都实跑，nightly |

快照存 classpath `eval/snapshots/{name}.json`（或 `EVAL_SNAPSHOT_DIRECTORY` 目录），仓库自带 `platform-smoke`。

### 4.1 门禁判定（`EvalGate`，纯函数，可确定性单测）

candidate 触发**任一**条即判回归（全带 `1e-6` 浮点容差，避免 `0.7499 < 0.75` 假回归）：

1. `candidate.passRate < oracle.passRate − passRateTolerance`（默认容差 0.05）
2. `candidate.averageScore < oracle.averageScore − averageScoreTolerance`（默认 0.05）
3. `agreement < minAgreement`（默认 0.6）

`averageScore` 是逐 case 对参考答案的确定性余弦相似度均值（与二元 passRate 互补，反映质量梯度）；`agreement` 是逐 case 比对 candidate / oracle 响应片段语义相似度取均值。`passed = regressions 为空`，`regressions[]` 是人类可读明细。容差 / `runs` 可在请求里逐次覆盖，缺省用 `app.eval.gate.*` 配置。

### 4.2 `/eval/dual-run`（信息化，恒 200）与 `/eval/gate`（CI 门禁，回归返 422）

两者请求体都是 `EvalDualRunRequest`，跑同一套双跑逻辑；差别只在**状态码语义**：

- **`/eval/dual-run`**：HTTP **恒 200**，返回完整门禁明细供人看（不用于 fail 构建）。
- **`/eval/gate`**：有回归返回 **HTTP 422**（body 为含 `regressions` 的 `EvalDualRunReply`，供 CI fail），无回归 200。

```bash
# PR 快照模式：只实跑 candidate（走网关），oracle 读 platform-smoke 快照
curl -s -o /tmp/gate.json -w '%{http_code}\n' -X POST 'http://localhost:8080/eval/gate' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{
        "suiteName": "platform-smoke",
        "candidateBaseUrl": "http://edge-gateway:8080",
        "oracleSnapshot": "platform-smoke"
      }'
jq . /tmp/gate.json
```

> CI 里用 HTTP 状态码判成败：`--fail-with-body` 让 curl 在 422 时非零退出即可卡门禁。live 模式把 `oracleSnapshot` 换成 `oracleBaseUrl`（现场起的冻结单体地址）。

响应 `EvalDualRunReply`（片段）：

```json
{
  "runId": "…", "suiteName": "platform-smoke", "mode": "snapshot",
  "startedAt": "…", "durationMs": 40, "finishedAt": "…",
  "gate": {
    "passed": false,
    "regressions": [
      "averageScore regression: candidate 0.31 < oracle 0.71 - tolerance 0.05 = 0.66"
    ],
    "agreement": 0.72,
    "candidate": { "name": "candidate", "passRate": 1.0, "averageScore": 0.31, "runs": 1, "results": [ … ] },
    "oracle":    { "name": "oracle",    "passRate": 1.0, "averageScore": 0.71, "runs": 1, "results": [ … ] },
    "tolerances": { "passRateTolerance": 0.05, "averageScoreTolerance": 0.05, "minAgreement": 0.6, "runs": 1 }
  }
}
```

> `suiteName` 不存在 → 404；`oracleSnapshot` 找不到 → 404；缺 `suiteName` / live 模式缺 `oracleBaseUrl` → 400。

### 4.3 `/eval/capabilities`（自述）

`GET /eval/capabilities` 返回服务自述（断言类型、双跑模式与门禁状态码约定），常用于连通性 smoke（也是 `platform-smoke` suite 打的靶点）。

```bash
curl -s 'http://localhost:8080/eval/capabilities' -H 'X-Api-Key: dev-key-acme' | jq
```

---

## 5. 召回率方法论（深挖：面试 / 评审都能用）

> 这节回答「**召回率到底怎么算**」——把本平台**实际算的两套**指标厘清，别混。

### 5.1 passRate ≠ 经典召回率

**通用 harness 算的是 case 通过率 `passRate`**（§3），单 case 内部是 `2xx && 各断言全过` 的**二元**结果，汇总成 0..1 的通过比例。它衡量「**检索 + 生成**整条链最终答得对不对」，是**端到端答案质量**的代理指标，**不是单独的检索召回率**。

你可以用一条**强约束 case** 让 passRate 间接反映召回完整性：把「必须被召回才可能写出」的事实塞进断言（如 `semanticExpected` 覆盖 5 个 provider 名），召回不全 → 答案漏项 → case fail。但它仍是 **case 粒度二元结果**，不是 0..1 的召回率数值，且把检索和生成耦在一起。要**纯检索**、**连续值**的召回率，就用 §2 的 `/eval/retrieval`（`Recall@k`）——这正是本平台把老单体「一直想落地的纯召回端点」补上的地方。

### 5.2 经典 IR 召回率 `Recall@k`

```
              top-k 检索结果中"相关"的文档数
Recall@k = ───────────────────────────────────
              该 query 全部"相关"文档总数
```

前提是**带标注的黄金集**（每个 query 标好哪些 doc/chunk id 相关，即 `/eval/retrieval` 的 `relevantDocIds`），对每个 query 跑检索器拿 top-k 求交集算 `Recall@k`，再宏平均。配套 `Precision@k`（管噪声）/ `MRR`（管排序）/ `Hit@k`（最宽松底线）/ `NDCG@k`（位置折扣综合），定义见 §2.1。

### 5.3 常见追问（FAQ）

- **没标注集怎么低成本造一个？** 用 LLM 反向造：遍历每个 chunk 让 LLM 基于这段生成 1~2 个「只有这段能回答的问题」，`(question → 该 chunk id)` 就是一对弱标注，再人工抽检 10~20% 校正。**务必去重 + 过滤太泛的问题**（答案能在多个 chunk 找到的丢掉），否则相关性标注不干净、Recall@k 没意义。
- **chunk 切大一点不就全召回了？** 不行——召回与精度的 trade-off：chunk 越大语义越杂 → 向量被稀释、cosine 相似度反降、精度掉，还烧 context。甜区 256~512 token。要又全又准的正解是**小 chunk 检索 + parent-child 召回大块**（检索粒度与喂 LLM 粒度解耦，见 [RAG 指南](../对话与检索/rag-guide.md)），不是无脑切大。
- **top-k / Recall@k 的 k 选多少？** k 是召回 vs 精度/成本的旋钮：k 越大召回单调不降但精度掉、噪声与 token 成本上升。方法是扫 `Recall@k` 曲线找收益变平的拐点；挂了 reranker 就**召回阶段放大 k 多召、rerank 收口**（fan-out 提召回、rerank 提精度）。
- **线上分布和黄金集不一样怎么持续评估？** 离线黄金集保下限，线上抓漂移：定期把真实 query（尤其无召回 / 追问重述 / 点踩的）补进黄金集；线上用无召回率、引用点击率、faithfulness 在线打分、用户负反馈做代理信号。
- **passRate 耦合了检索和生成，怎么定位是哪层？** 见 §1「归因心法」——先看本轮检索到的 context：相关 chunk 没进 context 是召回层，进了 context 但答案还漏是生成层。`/eval/retrieval` 把召回层单独钉出来量，就是为了解这个耦。

---

## 6. 开关速查

`eval-service`（`:8089`），前缀 `app.eval.*`，环境变量与默认值：

| 环境变量 | 默认 | 作用 |
| --- | --- | --- |
| `EVAL_TARGET_BASE_URL` | `http://edge-gateway:8080` | 回打平台的默认目标地址。**本地单服务跑请设 `http://localhost:8080`** |
| `EVAL_API_KEY` | （空） | eval-service 回打目标端点（`/rag/query`、case endpoint）携带的 api-key。**必须设为有效网关 key**，否则目标调用 401（检索评测全 0 召回）。其租户决定检索侧租户 |
| `EVAL_API_KEY_HEADER` | `X-API-Key` | 上面 key 的请求头名（大小写不敏感，等价 `X-Api-Key`）|
| `EVAL_RESPONSE_SNIPPET_LIMIT` | `512` | 报告里响应片段截断长度（下限 32）|
| `EVAL_JUDGE_ENABLED` | `false` | 开启 LLM-Judge 断言（`judgeExpected`），走网关确定性 ChatModel |
| `EVAL_JUDGE_MIN_SCORE` | `0.7` | Judge 通过阈值（case 未带 `judgeMinScore` 时的默认）|
| `EVAL_EMBEDDING_ENABLED` | `false` | 开启 embedding 相似度断言（`embeddingExpected`）|
| `EVAL_EMBEDDING_MIN_SCORE` | `0.75` | embedding 断言通过阈值 |
| `EVAL_EMBEDDING_MODEL` | `embedding-default` | 开启 embedding 断言时用的模型名（LiteLLM 逻辑名）|
| `EVAL_GATE_PASS_RATE_TOLERANCE` | `0.05` | 双跑门禁：candidate 相对 oracle 允许的 passRate 下滑 |
| `EVAL_GATE_AVERAGE_SCORE_TOLERANCE` | `0.05` | 双跑门禁：averageScore 允许下滑 |
| `EVAL_GATE_MIN_AGREEMENT` | `0.6` | 双跑门禁：跨目标语义一致性下限 |
| `EVAL_GATE_RUNS` | `1` | 每 case 重复次数（多跑抗抖动）|
| `EVAL_BASELINE_DIRECTORY` | （空） | 外部 suite 目录；空则回退 classpath `eval/baselines/*.json` |
| `EVAL_SNAPSHOT_DIRECTORY` | （空） | 外部 oracle 快照目录；空则回退 classpath `eval/snapshots/*.json` |
| `EVAL_REPORT_DIRECTORY` | （空） | 设了则把 `/eval/run` / suite 报告落盘成 `{runId}.json`，并回填 `reportPath` |

端点侧（均经网关 `:8080`，需调用方 key 带 `eval` scope，受 `eval` 限流桶 5/min）：

| 端点 | 方法 | 成功码 | 用途 |
| --- | --- | --- | --- |
| `/eval/capabilities` | GET | 200 | 服务自述 / 连通性 smoke |
| `/eval/retrieval` | POST | 200 | 检索质量评测（Recall@k/Precision@k/MRR/Hit@k），cases 在请求体 |
| `/eval/run` | POST | 202 | 即席跑一组 HTTP case |
| `/eval/suites/{name}/run` | POST | 202 | 跑内置 / 外部 baseline suite |
| `/eval/dual-run` | POST | 200 | 对齐冻结单体双跑，信息化（恒 200）|
| `/eval/gate` | POST | 200 / **422** | CI 门禁：有回归返 422（body 带 `regressions`）|

> 检索评测所需的**入库切分**开关（`app.rag.chunking.*` / 向量库 / embedding provider）属 `knowledge-service`，见 [RAG 接入指南](../对话与检索/rag-guide.md)。LLM-Judge / embedding 断言开启后走 `platform-gateway-client` → LiteLLM，模型路由见 [架构文档](../参考/架构文档.md)。
