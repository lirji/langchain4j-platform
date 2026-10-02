# 跨运行时可靠性整改状态

## 当前状态

- 目标：完成 DELIVERY_PLAN.md 的 S1–S8；当前 IN_PROGRESS。
- 授权：本会话用户“按照Claude的SKILL，对这些问题进行优化、修复”；Git 按 AGENTS.md，生产部署未授权。
- 分支：两仓均 `fix/cross-runtime-reliability`。
- 基线：Java `e8f11cb`，Python `c4fc90d`，均有原有未提交改动。
- 既有 diff/index/文件指纹已保存至各仓 `.git/codex-cross-runtime-reliability-baseline/`。
- 历史 ESS 阻塞不被本计划改写；本计划遵循 Engineering Skill 的交互式实施、验证、复审与交付边界。

## 切片

| Slice | 状态 | 证据／下一步 |
|---|---|---|
| S1 | LOCAL_PASS | 新用例旧代码 3 fail / 8 pass；修复后 11 pass；全量 483 pass；ruff/mypy PASS |
| S2 | LOCAL_PASS | 渠道 84 项通过；内存/H2 恢复、ACK 503、管理员隔离；MySQL 8.4 独立临时库 10 项通过；见 S2_CHANNEL_INBOX.md |
| S3 | LOCAL_PASS | Java reactor 1403/0 fail（最新修改聚焦复验）；Python 492 pass；Redis 4 项；真实本地 OpenAI HTTP/SSE 5 工厂出口单次计费；部署门禁/Compose/Helm PASS；见 S3_SHARED_BUDGET.md |
| S4 | TODO | 固定 producer revision 与契约制品 |
| S5 | LOCAL_PASS | 原实例只读回执、用户/参数/租户绑定；真实 MySQL lost-response PASS；Python 9 项；见 S5_REFUND_RECEIPTS.md |
| S6 | TODO | worker 分派与可信上下文恢复 |
| S7 | TODO | 流式 shadow 与独立进程验证 |
| S8 | TODO | 聚合回归、对抗复审、文档、发布 |

## 验证与交付门禁

| Gate | 状态 | 说明 |
|---|---|---|
| 基线 | PASS | Java 全量 BUILD SUCCESS；Python 原有 480 项通过，新增失败用例修复后全量 483 pass |
| 实现／聚焦验证／真实本地集成 | PENDING | 按切片记录，不以 Mock 代替数据库语义 |
| 文档／Code Hygiene／复审 | PENDING | 每切片及最终聚合检查 |
| Git main 发布 | PENDING | 排除原有无关改动，按依赖顺序发布 |
| 远程 CI | PENDING | 当前尚无本轮发布 revision |
| 生产部署／真实模型质量／目标容量 | NOT_APPLICABLE | 本轮本地工程范围外；生产 NO-GO 保持 |

## 下一步

S1/S2/S3/S5 已完成本地验证；继续 S6 独立只读 worker，协议稳定后完成 S4 与 S7/S8。
