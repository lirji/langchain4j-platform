# 评测控制面边界

## 结论

评测是发布控制面能力，不是在线业务域。默认部署不得让 `eval-service` 常驻，也不得通过
`edge-gateway` 暴露 `/eval/**`。

两套评测能力按被测对象分工：

| 能力 | 权威实现 | 运行方式 | 产物 |
| --- | --- | --- | --- |
| Agent 推理/编排 shadow | AgentScope Python `agentscope_platform.evaluation` | 独立 CLI/CI job | `shadow-report` JSON |
| Java HTTP/检索回归 harness | Java `eval-service` | Compose profile、临时 Pod 或 CI job | Java eval report |

Java `eval-service` 可以只读解析 Python `shadow-report` 的摘要，用于统一发布门禁或归档展示；
它不得重新执行 Agent shadow，也不得定义另一套 Agent 评测 schema。跨语言字段以 Python
仓库导出的 JSON Schema 为准，当前使用 snake_case。

## 默认部署边界

- Compose：`eval-service` 位于 `evaluation` profile，普通 `docker compose up` 不启动。
- Helm：`services.eval-service.enabled=false`，默认不生成 Deployment 或 Service。
- Gateway：没有 `/eval/**` route，也没有 `EVAL_URI`。
- 鉴权：按需启动后只能由受信任的内部 CI/运维调用方直连，仍须携带有效内部身份；健康探针
  除外。

本地按需启动 Java harness：

```bash
docker compose --profile evaluation up -d eval-service
```

Helm 临时启用：

```bash
helm upgrade --install platform deploy/helm/platform \
  --set services.eval-service.enabled=true
```

不要为了执行评测恢复 edge route。需要远程触发时，应由 CI job、受控运维任务或专用内部
控制面发起。

## 发布门禁

AgentScope candidate 只有在报告同时满足质量、完成率、错误率和延迟阈值后，才可以进入下一
级 shadow/canary。报告读取失败、字段缺失、schema 不兼容或 gate 未通过都必须 fail closed。
门禁通过只代表可以讨论下一阶段，不自动授权生产切流。

## 回滚

- Java harness 运行异常：停止临时 job/profile，保留报告，不影响任何在线路由。
- Python shadow runner 异常：保持当前 primary，不消费不完整报告。
- 需要恢复 Java harness：只启用 Compose profile 或 Helm workload；不得把 `/eval/**` 重新
  暴露到 edge。
