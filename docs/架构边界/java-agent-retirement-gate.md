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
