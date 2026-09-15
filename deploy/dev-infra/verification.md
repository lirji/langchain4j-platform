# 本机接入验证记录（2026-09-08）

本次以原平台应用停止为迁移前提，保留原卷、原库、备份与克隆。

| 检查 | 实际结果 |
| --- | --- |
| MySQL 8 个业务库 | 79 张表逐表行数匹配（包括各库 Flyway history） |
| PostgreSQL LiteLLM | 28 张表，20,422 行，逐表计数匹配 |
| Redis 数据恢复 | 10 条记录，保留 DB index / TTL，增加 lc4j: 前缀 |
| Redis 真机测试 | 项目账号可执行提交版本 Lua；未授权键读取被拒绝；补充 INFO 健康检查权限后复验通过 |
| MinIO 原文 | 备份克隆经 S3 复制到 lc4j-knowledge-sources，mc diff 无差异；目标任务桶名引用更新 |
| Qdrant | 克隆卷启动成功，REST 可读取原 24 个 collection；新文档向量写入及查询通过 |
| 真实入库/查询 | 独立测试库与租户，幂等重复提交返回同一 job；ES 两次超时后自动重试进入 READY，查询命中测试文档；第二轮强制提高向量阈值后 source=es 的全文查询也命中 |
| Kafka | Host AdminClient 连接共享 broker，10 个 lc4j 主题全部存在 |
| LiteLLM 数据库接入 | 项目账号通过 dev-infra 网络登录成功；应用报告 Prisma schema 已同步，liveliness/readiness 均 HTTP 200 |
| 入库数据库升级 | initial=2 → target=3，migrations=1，success=true |
| 相关模块回归 | Knowledge 305、Conversation 133、Channel 65、Eventbus 12，零失败（包含条件跳过项） |
| 全模块产物 | mvn -o package -Dmaven.test.skip=true 成功 |
| 配置检查 | 普通/生产拆分 Compose config、Python 编译、shell 语法、git diff --check 均通过 |

备份与详细迁移记录位于本机 `deploy/.dev-infra-state/`（Git 忽略）；凭据仅位于 0600 的 `.dev-infra.env`。逐表计数和 S3 diff 是本次核对口径，并非全库每字段哈希审计。

真实入库与查询由宿主机 Java 进程连接共享实例完成（hash embedding，不调用模型）。Graph、外部授权与 async-task HTTP 联动关闭，对应无操作 sink 的 SUCCEEDED 不表示外部能力已验证。首次查询中 ES 分支因本机压力超时降级，文档由其他已启用来源命中；ES 直接全文检索返回命中，后续应用内提高向量阈值后的查询也返回 source=es，确认全文分支可用。

开发机 Docker VM 存在较高内存/IO 压力，ES 冷启动约 25 分钟。原 MySQL/Redis/PG 在备份后已停止。LiteLLM 已通过就绪检查，验证期间短暂停止，收尾已恢复运行。Qdrant 保留原 1.19 服务镜像，SDK 1.17 存在兼容告警，实际向量写入/查询通过；本轮未进行版本升级。

全应用栈尚未整体启动；标准启动脚本新增 --dev-infra，保留模型凭据加载与前端端口设置，配置名单验证不会启动未启用 profile 的旧中间件。Kafka 历史消息及消费位点未重放，原 Kafka 保留。
