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
| KDP-05-A | DONE / 本地验证通过，待提交 | 独立解析 JVM/资源预算/截止/S3 超时/容器配置；638 项回归及 HTTP 修正后 10 项通过，真实 Boot 和 Linux 四格式通过 |
| KDP-06-B | NEXT | 结构化定位与格式切分；先 CSV/XLSX 行组表头，再 MD/DOCX 章节与 PDF 页级完整性 |
| KDP-05-B | TODO | 流式输入/hash、分批 embedding/sink、全局与租户公平并发 |
| KDP-06-C | TODO | 按普通企业中英合成扫描/混合/旋转样本实施选择性 OCR，无需再问同一业务取舍 |
| KDP-06-D | TODO | 基于质量证据的语义/上下文/存储成本调优，不虚构模型费用或改变缺省策略 |
| KDP-06-E | TODO | DOC/XLS/PPT/HTML/RTF/ODT 真实格式专项验收 |

## 当前验证与限制

- [TEST_RESULT](TEST_RESULT.md)、[REVIEW](REVIEW.md)、[KDP-05-A](KDP-05-A.md) 记录命令、通过/失败、证据边界。主 Agent 自审，不称独立评审。
- 最新完整回归 638 项、0 失败/错误、9 跳过（629 实际执行）；knowledge-service 458 项/5 跳过。最后 HTTP 错误分类改动后补测 10 项通过，之前镜像不含该 HTTP 修改，隔离实现未变。没有正在运行的 Maven 或任务容器。
- 证据根目录 `knowledge-service/target/knowledge-parsing-hardening/`；当前 `kdp05a/{full-regression,http-regression,container-smoke}.log` 和 `validated-files.json`。保留早期失败日志。Linux 临时容器已移除，镜像 `lc4j-parser-check:kdp05a` 留作证据，未部署业务服务。
- fork 不是文件/网络安全沙箱；Xmx 不是 RSS，tmpfs/容器限制需部署真正启用；输入单次阻塞靠 SDK 超时。旧目录 SPI 与全链路预算尚待统一。OCR/复杂版面与真实业务检索质量仍未验收。
- 已绑定准备结果重试不重算；V9 后禁止旧新 worker 混跑；升级需排空/隔离旧不安全任务。快照对象不自动清理。

## Git / CI / 恢复入口

- 当前提交 `f5c64e1d936c12bebb80828d26e86883ce96d21e` 已在任务分支与 origin/main；本地 main 尚在 `6138ee4`，下次交付正常快进。工作树未提交均为 KDP-05-A 的实现/测试/配置/文档。
- f5c64e1 的 [AgentScope CI](https://github.com/lirji/langchain4j-platform/actions/runs/39189680555) 成功；[Java 供应链](https://github.com/lirji/langchain4j-platform/actions/runs/39189680587) 在 `Scan aggregate dependency SBOM` 失败，后续镜像/证明/发布跳过。旧基线也在同阶段失败，但未逐漏洞比对，不能断言原因相同；生产供应链门禁未通过。
- 不新建 worktree，不删除其他任务工作树、私有环境文件或忽略证据。前任务记录在 `docs/delivery/business-assessment-20261014/CODEX_PROGRESS.md`，不续做邻近 AgentScope 任务。
- 下一操作：核对 KDP-05-A 验证指纹、文档链接、diff，按授权提交并正常合入 main；继续 KDP-06-B，先追踪 sync/async/目录三个入口，建立结构 DTO 与表格/章节/页定位验收。
