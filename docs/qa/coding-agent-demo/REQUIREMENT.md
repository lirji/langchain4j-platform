# Requirement

## 用户目标

在 P0 Engineering Kit 之上完成 Coding Agent P1/P2：真实 20 Case 基准、可复现 oracle、Java code graph、Docker 强隔离验证、hash-chained telemetry、50 条 GoldenCase、CI、团队文档和 review-ready PR 交付包。

## 已批准副作用

- 最多 20 次串行 `codex exec`，先跑 3 条 smoke，单条 8 分钟，连续 3 个 infra error 停止。
- 只基于本地 `busybox:latest` 构建 smoke image；禁止 Docker pull。
- 不 commit、push、创建/合并 PR、部署或访问生产。

验收口径见 `docs/delivery/coding-agent-productionization/DELIVERY_PLAN.md` 的 AC-01～AC-14。
