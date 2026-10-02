# 跨运行时可靠性整改状态

## 当前状态

- 目标：完成 DELIVERY_PLAN.md 的 S1–S8；当前 COMPLETED_WITH_ACCEPTED_FINDINGS。
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
| S4 | LOCAL_PASS | producer 6f43ddf、46项/消费25项固定SHA/digest；缺源/漂移 fail-closed；见 S4_IMMUTABLE_CONTRACTS.md |
| S5 | LOCAL_PASS | 原实例只读回执、用户/参数/租户绑定；真实 MySQL lost-response PASS；Python 9 项；见 S5_REFUND_RECEIPTS.md |
| S6 | LOCAL_PASS | Java 75、Python 507；MySQL 5；独立 API退出/worker崩溃/接管/DAG/旧epoch恢复 PASS；见 S6_DURABLE_WORKER.md |
| S7 | LOCAL_PASS | 原生取消、背压/上下文清理、真实跨进程JWT/SSE/TCP取消 PASS；见 S7_STREAM_SHADOW.md |
| S8 | DONE_WITH_ACCEPTED_FINDINGS | 回归/集成/文档/自复审完成; Python→Java正常快进合并/push main并核对远程; 用户接受已披露版本记录 |

## 验证与交付门禁

| Gate | 状态 | 说明 |
|---|---|---|
| 基线 | PASS | Java 全量 BUILD SUCCESS；Python 原有 480 项通过，新增失败用例修复后全量 483 pass |
| 实现／聚焦验证／真实本地集成 | PASS | Java1406/0fail、Python518pass; MySQL/Redis/独立worker/SSE见QA_REPORT |
| 文档／Code Hygiene／复审 | PASS_WITH_LIMITATIONS | 文档闭合、无blocking hygiene; 主Agent自复审, 非独立评审 |
| Git main 发布 | PASS | 两仓远程main包含任务提交; 已披露85条版本记录按MERGE_EXCEPTION接受 |
| 远程 CI | PASS_WITH_ACCEPTED_FINDINGS | 同代码Python quality/Java cutover成功; Java1ac14a0 SBOM FAIL已接受; main新CI运行中, 见DELIVERY_REPORT |
| 生产部署／真实模型质量／目标容量 | NOT_APPLICABLE | 本轮本地工程范围外；生产 NO-GO 保持 |

## 下一步

本轮源码合并交付已完成; 后续版本整改按SECURITY_MIGRATION_PROPOSAL另行安排。CI原门禁保持, 生产NO-GO保持。
