# Agent 异步任务迁移 QA 计划

## 测试目标

验证 Agent 异步任务生命周期部分迁移到 `async-task-service` 后，在当前镜像模式
（`AGENT_ASYNC_EXTERNAL_ENABLED=true`、`AGENT_ASYNC_EXTERNAL_AUTHORITATIVE=false`）下：

- Agent 仍可正常创建、执行和查询本地异步任务；
- 相同 `taskId` 的任务会同步到中心任务服务，状态和租户保持一致；
- 中心任务的持久化、事件流、租约、幂等和隔离逻辑没有回归；
- 迁移边界和暂未接入的能力被明确记录。

## 范围与约束

- 仅访问 localhost 当前开发栈。
- 使用当前工作区源码重建 `agent-service` 与 `async-task-service`。
- 使用现有 Chrome OIDC 登录态执行一条最小真实 Agent 任务，最多产生一次小规模模型任务成本。
- 不配置外部 webhook，不取消或删除任务，不清空数据库。
- QA 阶段只记录缺陷，不修改业务实现。

## 用例

| ID | 场景 | 预期 |
|---|---|---|
| AM-01 | 配置与健康基线 | 两服务健康，运行配置为 external enabled + mirror mode |
| AM-02 | 聚焦构建与测试 | `agent-service`、`async-task-service` 及依赖测试全部通过 |
| AM-03 | Agent 异步创建与本地查询 | UI 提交成功并返回 `taskId`，本地任务最终进入终态 |
| AM-04 | 中心任务镜像一致性 | `/agent/tasks/{id}` 与 `/async/tasks/{id}` 使用相同 ID，状态、租户一致 |
| AM-05 | SSE 生命周期 | 本地与中心 SSE 均能返回任务状态事件，中心流支持已持久化事件回放 |
| AM-06 | 中心事件日志与租约 | 事件追加校验、幂等、worker/lease 冲突测试通过 |
| AM-07 | 鉴权与租户隔离 | 无凭据业务请求返回 401；跨租户任务不可见 |
| AM-08 | webhook、孤儿回收与指标 | 单元测试覆盖结构和状态变化；不触发真实外部回调 |
| AM-09 | 运行日志 | 无镜像 create/update 失败或未处理异常 |
| AM-10 | 迁移边界 | 核对 Agent 进度事件是否已经同步至中心事件日志，并记录兼容性缺口 |

## 通过标准

- AM-01 至 AM-09 无阻断性失败；
- 同一真实任务在 Agent 本地与中心任务服务可见且最终状态一致；
- 若 AM-10 暴露尚未迁移的进度事件能力，作为已确认迁移缺口报告，不把源码推断当作黑盒通过。
