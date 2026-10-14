# HTTP知识源ETL交付

- 实现提交：`c78c449`，`feat(knowledge): add recoverable HTTP knowledge ETL`。
- 任务分支：`feat/knowledge-http-sync`，已推送origin；源码已正常快进合入并推送`origin/main`。未强推、未改历史、未创建PR、未部署。
- 范围：用户图片指定的知识接入ETL，第一数据源为HTTP产品说明/FAQ；新增、更新、显式删除退出检索、来源映射、质量校验、检查点和故障恢复均已实现。
- 验证：模块及上游558项/0失败/0错误/9条件跳过；真实MySQL18项通过；真实Redis及配置4项通过。命令、限制和源码指纹见`TEST_RESULT.md`及`VERIFICATION_EVIDENCE.json`。
- 接入：`docs/对话与检索/http-knowledge-sync-guide.md`；配置模板`deploy/knowledge-http-sync.example.yml`。默认关闭，真实来源URL/凭据尚未提供，未宣称实际业务源已联通。
- 发布边界：先执行V7扩展迁移、升级全部knowledge角色再开启；墓碑已产生后不能回退到不识别墓碑的旧worker。删除为逻辑退出检索及撤权，历史原文和投影保留，不宣称物理擦除。
- 远程CI状态以GitHub对应提交为准，本次本地验证不替代远程CI；未把历史供应链扫描状态宣称为已修复。

## 工作目录与保留内容

原项目目录实施，初始工作树干净，仅提交本任务文件，没有新建worktree。已有`cross-runtime-verification`、`persistent-profile-memory`和历史`/private/tmp/lc4j-integrate-main`均未修改/清理；其任务状态不由本任务推定。原有`deploy/.dev-infra.env`、`deploy/.dev-infra-state/`、其他忽略配置与构建缓存保留。测试仅清理自身随机MySQL库/用户和Redis测试租户key。

新增Maven目标目录下日志与报告可在不再需要复现证据时清理；本次没有清理授权，不自动删除。最终交付记录提交后返回原目录main，并核对远程refs与工作树。
