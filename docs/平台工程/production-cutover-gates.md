# AgentScope / Knowledge 生产切流门禁

本文定义生产切流前必须逐环境执行的门禁。localhost 结果只能证明实现和操作流程，不替代
目标云账号、目标集群和真实流量验证。

## 放行条件

1. **IAM**：Knowledge ingest 只持有 `documents/*` 写入、multipart 和失败清理权限；
   worker 只持有读取权限；query 不挂载原文存储凭据。
2. **恢复**：required sink 中断时 job 不得进入 READY；依赖恢复并通过端到端 warm-up 后，
   reconcile 只重试幂等 sink 并达到 READY。
3. **容量**：记录提交和查询的成功率、P50/P95/P99、任务排空时间与资源水位。阈值由目标
   环境 SLO values 指定，不能照搬开发机数值。
4. **Soak**：持续读写期间没有任务丢失、非预期 PARTIAL/FAILED、容器重启或资源持续增长。
5. **Canary**：先测试租户，再逐租户扩量；切换后通过响应头、路由配置和业务结果确认 backend。
6. **回滚**：停止新流量，等待 Agent/Knowledge 在途任务为零，再整体切 backend；禁止把同一
   请求自动重放到旧服务。

## 静态与 IAM 检查

```bash
bash deploy/test-production-cutover-config.sh
bash deploy/smoke-knowledge-s3-iam.sh
```

Helm 生产 values 应设置 `secrets.create=false` 并使用 workload identity 或 ESO 创建：

- `knowledge-source-ingest`
- `knowledge-source-worker`

不得把两组凭据重新放回全局 `platform-secrets`。

## 故障注入与恢复

在可弃环境把 worker poll delay 临时放大，停止一个 required sink（例如 Qdrant），提交唯一
文档并观察 job 到达 PARTIAL。恢复依赖后，先连续执行至少三次端到端查询 warm-up；只有
warm-up 全部成功，才恢复流量并等待该 job 到达 READY。

Knowledge readiness 已显式包含 `qdrant` 与 `embedding`，Qdrant 停止时应返回 503，依赖
健康恢复后才返回 200。Qdrant gRPC 连接在依赖刚恢复时仍可能处于 backoff；本项目的本地
演练实际观察过该窗口，因此 readiness 恢复后仍须以至少三次业务请求 warm-up 作为放流门禁。

## 任务排空

切 Agent backend 前检查中央任务库：

```sql
SELECT KIND, STATUS, COUNT(*)
FROM ASYNC_TASK
WHERE KIND LIKE 'agent%'
GROUP BY KIND, STATUS;
```

`PENDING` 和 `RUNNING` 必须为零。历史 `agent.task` 以及 RUNNING 但无 lease 的任务由 orphan
reaper 转为 `FAILED / ASYNC_TASK_ORPHANED`，保留审计记录，不直接删除。

## Knowledge canary 与回滚

本地 Compose 通过 `KNOWLEDGE_URI` 切换 edge：

```bash
KNOWLEDGE_URI=http://knowledge-query:8084 \
docker compose -f deploy/docker-compose.yml \
  -f deploy/docker-compose.knowledge-split.yml \
  up -d --no-deps --force-recreate edge-gateway

KNOWLEDGE_URI=http://knowledge-service:8084 \
docker compose -f deploy/docker-compose.yml \
  -f deploy/docker-compose.knowledge-split.yml \
  up -d --no-deps --force-recreate edge-gateway
```

每次切换后都要执行带真实身份的 `/rag/query`，并确认 `KNOWLEDGE_URI` 和结果。

## Agent 整服务回滚

按 [AgentScope 全量切换与回滚](../Agent编排/agentscope-full-cutover.md) 同时切换 edge 与
interop。顺序固定为：排空 → 启动/探活 legacy → 切 edge+interop → 新请求验证 → 恢复
AgentScope。切换期间的旧请求不重放。

## 目标环境仍需提供的证据

- 云 IAM policy simulator 或对象存储审计日志；
- 目标节点规格下的峰值并发、容量余量和自动扩缩结果；
- 至少一个完整业务高峰周期的 soak；
- 监控、告警、值班确认和变更单；
- canary 租户清单、扩量节奏、停止条件和回滚负责人。
