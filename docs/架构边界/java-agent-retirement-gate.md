# Java Agent 退役门禁

## 当前状态

AgentScope 是 `/agent/**` 的默认推理与编排运行时。Java `agent-service` 的代码和镜像定义暂时
保留为整服务回滚目标，但不再属于默认运行拓扑：

- Compose：`agent-service` 位于 `legacy-agent` profile。
- Helm：`services.agent-service.enabled=false`。
- edge：`AGENT_URI` 默认指向 `agentscope-orchestrator`。
- interop：`AGENT_BASE_URL` 默认指向 `agentscope-orchestrator`。

这一步只退出默认部署，不删除 Java 源码、数据或镜像，也不代表已经完成生产下线。

## 删除代码前的 release gate

只有以下条件全部满足，才可以另开变更删除 Java `agent-service`：

1. HTTP/JSON/SSE 契约与旧平台基线一致，跨租户和内部身份测试通过。
2. AgentScope shadow 报告通过质量、完成率、错误率和延迟阈值，并覆盖所有已发布 Agent
   模式。
3. canary 期间无未解释的安全、超时、工具副作用或异步任务回归。
4. `async-task-service` 中已无只能被 Java worker 领取的存量任务。
5. interop capability discovery、A2A/MCP 代理只依赖 AgentScope live discovery。
6. 值班、告警、容量和回滚演练完成，生产变更获得独立批准。

当前尚缺真实模型 shadow/canary、生产任务排空和回滚演练，因此只允许停用默认 workload，
禁止删除代码或生产资源。

## 第 4、5 条的机器检查

这两条原先只能人工翻库/读代码判断，现在都有可执行证据。

### 第 5 条：互操作层只依赖 AgentScope live discovery

`InteropToolRegistry` 早已只暴露本地 `platform.ping` + live discovery 结果，但**调用**路径此前不受
约束：`InteropToolDispatcher` 按 Java 侧硬编码的 4 个 case 代理，discovery 没宣告过的工具也照样代理
出去。现在代理前会校验 `InteropToolRegistry.capabilityNames()`，未被宣告一律拒绝且不碰下游：

```bash
mvn -B -pl interop-service -am test
```

`InteropToolDispatcherDiscoveryTest` 覆盖四种情形：宣告过则代理、未宣告则拒绝、discovery 不可达时
四个 agent 工具全部拒绝、`platform.ping` 不受影响。AgentScope 下线某个 Agent 模式后，interop 会在
一个 capability TTL 内自动停止代理它，不需要改 Java 代码。

### 第 4 条：async-task-service 已无只能被 Java worker 领取的存量任务

`GET /async/drain-inventory` 返回当前租户**未完结**的 Agent 异步任务，按 kind + 状态 + 租约持有者
分组。`leaseOwnerId` 为 `null` 表示当前无人持有租约（任何合法 worker 都能领取），非 null 即当前占用
该任务的运行时。默认统计 `agent.task`、`agent.run`、`agent.dag`、`agent.dag-plan`、`agent.analyst`、
`agent.process`，可用 `?kinds=` 收窄。

```bash
curl -s -H "X-Api-Key: $GATEWAY_API_KEY" \
  http://localhost:8080/async/drain-inventory
# 排空后返回 []
# 未排空示例：
# [{"kind":"agent.run","status":"RUNNING","leaseOwnerId":"agent-service","tasks":2}]
```

接口只报事实，不判断某个 `leaseOwnerId` 属于哪个运行时——那是部署侧的知识。因此门禁判定是
「返回为空」，或「所有 `leaseOwnerId` 都属于 AgentScope worker 且不再有 Java worker 持有的租约」。
内存与 JDBC 两种 store 的聚合语义由 `AsyncTaskDrainInventoryTest` 对齐，每个副本读到同一份中心状态。

## 显式回滚

Compose：

```bash
AGENT_URI=http://agent-service:8085 \
AGENT_BASE_URL=http://agent-service:8085 \
EDGE_AGENT_BASELINE_BACKEND=legacy-java \
docker compose --profile legacy-agent up -d agent-service edge-gateway interop-service
```

Helm 环境 values 至少需要同时覆盖：

```yaml
config:
  AGENT_URI: http://agent-service:8085
  AGENT_BASE_URL: http://agent-service:8085
  EDGE_AGENT_BASELINE_BACKEND: legacy-java
services:
  agent-service:
    enabled: true
```

回滚必须整体切换 Agent backend，不能让同一个异步任务被 Java 与 AgentScope 两边同时领取。
恢复完成后重新执行契约、安全和任务一致性 smoke test。
