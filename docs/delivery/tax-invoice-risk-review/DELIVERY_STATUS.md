# 交付状态

## 目标

使用现有 Coding Agent 工作流交付财税发票风险审查助手，并以 GoldenCase、代码图、Docker 沙箱、差异评审和 QA 报告形成完整证据链。

## 状态

- 阶段：Gate B——等待业务验收
- 状态：ready-for-acceptance
- 最后更新：2026-08-31
- Gate A：已批准
- Gate B：工程门禁全部通过，尚未提交或推送

## 已完成

- 新增无状态 `tax-service`、共享财税协议和 `/tax/invoices/review` API。
- 实现重复、价税合计、税额和所属期间四类确定性规则；AI 只解释，不修改风险结论。
- 接入租户隔离知识检索、证据编号、引用白名单、失败降级、提示注入边界和脱敏审计。
- 增加 `tax-review` scope、`tax-analyst` 角色、既有 JDBC 安装 V3 幂等迁移和 edge 路由。
- 同步 Compose、Helm、启动脚本、供应链矩阵、Tax AI CI 和平台文档。
- 交付 4 条财税 GoldenCase、代码图、Docker 强隔离、实际 HTTP 权限矩阵、评审和 QA 证据。

## 验证摘要

| 门禁 | 结果 | 证据 |
| --- | --- | --- |
| 受影响 Reactor | 通过 | 349 个测试，0 failure，0 error；6 个外部 Casdoor 集成测试按既有条件跳过 |
| 财税模块 | 通过 | 21/21，含规则、边界、403、租户、RAG/AI 降级、审计和双模式装配 |
| GoldenCase HTTP | 通过 | Eval → Edge → Tax，4/4，`passRate=1.0`，HTTP 202 |
| 权限矩阵 | 通过 | 无 key 401、无 scope 403、`tax-review` 200 |
| Docker 沙箱 | 通过 | 断网、只读源码、非 root，21/21，退出码 0 |
| Java 代码图 | 通过 | 1,079/1,079，0 failed，7,405 nodes / 63,611 edges |
| 部署与 CI 静态门禁 | 通过 | Compose、Helm、Shell、Workflow YAML、供应链 18 镜像、diff check |

## 评审结论

- 评审发现的 2 个高风险与 3 个中风险问题均已修复并回归。
- 当前无未解决的高/中风险代码问题。
- 已知环境限制：精简 HTTP smoke 未启动 Redis，Actuator 聚合健康显示 503；业务链路与审计成功，完整 Compose 已声明 Redis。演示脚本关闭该无关健康探针。
- 未执行生产部署、真实财税数据测试或真实模型/政策库调用；这些不属于已批准范围，不能标记为通过。

## 交付物

- `DELIVERY_PLAN.md`：批准的需求、设计和 AC-01～AC-14。
- `DELIVERY_REPORT.md`：最终范围、证据入口、发布和回滚建议。
- `docs/qa/tax-invoice-risk-review/QA.md`：验收条件到证据的逐项映射。
- `docs/qa/tax-invoice-risk-review/REVIEW.md`：差异评审发现、修复和验证。
- `docs/qa/tax-invoice-risk-review/GOLDEN_CASES.md`：GoldenCase 数据与实际运行结果。
- `docs/qa/tax-invoice-risk-review/CODEGRAPH_REPORT.md`：最终代码图摘要。
- `docs/qa/tax-invoice-risk-review/DOCKER_SANDBOX_REPORT.md`：隔离测试属性和结果。
- `docs/qa/tax-invoice-risk-review/DEMO_SCRIPT.md`：可重复演示步骤。

## 下一步

1. 由业务/技术负责人执行 Gate B 验收。
2. 验收后再单独授权提交、推送或部署；本轮未执行这些外部状态变更。
