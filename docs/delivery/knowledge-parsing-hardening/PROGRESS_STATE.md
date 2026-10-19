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
| KDP-06-B | DONE / 验证通过，待提交 | 结构解析/格式切分/来源传递；973 项完整回归、14 项 ES 权限补测及 34 项代码保真补测通过；真实 ES/Boot/Linux 四格式通过 |
| KDP-05-B | NEXT | 流式输入/hash、分批 embedding/sink、全局与租户公平并发 |
| KDP-06-C | TODO | 按普通企业中英合成扫描/混合/旋转样本实施选择性 OCR，无需再问同一业务取舍 |
| KDP-06-D | TODO | 基于质量证据的语义/上下文/存储成本调优，不虚构模型费用或改变缺省策略 |
| KDP-06-E | TODO | DOC/XLS/PPT/HTML/RTF/ODT 真实格式专项验收 |

## 当前验证与限制

- [TEST_RESULT](TEST_RESULT.md)、[REVIEW](REVIEW.md)、[KDP-05-A](KDP-05-A.md) 记录命令、通过/失败、证据边界。主 Agent 自审，不称独立评审。
- 最新跨 knowledge/conversation/agent 完整回归 973 项、0 失败/错误、16 跳过；最后 ES mapping 权限优化补测 14 项，超长代码保真补测 34 项通过。具体先后和 artifact 边界见 TEST_RESULT。没有运行中 Maven/任务容器。
- 证据根目录 `knowledge-service/target/knowledge-parsing-hardening/`；当前 `kdp05a/{full-regression,http-regression,container-smoke}.log` 和 `validated-files.json`。保留早期失败日志。Linux 临时容器已移除，镜像 `lc4j-parser-check:kdp05a` 留作证据，未部署业务服务。
- fork 不是文件/网络安全沙箱；Xmx 不是 RSS，tmpfs/容器限制需部署真正启用；输入单次阻塞靠 SDK 超时。当前无实际目录 SPI 调用；全链路预算尚待治理。OCR/复杂版面与真实业务检索质量仍未验收。
- 已绑定准备结果重试不重算；V9 后禁止旧新 worker 混跑；升级需排空/隔离旧不安全任务。快照对象不自动清理。

## Git / CI / 恢复入口

- KDP-05-A `813c699` 已合入 main；其远程 CI 的 Compose 合并发现重复 /tmp，已以独立修复 `176d83b` 推送任务分支及 main；本地 main 仍在 813c699，正常交付时快进。当前未提交为 KDP-06-B（knowledge-service、platform-protocol 加法字段、文档）。
- 813c699 Tax AI CI 成功；AgentScope CI 失败原因为 Compose 重复 tmpfs，已修复并通过四组合静态验证及 runtime hardening gate；176d83b 远程 CI 待完成。Java 供应链仍未通过，先前多版本在 SBOM 扫描失败，未逐项对比漏洞，不宣称原因完全相同。
- 不新建 worktree，不删除其他任务工作树、私有环境文件或忽略证据。前任务记录在 `docs/delivery/business-assessment-20261014/CODEX_PROGRESS.md`，不续做邻近 AgentScope 任务。
- 下一操作：KDP-06-B 指纹/文档已对齐，按授权提交；待 176d83b CI 完成后正常合入 main，避免取消在跑验证。继续 KDP-05-B 流式输入/hash、分批模型/索引、全局及租户公平并发。合同 KDP-06-B.md 与结构指标已落盘。
