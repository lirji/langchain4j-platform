# 本机知识库前端与 OCR 部署结果

结果：**PASS**。环境为 Docker Desktop `desktop-linux` 的既有 `langchain4j-platform`，复用原 dev_infra 与 auth-platform。

- 前端入口：[知识检索](http://localhost:8093/m/rag/rag.query)、[上传文档](http://localhost:8093/m/rag/rag.upload.file)、[入库任务](http://localhost:8093/m/rag/rag.ingestions.get)。登录方式仍为原有 Casdoor SSO。
- 知识服务、网关、前端固定部署源码 `c1eedd131930d4cc5d9c75c65b50f7ebe89832b7`，镜像标签 `c1eedd131930`。
- 本机知识库迁移器源码 `1c751037e277dfeb3fe5b8cc2ea44ab2b79c29d7`，标签 `1c751037e277`；knowledge-ingestion V10→V11，一次迁移，exit 0。
- 仅更新上述 3 个应用和该迁移器，其他固定容器 ID 保持。没有迁移其他数据库、替换模型/向量空间、重建索引或清理旧镜像。

## 已核对的实际状态

知识容器 `RAG_PARSING_OCR_ENABLED=true`，已安装 `chi_sim`、`eng`、`osd`，资源为 3g 内存 / 2 CPU / 256 pids。默认镜像完成七类真实 PDF OCR 验证，部署镜像与通过镜像的运行文件层一致。

14 个 Java readiness 为 HTTP 200 / UP；前端及 JS/CSS、授权只读接口正常。浏览器静态资源包含新增来源/行号/OCR 与异步上传代码；实际网关对 Authorization 和 API Key 的 Idempotency-Key 预检均通过。未改变前端构建认证参数和网关身份策略。

首次更新后日志揭示旧库 collation 兼容问题：历史任务列为 utf8mb4_0900_ai_ci，V10 轮转列为 utf8mb4_unicode_ci，任务领取关联报 1267。经过两种方向的隔离 MySQL 回归后，短暂停止知识服务，备份并运行独立 V11 migrator，只将轮转列对齐任务列。迁移后关联查询成功、34 个原任务仍均 READY；服务恢复后 `workerErrorsSinceRestart=0`。没有把此前健康接口成功当作任务可用的充分证据。

结构化脱敏证据见 [RUNTIME_EVIDENCE.json](RUNTIME_EVIDENCE.json)；完整私有日志与固定镜像 wrapper 位于 `deploy/.dev-infra-state/knowledge-ui-ocr/`。

## 备份与恢复边界

- 迁移前 `pre-v11.sql` 为单事务备份，51905 字节、权限 0600，SHA-256 `a95e9e621b7ece8df1cdbd13a682cdec1825e20671f31048f65097749e184e8d`，只保留在私有目录。
- 旧镜像与原容器/版本清单保留；V11 不转换任务数据，旧业务查询也兼容对齐后的轮转列。MySQL DDL 不提供事务回滚，不自动执行反向迁移或恢复覆盖数据库。
- 已完成迁移不得因会话恢复重复执行。先核对 `migration-after.txt`、`migrate-v11.log`、当前 Flyway 版本及容器镜像；当前固定应用容器是预期运行结果，不应清理。
- 临时 Vite 预览已关闭；没有新建工作树，其他任务工作树未清理。保留本任务日志、截图、测试镜像及备份用于追溯。

这是本地功能部署。生产供应链门禁仍沿用前任务 HOLD，不表示已完成生产发布。
