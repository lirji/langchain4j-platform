# Agent 能力与编排指南

本指南覆盖 `agent-service`（`:8085`）暴露的全部 Agent 编排模式与可插拔动作，外加落在 `conversation-service`（`:8081`）的模型级联（cascade）。所有端点均可经 `edge-gateway`（`:8080`）访问，也可服务直连本地调试。

> 阅读约定：
> - **经网关**：`http://localhost:8080` + `X-Api-Key`（网关校验 key → 签发内部 JWT → 路由到下游）。`/agent`、`/agent/**` 路由到 agent-service；`/chat`、`/chat/**` 路由到 conversation-service。调用 Agent 需 api-key 绑定 `agent` scope（cascade 走 `/chat` 需 `chat` scope）。
> - **直连**：`http://localhost:8085`（agent）、`http://localhost:8081`（conversation），仅本地调试/服务间调用。
> - **默认开关**：大多数能力默认关闭或需先配置。下文每项标注默认状态与开启用的环境变量。核心编排（ReAct / DAG / reflexion / voting / chaining）在 `AGENT_ENABLED=true`（默认 true）时端点即挂载。

---

## 0. 总览

| 模式 / 能力 | 端点 | 载体服务 | 默认状态 |
|---|---|---|---|
| 深度 Agent（ReAct） | `POST /agent/run`、`/agent/run/async` + `/agent/tasks/**` | agent :8085 | 常开（`AGENT_ENABLED=true`） |
| DAG 多 Agent | `POST /agent/dag/run`、`/agent/dag/plan-run`（各带 `/async`） | agent :8085 | 常开；replan 默认关 |
| 数据分析智能体（DAG 特化） | `POST /agent/analyst/run`（+`/async`） | agent :8085 | 常开；需 analytics `NL2SQL_ENABLED=true` 才能真取数 |
| 业务流程智能体（DAG 特化，人在环） | `POST /agent/process/run`（+`/async`） | agent :8085 | **默认关**（`AGENT_WORKFLOW_ENABLED=true`），需 workflow `WORKFLOW_ENABLED=true` |
| Reflexion 自省 | `POST /agent/reflexive`、`/agent/reflexive/stream` | agent :8085 | 常开 |
| Voting 投票共识 | `POST /agent/vote` | agent :8085 | 常开（n=3，majority） |
| Prompt Chaining | `POST /agent/chain` | agent :8085 | 端点常开，但 `steps` 默认空需先配置 |
| Model Cascade | `POST /chat/cascade` | conversation :8081 | 默认关（`CHAT_CASCADE_ENABLED=false`） |
| 异步任务 + SSE | `/agent/run/async`、`/agent/dag/**/async`、`/agent/tasks/{id}/stream` | agent :8085 | 常开 |
| 动作 rag_search / analytics_sql / schema_explore / current_time | ReAct 内部工具 | agent :8085 | 常开 |
| 动作 refund_start / workflow_status / workflow_tasks | ReAct 内部工具 | agent :8085 | 默认关（`AGENT_WORKFLOW_ENABLED=false`） |
| 动作 code_exec | ReAct 内部工具 | agent :8085 | 默认关（`AGENT_CODE_EXEC_ENABLED=false`） |
| 动作 mcp_call | ReAct 内部工具 | agent :8085 | 默认关（`AGENT_MCP_ENABLED=false`） |
| 动作 browser_open/click/click_xy/type/screenshot | ReAct 内部工具 | agent :8085 | 默认关（`AGENT_BROWSER_ENABLED=false`） |
| 动作 browser_see（视觉） | ReAct 内部工具 | agent :8085 | 默认关（需 browser + vision 双开） |

**编排关系**：Reflexion / Voting / Chaining / DAG 都是 `DeepAgentService`（ReAct）的**同级 sibling 编排器**，不塞进 ReAct 内部，各走一个独立端点，共享 agent-service 的鉴权链（内部 JWT + 多租户 + 限流 + 配额）、`agentTaskExecutor`、评分/聚合器与 SSE 桥接。级联是「模型选择」层，与检索/记忆正交，归属 `platform-gateway-client`（不暴露为第二个 `ChatModel` Bean）。

---

## 1. 深度 Agent（ReAct）—— `/agent/run`

`DeepAgentService` 是一个 ReAct（Reason + Act）循环：每一步让 `AgentBrain`（`@AiService`）产出一个决策（thought / action / actionInput / note / finalAnswer），执行选中的动作、把观测写回 scratchpad + history，直到模型 `finish` 或触发某个停止条件。

### 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/agent/run` | 同步跑完，返回 `AgentRunReply` |
| `POST` | `/agent/run/async` | 提交异步任务，返回 `202` + `AgentAsyncTask` |
| `GET` | `/agent/tasks` | 列出当前租户的异步任务 |
| `GET` | `/agent/tasks/{taskId}` | 查任务状态/结果 |
| `DELETE` | `/agent/tasks/{taskId}` | 取消任务 |
| `GET` | `/agent/tasks/{taskId}/stream` | SSE 订阅任务状态变化（`text/event-stream`） |
| `GET` | `/agent/capabilities` | 向 interop-service 声明能力（MCP tool descriptors），供 live discovery |

请求体 `AgentRunRequest`：`{"goal": "...", "webhookUrl": "..."}`（`goal` 必填，为空返回 `400`；`webhookUrl` 可选，仅异步生效）。

响应 `AgentRunReply`：`goal` / `steps[]`（每步 `n / thought / action / actionInput / observation`）/ `finalAnswer` / `stopReason` / `depth` / `tenantId`。

`stopReason` 取值：`DONE`（模型 finish）、`MAX_STEPS`（步数用尽）、`LOOP`（同一动作重复无进展被熔断）、`TIMEOUT`（墙钟超时）、`BUDGET`（token 预算耗尽）、`CANCELLED`（被取消/中断）、`ERROR`（brain 连续失败）。

### 内置动作

模型可从下列动作 + `delegate` + `finish` 中选择（是否出现 `delegate` 取决于 `allow-delegation` 与当前 `depth < max-depth`）：

- `rag_search`：调 knowledge-service 检索知识库，返回带 `[doc=ID]` 的片段（常开）。
- `analytics_sql`：调 analytics-service 做 NL2SQL 只读查询，返回 SQL + 行数 + 数据 + 解读（常开）。
- `current_time`：查当前时间，`actionInput` 填 IANA 时区（常开）。
- `code_exec` / `mcp_call` / `browser_*` / `browser_see`：见 [§7 动作与开关](#7-动作与开关default-多为关)。
- `delegate`：把独立子目标派给子 Agent（受 `allow-delegation` + `max-depth` 约束）。

### 关键调优开关（`app.agent.*`，均可用环境变量覆盖）

| 属性 | 环境变量 | 默认 | 含义 |
|---|---|---|---|
| `max-steps` | `AGENT_MAX_STEPS` | `8` | ReAct 最大步数 |
| `max-wall-clock-ms` | `AGENT_MAX_WALL_CLOCK_MS` | `0`（关） | 墙钟超时，`0` 不限 |
| `max-tokens` | `AGENT_MAX_TOKENS` | `0`（关） | 估算 token 预算，`0` 不限 |
| `brain-max-retries` | `AGENT_BRAIN_MAX_RETRIES` | `1` | brain 调用失败重试次数 |
| `brain-retry-backoff-ms` | `AGENT_BRAIN_RETRY_BACKOFF_MS` | `0` | 重试退避 |
| `max-repeats` | `AGENT_MAX_REPEATS` | `3` | 同一 `action|input` 在窗口内重复达此值即熔断 |
| `loop-window` | `AGENT_LOOP_WINDOW` | `6` | 循环检测窗口 |
| `max-scratchpad-chars` | `AGENT_MAX_SCRATCHPAD_CHARS` | `4000` | scratchpad 上限，超限压缩（可选 `ScratchpadSummarizer` 摘要早期结论） |
| `history-window` | `AGENT_HISTORY_WINDOW` | `6` | 喂给 brain 的近期步数 |
| `allow-delegation` | `AGENT_ALLOW_DELEGATION` | `true` | 是否允许 `delegate` 子 Agent |
| `max-depth` | `AGENT_MAX_DEPTH` | `1` | 子 Agent 递归深度上限 |

### curl

```bash
# 同步
curl -s -X POST 'http://localhost:8080/agent/run' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"goal":"查一下知识库里退款审批规则，并给出简短结论"}'

# 异步 + webhook + SSE
curl -s -X POST 'http://localhost:8080/agent/run/async' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"goal":"...", "webhookUrl":"http://host.docker.internal:9000/agent-task-callback"}'

curl -s 'http://localhost:8080/agent/tasks/{taskId}' -H 'X-Api-Key: dev-key-acme'
curl -N 'http://localhost:8080/agent/tasks/{taskId}/stream' -H 'X-Api-Key: dev-key-acme'
```

---

## 2. DAG 多 Agent —— `/agent/dag/run`、`/agent/dag/plan-run`

`AgentDagService`（Orchestrator-Workers 模式）：把目标拆成带依赖关系的子任务，按 Kahn 拓扑分层执行——同层并行、下游看到上游结果，最后由 synthesis 综合回答。可显式传入 DAG，也可只给目标让 `AgentDagPlanner` 自动拆。

### 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/agent/dag/run` | 显式 DAG（传 `tasks`），同步执行 |
| `POST` | `/agent/dag/plan-run` | 只传 `goal`，Planner 自动拆 DAG 后执行 |
| `POST` | `/agent/dag/run/async` | 显式 DAG 异步，返回 `202` + 任务 |
| `POST` | `/agent/dag/plan-run/async` | 自动规划 DAG 异步 |

`/agent/dag/run` 请求体 `AgentDagRunRequest`：`{"goal":"...", "tasks":[{"id","description","dependsOn":[...]}], "webhookUrl":"..."}`。`/agent/dag/plan-run` 请求体 `{"goal":"...", "webhookUrl":"..."}`。异步任务复用 `/agent/tasks/{id}` 与 `/agent/tasks/{id}/stream` 查询。

响应 `AgentDagRunReply`：`goal` / `levels[][]`（拓扑分层的任务 id）/ `taskResults[]` / `tenantId` / `attempts[]`（每轮执行 + `critique` + 聚合分）/ `acceptedByThreshold`。

### Critic / Replan 质量闭环（默认关）

开启后每轮 synthesis 由 `AgentDagCritic` 评分（correctness / completeness / clarity 三维加权聚合），低于阈值则 `AgentDagReplanner` 修订 DAG 再跑一轮，直到过阈值或达重规划上限。`acceptedByThreshold=false` 表示用尽重规划仍未过阈值。

| 属性（`app.agent.dag.*`） | 环境变量 | 默认 |
|---|---|---|
| `max-tasks` | `AGENT_DAG_MAX_TASKS` | `6` |
| `replan.enabled` | `AGENT_DAG_REPLAN_ENABLED` | `false` |
| `replan.threshold` | `AGENT_DAG_REPLAN_THRESHOLD` | `0.75` |
| `replan.max-replans` | `AGENT_DAG_REPLAN_MAX_REPLANS` | `1` |
| `replan.weights.correctness` | `AGENT_DAG_REPLAN_WEIGHT_CORRECTNESS` | `0.5` |
| `replan.weights.completeness` | `AGENT_DAG_REPLAN_WEIGHT_COMPLETENESS` | `0.35` |
| `replan.weights.clarity` | `AGENT_DAG_REPLAN_WEIGHT_CLARITY` | `0.15` |

### DAG SSE 阶段事件

订阅 DAG 异步任务 SSE，除了通用 `PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED` 状态事件，还会收到 DAG 阶段事件：`dag-planned`、`dag-levels`、`dag-level-start`、`dag-worker-start`、`dag-worker-result`、`dag-level-complete`、`dag-synthesis-start`、`dag-synthesis-result`、`dag-critique`、`dag-replan`、`dag-replanned`。

### curl

```bash
# 显式 DAG
curl -s -X POST 'http://localhost:8080/agent/dag/run' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{
    "goal": "分析退款审批规则，并给出运营建议",
    "tasks": [
      {"id":"t1","description":"从知识库检索退款审批规则要点","dependsOn":[]},
      {"id":"t2","description":"基于 t1 的规则总结潜在运营风险","dependsOn":["t1"]},
      {"id":"t3","description":"基于 t1 和 t2 给出简短改进建议","dependsOn":["t1","t2"]}
    ]
  }'

# 自动规划
curl -s -X POST 'http://localhost:8080/agent/dag/plan-run' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"goal":"分析退款审批规则，并给出运营建议"}'
```

### 数据分析智能体（analyst）—— `/agent/analyst/run`

数据分析智能体是 DAG 的一个「数据分析人设」入口：它不改 DAG 引擎，只是换用一个**数据分析专用 Planner**（`DataAnalystPlanner`），把自然语言数据问题拆成「探表 → 取数 → 计算 → 解读」子任务，再交给同一套 `AgentDagService`（拓扑分层 / 并行 worker / synthesis / 可选 critic+replan）执行。每个 DAG worker 都是一个能调 `schema_explore`（按需探表）、`analytics_sql`（只读取数）、`code_exec`（精确二次计算，默认关）的 ReAct agent。

- 端点：`POST /agent/analyst/run`（同步）、`POST /agent/analyst/run/async`（异步，返回 `202` + `AgentAsyncTask`）。
- 请求体：`{"goal":"..."}`（异步可带 `webhookUrl`）。响应结构同 DAG：`goal / levels / taskResults / synthesis / attempts / acceptedByThreshold`。
- 前置：analytics-service 开 `NL2SQL_ENABLED=true`（否则取数动作会被 Noop 兜底降级）；精确跨行计算再开 `AGENT_CODE_EXEC_ENABLED=true`，否则计算子任务退化为模型推理估算。
- 与直接 `/agent/dag/plan-run` 的区别：analyst 的 Planner 内置了「先探后取、明确工具用法、按维度而非实体拆解」的数据分析规则，省去在 goal 里手写这些约束。

```bash
curl -s -X POST 'http://localhost:8080/agent/analyst/run' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"goal":"上月退款金额 top5 的客户是谁，各退多少，占总退款额多少？"}'

# 异步：SSE 复用 /agent/tasks/{taskId}/stream，可见 dag-planned / dag-worker-* / dag-synthesis-* 阶段事件
curl -s -X POST 'http://localhost:8080/agent/analyst/run/async' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"goal":"分析上月各状态退款金额分布"}'
```

配套的按需探表端点由 analytics-service 提供（同受 `NL2SQL_ENABLED` 门控、只暴露白名单表结构，非白名单表 404）：

```bash
curl -s 'http://localhost:8080/analytics/schema/tables' -H 'X-Api-Key: dev-key-acme'
curl -s 'http://localhost:8080/analytics/schema/tables/orders' -H 'X-Api-Key: dev-key-acme'
```

### 业务流程智能体（process）—— `/agent/process/run`（默认关）

业务流程智能体也是 DAG 特化：专用 `ProcessPlanner` 把「帮我发起退款并跟进」这类诉求拆成「发起 → 查状态 → 如实汇报」子任务，worker 用 `refund_start` / `workflow_status` / `workflow_tasks` 动作驱动 workflow-service（`:8082`）。

**人在环治理（load-bearing）**：
- 智能体只能**发起 / 查询 / 汇报**，**绝不自动审批**——不提供 `workflow_complete` 动作；approve/reject 由具备 `approve` scope 的人走 `POST /workflow/tasks/{taskId}/complete`。
- 高风险退款返回 `WAITING_APPROVAL`，`refund_start` 会如实标注「已转人工、尚未批准」；ProcessPlanner 的系统提示词硬性要求智能体不得声称已批准。
- `refund_start` 经内部 JWT 透传调用方的租户与 `scope`，`workflow_tasks` 等审批相关调用天然受 `approve` 约束（无权限 → workflow-service 403 → 动作翻译成中文提示），智能体不越权。
- 智能体只在**流程外**编排（发起/查询/汇报），不把推理塞进 Flowable 同步 ServiceTask（那会阻塞事务）；流程内的 assess/resolve LLM 决策由 workflow-service 自己承担。

默认关（有副作用）：需 `AGENT_WORKFLOW_ENABLED=true`（agent 侧）+ `WORKFLOW_ENABLED=true`（workflow 侧，其 assess/resolve 还会调 conversation-service）。workflow 客户端读超时默认放宽到 60s（`refund/start` 同步跑两次 LLM ServiceTask）。

```bash
curl -s -X POST 'http://localhost:8080/agent/process/run' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"goal":"客户张三要退订单 O123 的 5000 元，帮我发起并告诉我进展"}'

# 异步变体：SSE 复用 /agent/tasks/{taskId}/stream
curl -s -X POST 'http://localhost:8080/agent/process/run/async' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"goal":"查一下有哪些待我审批的退款"}'
```

---

## 3. Reflexion 自省环 —— `/agent/reflexive`

`ReflexionService`（Evaluator-Optimizer 模式）：对**同一个答案**反复 `answer → critique → improve → critique`，三维加权聚合分达阈值即停，或用尽最大轮次。评分复用 DAG 的 `AgentDagCritic`，加权聚合与 DAG 共吃 `CritiqueAggregation`。与 DAG（把不同子任务并行）互补，这里是单答案纵向打磨。

### 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/agent/reflexive` | 同步跑完自省环，返回 `ReflexionReply` |
| `POST` | `/agent/reflexive/stream` | SSE，分阶段推 `attempt-start` / `answer` / `critique` / `done`（`error`） |

请求体 `ReflexionRequest`：`{"question":"..."}`（为空返回 `400`）。

响应 `ReflexionReply`：`question` / `finalAnswer` / `attempts[]`（每轮 `n / answer / aggregate / correctness / completeness / clarity / mainIssue`）/ `acceptedByThreshold` / `tenantId`。

对齐单体 `/chat/reflexive[/stream]`，迁到 `/agent/reflexive[/stream]`。

| 属性（`app.agent.reflexion.*`） | 环境变量 | 默认 |
|---|---|---|
| `threshold` | `AGENT_REFLEXION_THRESHOLD` | `0.75` |
| `max-attempts`（improve 轮数；总尝试 = 1 初答 + max-attempts 次 improve） | `AGENT_REFLEXION_MAX_ATTEMPTS` | `2` |
| `weights.correctness` | `AGENT_REFLEXION_WEIGHT_CORRECTNESS` | `0.4` |
| `weights.completeness` | `AGENT_REFLEXION_WEIGHT_COMPLETENESS` | `0.4` |
| `weights.clarity` | `AGENT_REFLEXION_WEIGHT_CLARITY` | `0.2` |

```bash
curl -s -X POST 'http://localhost:8080/agent/reflexive' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"question":"用三句话解释什么是 GraphRAG"}'

# 流式
curl -N -X POST 'http://localhost:8080/agent/reflexive/stream' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"question":"用三句话解释什么是 GraphRAG"}'
```

---

## 4. Voting 投票共识 —— `/agent/vote`

`VotingService`（Parallelization / Voting 模式）：同一问题**并行**跑 N 个 `Voter`，再按策略聚合。fan-out 复用 `agentTaskExecutor`（其 TaskDecorator 已透传 TenantContext / MDC）。

### 端点

`POST /agent/vote`，请求体 `VoteRequest`：`{"question":"...", "n": 5}`（`n` 可选，默认 `app.agent.voting.n`；`question` 为空返回 `400`）。

响应 `VoteReply`：`question` / `votes[]`（各次原始回答）/ `strategy` / `decision`（共识答案）/ `agreement`（majority 时为胜出票占比，synthesis 时为 `NaN`）/ `confident` / `tenantId`。

### 两种策略

- `majority`（默认）：确定性多数表决，归一化后计票取最高。**仅适用于离散/分类题**（是否合规、情感极性、单选标签等）——自由文本每次措辞不同、归一化后各自成派，`agreement≈1/n` 几乎必然不达 `min-agreement`。
- `synthesis`：由 `VoteAggregator`（temp=0 判官变体，不注册为第二个 `ChatModel` Bean）收口聚合，适用于自由文本题；`confident` 恒为 `true`，`agreement` 为 `NaN`。

| 属性（`app.agent.voting.*`） | 环境变量 | 默认 |
|---|---|---|
| `n` | `AGENT_VOTING_N` | `3` |
| `strategy`（`majority` / `synthesis`） | `AGENT_VOTING_STRATEGY` | `majority` |
| `min-agreement`（majority 置信阈值） | `AGENT_VOTING_MIN_AGREEMENT` | `0.5` |

```bash
curl -s -X POST 'http://localhost:8080/agent/vote' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"question":"这条评论的情感是正面还是负面：这产品太好用了","n":5}'
```

---

## 5. Prompt Chaining —— `/agent/chain`

`PromptChainService`（Prompt Chaining 模式）：把输入依次喂过一串预定义步骤，每步一次 LLM 调用（`ChainLink`）、只处理上一步输出，步间执行**确定性 gate**；gate 不过即**短路终止**，避免把跑偏的中间结果继续喂下去烧 token。步骤顺序与 gate 写死在配置里，不由模型决定流程，因此可重复、可单测。

### 端点

`POST /agent/chain`，请求体 `ChainRunRequest`：`{"input":"..."}`（为空返回 `400`）。

响应 `ChainRunReply`：`input` / `steps[]`（每步 `name / output / passed / gateReason`）/ `finalOutput` / `completed`（是否全程通过）/ `tenantId`。

> ⚠️ **默认 `app.agent.chaining.steps` 为空**：未配置步骤时端点返回 `400`（`no chain steps configured`）。需先在 `agent-service` 的 `application.yml` 定义预定义链，端点才有内容可跑。

每步字段（`ChainStep`）：`name`、`instruction`（喂给 `ChainLink`）、`gate-min-length`（输出最小长度，`≤0` 关）、`gate-must-contain`（必须包含的子串，空关）、`gate-must-match`（必须命中的正则，find 语义，空关；坏正则记警告后跳过该 gate 而不炸链）。三种 gate 可叠加。

```yaml
# agent-service application.yml
app:
  agent:
    chaining:
      steps:
        - name: extract
          instruction: 从输入中抽取关键事实，逐条列出
          gate-min-length: 20
        - name: summarize
          instruction: 把上一步的事实压缩成一句中文结论
          gate-must-contain: 结论
```

```bash
curl -s -X POST 'http://localhost:8080/agent/chain' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"input":"平台由 6 个共享库和 12 个服务组成，两层网关分别负责业务路由与 LLM 路由。"}'
```

---

## 6. Model Cascade —— `/chat/cascade`（conversation-service）

模型级联（成本路由）：先用**便宜模型**作答，仅当 `ConfidenceGate` 判「低置信」时才升级**强模型**重答。绝大多数简单问题便宜模型即可搞定，强模型只在需要时才烧钱。cheap/strong 都是 LiteLLM `model_list` 里的逻辑模型名，经 `GatewayChatModelFactory` 程序化构造；provider/failover 仍下沉在 LiteLLM。

> 归属：级联模型由 `platform-gateway-client` 的 `CascadeChatModelFactory` 构造，作为 `CascadeService` 的私有字段持有，**不注册为第二个 `ChatModel` Bean**（否则 langchain4j `@AiService` 自动发现见到 >1 个 ChatModel 会抛 conflict）。cheap/strong 两条都挂了全部 `ChatModelListener`，token/成本/审计照常按租户计量。

### 端点

`POST /chat/cascade`（conversation :8081，经网关 `/chat/**`，需 `chat` scope），请求体 `{"message":"..."}`（为空返回 `400`）。

响应 `CascadeResult`：`question` / `answer` / `served`（`cheap` | `strong`）/ `cheapConfident` / `tenantId`。

### 置信判定（`ConfidenceGate`，纯确定性启发式）

1. 空 / 过短（`< min-answer-chars`）→ 低置信 → 升级；
2. 命中不确定/拒答标记（`uncertainty-markers`，中英混排）→ 低置信 → 升级；
3. 可选自评（`self-rating=true`）：启发式通过后再让便宜模型对自己答案 temp=0 打 0–1 分，低于 `confidence-threshold` 也升级。

### 开关（`app.chat.cascade.*`，默认整块关闭）

| 属性 | 环境变量 | 默认 |
|---|---|---|
| `enabled`（关闭时端点/模型全不装配，对 `/chat` 零影响） | `CHAT_CASCADE_ENABLED` | `false` |
| `cheap-model`（留空退化为网关默认模型） | `CHAT_CASCADE_CHEAP_MODEL` | 空 |
| `strong-model`（留空退化为网关默认模型） | `CHAT_CASCADE_STRONG_MODEL` | 空 |
| `confidence-threshold`（仅 `self-rating` 生效） | `CHAT_CASCADE_CONFIDENCE_THRESHOLD` | `0.6` |
| `min-answer-chars` | `CHAT_CASCADE_MIN_ANSWER_CHARS` | `8` |
| `self-rating` | `CHAT_CASCADE_SELF_RATING` | `false` |

指标 `llm.cascade{served=cheap|strong}` 计数器（有 `MeterRegistry` 时）量化省下多少次强模型调用。

```bash
curl -s -X POST 'http://localhost:8080/chat/cascade' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"message":"今天是星期几这种简单问题"}'
# {"question":"...","answer":"...","served":"cheap","cheapConfident":true,"tenantId":"acme"}
```

---

## 7. 动作与开关（default 多为关）

ReAct 动作是「一个接口 `AgentAction` + `@ConditionalOnProperty` 可插拔实现」的注册表。默认装配的 `rag_search` / `analytics_sql` / `current_time` 在 `AGENT_ENABLED=true` 时即挂载；其余动作默认关闭，避免未配置依赖时影响 agent-service 启动。

### 7.1 rag_search（常开）

调 knowledge-service 检索。相关配置：`app.agent.knowledge.base-url`（`KNOWLEDGE_BASE_URL`，默认 `http://localhost:8084`）、`top-k`（`AGENT_RAG_TOP_K`=5）、`min-score`（`AGENT_RAG_MIN_SCORE`=0.0）、`category`（`AGENT_RAG_CATEGORY`，默认空）。

### 7.2 analytics_sql（常开）

调 analytics-service 做 NL2SQL 只读查询。`app.agent.analytics.base-url`（`ANALYTICS_BASE_URL`，默认 `http://localhost:8083`）、`analytics.enabled`（`AGENT_ANALYTICS_ENABLED`，默认 `true`；关闭则用 `NoopAnalyticsClient`）。护栏拦截/错误会作为观测回给模型。

### 7.3 current_time（常开）

`actionInput` 填 IANA 时区（如 `Asia/Shanghai`），留空用系统默认。

### 7.4 code_exec —— 子进程沙箱（默认关）

让 Agent 执行受限 Java 片段做精确计算/格式转换。默认沙箱为**独立子进程**（`SubprocessCodeSandbox`）：独立进程 + `-Xmx` 限堆、`environment().clear()`、空临时目录作 cwd、墙钟超时强杀、stdout/stderr 截断。另有可选 `jshell` 同 JVM 沙箱（隔离更弱）。

> ⚠️ 子进程为**中等隔离**而非容器级——仍与宿主共享内核/文件系统/网络命名空间，`CodeExecAction` 层保留 denylist（拦网络/文件/进程/反射/退出等 API）作纵深防御。对不可信输入务必谨慎，生产建议进一步容器化。

| 属性（`app.agent.code-exec.*`） | 环境变量 | 默认 |
|---|---|---|
| `enabled` | `AGENT_CODE_EXEC_ENABLED` | `false` |
| `sandbox`（`subprocess` / `jshell`） | `AGENT_CODE_EXEC_SANDBOX` | `subprocess` |
| `timeout-ms` | `AGENT_CODE_EXEC_TIMEOUT_MS` | `3000` |
| `max-output-chars` | `AGENT_CODE_EXEC_MAX_OUTPUT_CHARS` | `2000` |
| `max-source-chars` | `AGENT_CODE_EXEC_MAX_SOURCE_CHARS` | `4000` |
| `block-unsafe-apis` | `AGENT_CODE_EXEC_BLOCK_UNSAFE_APIS` | `true` |
| `max-heap-mb` | `AGENT_CODE_EXEC_MAX_HEAP_MB` | `64` |
| `java-executable`（子进程 JDK 路径，留空自动定位） | `AGENT_CODE_EXEC_JAVA_EXECUTABLE` | 空 |

### 7.5 mcp_call —— 调外部 MCP server（默认关）

`actionInput` 填 JSON `{"tool":"工具名","args":{...}}`，转调 MCP server 暴露的工具。动作描述里会列出 `mcp.listTools()` 目录。仅在 `McpClient` Bean 装配（即 MCP 已开启）时挂载。

| 属性（`app.agent.mcp.*`） | 环境变量 | 默认 |
|---|---|---|
| `enabled` | `AGENT_MCP_ENABLED` | `false` |
| `transport`（`http` / `stdio`） | `AGENT_MCP_TRANSPORT` | `stdio` |
| `http.url` | `AGENT_MCP_HTTP_URL` | `http://localhost:3001/mcp` |
| `stdio.command` | `AGENT_MCP_STDIO_COMMAND` | 空 |
| `log-events` | `AGENT_MCP_LOG_EVENTS` | `false` |

### 7.6 browser_* —— 无头浏览器（默认关）

`AGENT_BROWSER_ENABLED=true` 开启后暴露 `browser_open`、`browser_click`、`browser_click_xy`、`browser_type`、`browser_screenshot`（基于 Playwright Chromium，`PlaywrightBrowserSession`）。首次使用前需在联网环境安装浏览器二进制：

```bash
mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

- `browser_open`：打开 URL（执行页面 JS），返回标题 + 可见文本 + 可点击链接；run 结束会 `closeForThread()`。
- `browser_click` / `browser_click_xy` / `browser_type` / `browser_screenshot`：分别按选择器点击、按坐标点击、输入文本、截图。

### 7.7 browser_see —— 视觉看图（默认关，需双开）

截当前页面图并提交给独立 vision-service（`:8090`）理解，让 Agent「看」页面上文本抽不出的内容（图表/布局/纯图片/验证码）。串联 `browser_screenshot` 出图 + vision-service 看图。

> **双门控**：需 `app.agent.browser.enabled` **且** `app.agent.vision.enabled` 同时为 `true`，且 `BrowserSession` Bean 存在时才装配；否则 `browser_see` 不出现在工具清单。视觉 token 由 vision-service 的 `ChatModelListener` 按透传的租户归因，正确纳入配额。

| 属性（`app.agent.vision.*`） | 环境变量 | 默认 |
|---|---|---|
| `enabled` | `AGENT_VISION_ENABLED` | `false` |
| `base-url` | `VISION_BASE_URL` | `http://localhost:8090` |
| `read-timeout`（视觉调用较慢，放宽） | `AGENT_VISION_READ_TIMEOUT` | `60s` |

---

### 7.8 schema_explore —— 按需探表（常开）

让 Agent「先探后查」探索业务库结构：`actionInput` 留空=列出所有可查（白名单）表；填表名=查看该表字段、类型与枚举取值。据此再用 `analytics_sql` 取数，避免凭空猜列名/中文枚举。是数据分析智能体的关键动作，通用 `/agent/run` 也可用。

- 依赖 `AnalyticsClient`（与 `analytics_sql` 共用；Http/Noop 兜底），只门控 `app.agent.enabled`。
- 实际数据来自 analytics-service 的只读探表端点 `GET /analytics/schema/tables`、`GET /analytics/schema/tables/{table}`（受 `NL2SQL_ENABLED` 门控、只暴露白名单表结构，非白名单 404）。`NL2SQL_ENABLED=false` 时动作会被 Noop 兜底降级为「analytics action disabled」，不影响启动。

---

### 7.9 refund_start / workflow_status / workflow_tasks —— 业务流程（默认关）

业务流程智能体的三个动作，双门控 `{app.agent.enabled, app.agent.workflow.enabled}` 默认关（有副作用，不进通用工具集，防误发起退款）。经带租户/trace 透传的 `workflowRestTemplate` 调 workflow-service（`:8082`）。

| 动作 | 说明 | 权限 |
|---|---|---|
| `refund_start` | 发起退款审批流程，`actionInput`=用户诉求原文；返回 `COMPLETED`（自动受理）或 `WAITING_APPROVAL`（转人工，带 taskId） | 任意已认证 |
| `workflow_status` | 查实例状态与最终答复，`actionInput`=instanceId | 任意已认证（租户隔离） |
| `workflow_tasks` | 列本租户待审批任务 | 需 `approve` scope，无则 403 → 翻译成中文提示 |

**不提供 `workflow_complete`（审批）动作**——审批是不可逆高风险操作，须由人在流程外完成。`AGENT_WORKFLOW_ENABLED=false` 时对应 `NoopWorkflowClient` 兜底降级为「workflow action disabled」，不影响启动。

| 属性（`app.agent.workflow.*`） | 环境变量 | 默认 |
|---|---|---|
| `enabled` | `AGENT_WORKFLOW_ENABLED` | `false` |
| `base-url` | `WORKFLOW_BASE_URL` | `http://localhost:8082` |
| `read-timeout`（refund/start 同步跑两次 LLM，放宽） | `AGENT_WORKFLOW_READ_TIMEOUT` | `60s` |

---

## 8. 异步任务与 SSE

`/agent/run/async`、`/agent/dag/run/async`、`/agent/dag/plan-run/async` 提交后返回 `AgentAsyncTask`，用 `/agent/tasks/{taskId}` 查状态、`/agent/tasks/{taskId}/stream` 订阅 SSE、`DELETE /agent/tasks/{taskId}` 取消。任务按租户隔离，`GET /agent/tasks` 只列当前租户。

### Webhook 回调（可选）

三个 async 端点都支持可选 `webhookUrl`。任务进入 `SUCCEEDED` / `FAILED` / `CANCELLED` 终态后，agent-service 后台 POST 一份任务快照到该 URL，携带头 `X-Agent-Task-Id`、`X-Agent-Task-Status`、`X-Tenant-Id`（**不转发内部 JWT**）。

| 属性（`app.agent.async.webhook.*`） | 环境变量 | 默认 |
|---|---|---|
| `enabled` | `AGENT_TASK_WEBHOOK_ENABLED` | `true` |
| `max-attempts` | `AGENT_TASK_WEBHOOK_MAX_ATTEMPTS` | `3` |
| `backoff` | `AGENT_TASK_WEBHOOK_BACKOFF` | `250ms` |
| `connect-timeout` | `AGENT_TASK_WEBHOOK_CONNECT_TIMEOUT` | `1s` |
| `read-timeout` | `AGENT_TASK_WEBHOOK_READ_TIMEOUT` | `3s` |

任务 TTL / 清理：`app.agent.async.task-ttl`（`AGENT_TASK_TTL`，默认 `PT24H`）、`cleanup-delay-ms`、`cleanup-initial-delay-ms`。

### 接入通用任务中心 async-task-service（可选）

agent-service 可把本地 agent 任务生命周期同步到 async-task-service（`:8086`）：

- **mirror 模式**：`AGENT_ASYNC_EXTERNAL_ENABLED=true` + `ASYNC_TASK_BASE_URL=http://async-task-service:8086`。`/agent/tasks/{id}` 仍由 agent 本地 API 提供兼容响应，同一 `taskId` 也出现在 `/async/tasks/{id}`。
- **authoritative 模式**：再加 `AGENT_ASYNC_EXTERNAL_AUTHORITATIVE=true`、`AGENT_ASYNC_WORKER_ID=agent-service-1`、`AGENT_ASYNC_LEASE_SECONDS=300`。worker 执行前先 `/async/tasks/{id}/lease` 认领，被其他活跃 worker 租约持有则跳过；取消时先 `DELETE /async/tasks/{id}` 再用本地 cancellation token 阻止继续写入。
- `AGENT_ASYNC_EXTERNAL_MIRROR_WEBHOOK`（默认 `false`）：设 `true` 时 webhookUrl 交由 async-task-service outbox 投递，agent 本地 notifier 让位避免重复回调。

（异步任务中心自身的 `/async/tasks/**` 端点、SSE 断点续订、worker lease、webhook outbox、JDBC 持久化等，见 README 与 operations 文档。）

---

## 9. 快速对照：选哪个模式

| 场景 | 推荐 |
|---|---|
| 需要模型自主决定用什么工具、多步推理达成开放目标 | 深度 Agent `/agent/run` |
| 可拆成有依赖关系的子任务、想并行 + 综合 | DAG `/agent/dag/run` 或 `plan-run` |
| 单个答案要反复自省打磨到达标 | Reflexion `/agent/reflexive` |
| 离散/分类题想多跑取共识降随机性 | Voting `/agent/vote`（majority） |
| 自由文本题想并行多答再聚合收口 | Voting `/agent/vote`（synthesis） |
| 固定顺序流程 + 步间硬校验 + 跑偏即止 | Chaining `/agent/chain` |
| 想按难度省钱、简单问题走便宜模型 | Cascade `/chat/cascade` |
