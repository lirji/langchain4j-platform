# Agent 异步任务迁移 QA 报告

> **范围更正（2026-07-29）**：本报告测试的是旧 Java `agent-service` 向
> `async-task-service` 的兼容镜像链路，不是独立 `agentscope-platform` 的 AgentScope
> 重构迁移验收。正确的 AgentScope 验收结论见同级仓库
> `../agentscope-platform/docs/qa/agentscope-migration-acceptance-0729-1653/QA_REPORT.md`。
> 下述 `agent.task` 观察不适用于 AgentScope；AgentScope 使用 `agent.run`、`agent.dag`
> 等真实 kind，并已验证细粒度事件写入。

## 结论

**条件通过（PASS WITH GAP）**。

Agent 异步任务的生命周期镜像已经跑通：真实任务能够在 Agent 本地执行，并以同一
`taskId`、租户、用户、状态和结果同步到 `async-task-service`；中心任务及三段生命周期
事件在服务重启后仍可查询和回放。

当前尚未迁移的是 Agent 的细粒度进度事件。中心任务只收到
`PENDING → RUNNING → SUCCEEDED`，Agent 本地发布的步骤/DAG 进度没有转发到中心事件日志，
且中心 `/events` 接口会拒绝实际镜像任务使用的 `agent.task` 类型。

## 测试环境

- 时间：2026-07-29 16:39–16:46（Asia/Taipei）
- Compose：25/25 服务运行
- Edge：`http://127.0.0.1:18080`，Casdoor `only`
- 前端：`http://127.0.0.1:8093`
- 身份：Casdoor OIDC，`alice / acme`
- Agent 配置：
  - `AGENT_ASYNC_EXTERNAL_ENABLED=true`
  - `AGENT_ASYNC_EXTERNAL_AUTHORITATIVE=false`
  - `AGENT_ASYNC_EXTERNAL_MIRROR_WEBHOOK=false`
  - `ASYNC_TASK_BASE_URL=http://async-task-service:8086`
- 中心任务配置：
  - `ASYNC_TASK_STORE=jdbc`
  - 事件保留 `PT24H`
  - 单事件最大 `262144` 字节

测试前使用当前工作区源码打包并只重建、重启 `agent-service` 和
`async-task-service`，没有连带重建其它依赖。

## 自动化测试

执行：

```bash
mvn -pl agent-service,async-task-service -am test
```

结果：**BUILD SUCCESS，283 tests，0 failures，0 errors，0 skipped**。

其中：

- `agent-service`：127 项通过；
- `async-task-service`：43 项通过；
- 共享依赖模块：113 项通过。

覆盖了 Agent 本地任务、外部任务客户端、中心 controller、JDBC store、事件日志、
SSE、租约、事件幂等、webhook outbox、生命周期 relay、孤儿任务回收及指标。

## 真实端到端结果

提交目标：

```text
只调用一次 current_time 动作，返回当前时间后立即结束。
```

任务 ID：

```text
ac562ab4-294a-4c55-85e1-9b8b1a7ac469
```

| 检查项 | 结果 | 证据 |
|---|---|---|
| Chrome OIDC 登录 | PASS | 页面显示 `alice · acme · Bearer` |
| `POST /agent/run/async` | PASS | HTTP 202，返回同一任务 ID，初态 `PENDING` |
| Agent 本地任务 | PASS | `SUCCEEDED`，`stopReason=DONE`，仅执行一次 `current_time` |
| 中心镜像任务 | PASS | 同一 ID、tenant=`acme`、userId 一致、kind=`agent.task`、`SUCCEEDED` |
| 结果同步 | PASS | 中心 `result` 与 Agent 本地结果一致 |
| 中心 SSE | PASS | 回放 3 个事件：`PENDING`、`RUNNING`、`SUCCEEDED` |
| JDBC 持久化 | PASS | 重启 `async-task-service` 后任务仍为 200/SUCCEEDED |
| 事件持久化 | PASS | 重启后中心 SSE 仍完整回放 3 个事件 |
| 本地终态 SSE | PASS | 终态后订阅返回当前 `SUCCEEDED` 快照 |
| 镜像日志 | PASS | 两侧均有 submitted/finished audit，无 mirror create/update failure |

Agent 本地约 3.45 秒完成任务；中心终态更新时间比本地终态晚约 12 毫秒。

## 安全与隔离

| 场景 | 结果 |
|---|---|
| 直连 Agent，无凭据 | 401 |
| 直连中心任务，无凭据 | 401 |
| 经 Edge 访问 Agent，无凭据 | 401 |
| 经 Edge 访问中心任务，无凭据 | 401 |
| `globex` 查询 `acme` 的 Agent 本地任务 | 404 |
| `globex` 查询 `acme` 的中心镜像任务 | 404 |

## 旧 Java Agent 兼容链路观察（不归因于 AgentScope）

### LEGACY-GAP-01：旧 Java Agent 细粒度进度事件未进入中心事件日志（P2）

现象：

- 真实镜像任务在中心只回放三个生命周期状态；
- 对该中心任务调用 `POST /async/tasks/{id}/events` 返回 404；
- Agent 本地进度事件仍只发给本地 SSE 订阅者。

原因证据：

1. Agent 创建中心任务时固定使用 `kind="agent.task"`：
   `agent-service/src/main/java/com/lrj/platform/agent/async/ExternalAsyncTaskClient.java:127`。
2. 中心进度事件接口只接受 `agent.run`、`agent.dag`、`agent.dag-plan`、
   `agent.analyst`、`agent.process`，不包含 `agent.task`：
   `async-task-service/src/main/java/com/lrj/platform/asynctask/AsyncTaskController.java:239`
   和 `:371`。
3. `AgentAsyncTaskMirror` 只监听 `AgentTaskEvent` 生命周期事件，没有监听
   `AgentTaskProgressEvent`：
   `agent-service/src/main/java/com/lrj/platform/agent/async/AgentAsyncTaskMirror.java:28`。
4. `ExternalAsyncTaskClient` 当前没有调用中心 `/events` 的方法；本地进度仍由
   `AgentTaskSseService` 直接推送：
   `agent-service/src/main/java/com/lrj/platform/agent/async/AgentTaskSseService.java:75`。

影响：

- 当前“生命周期迁移”可用，不影响任务执行、最终状态、结果与持久化；
- 若下一步要让中心 SSE 完整替代 `/agent/tasks/{id}/stream`，DAG worker、规划、
  synthesis、critique 等进度会缺失；
- `/events` 已有的幂等、worker/lease 校验对当前镜像任务实际不可达。

建议修复方向：

- 为不同 Agent 异步入口保留真实中心 kind，或把 `agent.task` 纳入统一兼容集合；
- 在 Agent 侧增加 `AgentTaskProgressEvent` → 中心 `/events` 的转发；
- 增加真实镜像 kind 的 controller/client 合同测试，覆盖事件幂等、lease owner、
  终态拒绝和 SSE replay。

## 测试数据与副作用

- 中心 JDBC 中保留上述一条终态 QA 任务和三条生命周期事件；
- 未配置 webhook，未产生外部回调；
- 未取消、删除或清空任何数据；
- 测试结束时 25/25 Compose 服务仍在运行，`async-task-service` 已完成一次重启验证。
