# 文档内容去重

- 目标：解决当前租户异步文件入库中，不同提交编号、文件名和上传者重复上传相同字节造成重复文档及模型工作的问题。
- 授权：用户明确要求解决；沿用独立分支、验证后合并推送 main，以及本机知识库链路部署的授权。既有文档和重复副本保留，不自动合并/删除；不改凭据或模型，不调用计费模型，不部署生产。
- 仓库：`/Users/liruijun/personal/LLM/langchain4j-platform`，分支 `feat/knowledge-content-dedup`，基线 `5c1704f`，开始时工作树干净。
- 前任务：[入库增强失败保护](../ingestion-context-failfast/PROGRESS_STATE.md)、[知识库前端与 OCR](../knowledge-ingestion-ui/PROGRESS_STATE.md) 已完成，保留其证据。

## 方案与验收

1. 复用流式 SHA-256；原提交编号的指纹及上传者校验继续优先执行。
2. 按租户查相同内容的历史任务，以当前文档注册版本/删除状态和实际 view 权限筛选；无权访问的他人私有资料不提示。尚无授权关系的私有上传允许独立归属。
3. 数据库内容守卫序号 + 短事务条件创建，锁外检查 Redis 注册表/授权；多人竞争时重新校验，保证有权共享的内容只创建一次任务。查询和重试均有上限。
4. 重复返回结构化 409：已发布文档可查看；未完成/失败记录不伪装成功；只返回本人任务号，继续通过原任务恢复失败。旧版本、已删除文档不阻止新上传。
5. 前端明确提示、查看已有文档/本人任务、重新选择文件；未知响应仍保留原提交身份。相同文件名但不同内容仍由现有新建/更新版本入口决定，不自动覆盖。
6. 验证跨用户/跨租户/无权限、改名、并发、历史重复、失败恢复、删除和版本、幂等回放；H2 + dev_infra 隔离 MySQL；前端组件/类型/构建及可见页面检查。

## 当前状态

- 已实现 V12、存储条件创建、批量 ACL 查重、重复错误契约和前端 3 类反馈；历史副本优先提供可用文档。
- 已确认受控来源同步/审核发布依赖独立身份，保持 byte[] 编程式提交原语义，文件 multipart 开启内容去重；无 HTTP 绕过开关。
- 初版聚焦测试通过；真实 MySQL 并发/回放/回滚/历史候选已通过。时间精度测试曾失败，已改为整秒 fixture；并行 Maven 曾竞争编译输出，后续 Maven 验证改顺序执行。
- V12 两种历史 MySQL collation 迁移与大小写守卫唯一性通过。前端 609 项、类型检查及构建通过（保留原 bundle 体积提示）。
- 知识服务及上游完整测试通过：768 项/755 通过/13 条件跳过。最终 MySQL 聚焦、两个迁移方向、前端 609 项/类型/构建、边界门禁、Java 包构建均通过，见 [VALIDATION](VALIDATION.md)。
- 浏览器 4 组操作通过，宽/中/窄屏无溢出；修复实测发现的 file input 清空与锁定拖放问题。隔离 Vite session `7193` 在 5178，交付时关闭；业务认证参数保持。
- 本机预检：V11，36 个历史任务均 READY，30 个项目容器已记录于私有 `deploy/.dev-infra-state/knowledge-content-dedup/`。尚未变更业务库或运行容器。
- 下一步：按验证过的代码创建实现提交，构建固定知识服务/前端/V12 migrator；私有备份后停止旧知识入口，执行 V12，仅更新目标容器并验证原任务/其他容器保留；正常合并推送 main，补齐部署证据。
- 规范：根 AGENTS；`platform-java-feature`、`frontend-design`；架构规范的业务建模、持久化、分布式契约、并发、安全、容量、发布治理；个人技术栈并发/事务章节；共享前端偏好与异步反馈规范。
- 证据：`knowledge-service/target/knowledge-content-dedup/`；MySQL `knowledge-service/target/knowledge-parsing-hardening/knowledge-content-dedup/`；collation `database-migrations/target/knowledge-content-dedup/`；浏览器 `capability-showcase-frontend/target/knowledge-content-dedup/`（均忽略）。
