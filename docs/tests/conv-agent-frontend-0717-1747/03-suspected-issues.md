# 03 — 交互逻辑疑似问题

说明：以下均来自实际代码路径。`P0/P1/P2` 是建议优先级；标为“已核实”的条目可由当前实现直接推导，仍不应把错误现状写成“正确行为”断言。蓝图中的回归草案对这些条目使用 `it.skip('TODO(issue-...)', ...)`，断言写期望行为。

## ISSUE-01（P1，已核实）Workflow 动作错误会被后续刷新吞掉

- 预期行为：claim/unclaim/complete 失败时，动作错误应持续展示；可额外刷新待办，但刷新成功不能清除原动作错误。成功 complete 的“已通过/驳回”提示也不应被刷新无条件清掉。
- 现状：`claim`/`unclaim`/`complete` 无论 `exec` 成败都调用 `refreshInbox()`（`WorkflowDeskView.vue:158-175`）。刷新内部再次进入 `exec`，在请求前无条件执行 `actionError=null`、`actionNote=null`（`:97-100`）。因此动作失败 + 刷新成功最终没有错误；complete 成功提示也会在刷新开始时消失。
- 复现路径：设置有效 API Key；首次 `POST /workflow/tasks/t-1/claim` 返回 409 `already claimed`；紧接的 `GET /workflow/tasks` 返回 200 数组。点击“认领”。最终页面只显示刷新后的清单，不显示 409。complete 同理：POST 200 后 GET 200，`任务 t-1 已通过` 在用户看到前被清除。
- 建议处置：让 `exec` 返回判别联合 `{ok,data,error}`；仅动作成功后刷新，或把“动作消息”和“刷新消息”拆成不同 state；刷新不得清除调用方动作消息。

## ISSUE-02（P1，已核实）Analytics 表详情存在后到旧响应覆盖新选择的竞态

- 预期行为：快速选择 `orders` 再选择 `customers`，最终标题和详情都必须属于 `customers`；旧请求不得改变 busy/error/data。
- 现状：`selectTable` 直接写 `selectedTable`，await 后无请求序号/AbortController 守卫便写 `describeData`（`AnalyticsLabView.vue:118-134`）。
- 复现路径：加载两张表；点击 orders（请求 A pending），立即点击 customers（请求 B）；令 B 先返回 `{table:'customers'}`，A 后返回 `{table:'orders'}`。标题仍是 customers，数据区却被 A 覆盖为 orders。
- 建议处置：仿照 `RagWorkspaceView` 的 `detailSeq`，每次请求捕获序号和 table；或用 `useAbortable().fresh()` 中止旧请求，并在 finally 只结束当前请求。

## ISSUE-03（P1，已核实）`useCapabilityRun.reset()` 可被旧流的异步终态回写

- 预期行为：reset 后状态稳定为 `idle`，旧请求/旧流任何 onDone/onError 都不得再写回。
- 现状：`reset()` 先 `abort()`，随后清输出并设 `idle`；回调没有 generation token（`useCapabilityRun.ts:59-65, 123-151`）。真实 `consumeSseStream` 的 AbortError/onDone 是异步到达，可能在 reset 完成后把 phase/sse.status 改为 `aborted`。同类问题也可能发生在上一轮回调晚于下一轮开始时。
- 复现路径：启动一个未结束 SSE；立即调用 reset；让 reader 下一微任务抛 AbortError。观察 phase 从 idle 回到 aborted。
- 建议处置：每次 run/reset 递增 generation；所有回调先校验 generation；结束时清空当前 handle/controller。

## ISSUE-04（P1，已核实）Async Monitor 手动重订阅只显示检查点，却没有发送检查点

- 预期行为：第二次手动订阅已有 `lastEventId=evt-42` 的任务时，请求应带 `Last-Event-ID: evt-42`，或清楚标注“从头重播并去重”。
- 现状：`streamTask` 把 `ev.id` 存入 `TrackedTask.lastEventId` 并显示“续订点”，但每次调用 `streamCapability(streamCap,{taskId},session.runContext(),...)`，没有将该 id 传给新订阅（`AsyncMonitorView.vue:116-139`）。`streamCapability` 的 Last-Event-ID 仅用于其内部 Bearer 断流自动续订，不接受外部初始 checkpoint。
- 复现路径：第一次订阅收到 `id: 42` 后正常 EOF；再次点击“SSE 订阅”；检查第二次 fetch headers，无 `Last-Event-ID`；若后端从头重放，事件重复进入 `t.events`。
- 建议处置：扩展 `streamCapability` 选项支持 initial Last-Event-ID，或在 Monitor 层去重；UI 文案必须与真实传输一致。

## ISSUE-05（P1，已核实）Async Monitor 旧订阅 onDone 可把新订阅误标为已停止

- 预期行为：重订阅 B 已开始后，旧订阅 A 的 abort/onDone 不能修改 B 的 `streaming`。
- 现状：重订阅先 `streamHandles.get(id)?.abort()`，再设 `streaming=true`；A 与 B 的 `onDone` 都无 handle/generation 身份校验并执行 `upsert(id,{streaming:false})`（`AsyncMonitorView.vue:116-139`）。A 的异步 onDone 若晚到，会关闭 B 的 UI 状态。
- 复现路径：任务 t 首次订阅 A 完成但 onDone 延迟；触发第二次订阅 B；随后调用 A.onDone。按钮从“流式中”变回“SSE 订阅”，用户可建立第三条并发流。
- 建议处置：每 task 维护 subscription generation；回调只更新仍是当前 handle 的订阅；onDone 删除当前 map entry。

## ISSUE-06（P1，已核实）Async Monitor 的事件数组无上限，长连接可持续增长

- 预期行为：长时任务的 UI 事件缓存应有明确上限/窗口，或持久化分页；卸载/终态应释放 handle。
- 现状：`t.events.push(ev)` 无上限（`:124-126`），而共享 `useCapabilityRun` 明确有 `MAX_EVENTS=2000`。`streamHandles` 也只在重订阅或卸载清理，正常 done 后仍保留 handle。
- 复现路径：向单个任务推送 100,000 个 RUNNING/heartbeat 命名事件；`events.length` 持续增长，Timeline 每次又读取最新项，内存与响应性成本线性上升。
- 建议处置：统一使用有界 ring buffer（至少与 2000 一致），另记录 dropped count；当前订阅 done 后从 map 删除。

## ISSUE-07（P1，已核实）Agent DAG 必填校验逻辑使用 OR，可发送缺字段请求

- 预期行为：`agent.dag.run` 与 `agent.dag.run.async` 的目录声明 `goal`、`tasks` 均 required；任一缺失均禁止执行，并对非法 tasks JSON/数组形态显示字段错误。
- 现状：`canSend` 返回 `primaryFilled || tasksFilled`（`AgentLabView.vue:131-136`），UI 又把任务 DAG 标为“可选”（`:321`）。因此只填 goal 或只填 tasks 都可执行；自定义 composer 没有调用 `validateParams`。
- 复现路径：选 DAG；只填目标“分析退款”，tasks 留空；按钮启用并 POST `/agent/dag/run` body `{"goal":"分析退款"}`。反向只填 tasks 也会发送缺 goal 的请求。
- 建议处置：依据 active capability 的 `required` 参数动态校验，而非手写 OR；tasks 应 JSON.parse 后确认是数组。

## ISSUE-08（P2，已核实）Agent/RAG 自定义数字输入只靠 HTML min/max，仍可提交越界值

- 预期行为：`agent.vote.n` 只允许 1..9；RAG `topK` 只允许 1..50、`minScore` 只允许 0..1；越界/NaN 不发请求并显示字段错误。
- 现状：这些专用 composer 不调用 `validateParams`。HTML `min/max` 不会阻止手输越界值；`canSend/canSearch` 也未检查。
- 复现路径：vote 输入 `n=0` 后执行，body 含 `n:0`；RAG 输入 `topK=0,minScore=2` 后检索，body 原样发送。
- 建议处置：复用目录 ParamSpec + `validateParams`，或建立专用 computed errors；按钮禁用与提交函数都做同一校验。

## ISSUE-09（P1，已核实）Agent 切换能力后保留上一能力的结果并按新模式重新解释

- 预期行为：模式切换后结果区应 reset，或明确标注结果仍属于旧模式；绝不能按新模式判断旧响应类型。
- 现状：`modeId` 改变只让 `activeCap`/`isAsyncMode` 重算，未调用 `run.reset()`（`AgentLabView.vue:92-105`）。旧 `phase/result` 保留；`showAsyncPanel/showSteps` 却用当前 `isAsyncMode` 解释旧数据（`:198-215`）。
- 复现路径：agent.run 返回 `{id:'old-task',steps:[...]}` 成功；切换 `agent.run.async`。旧结果仍在，且 `extractTaskId` 可把旧 `id` 当异步 taskId，显示“已提交”。
- 建议处置：watch modeId，在非 running（按钮已保证）时 `run.reset()`，并重置 `showRawUnder`；若希望保留，结果必须按 run-time capId 存档而非 current mode 解读。

## ISSUE-10（P1，已核实）Chat 流内 `error` 事件会覆盖已收到的部分答案

- 预期行为：按源码注释（`ChatConsoleView.vue:191`），正常传输中出现业务 error 应保留已渲染 token，并以独立系统错误提示呈现。
- 现状：`useCapabilityRun` 发现命名 `error` 后令最终 phase=`error`；`finalizeIfTerminal` 的 error 分支直接把助手 text 改成 `errorMessage`（`:165-176`）。随后“仅 phase done 才 pushSystem”的分支不可达（`:191-194`）。已收到 token 被抹掉。
- 复现路径：SSE 依次发送 `data: 部分答案\n\n`、`event:error\ndata: tool failed\n\n` 后 EOF。最终助手气泡只剩 `tool failed`，部分答案丢失。
- 建议处置：明确产品语义。推荐 error 终态仍保留 tokens，在气泡/系统条单独展示错误；删除不可达条件或让 useCapabilityRun 区分 transport error 与 business error。

## ISSUE-11（P2，已核实）Chat 清空生成新 chatId，却保留旧 chatId 的画像结果

- 预期行为：清空对话切换到新 chatId 时，画像 data/error/busy 应同时清理；旧画像请求晚到也不能显示在新 chatId 下。
- 现状：`clearAll` 只清 messages/activeAsstId 并换 chatId（`:247-253`），不清 `memProfile/memError`；画像请求没有 abort/sequence（`:296-337`）。
- 复现路径：在 memory 模式读取 chatId=A 的画像；点击清空得到 chatId=B；再次展开画像仍显示 A。或读取 A pending 时清空，A 后到并写入 B 的抽屉。
- 建议处置：清空时重置画像并取消/失效旧请求；读取回调校验捕获的 chatId/generation。

## ISSUE-12（P2，已核实）RAG 检索响应与可编辑 query 未绑定，命中高亮可能对应错误问题

- 预期行为：响应与提交时 query/筛选条件绑定；请求中修改输入不会让旧结果按新 query 高亮。
- 现状：请求使用提交时 `query.value.trim()`，但结果渲染 `segmentsFor` 每次读取当前 `query.value`（`RagWorkspaceView.vue:343-364`）；请求期间 textarea 未禁用。
- 复现路径：以“退款”发起慢检索，等待时把输入改成“订单”；返回含“退款”的结果后，页面按“订单”高亮，并没有展示这批结果实际对应的 query。
- 建议处置：保存 `submittedQuery`/request snapshot；结果和高亮使用 snapshot。若支持并发检索，再加 searchSeq/abort。

## ISSUE-13（P2，已核实）RAG GraphRAG 区块硬编码“未启用”，与当前目录 ready 状态冲突

- 预期行为：说明文案和执行闸门都应以当前 `Capability.state/featureFlagDefault` 为事实源；ready 时不显示“未启用”。
- 现状：`capabilities.yml`/生成目录中 `rag.graph.query`、`rag.graph.entities` 为 `state: ready`、默认 flag=true；但工作台注释、subtitle、InfoNote 无条件写“需开启/未启用”（`RagWorkspaceView.vue:723-734`），下面 CapabilityRunner 实际允许执行。
- 复现路径：使用当前 `loadCatalog()` 挂载 RAG 工作台并填 Key；页面同时显示“未启用”，Graph Runner 的执行按钮却可用。
- 建议处置：InfoNote 按 graph caps 的实际 state 聚合；测试断言 ready 与 flag-off 两种克隆目录分支，不能只查整页“未启用”字符串。

## ISSUE-14（P2，已核实）现有 Agent flag-off 测试是跨区域文本误命中

- 预期行为：测试“业务流程 flag-off”时应断言该 mode 的 state/badge、composer gate 和按钮 disabled；当前目录 ready 时该测试应失败或改测 ready。
- 现状：`AgentLabView.test.ts` 用整页 `wrapper.text().toContain('未启用')` 和 `toContain('app.agent.workflow.enabled')`。前者可命中 `ModuleHeader` 固定状态分段标签“未启用”，后者可命中 capability description；均不证明 process mode 被锁。当前 catalog 为 ready，该测试仍全绿。
- 复现路径：当前 `npm run gen:catalog` 后单跑 `npx vitest run src/modules/agent/AgentLabView.test.ts`，8 例全绿；检查 process button 无 `is-off`，执行仍可用。
- 建议处置：限定 `.ag__mode`、`.ag__gate`、执行按钮；按当前生成目录改为 ready 断言，并用测试内克隆 capability state 的独立 gate 单测覆盖 off。

## ISSUE-15（P2，待产品确认）专用工作台的一次性请求普遍不可取消，卸载后仍会回写局部 state

- 预期行为：RAG docs/search、Analytics schema/sql、Workflow actions、Chat profile、Async refresh/deadletter 在组件卸载或被新请求替代时应中止/失效。
- 现状：这些调用大多使用 `session.runContext()` 而未传 AbortSignal，也没有 `onUnmounted` generation 失效。RAG list/detail 有 seq 抵御部分乱序，但没有 abort；其它多数连 seq 也没有。
- 复现路径：发起任一慢请求后立刻路由离开；fetch 继续占用连接并在 resolve 后写 ref。重复进出可形成多条无用请求。
- 建议处置：统一用 `useAbortable`，卸载 abort；所有 finally 只允许当前 generation 更新 busy。此项是否要求“切换区块即中止”待产品确认，但“卸载释放”应锁定。

## 已核实不是 bug 的线索

### Async cancel 成功后强写 CANCELLED

- 题面疑点：`cancelTask` 先 ingest 服务端响应，再强写 CANCELLED。
- 核实：后端 `AsyncTaskController.cancel` 成功即原子更新 store 为 CANCELLED，但响应契约只是 `{taskId,cancelled:true}`，不含 `status`。因此 `ingest` 无法更新状态，前端强写是当前契约下必要的乐观投影，并非竞态覆盖服务端状态。
- 应补回归：200 时应显示 CANCELLED；404/409/网络错误时不得强写，保留原状态并展示错误。若后端将来返回完整实体，再改为以服务端 status 为权威。
