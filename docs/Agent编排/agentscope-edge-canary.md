# AgentScope 测试租户灰度与回滚

> 历史 Phase 5 灰度记录：平台现已完成全量默认切换。当前操作请使用
> [AgentScope 全量切换与回滚](agentscope-full-cutover.md)；本页保留用于解释旧候选机制。

本手册用于 Phase 5 的第一条透明灰度切片：客户端仍请求
`POST /agent/run`，edge 使用已经验签的内部 JWT 取得 tenant；只有显式 allowlist 中的
租户会被转发到 AgentScope 的 `POST /agent/v2/run`。其他请求继续走
`${AGENT_URI}` 指向的旧 Java `agent-service`。

当前仅灰度同步只读 ReAct。`/agent/run/async`、DAG、Analyst、Process、Chaining、
Voting、Reflexion、任务查询和 SSE 均不在本切片中，仍走 Java。

## 安全与执行语义

- 灰度默认关闭：`EDGE_AGENT_CANARY_ENABLED=false`。
- allowlist 默认空：`EDGE_AGENT_CANARY_TENANTS=`；空列表会 fail closed 到 Java。
- tenant 只从 Casdoor、会话、API key 或栈内 service-token 认证成功后写入的请求内可信
  身份读取。`X-Tenant-Id`、`X-Internal-Token` 等客户端头不能构造该属性或影响路由；
  这也兼容 edge 只持 RS256 签名私钥的部署。
- 候选 URI 只能是无路径前缀、凭据、query 或 fragment 的绝对 HTTP(S) 地址。
- 不对候选失败做同请求自动重试。自动重放会造成重复模型费用，未来接入写工具时还可能
  重复副作用；故障时按下述顺序切回 Java。

`POST /agent/run` 响应会带固定低敏诊断头：

- `X-Agent-Backend: agentscope`：命中候选；
- `X-Agent-Backend: legacy-java`：该请求符合灰度接口，但开关/allowlist/身份未命中。

Micrometer 计数器 `edge.agent.canary.routing` 只使用固定 `target`、`reason` 标签，不包含
tenant、user、prompt、task 或 token。部署另行接入 Prometheus registry 时，再由 registry
按其命名规则导出。

## 本地启用

先在独立 `../agentscope-platform` 仓库启动候选，确保它与 Java 平台使用相同的内部 JWT
验签配置：

```bash
cd ../agentscope-platform
AGENT_V2_ENABLED=true APP_PORT=18085 uv run uvicorn agentscope_platform.main:app \
  --host 127.0.0.1 --port 18085
curl -s http://127.0.0.1:18085/readiness
```

readiness 的 `checks.candidateRoute` 必须为 `ENABLED`。不要把内部 token 写入 shell 历史、
报告或仓库。

再为 edge 配置一个已批准的本地测试租户：

```bash
export EDGE_AGENT_CANARY_ENABLED=true
export EDGE_AGENT_CANARY_URI=http://host.docker.internal:18085
export EDGE_AGENT_CANARY_TENANTS=acme
docker compose -f deploy/docker-compose.yml up -d --build edge-gateway
```

使用已有的登录/Casdoor 测试会话，或在 `dual` 本地模式下使用测试 API key 调用
`POST http://127.0.0.1:8080/agent/run`。验收：

1. HTTP 200，JSON 字段仍符合 `AgentRunReply`；
2. `tenantId` 是已认证租户；
3. `X-Trace-Id` 可关联；
4. `X-Agent-Backend=agentscope`；
5. 非 allowlist 租户以及 `/agent/dag/run` 仍由 Java 处理。

## 回滚演练

回滚顺序不能颠倒：

1. 设置 `EDGE_AGENT_CANARY_ENABLED=false`，重建/重启 edge；
2. 用同一测试身份重复 `POST /agent/run`，确认
   `X-Agent-Backend=legacy-java` 且请求成功；
3. 确认 Java `agent-service` 健康、错误率和延迟恢复基线；
4. 再设置 AgentScope `AGENT_V2_ENABLED=false` 并重启候选；
5. 确认候选 readiness 为 `candidateRoute=DISABLED`，直连 `/agent/v2/run` 返回 404。

关闭 edge 在先，可避免候选重启窗口向用户返回 404。回滚不需要修改全局 `AGENT_URI`。

## 扩量门禁

增加租户或迁移下一个 endpoint 前，至少核对完成率、工具错误率、P95 延迟、token/成本、
跨租户安全事件、候选 5xx 和上述路由计数。Phase 5 最终全量切换及删除 Java 编排代码仍是
独立审批动作。
