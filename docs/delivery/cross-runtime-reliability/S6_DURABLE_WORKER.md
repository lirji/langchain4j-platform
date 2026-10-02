# S6: 独立只读 worker 设计与验收

可选角色 `inline`（兼容默认）、`api`（持久化接单）、`worker`（独立轮询执行）。
API 角色仅接受无确认 grant 的任务，发起到 Java 时映射为 `agent.readonly.*.v1` 五种 kind。
Java 功能开关默认关，必须 JDBC；事务原子写 ASYNC_TASK 和调度上下文，不持久化用户 JWT/确认令牌。
上下文从已验签 TenantContext 捕获，仅授权 `agent` scope，保留部门、trace；请求体不能提升权限。
输入含 prompt/model/tool 版本、行为配置 digest 和 runtime revision；worker 不匹配则终止任务，不隐式改用新版本。
生产 api/worker 必须配置 `ASYNC_TASK_RUNTIME_REVISION=git:<40hex>` 或 `sha256:<64hex>`；本地允许 local。

独立 worker 通过已有独立 worker key 的新 `dispatch` action 领取；固定 task_id，固定调度身份，
普通用户 JWT 无法领取全局队列。领取事务锁一条全局调度行，限制全局和每租户活动租约数，
按租户游标轮转并选各租户最早任务, 排除已用满配额租户；CAS 复用原 leaseEpoch，续租/事件/终态沿用 fencing。
每次接管递增 epoch。崩溃恢复最多 3 次，超限任务转失败；不自动重放副作用。
worker 构建运行器时禁用退款、MCP、浏览器和代码执行；不保存闭包，仅按持久化 kind/输入路由。
任务接管提供短时 `agent` 内部 JWT，用于既有只读工具；需要 Java 有签发能力，RS256 纯验签节点不能启用。
取消是停止后续执行，不能撤销已完成的模型调用。优雅退出 drain 后停止续租，其他进程可接管。

## 本地验收与部署

- Java async-task 模块 75 项通过；新调度 5 项、真实签名控制面 2 项通过。
- MySQL 8.4 临时 UUID 库 5 项通过, 覆盖原子上下文、跨副本/租户配额、旧epoch拒绝和三次崩溃上限。
- Python 全量 507 项通过, 独立 worker 聚焦11项；Ruff/mypy通过。
- `scripts/test_durable_worker_process.py --java-repo <路径>` 跨真实独立进程通过：
  Java中心+MySQL, Python API接单后退出, worker首次调用本地假OpenAI时被强制结束,
  第二worker在租约过期后接管并完成原任务；DAG进度事件/合成/reviewer链路通过,
  原worker追加事件409。没有付费模型或外部业务副作用, 创建的库已清理。
- 两仓 `*.readonly-worker.yml` 可选Compose overlay静态展开通过；worker不监听HTTP、不暴露端口。
  `python -m agentscope_platform.worker --health` 仅在90秒内成功访问中心队列时健康。
  默认inline行为保持；新模式关闭时专用kind返回503, 不假接单。

启用前先执行async-task V3迁移, 再开启 `ASYNC_TASK_DISPATCH_ENABLED` 和对应overlay。
Java `ASYNC_TASK_DISPATCH_SERVICE_ID` 必须与Python `ASYNC_TASK_WORKER_ID`一致;
lease/heartbeat配置须一致, heartbeat*3<=lease；内部JWT默认5分钟, runtime默认240秒并按JWT余量缩短。
全局活动租约默认32/租户2；待处理队列总上限10000/租户100；任务超过24小时或领取3次后失败。
权限语义是接单时的只读委托, 不保存可重放确认grant；组织权限变更需取消待执行任务,
文档级实时授权仍由现有knowledge IAM决定, 本地证据不等于生产权限生命周期验收。
只读任务恢复从持久化输入重新执行, 可能重复读取与产生新的模型费用, 每次调用仍受S3预算控制。
写能力保持inline/新确认入口, 不提供自动副作用恢复。旧版本不可消费新kind, 回滚前先排空,
保留V3表和既有task记录; 不降级成无持久化队列。Helm生产worker拓扑与目标容量仍属外部发布验收。
