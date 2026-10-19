# 知识库文档解析修复进度

## 目标与授权

- 任务 `knowledge-parsing-hardening`，2026-10-19；用户要求先记录，再开始按建议修复；主要资料 PDF、Word、Markdown、Excel、CSV、技术文档。
- 权威问题清单及下一阶段排期：[生产优化方案](../../对话与检索/知识库文档解析生产优化.md)。已交付 KDP-01/02/03；KDP-04 已验证并合入远程 main（`6138ee4`）；后续专项不算已完成，不执行部署、索引重建或数据清理。
- 用户最新要求：**按照顺序开始实施**。沿已记录优先级持续推进，先 KDP-04，再格式基线/隔离及后续依赖；不在单片结束后等待“继续”。格式事实统一见[文档支持范围](../../对话与检索/知识库文档支持范围.md)。
- 原目录 `/Users/liruijun/personal/LLM/langchain4j-platform`；任务分支 `fix/knowledge-parsing-hardening`；起始基线 `348c13e0012fc36e40c9ad87942fb519b27311e6`，工作树干净。用户持续授权本地分批提交、正常合并并推送 `origin/main`；不强推、不绕过保护。
- 已采用：仓库 `platform-legacy-maintenance`、`platform-diff-review`；个人 `project-documentation`、`update-progress-docs`、`task-git-delivery`。已读 `~/.codex/architecture-standards.md` 的建模、持久化、接口、并发验证、容量演进、发布治理及个人技术栈并发章节。
- 前任务检查点独立保存在 `docs/delivery/business-assessment-20261014/CODEX_PROGRESS.md`；其报告已在 main，不续做相邻 AgentScope 任务。

## 任务清单

| Slice | 状态 | 验收与边界 |
| --- | --- | --- |
| 问题文档 | DONE | KP-01～20、理由、格式差异、优先级与后续计划 |
| 格式范围落盘 | DONE / 文档验证通过 | 六类主格式的样本验证与 DOC/XLS/PPT/HTML/RTF/ODT 的解析器注册证据分开；源码与验证范围核对通过 |
| 下一阶段规划 | DONE / 规划已记录 | P0/P1/P2、先后依赖、理由、第一项落地顺序与验收已写入方案；不代表后续代码已实现 |
| KDP-01 | DONE / 验证通过 | Markdown/父子/语义回退最终编号与 ES ID 唯一；保留租户、版本、父块关联 |
| KDP-02 | DONE / 验证通过 | CommonMark 真实标题、围栏/缩进/Setext/层级；英文句号与缩写、网址、版本、引号边界 |
| KDP-03 | DONE / 验证通过 | 正文预算、稳定错误分类、流关闭、减少内部复制、真实格式及配置绑定测试 |
| KDP-04 | DONE / 验证通过 | 持久化最终块/向量、V9 引用、租约 CAS、旧任务保护；完整 613 项及真实 MySQL/S3 5 项通过 |
| KDP-06-A | DONE / 合成语料验证通过 | 17 个场景通过；修复真实 CSV 分支依赖冲突，完整 630 项/0 失败错误/9 跳过；报告见 KDP-06-A |
| KDP-05 | IN_PROGRESS / 设计 | 可终止进程、阶段硬截止、有界公平并发、分批预算 |
| KDP-06 | TODO | 结构化内容、选择性 OCR、按格式切分和检索质量基线 |

## 验证与审查

- [TEST_RESULT](TEST_RESULT.md)：最终 `mvn -B -o -pl knowledge-service -am test` 成功，600 项、0 失败、0 错误、9 跳过；knowledge-service 420 项、5 跳过。实际执行 591 项；没有运行真实模型、OCR 或外部集成环境。
- [REVIEW](REVIEW.md)：主 Agent 对实际差异及调用链自审；未发现阻塞本轮的新增缺陷，不称为独立审查或生产验收。
- 24 个源码/配置/测试文件最终 SHA-256：`adf6c1105582a44a23316405f2433a80c41abf2005558ed02390aa9ab710aa4f`；完整回归后仅修正两行 Javadoc，没有可执行代码变化。
- 原始复现、聚焦与完整日志，以及 `validated-files.json` 均在 `knowledge-service/target/knowledge-parsing-hardening/`（本地忽略目录，保留恢复证据）。没有仍在运行的 Maven 任务。
- 运行说明已同步到 `docs/对话与检索/可靠入库接入.md` 和 `rag-guide.md`；[文档地图](../../doc-map.md) 记录同步范围。
- 格式与规划补充仅涉及 7 个 Markdown 文件；102 个本地链接及锚点校验通过，`git diff --check` 通过，24 个已验证源文件指纹未变，因此未重复运行 Java 测试。

## 关键决定与限制

- KDP-01/02 在同一最终分块链路及相同 splitter 文件上实现并一起验证，作为一个完整分块修复提交；KDP-03 独立提交，包含测试、配置与运行说明。
- 正文字符预算默认 2,000,000；超限整份失败，不静默截断。它不是整个解析进程的内存、解压或 CPU 上限。
- Markdown 原文保留，非标题首行不再伪装为 section；英文切句为有限规则，未宣称任意自然语言识别。
- KDP-04 为新任务持久化不可变准备结果；旧在途任务仍需排空/隔离，不能凭空补回首次生成的数据。V9 扩展后禁止新旧 worker 混跑，worker 须增加准备前缀 PutObject；不自动回填/重建历史索引。
- `d135d6c` 的远程结果已确认：[Coding Agent Kit](https://github.com/lirji/langchain4j-platform/actions/runs/39186991763)、[AgentScope Cutover](https://github.com/lirji/langchain4j-platform/actions/runs/39186991761) 成功；[Java 供应链](https://github.com/lirji/langchain4j-platform/actions/runs/39186991860) 在 `artifacts / Scan aggregate dependency SBOM` 失败，后续镜像扫描/发布/证明步骤跳过。这与[旧基线](https://github.com/lirji/langchain4j-platform/actions/runs/38581428740) 的失败阶段相同，未逐项比对漏洞，不能据此断言所有原因相同；生产供应链门禁未通过。本次文档任务不升级依赖或绕过扫描。

## Git 交付与恢复

- 已完成本地逻辑提交：`fa6757a`（问题文档及证据）、`ff041b7`（KDP-01/02）、`0f90bf1`（KDP-03，包含配置、测试和运行说明）。代码对应上述验证指纹，源码交付范围已固定。
- 首批源码及检查点已通过任务分支正常合入并推送 `origin/main`，交付提交 `d135d6cc0631964ec1cb2764fde0a0b3439811c4`；本次 fetch 后确认本地 main、远程 main 和任务分支均包含它。之前“待推送”的检查点已被实际 Git 事实取代，无需重复交付首批修复。
- 格式与优先级文档已在 `a63a167f8ab456d8a092e5bbde658b6c73634e95` 合入并推送远程 main。当前从该干净基线复用任务分支 `fix/knowledge-parsing-hardening` 实施；没有代码提交需要重复交付。
- 当前契约见 [KDP-04](KDP-04.md)，新验收见 [TEST_RESULT](TEST_RESULT.md) KDP-04 节。613 项回归 0 失败/错误，9 跳过；真实 MySQL/S3 5 项通过。源码指纹见 `kdp04/validated-files.json`；无运行中 Maven。Git 提交 `6138ee4` 已通过任务分支正常合入并推送 main，本地 main 已快进；`kdp04/DELIVERY_RESULT.json` 保存结果，远程 CI 待完成。
- KDP-04 已自审：对象内容/类型/身份校验、先绑定后 sink、失租不推进、临时 I/O 有限重试；保持结果对象不自动清理。真实 MySQL/S3 验证为适配器/worker 重建，不是断电灾备；完整限制见 REVIEW。
- 首批 Git 结果在本地 `knowledge-service/target/knowledge-parsing-hardening/DELIVERY_RESULT.json`；本次文档结果单独写 `DOCUMENTATION_DELIVERY_RESULT.json`。恢复时以 Git、CI 和结果记录共同核对，不盲目重做已完成操作。
- 不新增 worktree；保留现有其他任务工作树和忽略文件。没有清理授权，不删除它们或测试证据。
- 下一条可开始的开发准备为 **P1 / KDP-06-A：登记六类样本预期正文/结构/定位并生成可比较的质量与资源报告**；随后 **P1：格式基线、解析隔离、结构化切分、有界并发/分批和条件触发 OCR**；最后 **P2：语义/增强成本及其他格式验收**。详细依赖和验收只在生产优化方案维护。

- KDP-06-A 完整回归已结束：630 项，0 失败/错误，9 跳过；日志 `corpus-regression.log`，CSV 原始缺类失败在 `corpus-dependency-failure.log`。只在 knowledge-service 对齐 Tika/CSV 要求的 Commons IO 2.20.0，未升级全仓依赖。
- 用户最新取舍：按常见企业场景实施，无需等真实样本。采用中英混排、扫描/混合 PDF、旋转/长表/长手册和异常文件；合成验收不冒充真实业务检索收益。OCR 按页、有界、默认保守启用条件，预算作为可调保护参数而非生产 SLA。
- 下一步：KDP-05-A 独立解析进程与硬截止，明确输入/输出/堆/临时盘及嵌入附件预算；再做 06-B 结构化定位和按格式切分。没有运行中 Maven。
