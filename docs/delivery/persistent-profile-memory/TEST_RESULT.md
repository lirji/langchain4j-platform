# M1-PERSIST 验证结果

日期：2026-10-14。基线：38b3d80。验证目录：`~/.local/share/git-worktrees/langchain4j-platform/persistent-profile-memory`。

## 实现结果

- 用户画像默认切换到 JDBC/MySQL，HTTP 接口保持兼容；内存仅供显式开发测试，无故障自动回退。
- 独立 conversation schema、带完整表/列注释的 Flyway 迁移、应用/迁移账号隔离。
- 同一用户写入/去重/容量淘汰/清空以所有者行锁及事务串行化；不同身份的键通过长度定界摘要隔离。
- Compose、Helm、dev_infra 生成器和凭据补齐兼容新 schema；旧备份不要求不存在的画像库，仍检查历史必需库完整性。

## 执行证据

| 检查 | 命令或证据 | 结果 |
|---|---|---|
| 全 reactor | `mvn -o -B test` | PASS：1421 tests，0 failures，0 errors，13 skipped；25 个模块成功 |
| JDBC 存储及装配 | JdbcUserProfileStoreTest / ProfileJdbcConfigTest | PASS：新增 15 项，均未跳过 |
| MySQL 8.4 | `python3 deploy/test-profile-memory-mysql.py` | PASS：同一套 10 项存储测试使用真实 MySQL、独立临时库、独立 APP/MIGRATOR 账号 |
| 数据库权限及注释 | 同上脚本 | PASS：应用 DDL 被 1142 拒绝；两张表和全部字段注释非空 |
| 打包 | `mvn -o -B -pl conversation-service -am -DskipTests package` | PASS；打包不替代上面的测试 |
| 迁移/运行配置 | `bash deploy/test-database-migration-config.sh` | PASS；含 Compose/Helm 和 3 项离线凭据/备份兼容测试 |
| 运行加固 | `bash deploy/test-runtime-hardening-config.sh` | PASS |
| 生产切换静态配置 | `bash deploy/test-production-cutover-config.sh` | PASS；不代表生产准入 |
| 供应链静态配置 | `bash deploy/test-supply-chain-config.sh` | PASS；不代表漏洞扫描通过 |
| Code Hygiene | 既有 `code-hygiene.py gate` | IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS；无阻塞项，项目未配置 formatter/static analyzer |
| 差异检查 | `git diff --check` | PASS |

存储用例覆盖：关闭全部连接再建池后恢复、归一化/子串去重、稳定淘汰顺序、租户/用户/大小写/分隔符隔离、持久化清空、两个连接池并发写入、失败回滚、缺迁移拒绝启动、旧内存/JDBC 对照、HTTP GET/DELETE 契约和身份参数注入。

## 事务与边界复核

事务只包含数据库操作，无模型/RPC。唯一所有者键锁保护首次创建；锁定读避免 REPEATABLE READ 旧快照；异常回滚；淘汰检查影响行数。清空保留锁行，不以删除锁行导致多执行器竞争。SQL 值均参数绑定，动态后缀仅为内部布尔选择。

新增依赖为已有平台组合：spring-boot-starter-jdbc、mysql-connector-j，测试作用域 h2、database-migrations。沿用父 BOM，没有版本升级。内存/JDBC 共享归一化逻辑并固定 Locale.ROOT，避免实例默认 locale 差异。

## 未执行或限制

- 13 项既有条件测试跳过；没有将全 reactor 宣称为零跳过。
- 没有调用付费模型、重建全部镜像或部署应用/生产；MySQL 验证结束已清理本次临时库和账号。
- 旧 JVM 内存数据无自动导出迁移。异步抽取跨清空的旧任务防回填属于后续治理，不宣称本切片已实现。
- 未配置 Java formatter/static analyzer；已执行编译、测试、Code Hygiene 和人工差异审查。
- 现有 main `38b3d80` 的供应链运行 36983397611 仍在 `Scan aggregate dependency SBOM` 失败，镜像扫描未进入。用户本次排除版本与债务整改，保留该事实；前次交付的扫描合并例外不自动扩大为本轮例外。

远程基线：[供应链运行](https://github.com/lirji/langchain4j-platform/actions/runs/36983397611)。本任务当前 revision 的远程验证以交付状态为准，不能复用基线成功/失败充当本轮执行结果。
