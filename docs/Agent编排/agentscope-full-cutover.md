# AgentScope 全量切换与回滚

从 2026-07-29 起，`agentscope-platform` 是平台 `/agent/**` 的权威编排服务：

- edge `AGENT_URI` 默认 `http://agentscope-orchestrator:8085`；
- interop `AGENT_BASE_URL` 默认 `http://agentscope-orchestrator:8085`；
- AgentScope 异步任务默认接入中央 `async-task-service`；
- 旧 Java `agent-service` 继续运行或保留镜像，但只作为显式整服务回滚目标。

不会对 AgentScope 失败的请求自动重放到 Java。这样可以避免重复模型费用，也避免未来写
能力重复执行。当前 AgentScope 只注册只读工具；旧 Java 的 `refund_start`、code、browser、
MCP-client、vision 等未迁能力在默认路径不可用，不会静默旁路。

## 本地启动

先在独立 AgentScope 仓库构建平台栈消费的镜像：

```bash
docker compose -f ../agentscope-platform/compose.yml build orchestrator
docker compose -f deploy/docker-compose.yml up -d agentscope-orchestrator
docker compose -f deploy/docker-compose.yml up -d --no-deps --force-recreate interop-service edge-gateway
```

默认镜像名是 `agentscope-platform:local`，可用 `AGENTSCOPE_IMAGE` 改成已发布的不可变 tag。
等待以下检查通过：

```bash
curl -fsS http://127.0.0.1:18085/readiness
docker compose -f deploy/docker-compose.yml exec edge-gateway \
  sh -c 'printf "%s\n" "$AGENT_URI" "$EDGE_AGENT_BASELINE_BACKEND"'
docker compose -f deploy/docker-compose.yml exec interop-service \
  sh -c 'printf "%s\n" "$AGENT_BASE_URL"'
```

期望目标均为 `agentscope-orchestrator:8085`，baseline label 为 `agentscope`。再通过真实登录
会话调用同步、异步、任务查询/SSE、MCP discovery/call 和 A2A。`POST /agent/run` 的响应头
应为 `X-Agent-Backend: agentscope`。

## 整服务回滚

回滚必须同时切 edge 与 interop，且只验证新请求；不要重放切换时的在途请求：

切换前必须先按
[生产切流门禁](../平台工程/production-cutover-gates.md#任务排空)
确认中央任务库中 Agent `PENDING/RUNNING` 数量为零。历史无 lease 任务由 orphan reaper
审计性地终止，禁止直接删除记录或让 Java/AgentScope 同时领取同一任务。

```bash
AGENT_URI=http://agent-service:8085 \
AGENT_BASE_URL=http://agent-service:8085 \
EDGE_AGENT_BASELINE_BACKEND=legacy-java \
docker compose -f deploy/docker-compose.yml up -d --no-deps --force-recreate \
  interop-service edge-gateway
```

确认 `agent-service` 健康，并用新的请求验证：

- edge `/agent/run` 返回 `X-Agent-Backend: legacy-java`；
- interop `/agent/capabilities`、MCP 调用和 A2A task 均能访问 Java；
- AgentScope 可随后停止，但不要删除镜像或配置。

恢复 AgentScope：

```bash
AGENT_URI=http://agentscope-orchestrator:8085 \
AGENT_BASE_URL=http://agentscope-orchestrator:8085 \
EDGE_AGENT_BASELINE_BACKEND=agentscope \
docker compose -f deploy/docker-compose.yml up -d --no-deps --force-recreate \
  interop-service edge-gateway
```

生产/Kubernetes 使用对应 Helm values 覆盖同三个变量。AgentScope 的 RS256 验签通过
`INTERNAL_JWT_ALGORITHM` 和 `INTERNAL_JWT_PUBLIC_KEY` 显式映射平台 ConfigMap/Secret；
探针使用 `/health` 和 `/readiness`，不是 Spring Actuator 路径。
