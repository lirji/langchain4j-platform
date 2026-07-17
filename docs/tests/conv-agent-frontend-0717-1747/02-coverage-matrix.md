# 02 — 覆盖矩阵

## 1. 标记与目标测试编号

- `已有✓`：现有测试真正断言了该行为。
- `壳△`：只证明入口/文案/深链存在，没有走“用户操作→请求→响应→UI”。
- `缺口✗`：没有相关断言。
- `TODO(issue)`：期望行为草案应先 skip，待 `03-suspected-issues.md` 对应问题修复后启用，不能锁定错误现状。

目标测试文件简称：

| 简称 | 精确路径 |
|---|---|
| CONTRACT | `src/components/capability/CapabilityRunner.interaction.test.ts` |
| RUN | `src/composables/useCapabilityRun.test.ts` |
| ABORT | `src/composables/useAbortable.test.ts` |
| CHAT | `src/modules/chat/ChatConsoleView.interaction.test.ts` |
| RAG | `src/modules/rag/RagWorkspaceView.interaction.test.ts` |
| AGENT | `src/modules/agent/AgentLabView.interaction.test.ts` |
| STEP | `src/modules/agent/AgentStepTimeline.test.ts` |
| ASYNC | `src/modules/tasks/AsyncMonitorView.interaction.test.ts` |
| TIMELINE | `src/modules/tasks/AsyncTaskTimeline.test.ts`（扩充现有文件） |
| WF | `src/modules/workflow/WorkflowDeskView.interaction.test.ts` |
| ANALYTICS | `src/modules/analytics/AnalyticsLabView.interaction.test.ts` |
| INFRA | `src/components/capability/SseConsole.test.ts`, `src/stores/history.test.ts`, `src/stores/favorites.test.ts` |

## 2. 57 项能力逐项矩阵

所有能力都由 CONTRACT 做目录契约 + 用户点击 Runner + fetch 计划的参数化回归；专用视图还必须由对应模块测试锁定其自定义表单映射和结果呈现。

### chat（10）

| 能力 | 方法/路径/类型 | 关键分支与应锁行为 | 现有 | 缺口 → 目标 |
|---|---|---|---|---|
| chat.sync | POST `/chat`, json | chatId query；message/category body；用户/助手气泡；reply/文本/JSON fallback；400/401/500/abort | 壳△：默认/深链 | CONTRACT + CHAT-01/02/05 |
| chat.stream | POST `/chat/stream`, sse | API key + Accept；跨 chunk token；done/error/blocked/grounding-warning；停止 | 壳△：默认模式 | CONTRACT + RUN-04..07 + CHAT-03..06；业务 error 为 TODO(issue-10) |
| chat.extract | POST `/extract`, json | 通用表单 type query/text body、验证、成功/错误/历史 | 壳△：仅委派 Runner | CONTRACT + CONTRACT-error/history |
| chat.auto | POST `/chat/auto`, json | 模式切换；chatId/category 参数隔离；ready 与模拟 flag-off gate | 壳△：只显示模式 | CONTRACT + CHAT-07 |
| chat.cascade | POST `/chat/cascade`, json | 只发 message，不泄漏 chatId/category；模式切换保留对话但新请求参数正确 | 壳△ | CONTRACT + CHAT-07 |
| chat.mcp | POST `/chat/mcp`, json | 当前 flag-off；按钮禁用；0 fetch；显示精确 flag | 已有✓闸门文案，未验 0 fetch | CONTRACT + CHAT-08 |
| chat.memory | POST `/chat/memory`, json | chatId query + message；画像抽屉与当前 chatId 一致 | 壳△：入口 | CONTRACT + CHAT-07/09；clear 残留 TODO(issue-11) |
| memory.profile.get | GET `/memory/profile`, none | chatId query；busy/空画像/JSON/error；并发/清空隔离 | 壳△：按钮存在 | CONTRACT + CHAT-09；竞态 TODO(issue-11) |
| memory.profile.clear | DELETE `/memory/profile`, none | chatId query；成功“已清除”；错误不伪成功 | 壳△：按钮存在 | CONTRACT + CHAT-09 |
| chat.cache.clear | DELETE `/chat/cache`, none | 无 body；深链 Runner；成功/失败/历史 | 缺口✗ | CONTRACT |

### rag（11）

| 能力 | 方法/路径/类型 | 关键分支与应锁行为 | 现有 | 缺口 → 目标 |
|---|---|---|---|---|
| rag.query | POST `/rag/query`, json | trim；topK/minScore/category；分数降序；visibility badge；无命中/fallback/error | 壳△：深链和横幅 | CONTRACT + RAG-01..04；边界 TODO(issue-08)，query snapshot TODO(issue-12) |
| rag.upload.file | POST `/rag/documents`, multipart | File/FormData；无手写 Content-Type；ingest hint；成功刷新第 1 页 | 壳△：区块提示 | CONTRACT + RAG-09 |
| rag.upload.file.shared | POST `/rag/documents?visibility=public`, multipart | public-ingest；双控后才出现；query 不丢；成功刷新当前 tab | 缺口✗ | CONTRACT + RAG-08/09 |
| rag.upload.json | POST `/rag/documents`, json | title/text/category；required text；scope hint；成功刷新 | 壳△ | CONTRACT + RAG-09 |
| rag.upload.json.shared | POST `/rag/documents?visibility=public`, json | public-ingest；shared 双控；body 正确 | 缺口✗ | CONTRACT + RAG-08/09 |
| rag.documents.list | GET `/rag/documents?page=1&size=10`, none | tenant/public query；分页/clamp/pageSize；空/错误；docsSeq 乱序 | 壳△：只显示区块 | CONTRACT + RAG-05/06/10 |
| rag.documents.delete | DELETE `/rag/documents/{docId}`, none | encode id；二次确认 UI；public visibility；成功清详情+刷新；失败保留 | 缺口✗ | CONTRACT + RAG-07 |
| rag.documents.get | GET `/rag/documents/{docId}`, none | encode id；visibility；detailSeq 后到旧响应丢弃；error | 缺口✗ | CONTRACT + RAG-06/10 |
| rag.obsidian.import | POST `/rag/obsidian/import`, multipart | file + category query；ingest scope；刷新 | 壳△ | CONTRACT + RAG-09 |
| rag.graph.query | POST `/rag/graph/query`, json | 当前 ready 可执行；模拟 flag-off 时禁用；文案与 state 一致 | 壳△且旧文案误导 | CONTRACT；RAG-11 TODO(issue-13) |
| rag.graph.entities | GET `/rag/graph/entities`, none | optional category query；当前 ready；模拟 off | 壳△且旧文案误导 | CONTRACT；RAG-11 TODO(issue-13) |

### agent（18）

| 能力 | 方法/路径/类型 | 关键分支与应锁行为 | 现有 | 缺口 → 目标 |
|---|---|---|---|---|
| agent.run | POST `/agent/run`, json | goal/webhook；steps/final/fallback；error/abort | 壳△：深链 | CONTRACT + AGENT-01/04/07 |
| agent.run.async | POST `/agent/run/async`, json | taskId/id 抽取；已提交面板；原始响应 | 壳△：模式存在 | CONTRACT + AGENT-02 |
| agent.tasks.list | GET `/agent/tasks`, none | Runner 无 body；结果/错误 | 壳△：卡片 | CONTRACT |
| agent.tasks.get | GET `/agent/tasks/{taskId}`, none | path encode；required；结果 | 壳△：卡片组未逐项断言 | CONTRACT |
| agent.tasks.stream | GET `/agent/tasks/{taskId}/stream`, sse | 命名状态事件走 SseEventTimeline；终态/错误/abort | 已有✓渲染器类型；无网络 | CONTRACT + RUN-04/06 |
| agent.tasks.cancel | DELETE `/agent/tasks/{taskId}`, none | path；成功/404/409 | 壳△：卡片 | CONTRACT |
| agent.dag.run | POST `/agent/dag/run`, json | goal + required tasks JSON + webhook；steps | 壳△ | CONTRACT + AGENT-03；必填 TODO(issue-07) |
| agent.dag.plan-run | POST `/agent/dag/plan-run`, json | goal only；steps/fallback | 壳△ | CONTRACT + AGENT-03 |
| agent.dag.run.async | POST `/agent/dag/run/async`, json | goal/tasks/webhook；task panel | 壳△ | CONTRACT + AGENT-03；必填 TODO(issue-07) |
| agent.dag.plan-run.async | POST `/agent/dag/plan-run/async`, json | goal；task panel | 壳△ | CONTRACT + AGENT-03 |
| agent.chain | POST `/agent/chain`, json | primary param 是 input；不能误发 goal | 壳△ | CONTRACT + AGENT-03 |
| agent.vote | POST `/agent/vote`, json | primary question；n 数字 1..9 | 壳△ | CONTRACT + AGENT-03；范围 TODO(issue-08) |
| agent.reflexive | POST `/agent/reflexive`, json | question；同步 answer/fallback | 壳△ | CONTRACT + AGENT-03 |
| agent.reflexive.stream | POST `/agent/reflexive/stream`, sse | attempt/answer/critique/done/error；StageConsole；跨 chunk/终态 | 已有✓组件选择；共享 parser 已测 | CONTRACT + AGENT-05 + RUN-04/05 |
| agent.analyst.run | POST `/agent/analyst/run`, json | goal；响应分流 | 壳△ | CONTRACT + AGENT-03 |
| agent.analyst.run.async | POST `/agent/analyst/run/async`, json | goal；task panel | 壳△ | CONTRACT + AGENT-03 |
| agent.process.run | POST `/agent/process/run`, json | 当前 ready；goal；不得用整页文本假装 flag-off | 现有伪阳性△ | CONTRACT + AGENT-03/08；见 issue-14 |
| agent.process.run.async | POST `/agent/process/run/async`, json | 当前 ready；goal；task panel | 壳△ | CONTRACT + AGENT-03 |

### tasks（8）

| 能力 | 方法/路径/类型 | 关键分支与应锁行为 | 现有 | 缺口 → 目标 |
|---|---|---|---|---|
| async.create | POST `/async/tasks`, json | kind required；input JSON；可选 id/webhook；结果 ingest 到时间线 | 壳△：卡片/常驻时间线 | CONTRACT + ASYNC-01 |
| async.list | GET `/async/tasks`, none | array ingest 多任务；非数组防御 | 壳△：卡片 | CONTRACT + ASYNC-02 |
| async.get | GET `/async/tasks/{taskId}`, none | refresh 更新状态/kind；错误只写 task error | 缺口✗ | CONTRACT + ASYNC-02 |
| async.status.update | PATCH `/async/tasks/{taskId}/status`, json | select status；result JSON/error/workerId；结果 ingest | 壳△：卡片 | CONTRACT + ASYNC-01 |
| async.lease | POST `/async/tasks/{taskId}/lease`, json | workerId required；leaseSeconds；409 保持状态 | 壳△ | CONTRACT + ASYNC-01 |
| async.cancel | DELETE `/async/tasks/{taskId}`, none | 200 `{cancelled:true}` 后本地 CANCELLED；失败不强写 | 缺口✗ | CONTRACT + ASYNC-03（已核实非 bug） |
| async.stream | GET `/async/tasks/{taskId}/stream`, sse | status ingest；event id；error；重订阅；卸载 abort；有界缓存 | 壳△：卡片/时间线标识 | CONTRACT + ASYNC-04/05；TODO(issue-04/05/06) |
| async.deadletter | GET `/async/webhook-outbox/dead`, none | 多种 envelope→表；空；raw fallback；error/busy | 壳△：入口 | CONTRACT + ASYNC-06 |

### workflow（7）

| 能力 | 方法/路径/类型 | 关键分支与应锁行为 | 现有 | 缺口 → 目标 |
|---|---|---|---|---|
| workflow.refund.start | POST `/workflow/refund/start`, json | chatId/message/dedupe/webhook；deduplicated；started list | 已有✓仅 emit 串联，无请求 | CONTRACT + WF-01 |
| workflow.tasks.list | GET `/workflow/tasks`, none | approve scope；数组过滤；首项/保持选择/空；错误 | 壳△：主从空态 | CONTRACT + WF-02 |
| workflow.tasks.claim | POST `/workflow/tasks/{taskId}/claim`, none | task id；刷新 assignee；409 必须保留 | 缺口✗ | CONTRACT + WF-03；错误 TODO(issue-01) |
| workflow.tasks.unclaim | POST `/workflow/tasks/{taskId}/unclaim`, none | 204/null 仍算成功；刷新 assignee | 缺口✗ | CONTRACT + WF-03；错误 TODO(issue-01) |
| workflow.tasks.complete | POST `/workflow/tasks/{taskId}/complete`, json | approved boolean（false 不能被省略）；trim comment；成功清 comment+刷新；409 | 缺口✗ | CONTRACT + WF-04；消息 TODO(issue-01) |
| workflow.instances.get | GET `/workflow/instances/{instanceId}`, none | path encode；Runner success/error | 壳△：Runner 存在 | CONTRACT |
| workflow.data.purge | DELETE `/workflow/data?chatId=...`, none | 默认锁；确认后发 DELETE；approve 提示；历史 | 已有✓默认锁，未验确认执行 | CONTRACT + WF-05 |

### analytics（3）

| 能力 | 方法/路径/类型 | 关键分支与应锁行为 | 现有 | 缺口 → 目标 |
|---|---|---|---|---|
| analytics.schema.tables | GET `/analytics/schema/tables`, none | string/object/tables envelope；空/error；按钮 busy | 壳△：按钮禁用 | CONTRACT + ANALYTICS-01/02 |
| analytics.schema.describe | GET `/analytics/schema/tables/{table}`, none | URL encode；row table/raw/error；乱序 | 缺口✗ | CONTRACT + ANALYTICS-03；竞态 TODO(issue-02) |
| analytics.sql | POST `/chat/sql`, json | trim question；SQL + rows + answer/raw；columns/rows、objects、scalars、empty、fallback、error/curl | 壳△：入口 ready | CONTRACT + ANALYTICS-04..07 |

## 3. 公共方法/状态分支矩阵

| 被测函数/区域 | 分支/场景 | 现有 | 目标测试 |
|---|---|---|---|
| `useCapabilityRun.run` | gate 拒绝、JSON success、ApiError body/status、TypeError、AbortError | 缺口✗ | RUN-01..03 |
| `runStream` | onOpen trace/status、token、命名 note、业务 error、transport error、complete、abort | 缺口✗ | RUN-04..07 |
| `reset/abort/onScopeDispose` | reset 清全量输出；旧回调失效；dispose abort | 缺口✗ | RUN-08 + TODO(issue-03), ABORT |
| MAX_EVENTS | 第 2001 个不进入通用 event list | 缺口✗ | RUN-09 |
| CapabilityRunner | DynamicForm validate fail=0 fetch；示例；危险确认；Cmd/Ctrl+Enter；Esc；result emit | 缺口✗ | CONTRACT + Runner focused cases |
| CapabilityRunner history | success/error/abort 各记一次；snapshot 不被后续编辑改写；replay 只消费同 cap | 缺口✗ | CONTRACT-history + INFRA-history |
| SSE Console | transcript/events tab、搜索、note/error、status、下载 disabled | 缺口✗ | INFRA-sse |
| AgentStepTimeline | string、known aliases、unknown raw、null/circular fallback | 缺口✗ | STEP-01..04 |
| AsyncTaskTimeline | PENDING/RUNNING/LEASED/unknown/3 terminals；action disabled；emits id | 部分已有△ | TIMELINE-05..08 |
| ResultTable | 列并集、null/object/circular、显式 columns | 间接缺口✗ | ANALYTICS/ASYNC 组件断言；建议独立测试 |
| history store | 50 上限、newest first、clear、replay cap mismatch | 缺口✗ | INFRA-history |
| favorites store | 脏 localStorage、toggle/persist、setItem 抛错 | 缺口✗ | INFRA-favorites |

## 4. feature flag / 凭证 / 租户安全矩阵

| 场景 | 现有 | 目标 |
|---|---|---|
| 当前 catalog 恰好 57 项且 id/method/path/kind/state 不漂移 | 缺口✗ | CONTRACT-00 硬编码契约表对照 `loadCatalog()` |
| apikey 请求只有 `X-Api-Key`，无 Authorization | client 已有✓；视图未串联 | CONTRACT 每能力 fetch 断言 |
| 无凭证禁用且 0 fetch | 各壳层部分△ | CONTRACT/每专用视图至少一例 |
| scope-required + apikey：允许请求但显示可能 403 hint | gate 已有；视图只文案△ | CONTRACT + RAG/WF |
| Bearer 缺 scope：禁用；具备 scope：允许 | gate 测试已有✓ | 不在固定 apikey 视图重复造假；保留 gate 回归 |
| flag-off：0 fetch、精确 featureFlag | gate/MCP 文案已有△ | CONTRACT + CHAT-08 |
| destructive：未确认 0 fetch；确认才发 | 默认锁已有△ | CONTRACT/WF-05 |
| tenantId 不出现在请求 header/body/query | 缺口✗ | CONTRACT 全调用检查；RAG tenant/public 专例 |
| public KB 需要构建期开关 + runtime publicEnabled 双控 | 语义横幅已有△ | RAG-08；构建 off 用独立 `vi.mock('../../config')` 文件，待验证是否拆文件 |
| document/table/path 注入字符均 encode | api helper 部分已有 | CONTRACT + RAG/ANALYTICS |

## 5. 边界/异常矩阵（edge-case-hunter）

| 边界 | 触发 | 期望 | 目标 |
|---|---|---|---|
| required 空白 | 只输入空格/不选文件 | 按钮禁用或字段错误；0 fetch | CONTRACT + 各 composer |
| JSON 非法 | tasks/input/result=`{bad` | 字段错误；不把原始 SyntaxError 当服务响应 | CONTRACT/AGENT TODO(issue-07) |
| 数字越界/NaN | vote=0/10；topK=0/51；minScore=-.1/1.1 | 0 fetch + 人话错误 | TODO(issue-08) |
| Unicode/保留字符 id | `a/b ?中#` | path 使用 encodeURIComponent；只命中单一资源 | CONTRACT |
| XSS | chat reply `<img onerror>`；SQL cell HTML | 文本或 DOMPurify 后无脚本/handler | CHAT + ANALYTICS |
| 大结果 | 2001 SSE events；大量 rows/docs | 有界缓存；DOM 行为待产品虚拟化策略 | RUN-09；ASYNC TODO(issue-06) |
| 空响应 | 204、JSON null、空数组、无 body SSE | 不崩；分别显示空态/错误 | 各模块 |
| 错误体 | JSON、text、坏 JSON、401/403/404/409/429/500 | humanize，保留 HTTP status；不误报成功 | RUN + 模块错误例 |
| SSE 分块 | UTF-8/字段跨 chunk、末帧无空行、CRLF、heartbeat | 不丢/不重 token；正确终态 | api/sse 已有大部；RUN/CHAT 集成 |
| SSE 重复 id | 手动重订阅从头重放 | 发送 checkpoint 或去重 | TODO(issue-04) |
| 快速切换 | docs tab/detail、analytics table、agent mode | 后到旧响应丢弃、旧结果清理 | RAG-10；TODO(issue-02/09) |
| 卸载 | pending fetch/SSE 后 wrapper.unmount | signal aborted；无后续 state mutation | RUN/ABORT；TODO(issue-15) |
| 存储不可用 | localStorage get/set 抛异常 | favorites 不崩、不存敏感参数 | INFRA-favorites |

## 6. 跨模块回归链

| 链路 | 断言 |
|---|---|
| 任一 Runner → session → client | 57 项统一凭证头、method/path/body；专用视图不得绕过安全闸门 |
| Async create/get/update → Async timeline | result emit 后同 task upsert 而非重复；状态和 kind 更新；终态按钮禁用 |
| Agent async → Agent tasks stream | async taskId 正确展示/复制/深链；stream 是命名事件时间线，不是 token console |
| Workflow start → started list → claim/complete → inbox refresh | id 自动串联、boolean false 保留、comment trim、动作错误不被刷新吞掉 |
| RAG upload → documents list → detail/delete → query | upload success 回第 1 页；visibility 一致；删当前详情清理；query 命中 visibility 来自服务端 |
| Analytics tables → describe → NL2SQL | schema 选择不串表；SQL/rows 同一响应呈现；raw 不隐藏未识别字段 |

## 7. 当前闭合度结论

- 现有 38 个视图测试主要为 `壳△`，只有少量路由/组件选择/过滤行为是真覆盖。
- 57 项里没有任何一项被现有视图测试完整锁定“表单→fetch/SSE→响应 UI→失败/中断”。
- CONTRACT 参数化测试负责 57/57 的传输契约底线；六个专用视图测试负责自定义状态机，RUN/INFRA 负责共享状态机和资源释放。
- 15 项疑似问题中，issue-01/02/03/04/05/07/09/10/11/13 是必须先修再启用的关键回归断言；不得为了全绿而改成断言当前错误结果。
