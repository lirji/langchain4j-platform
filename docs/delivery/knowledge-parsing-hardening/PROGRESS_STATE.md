# 知识库文档解析修复进度

## 目标与授权

- 任务 `knowledge-parsing-hardening`，2026-10-19；用户要求先记录，再开始按建议修复；主要资料 PDF、Word、Markdown、Excel、CSV、技术文档。
- 权威问题清单：[生产优化方案](../../对话与检索/知识库文档解析生产优化.md)。本轮交付 KDP-01/02/03；后续专项不算本轮已完成，不执行部署、索引重建或数据清理。
- 原目录 `/Users/liruijun/personal/LLM/langchain4j-platform`；任务分支 `fix/knowledge-parsing-hardening`；起始基线 `348c13e0012fc36e40c9ad87942fb519b27311e6`，工作树干净。用户持续授权本地分批提交、正常合并并推送 `origin/main`；不强推、不绕过保护。
- 已采用：仓库 `platform-legacy-maintenance`、`platform-diff-review`；个人 `project-documentation`、`update-progress-docs`、`task-git-delivery`。已读 `~/.codex/architecture-standards.md` 的建模、持久化、接口、并发验证、容量演进、发布治理及个人技术栈并发章节。
- 前任务检查点独立保存在 `docs/delivery/business-assessment-20261014/CODEX_PROGRESS.md`；其报告已在 main，不续做相邻 AgentScope 任务。

## 任务清单

| Slice | 状态 | 验收与边界 |
| --- | --- | --- |
| 问题文档 | DONE | KP-01～20、理由、格式差异、优先级与后续计划 |
| KDP-01 | DONE / 验证通过 | Markdown/父子/语义回退最终编号与 ES ID 唯一；保留租户、版本、父块关联 |
| KDP-02 | DONE / 验证通过 | CommonMark 真实标题、围栏/缩进/Setext/层级；英文句号与缩写、网址、版本、引号边界 |
| KDP-03 | DONE / 验证通过 | 正文预算、稳定错误分类、流关闭、减少内部复制、真实格式及配置绑定测试 |
| KDP-04 | TODO | 不可变派生结果、处理版本、跨重试一致性 |
| KDP-05 | TODO | 可终止进程、阶段硬截止、有界公平并发、分批预算 |
| KDP-06 | TODO | 结构化内容、选择性 OCR、按格式切分和检索质量基线 |

## 验证与审查

- [TEST_RESULT](TEST_RESULT.md)：最终 `mvn -B -o -pl knowledge-service -am test` 成功，600 项、0 失败、0 错误、9 跳过；knowledge-service 420 项、5 跳过。实际执行 591 项；没有运行真实模型、OCR 或外部集成环境。
- [REVIEW](REVIEW.md)：主 Agent 对实际差异及调用链自审；未发现阻塞本轮的新增缺陷，不称为独立审查或生产验收。
- 24 个源码/配置/测试文件最终 SHA-256：`adf6c1105582a44a23316405f2433a80c41abf2005558ed02390aa9ab710aa4f`；完整回归后仅修正两行 Javadoc，没有可执行代码变化。
- 原始复现、聚焦与完整日志，以及 `validated-files.json` 均在 `knowledge-service/target/knowledge-parsing-hardening/`（本地忽略目录，保留恢复证据）。没有仍在运行的 Maven 任务。
- 运行说明已同步到 `docs/对话与检索/可靠入库接入.md` 和 `rag-guide.md`；[文档地图](../../doc-map.md) 记录同步范围。

## 关键决定与限制

- KDP-01/02 在同一最终分块链路及相同 splitter 文件上实现并一起验证，作为一个完整分块修复提交；KDP-03 独立提交，包含测试、配置与运行说明。
- 正文字符预算默认 2,000,000；超限整份失败，不静默截断。它不是整个解析进程的内存、解压或 CPU 上限。
- Markdown 原文保留，非标题首行不再伪装为 section；英文切句为有限规则，未宣称任意自然语言识别。
- 旧部分成功任务仍有跨规则重新准备但跳过成功 sink 的风险。发布切分规则前须排空/隔离这些任务；KDP-04 负责彻底解决。旧覆盖数据须按新版本重新入库，本轮不操作线上数据。
- 本地基线的远程 Java 供应链运行 [38581428740](https://github.com/lirji/langchain4j-platform/actions/runs/38581428740) 已在 `Scan aggregate dependency SBOM` 失败；本轮不整改历史依赖，不据此声称远程 CI/发布门禁通过。

## Git 交付与恢复

- 当前阶段：实现、回归、自审和文档已完成；尚待本记录后的分批提交、任务分支推送、正常合入并推送 main。不能仅凭本文件推断 Git 操作已经成功。
- 下一步：核对源文件指纹与 diff → 显式路径提交文档、分块、解析修复 → fetch 最新 main → 正常集成推送 → 核对远程包含关系及 CI。
- Git 操作实际结果写入本地 `knowledge-service/target/knowledge-parsing-hardening/DELIVERY_RESULT.json`；恢复时以 `git status`、`git log`、远程包含关系及 CI 实际状态核对，不因结果文件不存在而盲目重做已完成操作。
- 不新增 worktree；保留现有其他任务工作树和忽略文件。没有清理授权，不删除它们或测试证据。
- 本轮交付后，下一开发优先项为 KDP-04：先设计持久化派生清单及历史任务兼容，再补部分成功/重启/参数变化用例；不直接把内存缓存当恢复方案。
