# DAG 诊断中央兼容扩展

配合 agentscope-platform 的 DAG 运行诊断，中央只新增 `dag-diagnostic-model`、`dag-diagnostic-operation`、`dag-diagnostic-summary` 三个允许事件名。沿用现有worker身份、租户、epoch、活动租约、终态、幂等和大小限制，不开放任意事件，不改表结构。

源码：`AsyncTaskController.PROGRESS_EVENTS`；测试：`AsyncTaskControllerTest.progressAppendRequiresAgentKindTenantAndLiveWorkerLease`。

测试先验证三种新事件被400拒绝，扩展后 `mvn -q -pl async-task-service -am test` 199项通过，0失败/错误/跳过；参数化测试覆盖四种事件的成功、幂等、错误worker、错误epoch、跨租户404及未知事件400。`git diff --check`通过。Code Hygiene通过并注明未发现Java formatter，沿用原代码风格。

独立工作树：`~/.local/share/git-worktrees/langchain4j-platform/dag-diagnostics`，基线a23cc6c，分支`feat/dag-diagnostics`。原目录本地Docker部署和历史工作树均保持。源码提交 `e7410ed` 已正常推送任务分支及origin/main；未强推。

先升级中央服务再上线Python诊断；旧中央拒绝诊断事件时Python仍执行原业务并报告观测缺口。回滚时先关闭Python诊断即可；本次未部署。

基线a23cc6c的Java供应链38575411792在`Scan aggregate dependency SBOM`失败，为既有依赖安全问题；本次不改依赖，不宣称生产发布门禁通过。

## 远程与工作区结果

- 源码e7410ed的[AgentScope Cutover CI](https://github.com/lirji/langchain4j-platform/actions/runs/38576737391)通过。
- 同一源码的[Java供应链](https://github.com/lirji/langchain4j-platform/actions/runs/38576737341)在`Scan aggregate dependency SBOM`失败；与基线失败步骤相同，不宣称发布门禁通过。
- Python配套源码63db408及兼容修补e6c378a均已进入Python远程main；诊断事件无需修改中央数据库或旧契约pin。
- 本任务产品代码交付完成；本记录是后续文档提交。未部署，也没有修改原目录main或运行中的容器。
- 工作树保留以便追溯；只有本次生成的target构建目录可按后续清理授权清理。未跟踪用户文件为空；未删除历史工作树。
