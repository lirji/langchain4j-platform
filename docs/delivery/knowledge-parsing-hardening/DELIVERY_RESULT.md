# 知识库文档解析交付记录

日期：2026-10-19。任务 `knowledge-parsing-hardening`；授权来自用户按顺序持续实施及仓库的正常合并/推送 main 规则。实现与验证详情以 [PROGRESS_STATE](PROGRESS_STATE.md)、[TEST_RESULT](TEST_RESULT.md) 和[支持范围](../../对话与检索/知识库文档支持范围.md)为准。

## Git 结果

- 仓库：`/Users/liruijun/personal/LLM/langchain4j-platform`；复用 `fix/knowledge-parsing-hardening`，未新建工作树。
- 最终代码提交：`ef9b70cde43621dd7fd96a91c4f25834a3ede2d0`（旧 XLS 结构与其他格式验收）。前序追加提交：`6919c60`（前缀/维度预算）、`7573dd0`（跨阶段模型工作量）、`19e026c`（选择性 OCR）。更早批次见进度表。
- 已确认上述提交及 KDP-04/05/06-B 的提交均是 `origin/main` 祖先；本地 main、任务分支及两个远程分支已到达最终代码提交。正常快进合并和推送成功，未强推、重写历史或绕过保护。
- 本文件随最终交付状态的文档提交同步，不变更已验证执行代码。未创建 PR/tag/release，未部署、重建业务索引或清理数据。

## 验证对应关系

- 最终 1045 项多模块回归：0 失败/错误、17 环境条件跳过；真实 Boot/ES 已启用。额外 61 项格式/快照聚焦及 10 场景受限 Linux 验证通过。
- E 执行文件指纹 `0c5eebb40bffdca1c320c0a767020097619555e48f227bcedf8cc5c5d4aa0269`，最终 jar 摘要在 [KDP-06-E-formats.json](KDP-06-E-formats.json)。文档提交不使源码验证证据失效；本地相对文档链接目标和 diff 检查通过。
- 代码提交只包含已经验证的当前任务文件。提交后工作树曾确认无未提交/未跟踪文件；忽略的私有环境与测试证据保留，没有夹带到 Git。

## CI 与发布边界

最终代码提交的工作流已结束：[AgentScope 39197749121](https://github.com/lirji/langchain4j-platform/actions/runs/39197749121) 和 [Tax AI 39197748986](https://github.com/lirji/langchain4j-platform/actions/runs/39197748986) 成功；[Java supply chain 39197748980](https://github.com/lirji/langchain4j-platform/actions/runs/39197748980) 在 `artifacts / Scan aggregate dependency SBOM` 失败，image-scan/release-images/attest 作业跳过。前序版本同一步骤也失败，但未逐项比对漏洞，不声称完全相同原因。

开发交付完成；生产发布门禁仍为 HOLD。后续纯文档提交若触发供应链工作流，其新运行结果以 Actions 为准，不把上述代码提交的 CI 结果冒充新运行结果，也不取消或绕过检查。

## 现场保留与后续

- 本任务没有新增独立工作树；其他任务工作树未修改或移除。既有 `/private/tmp/lc4j-integrate-main` 显示 prunable，但归属其他交付，不自动清理。
- `knowledge-service/target/knowledge-parsing-hardening/` 保留成功/失败日志、指纹、生成夹具；私有环境文件不读取到报告或提交。解析测试容器已退出且自动移除，验证镜像 `lc4j-parser-check:*` 保留，后续可按需清理镜像/构建缓存，不应误删证据或进行中任务。
- 开发范围已经完成；生产放量仍需供应链门禁、目标资源混合负载和真实业务语料评测。顺序与考虑见[生产优化方案](../../对话与检索/知识库文档解析生产优化.md#验证与发布)，没有把这些未验证项标成完成。
