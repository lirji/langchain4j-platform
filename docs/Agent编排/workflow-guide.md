# 工作流指南：Flowable 业务流程引擎 + LLM 编排模式

> "workflow" 在本平台有**两个完全不同的含义**，本指南把两者一次讲清并各自给出新微服务下的端点 / 开关 / curl。

| 你想找的 "workflow" | 是什么 | 载体服务 | 端点前缀 | 默认状态 |
|---|---|---|---|---|
| **业务流程引擎**（本文 A 部分） | Flowable BPMN 引擎：退款审批这类"挂起等人工、跨天、可持久化"的长流程 | `workflow-service`（:8082） | `/workflow/**` | **默认开**（`WORKFLOW_ENABLED=true`，需可达 MySQL） |
| **LLM 编排模式**（本文 B 部分） | Anthropic《Building Effective Agents》的 5 种 workflow 模式 + agent：prompt chaining / routing / parallelization / orchestrator-workers / evaluator-optimizer | `agent-service`（:8085）+ `conversation-service`（:8081） | `/agent/**`、`/chat/auto` | 编排端点多为常开；路由默认开 |

这两个 "workflow" **没有代码关系**：A 是「业务流程赛道」的词（人工审批、状态机、DB 持久化），B 是「LLM 编排赛道」的词（用预定义代码路径编排多次模型调用）。唯一的交汇点在 C 部分——**渠道意图路由**：飞书入站消息命中"退款/投诉"时分流到 A 的退款工作流，其余走 B/对话。

阅读约定（与 `rag-guide.md`、`agent-guide.md` 一致）：

- **经边缘网关**：`http://localhost:8080` + `-H 'X-Api-Key: dev-key-acme'`。网关校验 api-key → 签发短时内部 JWT → 按路径路由下游；租户身份随 JWT 传播、下游还原进 `TenantContext`。
- **scope**：`dev-key-acme`（租户 `acme`）持 `[chat, approve, agent, channel, ...]`，本文所有 curl 均可用它。发起退款只需已认证；审批相关（`/workflow/tasks*`、`/workflow/data`）需 `approve` scope；调编排模式需 `agent` scope。
- **直连**（仅本地调试）：workflow :8082、agent :8085、conversation :8081、channel :8087。
- **默认开关**：每项标注默认状态与开启环境变量。相关文档：[`agent-guide.md`](agent-guide.md)（编排模式深调）、[`dingtalk-guide.md`](../互操作渠道/dingtalk-guide.md)（另一条渠道）、[`eventbus-guide.md`](../平台工程/eventbus-guide.md)（Kafka 终态回推）、[`operations.md`](../参考/operations.md)（运行配置）、[`api-reference.md`](../参考/api-reference.md)（接口速查）、[`架构文档.md`](../参考/架构文档.md)（总体架构）。

---

# A. 业务流程引擎（Flowable / 退款审批）

**一句话定位**：`workflow-service`（:8082）用 Flowable 7.1.0 BPMN 引擎跑退款审批长流程，整套由 `WORKFLOW_ENABLED` 开关（默认开，需可达 MySQL；置 `false` 则零开销、不装配任何 Bean）；端点 `/workflow/**`，首次自动建 `flowable` 库 + ~25 张 `ACT_*` 表。

## A.1 为什么是 Flowable，而不是扩现有 async 状态机

平台已有一个"一次性后台任务"状态机（`async-task-service`：`PENDING → RUNNING → SUCCEEDED|FAILED|CANCELLED`，可选内存/JDBC 持久化）。它**不是工作流引擎**——没有"挂起等人"、没有多步分支编排。客服退款这类流程恰恰需要这些：

```
用户问 → bot 答 → 命中"退款/改单/投诉升级"意图
       → 抽工单 → 路由人工 → [挂起，等审批，几分钟到几天] → 人工通过/驳回
       → bot 把结果回推用户 → 关单
```

那个 `[挂起等审批]` 期间服务可能重启，纯内存状态机扛不住。Flowable 的 `UserTask` + 引擎表把"挂起 + 持久化 + 多步编排"一次解决。**这是引入一个有状态 MySQL 依赖的唯一理由**——只有它能保证"挂起等审批、期间重启不丢"。

## A.2 引擎装配的三个坑（这套代码库特有）

引擎在 `WorkflowConfig` 里手动 `buildProcessEngine()` 构建（**不是** Flowable Spring Boot starter），因为 starter 只要在 classpath 就会强依赖一个自动装配的主 `DataSource`，与本项目"默认无主 SQL 源"冲突。手动构建让引擎只在 `WORKFLOW_ENABLED=true` 时才装配，关闭时零开销——与 NL2SQL 手动建只读 DataSource 同一套路。

| 坑 | 现象 | 落地对策 |
|---|---|---|
| **坑 1：无主 SQL 源** | 平台默认无主 `DataSource`（`DataSourceAutoConfiguration` 已排除），但 Flowable 强依赖 JDBC | `WorkflowConfig` 手动建**独立命名** Hikari 池 `workflowDataSource`（MySQL，驱动复用 `mysql-connector-j`），**不**注册成全局 `@Primary`，与 NL2SQL 只读库互不污染 |
| **坑 2：async executor 的 ThreadLocal 真空** | Flowable async executor 线程不过滤器链，`TenantContext`（ThreadLocal）为空 → ServiceTask 里调 LLM 拿不到租户 | `setAsyncExecutorActivate(false)`：所有 ServiceTask 在**触发线程**（用户/审批人请求线程，已过滤器链、租户有值）同步执行；delegate 内仍从流程变量 `tenantId` 防御性重设 `TenantContext`，将来切 async executor 业务不用改 |
| **坑 3：租户隔离** | Flowable 原生 start-tenant 需 tenant-aware 部署 + fallback 配置 | 改用**流程变量** `tenantId`：发起时写入，待办/完成/查实例/删除都按 `processVariableValueEquals("tenantId", 当前租户)` 过滤——更简单且同样严格，租户 B 拿不到也 complete 不了租户 A 的任务 |

> 版本已钉死 **Flowable 7.1.0**：7.x 系专为 Spring Boot 3 / Spring 6 / Java 17+ 而生（6.x 是 Spring Boot 2）。BPMN 由 `WorkflowConfig` **显式部署**（`createDeployment().addClasspathResource(...).enableDuplicateFiltering().deploy()`），比自动部署可靠——手动 `buildProcessEngine()` 下自动部署不一定触发，会报 `No process definition found for key 'refundApproval'`。

## A.3 退款审批流程（`refund-approval.bpmn20.xml`，process id = `refundApproval`）

```
Start(入参: tenantId, userId, chatId, message)
  ▼
ServiceTask "assess"(抽工单)  ──► 调 WorkflowAiClient.extractTicket(message)
  │                              写流程变量: priority, category, summary
  ▼
ExclusiveGateway              ──► needsApproval = priority ∈ {HIGH, CRITICAL}
  ├─ true  ─► UserTask "approveRefund"(审批退款)  ← 挂起等人（DB 持久化）
  │              complete 时带变量: approved(boolean), comment
  │              ▼  Gateway: ${approved}?
  │              ├─ true  ─► ServiceTask "resolve"(生成答复)
  │              └─ false ─► ServiceTask "reject"(驳回)
  └─ false ─────────────────► ServiceTask "resolve"(生成答复)
  ▼
End
```

- 三个 ServiceTask 用 `flowable:expression="${serviceTaskDelegates.assess(execution)}"` 调 Spring bean。
- `resolve`/`reject` 产出的答复（`reply`）**不进流程变量**（长文本会灌爆 `ACT_HI_VARINST`），而是落业务表 `WF_REPLY`（见 A.5 #5），由 status 端点取回。`priority`/`category`/`summary` 这种短字段仍留流程变量。
- **触发审批分支需描述体现紧迫/业务影响**（deadline、投诉升级、阻断性影响）；`priority` 由模型判定，措辞平淡会被判 LOW 走自动受理。

## A.4 REST 端点

| 方法 | 路径 | scope | 行为 |
|---|---|---|---|
| `POST` | `/workflow/refund/start` | 已认证 | 启流程。低风险自动受理 → `{status:COMPLETED, reply}`；高风险挂起 → `{status:WAITING_APPROVAL, taskId}`。body 可选 `dedupeId`（幂等）/`webhookUrl`（终态回推） |
| `GET` | `/workflow/tasks` | `approve` | 本租户待审 UserTask 列表（含 `assignee`） |
| `POST` | `/workflow/tasks/{taskId}/claim` | `approve` | 认领（设 assignee=当前用户）；已被他人领 → **409** |
| `POST` | `/workflow/tasks/{taskId}/unclaim` | `approve` | 取消认领，放回待领池 |
| `POST` | `/workflow/tasks/{taskId}/complete` | `approve` | body `{approved, comment}` → 同步跑 resolve/reject → `{reply}`。并发双重审批 → **409** |
| `GET` | `/workflow/instances/{instanceId}` | 已认证（本租户） | 实例状态 + reply；跨租户 → 404 |
| `DELETE` | `/workflow/data?chatId=` | `approve` | PII 合规删除：清本租户该 chatId 的运行/历史实例 + `WF_REPLY` + outbox |

### 全流程 curl（三条分支）

**① 低风险自动受理**（直接完成，无需人工）：

```bash
curl -X POST http://localhost:8080/workflow/refund/start \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"chatId":"u1","message":"杯子颜色不太喜欢想退，不急"}'
# → {"instanceId":"...","status":"COMPLETED","reply":"（受理话术）","taskId":null,"priority":"LOW","deduplicated":false}
```

**② 高风险 → 转人工 → 批准**：

```bash
# start：高风险挂起
curl -X POST http://localhost:8080/workflow/refund/start \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"chatId":"u2","message":"#88231 付款 5 天还不发货，再不处理就投诉"}'
# → {"instanceId":"I","status":"WAITING_APPROVAL","reply":null,"taskId":"T","priority":"HIGH","deduplicated":false}

# 审批人查待办（需 approve scope）
curl http://localhost:8080/workflow/tasks -H 'X-Api-Key: dev-key-acme'
# → [{"taskId":"T","name":"审批退款","instanceId":"I","priority":"HIGH","summary":"...","assignee":null}]

# （可选）认领，避免多人同审
curl -X POST http://localhost:8080/workflow/tasks/T/claim -H 'X-Api-Key: dev-key-acme'

# 批准
curl -X POST http://localhost:8080/workflow/tasks/T/complete \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"approved":true,"comment":"已核实，同意退款"}'
# → {"instanceId":"I","status":"COMPLETED","reply":"（LLM 答复）","approved":true}

# 查终态
curl http://localhost:8080/workflow/instances/I -H 'X-Api-Key: dev-key-acme'
# → {"instanceId":"I","status":"COMPLETED","reply":"（LLM 答复）"}
```

**③ 高风险 → 驳回**：complete 时 `{"approved":false,"comment":"不符合退款条件"}` → `reply` 为含审批意见的驳回话术。

### 幂等 + 可靠回推（渠道场景）

```bash
curl -X POST http://localhost:8080/workflow/refund/start \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"chatId":"feishu:ou_x","message":"...","dedupeId":"feishu-msg-abc","webhookUrl":"https://example.com/hooks/wf"}'
```

- 传 `dedupeId`（渠道消息 id）→ businessKey `tenant:chatId:dedupeId`，重推同一诉求只起一个流程（防飞书 ~3s ack 超时重推起 N 个流程 + N 个审批任务）；返回同一 `instanceId` + `deduplicated:true`。
- 传 `webhookUrl` → 流程终态时把答复经 outbox **可靠回推**（见 A.6），否则客户端轮询 status 端点。

### PII 合规删除

```bash
curl -X DELETE 'http://localhost:8080/workflow/data?chatId=u2' -H 'X-Api-Key: dev-key-acme'
# → {"chatId":"u2","purgedInstances":1}
```

## A.5 上线硬化：审批类长流程的 10 个"咬人"问题（全部已落地）

curl happy path 测不出这些——全是"挂起期间出意外 / 渠道重试 / 跑久了 / 多人并发"才暴露的。10 项均已实现：

| # | 问题 | 触发信号 | 对策 |
|---|---|---|---|
| 1 | **审批超时 / 永久挂起** | 审批人漏看 → UserTask 永挂，用户永远收不到回复 | `ApprovalTimeoutSweeper` `@Scheduled` 扫挂起超 `approval-timeout`（默 PT24H）的任务 → `expireTask` 自动驳回 + 审计 `approval.timeout`。走**调度扫描**而非 BPMN boundary timer（timer 需 async executor，撞坑 2） |
| 2 | **幂等 / 重复启动** | 飞书重推同一条消息 → 一个诉求起 N 个流程 | `dedupeId` → 稳定 businessKey 查重复用既有实例；无 dedupeId 走随机 UUID（不去重） |
| 3 | **complete 内同步跑 LLM 的事务边界 / 失败补偿** | 审批人点"通过"后 LLM 挂 → 事务回滚、**人工审批决定一并丢失**、任务退回 active、审批人吃 500 | `ServiceTaskDelegates.withRetry` 有界重试（`llm-max-attempts`，默 2）+ **降级兜底、绝不向 Flowable 抛异常**。事务边界 = 「人工决定 + 一定有终态 reply」原子提交，LLM 是事务内 best-effort |
| 4 | **历史表无限增长** | `ACT_HI_*` 无 TTL，跑几个月几千万行拖垮查询/备份 | `WorkflowConfig.setHistory("audit")`（不用 `full`）+ `WorkflowHistoryCleaner` `@Scheduled` 删超 `history-retention`（默 P30D）的已结束实例 + `WF_REPLY` 行 |
| 5 | **大文本进流程变量** | `reply` 长答复灌 `ACT_RU/HI_VARIABLE`，放大 #4 | `reply` 挪出流程变量到业务表 `WF_REPLY`（`WorkflowReplyStore`，建在 workflow 数据源、写 join 同事务 → 原子 + 重启不丢） |
| 6 | **流程定义版本化 / in-flight 实例** | 改 BPMN 重部署，旧实例仍按旧定义跑；结构性改动跨版本不兼容 | 续旧版是 Flowable 原生默认；`logVersionTopology` 启动打印各版本在途实例数（旧版有在途则 WARN）。策略：微调直接重部署，结构性改动换 `process id`（新 key） |
| 7 | **任务分配粒度 + 并发双重审批** | 两人同点同一 task → 第二次 complete 抛 `FlowableObjectNotFoundException`(500) | `claim`/`unclaim` 端点（已被他人领 → 409）；`complete`/`expireTask` 把竞态异常翻成友好 **409**；`TaskView` 加 `assignee` |
| 8 | **回推"最后一公里"可靠性** | 流程 COMPLETED 但回推失败 → 系统以为办完、用户在干等 | 持久化 **outbox**（`WF_OUTBOX` 表）+ `WorkflowOutboxDispatcher` `@Scheduled` 指数退避重投，4xx/超阈 → DEAD DLQ，重启后接着投。补内存重试"进程一挂就丢"的缺口。见 A.6 |
| 9 | **工作流可观测性** | LLM 指标不含工作流维度：挂起数 / 审批时长 / 超时率 / 分支占比 | `WorkflowMetrics`（Micrometer）：`workflow.tasks.pending`(gauge) / `workflow.approval.duration`(timer) / `workflow.completed`(counter, tag=outcome) / `workflow.started`(tag=priority) / `workflow.approval.timeout`(counter)，走 `/actuator/prometheus` |
| 10 | **PII 合规删除** | `message`/`summary`/`reply` 含 PII 进了持久化表 | `WorkflowService.purge(chatId)` 删运行/历史实例 + `WF_REPLY` + `WF_OUTBOX`；`DELETE /workflow/data`；审计 `workflow.data_purged` |

**#3 的降级取向**（关键权衡）：`assess`（抽工单）降级 = **强制 `priority=HIGH` 转人工**，不是默认 LOW——抽取失败时风险未知，宁可多一道人工审，绝不默认放过潜在高风险退款；`resolve`/`reject` 降级 = 写兜底话术。降级时审计 `reply.degraded`。

## A.6 终态回推：三种通知模式

流程到终态（自动受理 / 人工 complete / 超时驳回）时，若发起方传了 `webhookUrl`，答复需可靠投出去。由 `app.workflow.terminal-notification.mode` 选择：

| 模式（`WORKFLOW_TERMINAL_NOTIFICATION_MODE`） | 机制 | 适用 |
|---|---|---|
| `local`（**默认**） | workflow 自己的 `WF_OUTBOX` 表 + `WorkflowOutboxDispatcher` 指数退避重投；成功 `DELIVERED`，4xx/超阈 `DEAD`（DLQ）。签名走 `WorkflowWebhookSigner`(HMAC-SHA256) | 零依赖 dev/test；单服务部署 |
| `async-task` | 终态写入 `async-task-service`（:8086，`ASYNC_TASK_BASE_URL`）由共享任务中心负责 webhook outbox；`fallback-to-local-outbox=true`（默认）时失败回落本地 outbox | 想让回推与其他异步任务统一管控 |
| `kafka` | 终态事件 outbox 权威写与 Kafka 事件发布**进同一 Flowable 终态事务**（`WorkflowTerminalOutboxListener` 写 → `WorkflowTerminalEventRelay` relay 到 Kafka），由 `channel-service` 的 `WorkflowTerminalKafkaListener` 消费回推。需 `platform.eventbus.enabled=true` + producer `transactional-id-prefix` | 生产多实例 / 事件驱动，见 [`eventbus-guide.md`](../平台工程/eventbus-guide.md) |

无论哪种模式，`WorkflowTerminalEvent` 都在进程内发布——本地 `@EventListener`（如渠道回推监听器）可据此把 `feishu:` 前缀 chatId 的终态主动推回用户。

## A.7 ServiceTask 的 AI 来源：http vs local

ServiceTask 里"抽工单 / 生成答复"的 LLM 能力由 `app.workflow.ai-client.mode`（`WORKFLOW_AI_CLIENT_MODE`）决定：

- `http`（**默认，推荐 prod**）：经带 tenant/trace 传播的 `RestTemplate` 调 `conversation-service`（`CONVERSATION_BASE_URL`，默 :8081），workflow **彻底断开对本地 `ChatModel`/gateway-client 的依赖**。HTTP 失败抛异常 → 交 `ServiceTaskDelegates` 的 withRetry + 降级兜底接管（#3）。因在 Flowable 同步事务内调用，`connect/read timeout` 均设紧超时（1s/3s）。
- `local`：保留 `DefaultWorkflowAiClient` 本地兜底（直连网关 `ChatModel`，无则确定性兜底），用于零依赖 dev/test 或 conversation-service 不可达。

## A.8 RBAC / 审计 / 租户隔离

- **RBAC**：`/workflow/tasks*`、`/workflow/data` 要求 `SCOPE_approve`（controller 内 `TenantContext.current().hasScope("approve")` 校验，缺则 403）；`/workflow/refund/start` 任意已认证 key 即可。
- **审计**（复用 `platform-audit`，落 `logs/audit.jsonl`）：`workflow.started` / `approval.requested` / `approval.granted|rejected` / `approval.timeout` / `workflow.completed` / `reply.degraded` / `workflow.history_pruned` / `workflow.data_purged` / `workflow.push_*`，`tenantId` 正确归属。
- **租户隔离**：全走流程变量 `tenantId` 过滤（坑 3）。租户 B 在 `/workflow/tasks` 看不到、也 complete 不了租户 A 的任务；`getInstance` 跨租户按 404 处理（不泄露存在性）。

## A.9 启动与本地验证

```bash
# 前置：本机 MySQL（首次自动建 flowable 库 + ACT_* 表）；ServiceTask AI 走 conversation（默认 http 模式）或 local 兜底
WORKFLOW_ENABLED=true WORKFLOW_DB_PASSWORD=root \
  mvn -pl workflow-service -am spring-boot:run     # :8082

# 或整栈：docker compose -f deploy/docker-compose.yml up --build
```

压测超时/幂等时把阈值压小（沿用相对绑定的 `app.workflow.*` env）：

```bash
WORKFLOW_ENABLED=true WORKFLOW_DB_PASSWORD=root \
  APP_WORKFLOW_APPROVAL_TIMEOUT=PT20S APP_WORKFLOW_TIMEOUT_SWEEP_INTERVAL_MS=5000 \
  mvn -pl workflow-service -am spring-boot:run
# 高优先级 start 后不 complete，等 ~25s → GET /workflow/instances/{id} 为 COMPLETED，reply 为超时驳回话术
```

## A.10 开关速查（workflow-service）

| 属性 / 环境变量 | 默认 | 含义 |
|---|---|---|
| `app.workflow.enabled` / `WORKFLOW_ENABLED` | `true` | 总开关（需可达 MySQL），置 `false` 时整套 Bean 不装配 |
| `app.workflow.datasource.url` / `WORKFLOW_DB_URL` | `jdbc:mysql://localhost:3306/flowable?...` | Flowable 引擎数据源（独立 Hikari 池） |
| `app.workflow.datasource.username` / `WORKFLOW_DB_USER` | `root` | 数据源用户 |
| `app.workflow.datasource.password` / `WORKFLOW_DB_PASSWORD` | `root`(yml)/空(默认) | 数据源密码 |
| `app.workflow.approval-timeout` / `APP_WORKFLOW_APPROVAL_TIMEOUT` | `PT24H` | 审批超时自动驳回阈值（#1） |
| `app.workflow.timeout-sweep-interval-ms` | `60000` | 超时扫描间隔 |
| `app.workflow.llm-max-attempts` | `2` | ServiceTask LLM 有界重试次数（#3） |
| `app.workflow.llm-retry-backoff-ms` | `500` | 重试退避 |
| `app.workflow.history-retention` | `P30D` | 历史实例 + WF_REPLY 保留期（#4） |
| `app.workflow.history-cleanup-interval-ms` | `3600000` | 历史清理间隔 |
| `app.workflow.ai-client.mode` / `WORKFLOW_AI_CLIENT_MODE` | `http` | ServiceTask AI 来源：`http`(conversation) / `local`(兜底) |
| `app.workflow.ai-client.conversation-base-url` / `CONVERSATION_BASE_URL` | `http://localhost:8081` | conversation 基址 |
| `app.workflow.terminal-notification.mode` / `WORKFLOW_TERMINAL_NOTIFICATION_MODE` | `local` | 终态回推：`local` / `async-task` / `kafka` |
| `...terminal-notification.async-task-base-url` / `ASYNC_TASK_BASE_URL` | `http://localhost:8086` | async-task 模式基址 |
| `...terminal-notification.fallback-to-local-outbox` / `WORKFLOW_TERMINAL_FALLBACK_LOCAL` | `true` | async-task 失败回落本地 outbox |
| `app.workflow.outbox.max-attempts` | `6` | 本地 outbox 最大投递次数，超过进 DEAD |
| `app.workflow.outbox.base-backoff-ms` | `5000` | outbox 指数退避基数 |

---

# B. LLM 编排模式（Anthropic《Building Effective Agents》光谱）

**一句话定位**：Anthropic 把 LLM 系统分成 **workflow**（预定义代码路径编排 LLM 调用）与 **agent**（LLM 自己决定流程）。本平台把该文的 **5 种 workflow 模式 + agent** 全部落地，主要在 `agent-service`（:8085），路由落在 `conversation-service`（:8081）。编排端点在 `AGENT_ENABLED=true`（默认 true）时即挂载。

> ⚠️ 这里的 "workflow" 是 **LLM 编排模式**，与 A 部分的 Flowable 业务流程引擎**不是一回事**。本节侧重"模式↔端点↔开关"映射；每种模式的深度调优开关见 [`agent-guide.md`](agent-guide.md)。

## B.0 5 模式 ↔ 代码 ↔ 端点 ↔ 开关（单体端点已全部重映射到新微服务）

| Anthropic 模式 | 一句话 | 新端点（经 :8080） | 载体 | 开关（新） | 单体旧端点 |
|---|---|---|---|---|---|
| **Prompt Chaining** | 固定顺序链，步间插确定性 gate | `POST /agent/chain` | agent | `app.agent.chaining.steps`（无独立 enable，空则 400） | `/chat/chain` |
| **Routing** | 分类 → 分派到专门链路 | `POST /chat/auto` | conversation | `CONVERSATION_ROUTER_ENABLED`（默认 true） | `/chat/auto` |
| **Parallelization · Sectioning** | 拆**不同**独立子任务并行 | `POST /agent/dag/run`、`/agent/dag/plan-run` | agent | 常开（`AGENT_DAG_MAX_TASKS`=6） | `/chat/multi-agent` |
| **Parallelization · Voting** | **同一**任务并行多跑取共识 | `POST /agent/vote` | agent | 常开（`AGENT_VOTING_*`） | `/chat/vote` |
| **Orchestrator-Workers** | 中枢动态拆任务 → worker → 综合 | `POST /agent/dag/plan-run`（+ replan 闭环） | agent | 常开；replan `AGENT_DAG_REPLAN_ENABLED`（默认 true） | `/chat/multi-agent` |
| **Evaluator-Optimizer** | 生成 → 评估 → 反馈循环 | `POST /agent/reflexive`（+ `/stream`） | agent | 常开（`AGENT_REFLEXION_*`） | `/chat/reflexive` |
| *(Agent，非 workflow)* | LLM 自己决定下一步 | `POST /agent/run` | agent | 常开（`AGENT_ENABLED`=true） | `/agent/run` |

**编排关系**：Chaining / Voting / Reflexion / DAG 都是 `DeepAgentService`（ReAct）的**同级 sibling 编排器**，不塞进 ReAct 内部，各走独立端点，共享 agent-service 的鉴权链（内部 JWT + 多租户 + 限流 + 配额）、`agentTaskExecutor`、评分/聚合器与 SSE 桥接。它们**可组合**（路由后走链、链的某步内部投票、Orchestrator 的 worker 本身是 Evaluator-Optimizer……）——正如 Anthropic 原文强调"从简单开始、按需组合"。

## B.1 Prompt Chaining —— `POST /agent/chain`

预定义顺序链 + 步间确定性 gate。步骤顺序与 gate 写在配置里、不由模型决定流程，因此可重复、可控、可单测。`gate` 是关键——把跑偏的中间结果拦在早期，别继续喂下去烧后续 token；不过就**短路终止**（返回 `completed=false` + 卡点）。

链步骤在 `app.agent.chaining.steps` 定义（**默认空 → 端点返回 400**）。每步字段：`name` / `instruction` / `gate-min-length` / `gate-must-contain` / `gate-must-match`（正则 find）。改链只动 yml，不动 Java。

```yaml
# agent-service application.yml
app:
  agent:
    chaining:
      steps:
        - name: outline
          instruction: 为输入主题列一个简明提纲
          gate-min-length: 20
        - name: draft
          instruction: 把上一步的提纲扩写成一段中文说明
          gate-min-length: 80
```

```bash
curl -X POST http://localhost:8080/agent/chain \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"input":"LangChain4j 的 RAG 能力"}'
# 未配置 steps → 400 {"error":"no chain steps configured (app.agent.chaining.steps)"}
# 配置后 → {"input","steps":[{"name","output","gatePassed","gateReason"}],"finalOutput","completed","tenantId"}
```

## B.2 Routing —— `POST /chat/auto`

混合 Router：订单问题先由 `OrderQueryRoute` 做确定性高置信识别，提取订单号后携带当前租户身份调用 order-service，并以 `TOOL` 路由返回；其它问题再由 `QueryClassifier`（temp=0 判官模型）分到 `RAG` / `TOOL` / `CHAT` 三条链路。默认开（`CONVERSATION_ROUTER_ENABLED=true`，订单快路径另由 `CONVERSATION_ROUTER_ORDER_ENABLED=true` 控制）；置总开关为 `false` 时端点返回明确禁用提示。记忆键与 `/chat` 一致（`<tenantId>::<chatId>`），普通 LLM 对话共享多轮记忆，确定性订单答复不进入 LLM 记忆。

```bash
# 启用：CONVERSATION_ROUTER_ENABLED=true 起 conversation-service
curl -X POST 'http://localhost:8080/chat/auto?chatId=u1' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"message":"帮我查下最近的退款政策"}'
# 未启用 → {"error":"Query router not enabled. Set app.conversation.router.enabled=true.", "chatId","tenantId"}
# 启用后 → {"reply","route":"RAG|TOOL|CHAT","reason","classifyMs","answerMs","chatId","tenantId","userId"}
```

> 注意 `chatId` 是 query 参数，`message` 在 body。

## B.3 Parallelization · Sectioning（DAG）—— `POST /agent/dag/run` / `/agent/dag/plan-run`

拆**不同**独立子任务并行：Planner 产出 DAG → 按拓扑分层并行执行 → Synthesizer 综合。两个入口：

- `/agent/dag/run`：你自己给 `tasks`（每个 `{id, description, dependsOn}`）。
- `/agent/dag/plan-run`：只给 `goal`，由 Planner 自动规划 DAG（对应旧单体的自动多 Agent）。
- 各自还有 `/async` 变体（返回 202 + 任务，配 SSE 进度）。

```bash
# 显式 DAG
curl -X POST http://localhost:8080/agent/dag/run \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"goal":"对比 A、B 两个方案",
       "tasks":[{"id":"t1","description":"分析方案 A"},
                {"id":"t2","description":"分析方案 B"},
                {"id":"t3","description":"综合对比","dependsOn":["t1","t2"]}]}'

# 自动规划 DAG
curl -X POST http://localhost:8080/agent/dag/plan-run \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"goal":"对比向量库 Qdrant 与 pgvector 的取舍"}'
# → {"goal","levels":[["t1","t2"],["t3"]],"taskResults":[...],"synthesis":{...},"tenantId","attempts":[...],"acceptedByThreshold"}
```

## B.4 Parallelization · Voting —— `POST /agent/vote`

**同一问题并行跑 N 次 + 聚合取共识**，降低单次随机性。与 Sectioning 互补：Sectioning 并行**不同**子任务，Voting 并行**同一**任务。两种策略（`AGENT_VOTING_STRATEGY`）：

- `majority`（**确定性**多数表决，默认）：归一化（trim+lower）后计票取最高，`agreement=胜出票/总票`，`< AGENT_VOTING_MIN_AGREEMENT`（默 0.5）标低置信。**仅适用离散/分类题**（是否违规、情感极性、单选标签）——自由文本每次措辞不同、几乎必然不达标。
- `synthesis`：聚合器 LLM（temp=0 判官变体）把 N 票综合成共识答案，适用自由文本题。

```bash
curl -X POST http://localhost:8080/agent/vote \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"question":"这条评论是否违规？只回答 是/否","n":5}'
# → {"question","votes":[...],"strategy":"majority","decision":"是","agreement":0.8,"confident":true,"tenantId"}
# n 可选，缺省用 AGENT_VOTING_N（默 3）
```

## B.5 Orchestrator-Workers —— `POST /agent/dag/plan-run`（+ replan）

Orchestrator-Workers 是 Sectioning 的动态版：中枢（Planner）动态拆任务派给 worker，再综合。落在同一个 DAG 服务，额外带 **replan 闭环**——综合结果经 `AgentDagCritic` 三维加权评分，低于 `AGENT_DAG_REPLAN_THRESHOLD`（默 0.75）则重规划（最多 `AGENT_DAG_REPLAN_MAX_REPLANS` 次）。replan 默认开（`AGENT_DAG_REPLAN_ENABLED=true`）；响应的 `attempts[]` 记录各轮评分、`acceptedByThreshold` 标是否达阈收敛（置 `false` 则跳过重规划）。

## B.6 Evaluator-Optimizer（Reflexion）—— `POST /agent/reflexive` / `/agent/reflexive/stream`

生成 → 评估 → 反馈循环：单答案 `answer → critique → improve`，三维加权（`AGENT_REFLEXION_WEIGHT_{CORRECTNESS,COMPLETENESS,CLARITY}`，默 0.4/0.4/0.2）聚合达 `AGENT_REFLEXION_THRESHOLD`（默 0.75）即停，最多 `AGENT_REFLEXION_MAX_ATTEMPTS`（默 2）次 improve。评分复用 DAG 的 `AgentDagCritic` / `CritiqueAggregation`。

```bash
# 同步
curl -X POST http://localhost:8080/agent/reflexive \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"question":"用一句话解释 CAP 定理"}'
# → {"question","finalAnswer","attempts":[{"n","answer","aggregate","correctness","completeness","clarity","mainIssue"}],"acceptedByThreshold","tenantId"}

# SSE 流式：分阶段推 attempt-start / answer / critique / done 事件
curl -N -X POST http://localhost:8080/agent/reflexive/stream \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"question":"用一句话解释 CAP 定理"}'
```

## B.7 Agent（自主）—— `POST /agent/run`

光谱的最右端：不是预定义 workflow，而是 LLM 自己 plan → act → observe 的开放式 ReAct 循环。开关 `AGENT_ENABLED`（默 true）。它有内置动作、异步任务、SSE、DAG 委派等一整套——**完整说明见 [`agent-guide.md`](agent-guide.md)**，本文不重复。

## B.8 开关速查（编排模式）

| 模式 | 环境变量 / 属性 | 默认 | 端点 |
|---|---|---|---|
| Prompt Chaining | `app.agent.chaining.steps`（yml，无 enable，空则 400） | 空 | `/agent/chain` |
| Routing | `CONVERSATION_ROUTER_ENABLED` | `true` | `/chat/auto` |
| DAG (Sectioning) | `AGENT_DAG_MAX_TASKS` | `6` | `/agent/dag/run`、`/agent/dag/plan-run`(+`/async`) |
| Orchestrator replan | `AGENT_DAG_REPLAN_ENABLED` | `true` | `/agent/dag/plan-run` |
| — replan 阈值 | `AGENT_DAG_REPLAN_THRESHOLD` / `_MAX_REPLANS` | `0.75` / `1` | — |
| Voting | `AGENT_VOTING_N` / `AGENT_VOTING_STRATEGY` / `AGENT_VOTING_MIN_AGREEMENT` | `3` / `majority` / `0.5` | `/agent/vote` |
| Reflexion | `AGENT_REFLEXION_THRESHOLD` / `_MAX_ATTEMPTS` | `0.75` / `2` | `/agent/reflexive`(+`/stream`) |
| — Reflexion 权重 | `AGENT_REFLEXION_WEIGHT_{CORRECTNESS,COMPLETENESS,CLARITY}` | `0.4/0.4/0.2` | — |
| Agent (ReAct) | `AGENT_ENABLED` / `AGENT_MAX_STEPS` | `true` / `8` | `/agent/run` |

---

# C. 渠道意图路由：把 A 和 B 接起来（飞书）

这是两个 "workflow" 唯一的交汇点——渠道入站消息经**意图路由**分流：退款/投诉命中 → A 的退款工作流；其余 → B/对话。样板是飞书（交互卡片最适合做审批 UI，零额外前端），载体 `channel-service`（:8087），端点 `POST /channel/feishu/events`（单体旧端点 `/channel/feishu/event` 已改为复数），默认关。

- 该端点**不带平台 api-key**（飞书不知道），在 edge-gateway 免鉴权放行，靠飞书 `X-Lark-Signature` 签名 + `verification-token` 验真；`encrypt` 事件先 AES-256-CBC 解密。
- **意图路由默认关**（`app.channel.feishu.intent-routing.enabled=false`）：关时命中也不起工单，行为等价纯对话桥。开启后入站先过 `FeishuIntent`（退款/投诉关键词分类，纯函数）分流到 `/workflow/refund/start`。
- **异步 ack + 主动回推**：收消息 → 异步处理 → controller 立刻 200（满足飞书 3s ack）。CHAT 路径直接回推；高风险 WORKFLOW 先回"已转人工"ack，终态结果经 A.6 的终态事件（`kafka` 模式下由 `WorkflowTerminalKafkaListener` 消费）主动回推用户。
- **v1 单租户**：一个飞书应用绑一个平台租户（`app.channel.feishu.tenant-id`，默 `default`）；多租户 `tenant_key→tenantId` 映射留后续。

配置（`app.channel.feishu.*`，可经 Spring relaxed-binding 环境变量如 `APP_CHANNEL_FEISHU_ENABLED` 覆盖；application.yml 未定义专属别名，与 dingtalk 显式别名不同）：

| 属性 | 默认 | 含义 |
|---|---|---|
| `app.channel.feishu.enabled` | `false` | 总开关，关时端点不装配 |
| `app.channel.feishu.tenant-id` | `default` | 该飞书应用绑定的平台租户 |
| `app.channel.feishu.verification-token` | 空 | 事件订阅校验 token |
| `app.channel.feishu.encrypt-key` | 空 | 事件 AES 解密 key（配了则事件体为 `{"encrypt":"..."}`） |
| `app.channel.feishu.verify-signature` | `true` | 是否校验 `X-Lark-Signature`（需 encrypt-key） |
| `app.channel.feishu.intent-routing.enabled` | `false` | 意图路由（退款/投诉 → 退款工作流） |
| `app.channel.feishu.reply.enabled` | `false` | 是否回推（关时只收不回，便于先验证入站链路） |

> 另一条已落地渠道（钉钉知识库客服桥，`/channel/dingtalk/events`）见 [`dingtalk-guide.md`](../互操作渠道/dingtalk-guide.md)。**SSO/OAuth** 暂缓，继续用 `X-Api-Key`（IdP 未定；现有 `ApiKeyToInternalTokenFilter` + `TenantContext` 够用，将来平行加 JWT filter 汇同一 `TenantContext` 即可，下游 RBAC/限流/配额零改动）。

---

# 总开关速查

## 业务流程引擎（workflow-service :8082）

| 环境变量 | 默认 | 作用 |
|---|---|---|
| `WORKFLOW_ENABLED` | `true` | 总开关（需可达 MySQL） |
| `WORKFLOW_DB_URL` / `WORKFLOW_DB_USER` / `WORKFLOW_DB_PASSWORD` | localhost:3306/flowable / root / root | Flowable 引擎数据源 |
| `WORKFLOW_AI_CLIENT_MODE` | `http` | ServiceTask AI 来源：`http`/`local` |
| `CONVERSATION_BASE_URL` | `http://localhost:8081` | http 模式下 conversation 基址 |
| `WORKFLOW_TERMINAL_NOTIFICATION_MODE` | `local` | 终态回推：`local`/`async-task`/`kafka` |
| `WORKFLOW_TERMINAL_FALLBACK_LOCAL` | `true` | async-task 失败回落本地 outbox |
| `APP_WORKFLOW_APPROVAL_TIMEOUT` | `PT24H` | 审批超时自动驳回阈值 |
| `APP_WORKFLOW_LLM_MAX_ATTEMPTS` | `2` | ServiceTask LLM 重试次数 |
| `APP_WORKFLOW_HISTORY_RETENTION` | `P30D` | 历史实例保留期 |

## LLM 编排模式（agent-service :8085 / conversation-service :8081）

| 环境变量 | 默认 | 作用 |
|---|---|---|
| `AGENT_ENABLED` | `true` | 编排端点总开关 |
| `app.agent.chaining.steps`（yml） | 空 | Prompt Chaining 链步骤（空则 `/agent/chain` 返回 400） |
| `CONVERSATION_ROUTER_ENABLED` | `true` | Routing `/chat/auto` |
| `AGENT_DAG_MAX_TASKS` | `6` | DAG 最大任务数 |
| `AGENT_DAG_REPLAN_ENABLED` / `_THRESHOLD` / `_MAX_REPLANS` | `true` / `0.75` / `1` | Orchestrator replan 闭环 |
| `AGENT_VOTING_N` / `_STRATEGY` / `_MIN_AGREEMENT` | `3` / `majority` / `0.5` | Voting |
| `AGENT_REFLEXION_THRESHOLD` / `_MAX_ATTEMPTS` | `0.75` / `2` | Reflexion |
| `AGENT_REFLEXION_WEIGHT_{CORRECTNESS,COMPLETENESS,CLARITY}` | `0.4/0.4/0.2` | Reflexion 三维权重 |

## 渠道（channel-service :8087）

| 属性 | 默认 | 作用 |
|---|---|---|
| `app.channel.feishu.enabled` | `false` | 飞书事件桥总开关 |
| `app.channel.feishu.intent-routing.enabled` | `false` | 退款/投诉 → 退款工作流 |
| `app.channel.feishu.reply.enabled` | `false` | 是否回推用户 |
| `DINGTALK_ENABLED` | `false` | 钉钉知识库客服桥（见 dingtalk-guide.md） |
