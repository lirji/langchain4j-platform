# 数据存储清单（数据库 / 缓存 / 向量库 / 消息）

本文汇总平台用到的各类数据存储中间件：类型、端口、账号密码、所属服务与用途。面向本地起栈、排障和生产改配。

> ⚠️ **凭据全部是本地开发/演示默认值**（源码里的 `root/root`、`dev-only-...` 等），生产必须用 External Secrets / 环境变量覆盖，详见[部署指南](../平台工程/deployment-guide.md)。
>
> ℹ️ 平台几乎每个存储都有**内存/确定性默认实现**，因此本地跑和单测**不接任何外部库也能启动**。下表是「开启落库开关 / 跑演示套件」后才真正用到的外部依赖。配置以各服务 `application.yml` 与 `deploy/docker-compose.yml` 为准。

## 概览

| 类型 | 组件 | 镜像 | 端口 | 默认账号/密码 | 何时用到 |
|---|---|---|---|---|---|
| 关系型 | MySQL | `mysql:8.4` | 3306 | root / root | 工作流引擎、NL2SQL、GraphRAG、异步任务（各自逻辑库；均可关，默认内存） |
| 缓存/状态 | Redis | `redis:7-alpine` | 6379 | 无密码 | 限流、token 预算、成本、语义缓存、RAG 注册表、会话记忆 |
| 向量库 | Qdrant | `qdrant/qdrant:latest` | 6333 REST / **6334 gRPC** | 无 api-key | RAG 默认向量库 |
| 消息/事件流 | Kafka | `apache/kafka:3.8.0` | 9092（控制器 9093） | PLAINTEXT 无认证 | 渠道出入站事件（默认内存总线，需开关） |
| 测试库 | H2 | 内嵌 | 无 | 无 | 依赖 DB 的单测（内存，无 Testcontainers） |

> Kafka 严格说不是数据库，是跨服务事件总线；一并列出便于对齐端口与凭据。

## 一、MySQL 8.4（关系型，:3306）

Docker 单实例 `mysql:8.4`，`MYSQL_ROOT_PASSWORD=root`、初始库 `platform`。各服务按需自建**独立逻辑库**（连接串带 `createDatabaseIfNotExist=true`，无迁移工具，靠 `Jdbc*Store` 类里的 `CREATE TABLE IF NOT EXISTS` / `ALTER TABLE ADD COLUMN` 演进表结构；Flowable 自管其表）。

| 服务 | 逻辑库 | 账号 | 密码 | 落库开关 | 用途 |
|---|---|---|---|---|---|
| workflow-service | `flowable` | root | root | 恒开 | Flowable BPMN 退款审批引擎表 + outbox |
| analytics-service | `nl2sql_demo` | root（admin） | root | `NL2SQL_ENABLED=true` | NL2SQL 演示库（建表/写入） |
| analytics-service | `nl2sql_demo` | `nl2sql_ro`（只读） | `nl2sql_ro` | 同上 | NL2SQL 只读执行连接（六层 SQL 护栏之一） |
| knowledge-service | `knowledge_graph` | root | Docker `root` / 本地默认**空** | `RAG_GRAPH_STORE=jdbc` | GraphRAG 图谱存储 |
| async-task-service | `async_task` | root | Docker `root` / 本地默认**空** | `ASYNC_TASK_STORE=jdbc` | 通用异步任务中心 |
| channel-service | （去重表，指向 MySQL） | — | — | `CHANNEL_DEDUP_STORE=jdbc` | 事件去重（跨重启幂等）；默认 `memory` |

连接串形如 `jdbc:mysql://localhost:3306/<库名>?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true`（Docker 内主机名为 `mysql`）。

> ⚠️ **本地 vs Docker 密码不一致**：`knowledge_graph` / `async_task` 在各自 `application.yml` 的默认密码是**空串**，而 `docker-compose.yml` 显式注入 `root`。按运行方式区别对待。

相关环境变量：`WORKFLOW_DB_URL/USER/PASSWORD`、`NL2SQL_DB_URL/READONLY_URL/ADMIN_USER/ADMIN_PASSWORD/READONLY_USER/READONLY_PASSWORD`、`RAG_GRAPH_DB_URL/USER/PASSWORD`、`ASYNC_TASK_DB_URL/USER/PASSWORD`。

## 二、Redis 7（缓存/状态，:6379）

- `redis:7-alpine`，**无密码**；`--appendonly yes` + 命名卷 `redis-data`，跨重启持久化。
- 主机名：Docker 内 `redis`，本地默认走 Spring Boot 默认 `localhost:6379`（`application.yml` 未显式配，靠 `SPRING_DATA_REDIS_HOST/PORT` 覆盖）。
- 用途：限流（`platform-security` ratelimit）、按租户 token 预算与 USD 成本计数（分布式计数，保证水平扩容正确性）、语义缓存 L1、RAG 文档注册表、多轮会话记忆（`CONVERSATION_MEMORY_STORE=redis` 时）。
- 多数开关默认即为 `redis`（token 预算、成本、语义缓存、RAG 注册表），部分默认内存（会话记忆）。

## 三、Qdrant（向量库，:6333 REST / :6334 gRPC）

- `qdrant/qdrant:latest`，卷 `qdrant-data`；**应用侧走 6334 gRPC**（`QDRANT_PORT=6334`），6333 为 REST/UI。
- 默认**无 api-key**（`QDRANT_API_KEY` 空）、`use-tls=false`；collection 基名 `knowledge_segments`，`collection-per-tenant` 隔离下按 `<base>_<tenant>` 建。
- RAG 默认向量库（`RAG_VECTOR_STORE_PROVIDER=qdrant`）。冒烟测试：`bash deploy/smoke-qdrant-rag.sh`。

### 可选替代向量库（feature-flag，非默认）

以下都是**可用的真实现**（非配置占位），且客户端依赖**已打进 `knowledge-service` 的 jar**——切换不改代码、不加依赖。装配逻辑见 `KnowledgeEmbeddingConfig.java`：单属性 `app.rag.vector-store.provider` + `@ConditionalOnProperty` 分派出对应 `CollectionManager`，上层 `ManagedEmbeddingStoreRouter` 统一复用（含维度守卫）。

| provider | 默认端口 | 账号/密码 | 环境变量前缀 | 实现类 / 底层 |
|---|---|---|---|---|
| `in-memory`（默认） | — | — | 无外部依赖 | langchain4j `InMemoryEmbeddingStore` |
| `qdrant`（Docker 默认） | 6334 gRPC | 无 api-key | `QDRANT_*` | `QdrantClientCollectionManager` |
| `pgvector`（PostgreSQL） | 5432 | postgres / postgres | `RAG_PGVECTOR_*` | `PgVectorCollectionManager`（`langchain4j-pgvector`） |
| `milvus` | 19530 | 空 / 空 | `RAG_MILVUS_*` | `MilvusCollectionManager`（`langchain4j-milvus`） |
| `chroma` | 8000（HTTP） | 无 | `RAG_CHROMA_BASE_URL` | `ChromaCollectionManager`（`langchain4j-chroma`） |
| `doris`（走 mysql 协议 JDBC） | 9030 | root / 空 | `RAG_DORIS_*` | `DorisCollectionManager` + 自研 `DorisEmbeddingStore`（HNSW ANN） |

### 快速切换向量库

核心开关只有一个 `RAG_VECTOR_STORE_PROVIDER`，补上该后端的连接变量后**重启 knowledge-service 即可，无需重新打包**。

```bash
# 切 pgvector
RAG_VECTOR_STORE_PROVIDER=pgvector \
RAG_PGVECTOR_HOST=localhost RAG_PGVECTOR_PORT=5432 \
RAG_PGVECTOR_USER=postgres RAG_PGVECTOR_PASSWORD=postgres \
mvn -pl knowledge-service spring-boot:run

# 切 milvus：RAG_VECTOR_STORE_PROVIDER=milvus + RAG_MILVUS_HOST/PORT(19530)
# 切 doris ：RAG_VECTOR_STORE_PROVIDER=doris + RAG_DORIS_JDBC_URL=jdbc:mysql://localhost:9030/demo + RAG_DORIS_USER/PASSWORD
# 回退零依赖：RAG_VECTOR_STORE_PROVIDER=in-memory
```

**切换必看的三个坑：**

1. **数据不迁移**。换 provider 得到的是空库，原有向量不会跟着过去——需**重新 ingest 文档**。
2. **外部后端要先起来**（in-memory 除外）。pgvector/milvus/chroma/doris 都得有对应服务可达，否则启动即失败。
3. **隔离模式限制**。默认 `collection-per-tenant`（每租户独立 collection/表）四个后端全支持；`RAG_VECTOR_STORE_ISOLATION=shared` **仅 in-memory / qdrant** 支持，其它 provider 用 shared 会直接抛异常。另外换 embedding provider（hash=64 维 vs openai/ollama 维度不同）也需重建库，维度守卫 `DimensionMismatchException` 会拦截不匹配。

## 四、Kafka 3.8（消息/事件流，:9092）

- `apache/kafka:3.8.0`，KRaft 模式（控制器监听 9093），PLAINTEXT 无认证。
- 用途：channel-service 渠道出入站事件（`CHANNEL_EVENTS_ENABLED=true` 才用；默认内存事件总线）。事务性 outbox + relay + 消费侧去重实现 effective exactly-once，详见[事件总线指南](../平台工程/eventbus-guide.md)。
- 主机名：Docker 内 `kafka:9092`（`KAFKA_BOOTSTRAP_SERVERS`）。

## 五、H2（测试，内存）

依赖 DB 的单测统一用内存 H2，无 Testcontainers、不占端口、无账号。测试与被测代码同包，纯 POJO 单测直接 new 组件注入 mock。

## 相关文档

- [部署指南](../平台工程/deployment-guide.md)：docker-compose、k8s/Helm、External Secrets、Config Server。
- [运行与配置手册](operations.md)：本地启动、环境变量全量、验证与排障。
- [架构文档](架构文档.md)：服务边界、数据边界、事件流。
- [RAG 接入指南](../对话与检索/rag-guide.md)：向量库 provider、collection-per-tenant 隔离、GraphRAG。
- [NL2SQL 指南](../对话与检索/nl2sql-guide.md)：只读连接、SQL 安全护栏、多租户库隔离。
