# 知识库文档解析修复进度

## 目标与授权

- 任务 `knowledge-parsing-hardening`；用户要求记录问题、按顺序持续实施，主要资料 PDF/Word/Markdown/Excel/CSV/技术文档；最新选择按常见企业场景处理 OCR，不必等待业务样本。采用中英、扫描/混合/旋转、长表/长手册和异常文件；合成验收不冒充真实检索收益。
- 这是唯一实施状态。问题与优先级：[生产优化方案](../../对话与检索/知识库文档解析生产优化.md)；格式事实：[支持范围](../../对话与检索/知识库文档支持范围.md)。
- 原目录 `/Users/liruijun/personal/LLM/langchain4j-platform`，任务分支 `fix/knowledge-parsing-hardening`。用户持续授权按逻辑单元提交、正常合并并推送 origin/main；不强推、不绕过保护、不部署、不重建索引或清理历史数据。
- 已使用 platform-legacy-maintenance/java-feature/diff-review、continue-approved-delivery、task-git-delivery、文档同步技能；规范 `~/.codex/architecture-standards.md` 的建模、事务、接口、并发、容量、发布治理及个人技术栈并发章节已读。

## 任务清单与证据

| 顺序 / Slice | 状态 | 当前事实 |
| --- | --- | --- |
| 问题/格式/优先级文档 | DONE | KP-01～21 与格式证据等级已记录 |
| KDP-01/02/03 | DONE / 已交付 | 最终编号、Markdown/切句、正文预算与稳定错误；`ff041b7`、`0f90bf1`、`d135d6c`，历史验证见 TEST_RESULT |
| KDP-04 | DONE / 已交付 | `6138ee4`：不可变准备结果与 V9/CAS/旧任务保护；613 项回归及真实 MySQL/S3 5 项通过 |
| KDP-06-A | DONE / 已交付 | `f5c64e1`：17 场景格式基线及 CSV 依赖修复；630 项、0 失败错误、9 跳过 |
| KDP-05-A | DONE / 已交付 `813c699` | 独立解析 JVM/资源预算/截止/S3 超时/容器配置；638 项回归及 HTTP 修正后 10 项通过，真实 Boot 和 Linux 四格式通过 |
| KDP-06-B | DONE / 已交付 `1b5b3b7` | 结构解析/格式切分/来源传递；973 项完整回归、14 项 ES 权限补测及 34 项代码保真补测通过；真实 ES/Boot/Linux 四格式通过 |
| KDP-05-B | DONE / B1 已交付 `88ea47c`，B2 已交付 `54f8b1f` | 流式/hash/批次/失租停止；B2 692 项完整回归、0 失败错误、10 跳过，最终真实 MySQL/S3 与故障专项 22 项通过 |
| KDP-06-C | DONE / 已交付 `19e026c` | 703 项回归、43 项聚焦及后续 11/9 项通过；真实 Linux 7 场景、资源与清理通过 |
| KDP-06-D | DONE / 已交付 `7573dd0` | 715 项完整回归、46 项聚焦与 8 项补测通过；跨阶段预算/邻近上下文/载荷度量，候选策略缺省不变 |
| KDP-06-D2 | DONE / 已交付 `6919c60` | 任务前缀/隐式维度探测预算追补；44 项聚焦与 14 项兼容通过，8 文件指纹见 TEST_RESULT |
| KDP-06-E | DONE / 待 Git 交付 | 61 项聚焦、10 场景真实 Linux、最终 1045 项回归通过；0 失败/错误、17 条件跳过 |

## 当前验证与限制

- [TEST_RESULT](TEST_RESULT.md)、[REVIEW](REVIEW.md) 保留每批通过/失败与证据边界；主 Agent 自审，不称独立评审。
- B2 完整回归 692 项、0 失败/错误、10 跳过；后续 DB 超时专项与真实 MySQL/S3 最终 22 项通过。证据根目录 `knowledge-service/target/knowledge-parsing-hardening/`，不得删除早期失败日志。
- fork 不是文件/网络安全沙箱，Xmx 不是 RSS，单次网络调用由 SDK 超时约束。准备结果绑定后重试不重算；V9/V10 需全新 worker 接管，禁止旧 worker 混跑宣称一致性/全局额度有效。
- 当前格式/资源边界见支持矩阵；合成样本不代表真实业务检索准确率和生产 SLA。E 的 DOC/XLS/PPT/PPTX/HTML/RTF/ODT/UTF-16 已样本验收；D 的真实模型检索质量和金额未测。后续生产验收优先级见生产优化方案，不是当前代码交付的通过声明。

## Git / CI / 恢复入口

- 原任务分支 `fix/knowledge-parsing-hardening`，HEAD `6919c60`；D/D2 已正常合入并推送 main/任务分支；当前新增为 E 格式专项。
- `7573dd0` 的 Tax AI / AgentScope CI 已成功（39196553617、39196553598）；Java 供应链 39196553641 在 `artifacts / Scan aggregate dependency SBOM` 失败，发布作业跳过。该门禁历史多版本也失败，未逐项对比，不称完全相同漏洞或生产可发布。
- 不新建 worktree，不删除其他任务工作树、私有环境文件或忽略证据；不续做相邻业务评估/AgentScope 任务。

## 下一执行检查点

- E 最终验证已完成，无运行中 Maven/容器：61 项聚焦、10 场景 Linux、1045 项 knowledge/conversation/agent 回归；执行代码 10 文件指纹 `0c5eebb40bffdca1c320c0a767020097619555e48f227bcedf8cc5c5d4aa0269`，细节与早期失败保留见 TEST_RESULT。
- D2 的 AgentScope CI 39197266933 已成功，供应链 39197266946 已完成并在 `artifacts / Scan aggregate dependency SBOM` 失败，发布作业跳过。当前工作流已结束，可以正常交付 E。
- 下一步：核对文档链接、暂存范围与指纹，提交 E，快进合并/推送 main 和任务分支，检查远程 CI，落盘 DELIVERY_RESULT 和最终恢复入口。仅处理当前任务，不部署或删除忽略证据。
