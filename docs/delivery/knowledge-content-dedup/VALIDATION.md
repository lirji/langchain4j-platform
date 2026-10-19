# 文档内容去重验证

结果：实现及必要检查 **PASS**，2026-10-19。本机部署结果另行记录，不将测试替代实际部署。

| 验证 | 实际结果 |
| --- | --- |
| `mvn -o -B -pl knowledge-service -am test` | 768 项：755 通过、13 条件跳过，0 失败；包含数据源同步和审核发布兼容回归 |
| `python3 deploy/test-ingestion-prepared.py --tests JdbcIngestionContentDedupTest,JdbcIngestionJobStoreTest,IngestionContentDedupTest,IngestionSubmissionServiceTest,IngestionControllerTest --evidence-dir knowledge-content-dedup` | 随机 dev_infra MySQL 库通过；两个独立服务同时检查空快照后只创建一个任务；隔离库/账号已按工具原有 finally 清理 |
| `python3 deploy/test-ingestion-collation.py --evidence-dir database-migrations/target/knowledge-content-dedup` | 两种历史 collation 方向通过；任务不改写，守卫继承任务表租户比较语义，大小写变体不能绕过唯一键，迁移重跑无新变更 |
| `npm test` | 75 个文件、609 项全部通过；重复回执、本人恢复、他人任务隔离、身份切换、损坏响应及锁定期间拖放覆盖 |
| `npm run type-check` / `npm run build` | 通过；仍有既有主 bundle 大于 500 kB 提示 |
| `scripts/verify-ingestion-dedup.mjs` | Chrome 隔离契约测试 4 组通过，1440/1024/414 无页面溢出、0 页面异常；已核对宽/窄截图，打开已有文档、原任务恢复、重复检查与更换文件均通过 |
| MyBatis / migration gates | `python3 deploy/test-mybatis-boundary.py`、`bash deploy/test-database-migration-config.sh` 通过；新增 JDBC 仅位于 V12 独立迁移器，运行 SQL 仍在 Mapper XML |
| `mvn -o -B -pl knowledge-service -am -DskipTests package` | 知识服务及迁移器构建通过；无依赖升级 |
| `git diff --check` | 通过 |

后端覆盖：改名和不同提交编号、跨上传者、跨租户、真实 view 权限分支、私人在途归属、历史成功/失败副本选择、版本更新与旧内容重传、删除后重传、原任务恢复、幂等回放、候选预算、真实事务回滚、持久化重建和有界候选。受控来源/审核提交保留自己的身份协议，用户文件可识别其已发布内容。

自审重点：序号必须先读再查候选；锁内不调用对象存储、注册表或 IAM；同键重放优先验证指纹和上传者；重复回执不返回他人任务号；旧版本/删除记录不能永久占用内容；历史重复优先返回可用文档。未以自审冒充独立评审。

浏览器发现并修复了原生 file input 未随父表单清空的问题，验证了重新选择同一个文件；同时阻止 fieldset 锁定期间由 div 拖放改写提交文件。仅隔离 Vite 测试进程设置 `VITE_AUTH_MODE=apikey VITE_REQUIRE_LOGIN=false`；产品构建与本机部署沿用原 SSO 参数。浏览器业务 HTTP 使用测试契约拦截，未向实际业务库写测试文件；真实 MySQL 证据来自上述隔离集成测试，未执行付费模型调用。

证据及失败修正记录保存在忽略目录：

- `knowledge-service/target/knowledge-content-dedup/`：完整回归、类型/构建、浏览器日志、门禁、`test-result.json`、`file-fingerprints.json`。
- `knowledge-service/target/knowledge-parsing-hardening/knowledge-content-dedup/mysql-s3.log`：最新 MySQL 聚焦回归。
- `database-migrations/target/knowledge-content-dedup/`：两种 collation 回归。
- `capability-showcase-frontend/target/knowledge-content-dedup/browser/`：截图与结果。

最初测试签名不兼容、时间精度 fixture、并行 Maven 编译输出冲突均已修正并重跑对应验证。未尝试语义近似去重、自动清理历史副本或跨旧同步入口补录摘要；这些不属于本次文件上传范围。
