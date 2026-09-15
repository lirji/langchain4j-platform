# 接入共享 dev-infra

本目录将应用连接到相邻 `../dev-infra`，沿用旧 Compose 作为回退路径。需要 Docker Compose 支持 `!override`（2.24.4+）、Python 3、Java 21 和 Maven。凭据由 `manage.py provision` 生成到 `deploy/.dev-infra.env`（0600，Git 忽略）；不要把 `compose config` 的完整解析结果发到日志或提交。

| 资源 | Docker 地址 | 项目隔离 |
| --- | --- | --- |
| MySQL 8.4 | infra-mysql84:3306 | lc4j_auth / async_task / flowable / knowledge_graph / knowledge_ingestion / order_service / channel / nl2sql_demo，各库名称均加 lc4j_，应用/迁移分账号 |
| PostgreSQL 16 | infra-postgres16:5432 | lc4j_litellm，独立 owner；本项目明确选择共享实例而非额外部署 PG |
| Redis 7 | infra-redis7:6379 | lc4j ACL 用户，仅 lc4j:* 键；独立前缀，保留原 DB index |
| Kafka 3.8 | infra-kafka38:9092 | lc4j. 主题、独立消费组，显式建主题 |
| MinIO | infra-minio:9000 | lc4j-knowledge-sources 桶、lc4j-knowledge 账号与桶级策略 |
| Qdrant | lc4j-qdrant:6334 | dev-infra lc4j 专属实例与克隆卷，保留原镜像 digest |
| Elasticsearch | lc4j-elasticsearch:9200 | 专属 8.15.3 + smartcn；保留原索引 |
| 追踪 | infra-otel-collector:4318 | 沿用各服务 service.name |

Redis ACL 由 dev-infra 的 `lc4j/redis-acl.sh` 定期恢复，以适应共享 Redis 重启，不修改默认用户与其他项目配置。此账号仍属于本地开发环境的共享信任边界；Kafka 前缀用于命名隔离，不代表服务端 ACL。Kibana 按需开启 `lc4j-ui` profile。

## 首次迁移

先停止旧平台所有应用写入。不要执行 `down -v`。工具仅适用于本机已存在的 `langchain4j-platform-*` 旧实例与默认 dev-infra 实例名。

```bash
python3 deploy/dev-infra/manage.py provision
python3 deploy/dev-infra/manage.py provision_s3
python3 deploy/dev-infra/manage.py backup
python3 deploy/dev-infra/manage.py migrate
python3 deploy/dev-infra/manage.py migrate_sources
../dev-infra/bin/lc4j-infra up -d
```

备份保存在 `deploy/.dev-infra-state/<时间>/`，包含校验和、逐表行数、Redis DUMP 与 TTL、保留稀疏布局的数据卷归档。导入不覆盖有冲突的目标资源；目标库已有相同行数并不证明内容相同，因此仅在维护窗口、确认目标未被应用写入时重试。源对象通过备份克隆中的临时 MinIO 按 S3 导入，更新目标任务的 SOURCE_BUCKET；克隆容器停止后保留。旧库与卷不变。重新备份后不得向已投入使用的目标重复迁移。

Kafka 历史消息与消费位点不自动重放，避免产生重复业务动作；旧 Kafka 容器/卷保留。切换前应处理完旧消费积压和 outbox；有积压时需另行设计事件重放与去重方案，不能把建空主题视为消息迁移完成。

## 构建与运行

```bash
mvn -o package -Dmaven.test.skip=true
# 保留标准脚本中的模型凭据加载、网关端口及前端构建参数
bash deploy/start-all.sh --dev-infra
# 开发 HMR：bash deploy/start-dev.sh --dev-infra --build
# 可靠入库拆分（按需替代上一条）
bash deploy/dev-infra/compose.sh --split up -d
```

`--split` 同时加载生产写入约束和拆分角色，关闭同步写接口，入口 `/rag/ingestions`；默认模式保持原组合服务行为。所有 knowledge 角色等待入库迁移。拆分模式默认 hash embedding 仅供功能验证；真实语义环境必须同时配置 `KNOWLEDGE_SPLIT_EMBEDDING_*` 并使用匹配的向量集合，不能用 hash 查询旧语义向量。

直接使用 `compose.sh` 启动时，调用方需自行准备原启动脚本所加载的模型配置与端口变量；整栈优先使用 `start-all.sh --dev-infra`。

仅修改基础 Compose 后，可执行 `python3 deploy/dev-infra/generate.py` 重新生成覆盖文件。运行测试用 `mvn -o -pl knowledge-service,conversation-service,channel-service -am test`；打包与 test 分开执行，避免当前 migration 模块可执行 jar 重打包影响 reactor 测试类路径。

## 回退

先停止新应用写入，再移除 dev-infra 覆盖，使用原 Compose 和原数据卷启动。旧数据是切换时快照，不包含切换后的新写入；需要保留新增数据时应先做反向迁移，不能直接回退。禁止删除共享网络、共享实例或原卷。开发机凭据丢失时不要重新生成并直接使用旧账号，应按受控流程恢复/轮换。

## 真实实例冒烟

在专属 Qdrant/ES 就绪且镜像构建完成后，运行 `python3 deploy/dev-infra/smoke.py`。开发机 Docker 内存紧张时可用 `python3 deploy/dev-infra/smoke.py --host`，在宿主机启动 Java 应用并通过共享实例映射端口验证（默认 MySQL 43306、Redis 46379、S3 49000、Qdrant 46334、ES 49200）。脚本创建独立测试数据库和随机租户，验证重复提交返回同一 job、状态 READY、检索命中；测试容器停止后保留测试数据与日志，便于审计。结果位于 Git 忽略的 `deploy/.dev-infra-state/smoke.json`。该测试关闭外部模型、图谱与 async-task HTTP 联动，不代表整个应用栈端到端验证。
