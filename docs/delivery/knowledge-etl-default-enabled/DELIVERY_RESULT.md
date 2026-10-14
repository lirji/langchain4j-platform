# ETL默认开启交付

## 范围与行为

用户要求功能默认开启。本切片 `ETL-DEFAULT-ON` 将配置绑定、装配条件、application.yml及示例的默认值统一为true。combined/ingest-api默认提供管理入口；未配置来源时授权列表返回`[]`，不装配采集Store/Service/Scheduler，不访问外部源。配置有效来源后自动采集，仍强制JDBC/S3/Redis；显式false关闭，query/ingest-worker不开放同步入口。

沿用现有租户与ingest权限校验；无来源的详情、控制、运行接口返回404。配置不完整仍启动失败，不能误判为无来源。无数据库迁移、新依赖、前端协议变更或部署操作。已有来源的升级必须显式关闭开关至迁移和全部角色升级完成，见[接入指南](../../对话与检索/http-knowledge-sync-guide.md)。

## 验证

- `mvn -o -B -pl knowledge-service -am '-Dtest=KnowledgeSyncConfigTest,KnowledgeSyncControllerTest' -Dsurefire.failIfNoSpecifiedTests=false test`：11项通过。
- 最终代码 `mvn -o -B -pl knowledge-service -am test`：574项，0失败、0错误、9条件跳过；knowledge-service为394项、5跳过。
- 覆盖默认空配置、授权空列表、未知来源所有接口404、显式关闭、角色隔离、配置来源自动装配、各持久化配置退化拒绝、缺数据源拒绝、不完整来源拒绝、application.yml环境开关。
- `git diff --check`通过；人工差异检查未发现阻断项。未重跑真实MySQL/浏览器：本次未改变SQL/事务或前端，装配测试使用既有隔离H2测试库。
- 本机日志：`/tmp/etl-default-focused.log`、`/tmp/etl-default-regression.log`。以上为本地验证，不代表远程CI或实际业务源联调。

## Git交付

任务分支`feat/knowledge-etl-default-enabled`，基线`8206191`。源码提交`d2d4043`已推送任务分支，正常快进合入并推送origin/main；无强推、无PR、无发布标签或部署。本次仅补记交付事实，并沿同一任务分支交付。核查时无本任务外未提交/未跟踪改动，全部验证文件SHA256与源码提交一致。

## 资源保留

在原目录任务分支修改，无新worktree。既有cross-runtime-verification、persistent-profile-memory、dag-node-recovery工作树及prunable的/private/tmp/lc4j-integrate-main登记保持原状。私有env、deploy本地状态、target、node_modules/dist与缓存保留；未清理用户内容。仅本次/tmp日志可在证据不再需要时清理。

## 验证代码指纹（SHA256）

验证后仅新增交付/进度记录，以下文件不再修改：

- `797ad416ba954cc54dc11c118f45dd23e922828cca87b54e8fe4cad26bcfe1de` `deploy/knowledge-http-sync.example.yml`
- `f665afe101e9f9a6e4dc097afa57fe989861613094112a1ac4cbbcfc7db9f293` `docs/对话与检索/http-knowledge-sync-guide.md`
- `3a1da53c6024a6c2c02b880b3ebfb54f5cd49d6920d74b6d0ab33a70217b64ca` `docs/对话与检索/knowledge-operations-guide.md`
- `923648c1977dfacd10a9b3192a53d3f41278a322323168b52a0827762b71bc00` `knowledge-service/src/main/java/com/lrj/platform/knowledge/ingest/sync/KnowledgeSyncConfig.java`
- `7acf6508a6eb8a8ebd63cd0ad08bb97e899d5afb6783454fb46ac9a7ac1ad2d1` `knowledge-service/src/main/java/com/lrj/platform/knowledge/ingest/sync/KnowledgeSyncController.java`
- `45b38fe8193bae9f69d19ab051e4e7cd2024a1e5b2c639a4335076f759874981` `knowledge-service/src/main/java/com/lrj/platform/knowledge/ingest/sync/KnowledgeSyncProperties.java`
- `a595a864d29906881c9ffa8202231491d2e7f70aedc6a11c399d6aa0d9212487` `knowledge-service/src/main/resources/application.yml`
- `bf2493f5f7e80bad94bf6fc72253b7dacccc01e17d01bfbb5e15f1f3cbffed93` `knowledge-service/src/test/java/com/lrj/platform/knowledge/ingest/sync/KnowledgeSyncConfigTest.java`
