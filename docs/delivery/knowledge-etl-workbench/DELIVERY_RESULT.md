# ETL工作台交付

本轮范围已完成并正常合入推送`origin/main`。任务分支为`feat/knowledge-etl-workbench`；无强推、无PR/发布标签、无部署。

- `c0d3091`：HTTP同步暂停/恢复、同租户运营授权、原任务重试、MySQL页运行记录与分页查询、V8迁移及测试。
- `fed773e`：RAG数据接入工作台、来源详情/运行历史/失败明细与恢复操作、前端测试和验证材料。
- 上述两提交已推送任务分支，并正常快进合并推送main；核对远程两分支均为`fed773e`后补充本交付记录。文档收尾沿同一任务分支交付。

验证：后端完整回归569项（9条件跳过）、最终聚焦41项、真实MySQL28项；前端完整585项、最终RAG32项、类型/构建均通过。Chrome真实组件验证桌面和390px容器，使用隔离UI夹具；详见[TEST_RESULT.md](TEST_RESULT.md)。代码指纹在[VERIFICATION_EVIDENCE.json](VERIFICATION_EVIDENCE.json)，提交前已逐项核对一致。

入口：RAG工作台`/m/rag` → 数据接入 → 查看数据源。真实来源仍默认关闭，配置/迁移/运营授权与升级步骤见[HTTP接入指南](../../对话与检索/http-knowledge-sync-guide.md)。运行历史不自动清理；保留政策与容量需在实际启用前明确。旧协调器不理解暂停及运行记录，升级时需全部关闭后切换。

## 工作树与临时资源

本任务在原项目目录完成，未新建worktree。交付前除本任务文档收尾外无未提交或未跟踪文件。构建target、前端node_modules/dist、既有env与本地状态均按原状忽略和保留，不包含在提交中。

隔离MySQL临时schema和账号由测试脚本清理；本次Vite 5197预览服务已停止，临时HTML夹具已从前端node_modules缓存移出，仅在`/tmp/etl-workbench-preview-fixture.html`保留验证参考。没有改动运行中业务服务。

已有`cross-runtime-verification`、`persistent-profile-memory`及并行任务`dag-node-recovery`工作树均保留；`/private/tmp/lc4j-integrate-main`为既有prunable登记，未执行清理。`/tmp/etl-workbench-*`日志/夹具可在不再需要本地证据时清理；既有worktree和env不得随本任务删除。
