# 意图识别与 Agent 执行原理

本文从代码调用链解释平台的意图识别、Agent 编排，以及 ReAct 的
Reasoning → Action → Observation 循环。接口用法和完整配置表见
[Agent 能力与编排指南](agent-guide.md)，自定义动作接入见
[让 Agent 主动调接口](让Agent主动调接口.md)。

## 1. 总体结构

平台把“选择处理路径”和“自主执行任务”分成了两个入口：

```text
POST /chat/auto
  └─ conversation-service：确定性订单快路径 + LLM 意图分类

POST /agent/run
  └─ agent-service：LLM 逐步选择动作，Java 执行动作并控制 ReAct 循环

POST /agent/dag/**
  └─ agent-service：Planner 拆任务 → DAG 分层并行 → 综合 → Critic/Replanner
```

两者不能等同：

- `/chat/auto` 主要解决“这条消息应不应该检索知识库”的路由问题。
- `/agent/run` 解决“为了完成目标，下一步应该调用哪个工具”的自主执行问题。
- 除订单确定性快路径外，`/chat/auto` 当前不会执行 `AgentAction`；需要真正的多步工具调用时应使用 `/agent/run`。

## 2. 意图识别

### 2.1 HTTP 入口和租户记忆键

入口是 `conversation-service` 的 `POST /chat/auto`：

- Controller：`conversation-service/.../routing/ChatAutoController.java`
- 编排服务：`conversation-service/.../routing/QueryRouterService.java`

Controller 从 `TenantContext` 取得当前租户，并将对话记忆键构造成
`<tenantId>::<chatId>`，然后调用 `QueryRouterService.route()`。响应包含：

- `route`：`RAG`、`TOOL` 或 `CHAT`
- `reason`：分类理由
- `classifyMs`：分类耗时
- `answerMs`：回答耗时
- `reply`：最终回复

路由由 `app.conversation.router.enabled` 控制，当前
`application.yml` 默认值为 `true`。

### 2.2 第一层：订单查询确定性快路径

`OrderQueryRoute` 在调用 LLM 之前检查订单意图：

1. 消息包含“订单”或 `order`。
2. 消息包含查询、状态、金额、退款、发货等业务提示，或者能够直接提取订单号。
3. 排除“本项目订单接口怎么实现”“订单表结构”等明显的文档问题。
4. 用正则提取订单号；没有订单号时返回补充参数提示。
5. 通过 `OrderLookupClient` 调用 `order-service`，确定性组装状态、金额、客户和下单日期。

命中这条快路径时不会调用 `QueryClassifier`，避免模型猜测订单事实：

```text
消息
  → OrderQueryRoute.matches()
  → OrderQueryRoute.query()
  → OrderLookupClient.getByNo(orderNo)
  → order-service GET /orders/{orderNo}
```

订单快路径由 `app.conversation.router.order.enabled` 控制，当前默认值为
`true`。跨服务调用使用平台 RestTemplate 拦截器透传租户身份和 trace。

### 2.3 第二层：LLM-as-Router

非订单请求交给 `QueryClassifier`。它是一个 LangChain4j AiService 接口，
由 `RoutingConfig` 使用 `GatewayChatModelFactory.buildDeterministic()` 构建，
即使用确定性判官模型做分类。

分类结果是结构化的 `RouteDecision`：

```java
record RouteDecision(RouteKind kind, String reason) {
}
```

三种类别的含义是：

| 类别 | 含义 | 当前分派 |
|---|---|---|
| `RAG` | 问题依赖项目文档或知识库内容 | `RagPromptAugmenter.contextFor()` 检索，然后把 context 交给 `Assistant` |
| `TOOL` | 理论上需要工具或业务系统 | 非订单请求当前以空 context 调用普通 `Assistant` |
| `CHAT` | 通用解释、普通对话、代码示例 | 以空 context 调用普通 `Assistant` |

分类器异常时，`QueryRouterService` 降级为 `RAG`，以一次额外检索换取更保守的回答。

> **当前边界**：`TOOL` 是路由标签，不代表 `/chat/auto` 已经接入通用工具执行。
> 例如“现在几点”可被分类为 `TOOL`，但非订单 TOOL 目前仍直接调用普通
> `Assistant`。需要调用 `current_time`、`rag_search`、`analytics_sql` 等动作时，
> 使用 `/agent/run`。

### 2.4 渠道侧的轻量意图识别

飞书入站桥还有一套独立的关键词分类器
`channel-service/.../feishu/FeishuIntent.java`：

- 命中退款、退货、投诉、赔偿、`refund`、`chargeback` 等关键词 → `WORKFLOW`
- 其他消息 → `CHAT`

它是零 LLM 调用的纯函数，适合低延迟客服分流，不与 `/chat/auto` 的
`RAG/TOOL/CHAT` 分类共享模型或枚举。

## 3. ReAct Agent 的实现

### 3.1 不是 LangChain4j 内置 Agent

项目使用 LangChain4j 构建结构化 LLM 接口，但 ReAct 状态机由
`DeepAgentService` 自行实现：

- `AgentBrain`：每轮产生一个结构化决策
- `AgentDecision`：单步决策 DTO
- `AgentAction`：可调用动作的统一接口
- `DeepAgentService`：循环、分派、历史、预算和停止条件
- `AgentRunMapper`：把内部运行轨迹转换成 `platform-protocol` 响应

`AgentConfig` 使用 JSON Mode 模型构建 `AgentBrain`，降低结构化决策因非法
JSON 而解析失败的概率。

### 3.2 动作注册表

所有工具实现统一接口：

```java
public interface AgentAction {
    String name();
    String description();
    String run(String input);
}
```

Spring 将所有启用的 `AgentAction` Bean 注入 `DeepAgentService`。构造器将它们
按小写动作名放入 `Map<String, AgentAction>`。每轮开始前，
`describeActions()` 把动作渲染成：

```text
- rag_search: ...
- analytics_sql: ...
- order_query: ...
- current_time: ...
- finish: 任务已完成，在 finalAnswer 给出最终答案
```

因此：

- `description()` 是模型选择工具和填写 `actionInput` 的主要说明。
- `@ConditionalOnProperty` 决定动作是否进入模型可见的动作清单。
- 新增动作通常只需增加一个 `AgentAction` Bean，不需要修改循环分派代码。

典型动作包括 `rag_search`、`analytics_sql`、`schema_explore`、
`order_query`、`current_time`、工作流动作、MCP、代码执行和浏览器动作。
其中 MCP、代码执行和浏览器等高风险能力默认关闭。

## 4. Reasoning → Action → Observation

### 4.1 Reasoning：模型只决定下一步

每轮调用：

```java
brain.decide(goal, actionsDesc, scratchpad, history)
```

模型看到四部分输入：

- `goal`：原始目标
- `actions`：本轮可用动作及描述
- `scratchpad`：需要跨步骤保留的结论
- `history`：最近若干步的动作和 Observation

模型返回一个 `AgentDecision`：

```java
record AgentDecision(
    String thought,
    String action,
    String actionInput,
    String note,
    String finalAnswer
) {
}
```

字段职责：

- `thought`：当前步骤的临时推理
- `action`：一个动作名，或者 `finish`
- `actionInput`：动作输入
- `note`：需要跨步骤保留到 scratchpad 的结论
- `finalAnswer`：`action=finish` 时的最终回答

### 4.2 Action：Java 按名称执行

如果模型选择 `finish`，循环立即返回 `finalAnswer`。否则
`DeepAgentService.dispatch()`：

1. 处理内置 `delegate`，在最大深度内递归执行子 Agent。
2. 从动作注册表按名称查找 `AgentAction`。
3. 调用 `AgentAction.run(actionInput)`。
4. 将动作异常转换为可观察的错误字符串，使模型下一轮可以换参数或动作。

模型不会直接执行 Java 方法，也不能调用动作清单之外的 Bean。

### 4.3 Observation：动作返回的文本

项目没有独立的 `Observation` 类。Observation 就是
`AgentAction.run()` 返回的字符串：

```java
String observation = dispatch(action, actionInput, depth);
steps.add(new Step(n, thought, action, actionInput, observation));
```

最近步骤会被 `renderHistory()` 格式化为：

```text
1. rag_search(退款审批规则) -> [doc=...] ...
2. order_query(204) -> 订单号：204，状态：已退款
```

下一轮再把这段 `history` 传给 `AgentBrain`，形成闭环：

```text
AgentBrain 产生结构化决策
  → DeepAgentService 分派动作
  → AgentAction 返回 Observation 字符串
  → Step 保存 thought/action/input/observation
  → 最近 Step 渲染成下一轮 history
```

需要特别区分：

- Observation 自动进入 `history`。
- 只有模型返回的 `note` 会追加到 `scratchpad`。
- Observation 不会自动完整复制到 `scratchpad`。
- `history` 受 `history-window` 限制，`scratchpad` 超限时会压缩或丢弃较早内容。

### 4.4 停止条件和护栏

循环控制在 Java 侧，不依赖模型自觉停止：

| 停止原因 | 触发条件 |
|---|---|
| `DONE` | 模型选择 `finish` |
| `MAX_STEPS` | 达到最大步数 |
| `TIMEOUT` | 达到墙钟时间限制 |
| `BUDGET` | 达到近似 token 预算 |
| `LOOP` | 相同 `action + actionInput` 在窗口内重复达到阈值 |
| `CANCELLED` | 异步任务取消或线程中断 |
| `ERROR` | AgentBrain 重试后仍无法产生决策 |

主要参数位于 `app.agent.*`：`max-steps`、`max-wall-clock-ms`、
`max-tokens`、`brain-max-retries`、`max-repeats`、`loop-window`、
`max-scratchpad-chars`、`history-window`、`allow-delegation` 和
`max-depth`。

API 响应会通过 `AgentRunMapper` 返回完整的
`thought/action/actionInput/observation` 轨迹。生产环境是否向最终用户暴露
`thought`，应根据安全和产品策略由上层 API 或前端决定。

## 5. 多 Agent 与其他编排模式

### 5.1 DAG Orchestrator-Workers

`AgentDagService` 支持显式 DAG 和自动规划 DAG：

```text
目标
  → AgentDagPlanner 拆成 1～6 个任务
  → 校验 id、描述、数量和环
  → 按依赖关系拓扑分层
  → 同层任务 CompletableFuture 并行
  → 每个 worker 调用 DeepAgentService
  → 下游任务注入上游 finalAnswer
  → 再调用 DeepAgentService 综合所有任务结果
```

`POST /agent/dag/run` 由调用方提供任务图；
`POST /agent/dag/plan-run` 先调用 LLM Planner 自动生成任务图。两者都有异步版本。

同层并行复用 `agentTaskExecutor`。下游 worker 只接收其 `dependsOn` 指向的
上游结果，不共享一个可变 scratchpad。

### 5.2 Critic/Replanner 闭环

DAG 的 synthesis 完成后，可由 `AgentDagCritic` 对 correctness、
completeness、clarity 评分并加权聚合。分数低于阈值时，
`AgentDagReplanner` 根据旧计划、旧答案和主要问题修订任务图，然后重新执行。

当前默认：

- `AGENT_DAG_REPLAN_ENABLED=true`
- `AGENT_DAG_REPLAN_THRESHOLD=0.75`
- `AGENT_DAG_REPLAN_MAX_REPLANS=1`

异步执行会通过 SSE 发出 `dag-planned`、`dag-level-*`、`dag-worker-*`、
`dag-synthesis-*`、`dag-critique` 和 `dag-replan*` 等阶段事件。

### 5.3 同级编排器

以下编排器与 `DeepAgentService` 是同级能力，不是 ReAct 循环内部的固定阶段：

| 模式 | 实现 | 特点 |
|---|---|---|
| Prompt Chaining | `PromptChainService` | 预定义顺序执行；每步后做长度、包含内容或正则 gate，不通过即短路 |
| Voting | `VotingService` | 同一问题并行调用 N 次；离散问题可 majority，自由文本可 synthesis |
| Reflexion | `ReflexionService` | answer → critique → improve，达到阈值或最大轮次停止 |
| DAG | `AgentDagService` | 不同子任务分层并行，最后综合；可选重规划 |

`DataAnalystPlanner` 和 `ProcessPlanner` 是领域化 Planner，分别将数据分析和
业务流程目标转成任务图，再复用同一个 DAG 引擎。

## 6. 关键代码索引

| 关注点 | 代码 |
|---|---|
| `/chat/auto` 入口 | `conversation-service/.../routing/ChatAutoController.java` |
| 意图分类提示词 | `conversation-service/.../routing/QueryClassifier.java` |
| 路由分派 | `conversation-service/.../routing/QueryRouterService.java` |
| 订单快路径 | `conversation-service/.../routing/OrderQueryRoute.java` |
| ReAct 决策提示词 | `agent-service/.../AgentBrain.java` |
| 单步结构化决策 | `agent-service/.../AgentDecision.java` |
| 动作契约 | `agent-service/.../AgentAction.java` |
| ReAct 循环 | `agent-service/.../DeepAgentService.java` |
| LLM 与服务装配 | `agent-service/.../AgentConfig.java` |
| DAG 编排 | `agent-service/.../dag/AgentDagService.java` |
| DAG Planner | `agent-service/.../dag/AgentDagPlanner.java` |
| Reflexion | `agent-service/.../reflexion/ReflexionService.java` |
| Prompt Chain | `agent-service/.../chaining/PromptChainService.java` |
| Voting | `agent-service/.../voting/VotingService.java` |
| 异步任务 | `agent-service/.../async/AgentAsyncTaskService.java` |

## 7. 已验证的测试入口

相关行为由以下聚焦测试覆盖：

- `conversation-service/.../routing/QueryRouterServiceTest.java`：RAG/CHAT/TOOL
  分派、分类异常降级、订单快路径绕过 LLM。
- `conversation-service/.../routing/OrderQueryRouteTest.java`：订单意图和订单号提取。
- `agent-service/.../DeepAgentServiceTest.java`：finish、动作 Observation、循环检测和委派深度。
- `agent-service/.../dag/AgentDagServiceTest.java`：拓扑分层、并行任务、环检测和重规划。
- `agent-service/.../chaining/PromptChainServiceTest.java`：顺序执行和 gate 短路。
- `agent-service/.../voting/VotingServiceTest.java`：并行投票和聚合。
- `agent-service/.../reflexion/ReflexionServiceTest.java`：评审改进循环和阈值停止。
