# 版本化数据库迁移与回滚

业务进程不再拥有生产 schema：不会建库、建表、改列，也不会让 Flowable 在启动时升级表结构。
所有关系库变更统一由 `database-migrations` 在应用发布前执行；业务账号只有运行所需的
`SELECT/INSERT/UPDATE/DELETE` 权限，迁移账号单独保管并且只注入一次性迁移任务。

## Schema 所有权

| migration id | 数据库 | 主要使用方 | 默认 Helm 迁移 |
| --- | --- | --- | --- |
| `auth` | `auth` | auth-service | 开 |
| `async-task` | `async_task` | async-task-service | 开 |
| `workflow` | `flowable` | workflow-service / Flowable 7.1 | 开 |
| `order` | `order_service` | order-service | 开 |
| `knowledge-ingestion` | `knowledge_ingestion` | Knowledge ingest API/worker | 关，随 split 能力开启 |
| `knowledge-graph` | `knowledge_graph` | Knowledge graph/worker | 关，随 JDBC Graph 开启 |
| `channel` | `channel` | platform-eventbus/channel | 关，随 JDBC 去重开启 |
| `analytics-demo` | `nl2sql_demo` | 本地 NL2SQL 演示 | 关，生产真实分析库不得启用 |

版本脚本位于 `database-migrations/src/main/resources/db/migration/<migration-id>/`，历史表固定为
`PLATFORM_SCHEMA_HISTORY`。已有但尚未纳管的库会以版本 `0` 建立 baseline，再执行仓库内的
expand/backfill 迁移；迁移不删除业务表或列，Flyway clean 永久关闭。

## 变更规则：expand-contract

一次兼容性变更至少跨两个发布窗口：

1. Expand：增加可空列、新表或兼容索引；先迁移，再发布同时兼容旧/新结构的应用。
2. Backfill：用独立、可重入迁移回填数据；大表回填应拆成受监控的批处理，不占用 Helm Hook。
3. Switch：观察旧、新版本都能运行且双跑/回滚门禁通过后，才把读路径切到新结构。
4. Contract：至少等旧应用彻底退出回滚窗口后，另开变更删除旧列/约束。Contract 不与 Expand
   放在同一版本，也不允许在应用启动时执行。

已发布的 Flyway 版本文件不可修改或重排。需要修正时增加下一个版本；Java migration 也必须使用
新版本和新 checksum。

## 本地 Compose

先生成迁移镜像所需的 JAR：

```bash
mvn -pl database-migrations -am -DskipTests package
docker compose -f deploy/docker-compose.yml up --build
```

首次创建 `mysql-data` 时，MySQL 会执行 `deploy/mysql/init/001-platform-databases.sql`，只负责创建
本地库、app/migrator 用户与授权。随后每个应用通过 `service_completed_successfully` 等待自己的
`migrate-*` 一次性服务。迁移失败时依赖应用不会启动。

MySQL 官方镜像只会在空数据目录执行 init 脚本。已有本地卷不要删除；先幂等补齐库和账号，再运行迁移：

```bash
docker compose -f deploy/docker-compose.yml up -d mysql
docker compose -f deploy/docker-compose.yml exec -T mysql \
  mysql -uroot -proot < deploy/mysql/init/001-platform-databases.sql
docker compose -f deploy/docker-compose.yml up --build \
  migrate-auth migrate-async-task migrate-workflow migrate-order \
  migrate-knowledge-graph migrate-knowledge-ingestion migrate-channel migrate-analytics-demo
```

第二次执行应全部显示 `migrations=0 success=true`。不要用删除卷来“修复”迁移失败。

## Helm / 生产发布

生产数据库和账号必须先由 IaC/DBA 创建，迁移 Job 不负责 `CREATE DATABASE` 或用户授权。每个 schema
使用独立 migrator；对应密码由 External Secrets/IaC 写入独立
`platform-migration-secrets` 的 `*_MIGRATION_DB_PASSWORD` key。它们不会进入
`platform-secrets`，业务 Deployment 也不会引用这些 key。

Helm migration Job 是 `pre-install,pre-upgrade` Hook，因此不能在首次安装时依赖同一 release
才创建的普通 Secret/ExternalSecret。生产设置 `secrets.create=false`，然后在 Helm 之前应用
chart 外的 `deploy/helm/platform-migration-externalsecret.example.yaml`（先替换 Vault 路径和
SecretStore），并等待 ExternalSecret `Ready` 且同名 Secret 存在：

```bash
kubectl apply -n platform \
  -f deploy/helm/platform-migration-externalsecret.example.yaml
kubectl wait -n platform --for=condition=Ready \
  externalsecret/platform-migration-secrets --timeout=120s
kubectl get -n platform secret platform-migration-secrets
```

默认 `secrets.create=true` 只会为本地演示渲染包含 `change-me` 的 Hook Secret；不得用于生产。
该 Hook Secret 可能在 Helm uninstall 后保留，本地环境应自行管理其清理或轮换。

发布前：

```bash
mvn -pl database-migrations -am test
bash deploy/test-database-migration-config.sh
helm lint deploy/helm/platform
helm template platform deploy/helm/platform > rendered.yaml
```

`migrations.yaml` 把启用的 schema 渲染为 `pre-install,pre-upgrade` Hook Job。Job 有 10 分钟 deadline、
一次失败重试、非 root/只读根文件系统、无 Kubernetes token。默认迁移 auth、async-task、workflow、
order；只有启用相应持久化能力时，才在环境 values 中打开 Knowledge、channel。`analytics-demo`
只用于本地演示，生产真实只读库保持关闭。

上线顺序是：数据库备份与容量检查 → 运行/观察迁移 Job → 发布应用 → readiness 与业务冒烟 →
保留扩展 schema 至回滚窗口结束。任何 Hook 失败都应停止应用 rollout，先查看 Job 日志和
`PLATFORM_SCHEMA_HISTORY`，不得跳过校验强行启动业务进程。

## 回滚与故障恢复

- 应用发布失败：回滚应用镜像，保留向后兼容的 Expand schema；不要回滚数据库版本或删列。
- 迁移在事务内失败：修正问题后新增 forward-fix 版本再执行。不要手改 Flyway history。
- MySQL 不支持事务化的 DDL 或 Flowable v3 中途失败：停止发布，从发布前备份恢复到新实例，验证后
  再切流量；不要在原库上猜测性删除半成品对象。
- 误执行 Contract：立即停止写入，按备份/PITR 恢复，并回放已审计写入；这属于事故处理，不是普通
  Helm rollback。
- 紧急回退迁移镜像：只允许在尚未执行任何新版本时回退。只要 history 中已记录新版本，就使用
  新 migration 进行 forward fix。

业务进程在缺表、缺列或迁移未完成时会启动失败且不创建任何对象。排障时先验证迁移 Job 与账号权限，
不要临时给 app 用户 DDL 权限。
