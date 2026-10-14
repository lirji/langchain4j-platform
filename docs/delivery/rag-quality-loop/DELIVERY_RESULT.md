# 源码交付记录

三个有依赖顺序的任务已独立分支提交并正常快进合入、推送main：

| 顺序 | 分支 | 提交 | 结果 |
| --- | --- | --- | --- |
| 1 | fix/knowledge-probe-version | 12cf1a2 | 远程发布复查必须匹配文档版本 |
| 2 | fix/knowledge-gc-progress | 160f6bb | 跨轮公平GC、Redis检查点、文档/版本预算 |
| 3 | feat/rag-retrieval-quality | cfa1d5b | 租户评测集、反馈标注、持久化运行/基线、质量和延迟门禁、运营界面 |

后续本分支补充可复现Docker检索评测冒烟与交付证据。没有强推、重置历史或删除用户改动。

验证见TEST_RESULT，使用见[检索评测指南](../../对话与检索/retrieval-quality-loop.md)，实际本地部署见DEPLOYMENT_RESULT。

原目录工作树用于本任务；保留三个既有worktree，未新增worktree。忽略的本地.env、dev-infra配置、构建缓存、依赖、Docker发布与恢复证据不纳入提交。构建缓存可按需重新生成；凭据、恢复副本、测试记录和既有worktree需先核查再清理，本轮没有清理授权。

## 本地运行兼容修复

用户确认后，两仓fix/java-management-readiness分支分别交付Python `22ba8db`和Java Compose `f89cee0`，均已正常合入并推送main。Python源码先交付，Java管理地址配置后交付；本地运行与固定镜像证据见DEPLOYMENT_RESULT和RUNTIME_EVIDENCE.json。最终文档提交不改变镜像中的应用源码。

Python原目录保留任务分支；main位于既有cross-runtime-verification工作树，已在该干净工作树正常快进，无新建或清理工作树。原有`.engineering/remediation/`和`.workflow/`仍未跟踪，未提交。
