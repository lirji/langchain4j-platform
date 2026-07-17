# TEST_PROGRESS — 对话与检索 / 智能体与编排 前端全能力测试

日期：2026-07-17 ｜ 处置决策：**C 方案**（疑似 bug 用 `it.skip('TODO(issue-xx)')` + 断言期望行为显式暴露，不锁定错误现状，补测轮不改业务代码）｜ 落地方式：分两批。

> **【已收口】修复轮（同日晚间）**：用户随后拍板「修复遗留的问题」——16 条疑似 bug（issue-01..16，除已排除项）全部修复，
> 对应 skip 全部摘除转为激活回归；SideNav 过时测试与 issue-14 假阳性测试已改写；共享库构建期 off 分支已自动化。
> 终态：**`npm test` 460 例全绿、0 skip、两次运行一致，`type-check` 全绿**。修复明细见文末「修复轮记录」。

## 新增测试清单（15 个文件，158 个用例）

### 第一批：共享夹具 + 57 项契约 + 状态机 + 基础设施（79 例，78 绿 + 1 skip）

| 文件 | 用例 | 说明 |
|---|---|---|
| `src/test/interactionHarness.ts` | — | 共享夹具：真实 catalog(structuredClone) + 新 Pinia + 固定 api-key + edgeBaseUrl 归零 + Response/SSE/deferred 工厂 |
| `src/components/capability/CapabilityRunner.interaction.test.ts` | 60 | **57 项能力传输契约**（独立硬编码合同表 × 用户点击 × fetch method/URL/凭证头/请求体/multipart/SSE Accept/无 tenantId 串味）+ 目录无漂移 + 校验失败 0 fetch + result emit/历史快照 |
| `src/composables/useCapabilityRun.test.ts` | 9(1 skip) | JSON 成功/HTTP 错误/网络错误/gate 拒绝 0 fetch、SSE 跨 chunk/旁路 note/业务 error 终态/握手错误/abort/MAX_EVENTS=2000；skip: issue-03 reset 竞态 |
| `src/composables/useAbortable.test.ts` | 2 | fresh 中止旧 controller / dispose 自动中止 |
| `src/components/capability/SseConsole.test.ts` | 3 | token/状态/note/error/trace、搜索命中数与高亮、事件流 tab/下载禁用 |
| `src/stores/history.test.ts` | 3 | 50 条上限 newest-first、replay 同 capId 一次性消费、clear |
| `src/stores/favorites.test.ts` | 2 | 脏 JSON 容错、只持久化 id、setItem 抛错不崩 |

### 第二批：六视图交互（79 例，64 绿 + 15 skip）

| 文件 | 用例 | 说明 |
|---|---|---|
| `src/modules/chat/ChatConsoleView.interaction.test.ts` | 9(2 skip) | 同步 query/body 分离+气泡+XSS 消毒+trace、SSE 跨 chunk/note/终态、HTTP 失败气泡、停止流 signal+已中断、auto/cascade/memory 参数隔离、MCP flag-off 0 fetch、画像 GET/DELETE；skip: issue-10/11 |
| `src/modules/rag/RagWorkspaceView.interaction.test.ts` | 10(4 skip) | 检索 trim/排序/visibility/高亮、空/fallback/错误三分支、list→分页→详情→删除（含保留字符编码）、tab 切换乱序守卫、详情乱序守卫、上传回流归 1 页；skip: issue-08/12/13/15 |
| `src/modules/agent/AgentLabView.interaction.test.ts` | 22(3 skip) | **14 个执行模式的 primary 字段映射**（goal/input/question 互斥+DAG tasks parse+vote n）、steps 时间线/原始响应、异步 taskId 面板+深链、反思 SSE 逐阶段、HTTP 错误、process ready 真调用（修复既有假阳性盲区）；skip: issue-07/08/09 |
| `src/modules/agent/AgentStepTimeline.test.ts` | 3 | string/aliases/primitive 编号、未知对象 JsonView 兜底、alias 优先级 |
| `src/modules/tasks/AsyncMonitorView.interaction.test.ts` | 9(3 skip) | Runner 结果 upsert 不重复、刷新 GET+错误只挂 task、cancel 成功投影 CANCELLED/失败不强写（成对断言）、SSE handlers 注入投影/续订点/错误、卸载 abort、死信三形态；skip: issue-04/05/06 |
| `src/modules/tasks/AsyncTaskTimeline.interaction.test.ts` | 8 | 四种非终态归组/三终态禁用矩阵、action emit 精确 id、disabled 不 emit |
| `src/modules/workflow/WorkflowDeskView.interaction.test.ts` | 7(2 skip) | 发起串联、待办解析/过滤/自动选首项/优先级、claim/unclaim URL+assignee 投影、**驳回 approved:false 深相等**+comment trim、无凭证 0 调用；skip: issue-01×2 |
| `src/modules/analytics/AnalyticsLabView.interaction.test.ts` | 11(1 skip) | 表清单三种 envelope、选表原值发送+列表渲染、NL2SQL trim/SQL/行集/空值/raw、四种行集兜底形态、schema/sql 错误隔离、curl 不泄露 key；skip: issue-02 |

## 跑测记录

| 轮次 | 命令 | 结果 |
|---|---|---|
| 1 | 第一批 6 文件 | 56 failed：本地 `.env` 的 `VITE_EDGE_BASE_URL=http://localhost:18080` 渗入 → 夹具 `edgeBaseUrl=''` 归零后全绿 |
| 2 | 第一批复跑 | ✅ 78 passed + 1 skipped |
| 3 | 第二批 8 文件 | 6 failed（workflow×3 / tasks×3）：`vi.mock('api/client')` 的 `importActual` 依赖子图产生 client 双实例，视图绑定到真实实现 → 真实 fetch 报网络错误。改用 `vi.stubGlobal('fetch')` 路由式 mock（与 chat/rag 系一致，断言更贴传输层）后全绿。另修草案两处：workflow 动作按钮需限定 `.wf__detail-actions` 作用域（避免误命中任务行「未认领」文本）、agent 深链断言改 findAll+some |
| 4 | 第二批复跑 | ✅ 64 passed + 15 skipped |
| 5 | `npm test` ×2 | 两次结果一致：**439 passed + 16 skipped + 1 failed**，共 456 例 / 59 文件。⚠️ 套件退出码为失败——红的是**既有** `SideNav.test.ts`（改动前即红，非本次引入，见遗留 1）；本次新增 158 例全绿 |
| 6 | `npm run type-check` | ✅ 全绿 |
| 7 | Codex 验收后修正复跑（见下节） | ✅ 修正涉及的 6 个文件全绿；全量 **441 passed + 16 skipped + 1 failed（仅既有 SideNav）**，共 458 例（新增 2 例状态机边界）；type-check 全绿 |

## Codex 独立验收（阶段五）回应

Codex 只读复审结论「部分达标」。逐条回应（✔=同意已修，📌=同意记遗留，✖=不同意/说明）：

**已修（同一轮内落实并复跑）**：
- ✔ 弱断言：网络错误 `toBeTruthy`→精确断言「网络请求失败」；RAG 删除改精确 URL+DELETE；Agent 冗余 `toBeTruthy` 删除；XSS 改无条件断言（`[onerror]`/`script` 不得存在）；历史快照补「执行后改表单→快照不变」的真不可变性验证；Analytics 行集断言限定 `.rt__table` 作用域。
- ✔ 边界：chat 画像补空画像(null→「空画像」)与 500 错误(role=alert)分支；RAG 补真实点击「下一页」→page=2 请求+第 2 页渲染；useCapabilityRun 补非 SSE abort、SSE 读取中途 transport 异常（保留已收 token）两例。
- ✔ 契约硬化：危险能力确认前强行点击 0 fetch；json 体断言 `Content-Type: application/json`；URL 也断言无 tenantId。
- ✔ 隔离：`setupCatalog` 统一 `localStorage.clear()`。
- ✔ 进度文档更正：skip 实为 **14 条 issue**（issue-14 是既有测试假阳性，无法用 skip 表达——已由新增 process-ready 主动测试补强盲区，旧测试修正记遗留）；`npm test` 明确为「新增全绿但套件因既有 SideNav 红而退出失败」。

**同意，记遗留（见下节遗留 5-8）**：
- 📌 完整边界矩阵（deadletter HTTP 错误/busy 重入、workflow list/unclaim/complete 失败、agent 各模式错误与 SSE error/abort、空 body SSE、连续 run 旧响应覆盖）；📌 issue 类 skip 的复现完整度（issue-11 缺 deferred 晚到、issue-07 缺仅 tasks 分支、数字边界只测下界）；📌 Async `refreshTask` 无 seq/busy 守卫——**验收新发现的疑点（候选 issue-16）**；📌 Async SSE 正常 complete 的 UI 终态与 handle 清理。
- 📌 afterEach `cleanup()` 无法兜底断言中途失败时的 wrapper unmount（残余风险由 vitest 文件级隔离缓解）。

**不同意 / 说明**：
- ✖「57 项契约的 query/body 期望取自 cap.params 属自证」：期望取自 **catalog 数据**、实际取自 **client 装配代码**，二者独立，管线（catalog→表单→fetch）不自证；自证风险仅在 catalog 本身，而 method/path/kind/state 已由独立硬编码表钉死。params 级独立钉死收益低、维护成本高，不采纳。
- ✖「Analytics 模块 mock 未验传输」：analytics 三能力的 URL 编码/method/header/body 已由 CONTRACT 层 57 项契约覆盖，视图层重复验证传输属冗余；模块 mock 在该文件导入序下稳定（多轮复跑一致）。保留，但若后续 flaky 即切 fetch stub（原遗留 3）。
- ✖「跨模块链靠 $emit 不真实」：Runner 的「执行→emit result」链路已在 CONTRACT 的 result-emit 用例真实覆盖；视图测试注入 emit 是分层隔离设计，非偷工。

## 疑似 bug 上报（15 条已核实：14 条以 skip+期望断言暴露，issue-14 为既有测试假阳性由主动测试补强，详见 03-suspected-issues.md）

P1（9）：issue-01 workflow 动作错误/成功提示被 refresh 吞掉；issue-02 analytics 选表乱序竞态；issue-03 useCapabilityRun.reset 被旧流回写；issue-04 async 手动重订阅不带 Last-Event-ID；issue-05 async 旧 onDone 误关新订阅；issue-06 async 事件缓存无上限；issue-07 agent DAG 必填校验用 OR；issue-09 agent 切模式误解释旧结果；issue-10 chat 业务 error 抹掉已收 token。
P2（6）：issue-08 数字越界无校验；issue-11 chat 清空后画像残留；issue-12 rag 高亮绑定实时 query；issue-13 rag GraphRAG 硬编码「未启用」与 ready 目录矛盾；issue-14 既有 agent 测试整页文本假阳性（本轮已用作用域断言修复盲区，旧测试未动）；issue-15 一次性请求卸载不 abort。
已排除（1）：async cancel 强写 CANCELLED——后端契约 `{taskId,cancelled:true}` 无 status，乐观投影必要；已补「成功投影/失败不强写」成对回归。

## 遗留待办

1. **SideNav.test.ts 既有失败**（与本次无关，改动前即红）：`点击模块行即展开能力，且不跳转` 断言 `.nav__overview.active` 不存在——导航壳层高亮逻辑回归，疑与 4111f0d(RBAC 移除)/3b1a19c(导航重设计) 后的 active 判定有关，需单独排查。
2. 15 条疑似 bug 修复后去掉对应 `it.skip` 即成回归测试（issue-04 的 skip 假定 `streamCapability` 未来第 5 参传 lastEventId，落地时按实际实现调整）。
3. `AnalyticsLabView.interaction.test.ts` 仍用 `vi.mock('api/client')` 模块 mock（该文件的导入顺序恰好命中 mock 实例，两次运行稳定）；如后续 flaky 再统一切 fetch stub。
4. 共享库构建期开关 off（`VITE_SHARED_KB_UI_ENABLED=false`）分支未自动化（需独立文件 + vi.resetModules，见 TEST_PLAN §9.1）。
5. **候选 issue-16（Codex 验收新发现）**：`AsyncMonitorView.refreshTask` 无 busy/seq/abort 守卫，连续刷新或 refresh/cancel 乱序时旧结果可覆盖新状态（`AsyncMonitorView.vue:93-101`）——与 issue-02 同族，修复时一并按 seq 模式处理。
6. 边界矩阵深化：deadletter HTTP 错误/busy 重入、workflow list/unclaim/complete 失败路径、agent 各模式错误与 SSE error/abort UI、空 body SSE、连续 run 旧响应覆盖新 run 的守卫测试。
7. issue 类 skip 的复现完整度：issue-11 补 deferred 晚到分支、issue-07 补「仅填 tasks」与「合法 JSON 非数组」、issue-08 补上界/负值/NaN（`n=10`、`topK=51`、`minScore<0`）。
8. issue-14 的旧假阳性测试（`AgentLabView.test.ts` 整页文本断言）按当前 ready 目录改写为作用域断言——留待 bug 修复轮一并处理，避免本轮触碰既有测试。

## 修复轮记录（2026-07-17 晚，「修复遗留的问题」）

### 生产代码修复（12 个文件）

| Issue | 文件 | 修复 |
|---|---|---|
| issue-03 | `composables/useCapabilityRun.ts` | 引入 generation 代号：run/reset/gate-fail 均递增并中止旧请求，全部回调凭代号失效——旧流 onDone 不再把 reset 后的 idle 打回 aborted |
| issue-10 | `modules/chat/ChatConsoleView.vue` | SSE 业务 error 且已有 token 时保留正文（渲染 markdown）+ 系统条提示「流错误」，不再抹掉已收内容；顺带删除原不可达分支 |
| issue-11 | 同上 | clearAll 清 memProfile/memError/memBusy 并使在途画像请求失效；loadProfile/clearProfile 加 memSeq 乱序守卫 |
| issue-07 | `modules/agent/AgentLabView.vue` | canSend 按目录 required 声明逐项校验（DAG goal+tasks 缺一不可）；tasks 必须为合法 JSON 数组，否则字段错误 |
| issue-08 | 同上 + `modules/rag/RagWorkspaceView.vue` | voteN 1..9 整数校验；RAG topK 1..50 / minScore 0..1 校验，越界禁发+字段错误（不再只靠 HTML min/max） |
| issue-09 | `modules/agent/AgentLabView.vue` | watch(modeId) → run.reset()+收起原始响应：切模式清理旧结果，不再按新模式误解释旧 id |
| issue-12 | `modules/rag/RagWorkspaceView.vue` | submittedQuery 提交快照：命中高亮绑定本批结果对应的查询 |
| issue-13 | 同上 | GraphRAG subtitle/InfoNote 按目录 state 动态呈现（ready 不再硬编码「未启用」），flag 名取自 capability.featureFlag |
| issue-15 | 同上 | 检索走 useAbortable：新检索中止旧请求、卸载自动中止（其余一次性调用的卸载中止仍待产品确认切换策略） |
| issue-04 | `api/sse.ts` + `modules/tasks/AsyncMonitorView.vue` | streamCapability 新增 StreamOptions.lastEventId（首次/401 重试均带 Last-Event-ID）；手动重订阅传入 t.lastEventId，服务端断点续发不重放 |
| issue-05 | `modules/tasks/AsyncMonitorView.vue` | 每任务订阅 generation：旧订阅迟到回调失效，不误关新订阅；onDone 终态释放 handle |
| issue-06 | 同上 + `AsyncTaskTimeline.vue` + `types.ts` | 单任务事件缓存上限 2000（与 MAX_EVENTS 对齐），超出计 dropped 并在时间线展示「已丢弃 N」 |
| issue-16 | `modules/tasks/AsyncMonitorView.vue` | refreshTask 每任务 seq 守卫：连点刷新旧响应晚到丢弃 |
| issue-02 | `modules/analytics/AnalyticsLabView.vue` | selectTable 加 describeSeq 乱序守卫（仿 RAG detailSeq 模式） |
| issue-01 | `modules/workflow/WorkflowDeskView.vue` | exec 增加 clearMessages 选项：动作后的静默刷新（loadInbox(false)）不清动作错误/成功提示；空清单提示仅手动刷新时给出 |

### 测试变更

- 14 个 `it.skip('TODO(issue-xx)')` 全部摘除转为激活回归（标题改为「issue-xx 回归」），其中 issue-07 补「仅填 tasks」「合法 JSON 非数组」、issue-08 补 n=10/topK=51/minScore=-0.1 上下界、issue-11 补 deferred 晚到分支。
- 新增：issue-16 回归（连点刷新乱序）、`RagWorkspaceView.sharedOff.test.ts`（构建期 kill switch off 分支，vi.mock config）。
- `SideNav.test.ts`：三个过时测试按现行「双动作」设计改写（NavModuleRow 注释明确模块行=展开+跳转 /m/:id，旧测试断言的是重设计前行为）——这就是此前"既有失败"的根因，非生产 bug。
- `AgentLabView.test.ts`（issue-14）：两个整页文本假阳性测试改为作用域断言（`.ag__mode.is-off` 不存在 / process ready 可选中且端点提示正确）。
- 既有 async SSE 订阅断言补第 5 参 `{ lastEventId: undefined }`。

### 终态验证

- `npm test`：**59 文件 / 460 例全部通过，0 skip**，两次运行一致。
- `npm run type-check`：全绿。
- 仍开放的低优先级项：一次性请求「切换分区即中止」策略待产品确认（卸载中止已对检索落地）；边界矩阵进一步深化（deadletter HTTP 错误、workflow list 失败等）可随后续迭代补充。
