# 本次 main 合并例外

## 用户决定与适用范围

2026-10-02, 用户明确指示: “要是因为框架等级问题的话, 可以忽略。合并main分支吧”。
本次按该指示接受 Java 既有框架/客户端及传递依赖版本的扫描结果, 继续七项可靠性修复的正常main合并。
批准对象为任务分支fix/cross-runtime-reliability的当前改动; 不实施新增Boot/Cloud框架升级。

例外只覆盖已披露的 Java supply-chain run36978975220 在1ac14a0聚合SBOM报告的
85条HIGH/CRITICAL记录、31个依赖坐标, 详见SECURITY_MIGRATION_PROPOSAL.md。
本任务未升级Java框架或客户端版本; 成功验证的deba7bc至合并前HEAD只新增交付文档。
固定契约producer6f43ddf和IAM SDK来源保持不变。

## 证据与实际状态

- Java cutover36980014838 SUCCESS: 固定契约/SDK、完整reactor、Compose和Helm。
- Python quality36979153821 SUCCESS: 审计、契约、测试、构建和镜像扫描。
- 本地Java1406tests/0fail、Python518pass、真实MySQL/Redis/进程恢复与取消验证通过。
- Java SBOM安全扫描仍FAIL; 85条记录仍待整改, 不改写为PASS或已修复。
- Java18镜像扫描因前置SBOM失败未执行, 不宣称其通过; 不把例外扩展到未观测的新失败。

不修改CI severity、ignore列表、工作流判断或分支保护。main推送后的自动CI继续按原门禁执行;
若再次报告既有版本记录, 保留其失败结论并引用本次合并例外。
本例外用于源码合并, 不将生产runbook改为GO, 不执行tag/release或生产部署。

## Git执行

复用既有两处干净verification worktree保护原项目dirty文件。
确认远程main未前进后, 按Python producer→Java consumer顺序正常快进合并并推送main;
不强推、不重置历史、不夹带用户未提交贡献。实际结果由DELIVERY_REPORT/DELIVERY_STATUS记录。
