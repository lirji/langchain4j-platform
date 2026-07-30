# Knowledge 运行面拆分

## 边界

Knowledge 仍是 Java 数据面，不迁入 AgentScope。拆分使用同一 artifact 的不同运行角色，先获得
独立发布、扩缩容和网络边界，再根据稳定所有权决定是否拆仓：

- `combined`：现有 `/rag/**` 兼容 façade 和回滚目标，默认角色。
- `query`：只开放 `/rag/**` 的 GET/HEAD/OPTIONS，禁止 ingestion job API；要求持久化向量库、
  registry，开启 hybrid 时要求 Elasticsearch query。当前必须关闭 graph query，直到图命中携带
  `documentId/documentVersion` provenance。
- `ingest-api`：只开放 `/rag/ingestions`，负责 S3 原文落盘和 durable job 创建，不做索引。
- `ingest-worker`：不开放业务 HTTP，只保留 Actuator 探针；消费 job 并执行 Java 领域 sink。

所有业务调用仍先经过 `X-Internal-Token` 校验。job 固化 tenant、user、scopes、department 和
traceId；worker 不从模型输出或全局变量恢复身份。

## Durable ingestion v2

`POST /rag/ingestions` 使用 multipart：

- Header `Idempotency-Key`：调用方稳定生成。
- Query `documentId`、`documentVersion`：显式版本。
- Part `file`：原始文件。

API 返回 `202` 和
`platform-protocol/src/main/resources/contracts/knowledge/ingestion-job.schema.json` 所定义的
语言中立 job view。`GET /rag/ingestions/{jobId}` 只在当前租户内查找；跨租户与不存在均为
`404`。

原文 key 为：

```text
<prefix>/<tenant>/<document>/v<version>/<content-hash>/source
```

hash 进入 key，避免同一版本的并发冲突覆盖原文。对象元数据同时保存 tenant、document、version
和 hash。生产 ingest 角色启动时强制要求 `RAG_SOURCE_STORE=s3`、
`RAG_INGESTION_STORE=jdbc` 和共享持久化 registry；ingest-api 使用内存 registry 会启动失败。

## Worker 与可见性提交

worker 定时扫描 JDBC job，先从 S3 原文读取并通过 Tika 解析，然后只在本次执行内完成切分、
contextual enrich 与 embedding。框架对象不进入任务表。各 sink 按以下顺序执行：

```text
VECTOR -> ELASTICSEARCH -> GRAPH -> AUTHORIZATION -> REGISTRY
```

- Vector id 为 `<tenant>/<document>/v<version>/<chunk>`。
- Elasticsearch id 与融合 key 同时包含 document version。
- 新建文档的 owner/home department 在 Registry 提交前写入；覆盖不重写 owner。
- Registry 是版本可见性提交点。query 会丢弃与 Registry 当前版本不一致的向量/ES 命中，
  因而新版本半完成时旧版本仍可见，新版本不可见。
- 原文准备失败进入可 reconcile 的 `FAILED`；sink 失败进入 `PARTIAL`。只有声明幂等的 sink
  才会被自动恢复。

旧版本派生索引不在提交流程中物理删除，避免半完成替换破坏当前可见版本。可在唯一
`ingest-worker` 上显式开启版本 GC：

```text
RAG_INGESTION_VERSION_GC_ENABLED=true
RAG_INGESTION_VERSION_GC_RETAIN_VERSIONS=2
RAG_INGESTION_VERSION_GC_GRACE_PERIOD=P7D
```

GC 以 Registry 当前版本为权威，只清理 `currentVersion-retainVersions` 以前的 Vector、ES
和带版本 provenance 的 Graph 派生数据；当前版本和最近回滚窗口永远保留，且只有当前版本
已稳定超过 grace period 才执行。单 sink 失败不会阻断其它 sink，下一轮会幂等重试。S3 原文
不由 GC 删除，继续由 bucket lifecycle 独立管理。历史 Graph source 没有版本 provenance，
无法证明归属，因此 fail-safe 保留，不能猜测性删除。

## async-task 生命周期

开启 `RAG_INGESTION_ASYNC_TASK_ENABLED=true` 后，worker 以 Knowledge `jobId` 作为通用
async-task `taskId`，创建 `kind=knowledge.ingestion` 的任务信封，并同步 RUNNING/SUCCEEDED
状态。重复创建 409 按幂等处理。async-task 拥有 SSE/webhook 生命周期；Knowledge JDBC job
仍拥有原文引用、版本、逐 sink 状态与 reconcile 业务语义。调用只传播已经持久化的
tenant/user/scopes/department/trace，不发送原文字节。

## 本地验证拓扑

可选 override 不改变 edge 默认路由，也不会删除原 `knowledge-service`：

```bash
docker compose \
  -f deploy/docker-compose.yml \
  -f deploy/docker-compose.knowledge-split.yml \
  config --quiet
```

需要真实启动时，应显式列出 `minio`、`minio-init`、`knowledge-query`、
`knowledge-ingest-api` 和 `knowledge-ingest-worker`，避免误将兼容 façade 一并切流。
可选拓扑未提供外部 embedding 配置时默认使用 `hash`，便于本地跨进程/S3 验证；生产或
真实语义测试必须显式注入同一组 `KNOWLEDGE_SPLIT_EMBEDDING_*` 参数。split 使用独立变量
族，避免基础 `deploy/.env` 中只设置 provider、尚未注入 base URL/key 的配置导致启动失败。
同理，query 的外部 rerank 默认关闭；只有同时提供 endpoint/key 时才通过
`KNOWLEDGE_SPLIT_RERANK_ENABLED=true` 开启。
默认 hash 模式使用隔离的 `knowledge_segments_split_hash` Qdrant collection 基名，避免与
基础栈 1024 维真实 embedding collection 混写；真实语义部署应通过
`KNOWLEDGE_SPLIT_VECTOR_BASE_COLLECTION` 指向经过迁移的独立 collection。
ingest-api 本身不执行
embedding，因此固定使用 `hash`，不会因模型凭据缺失影响接收任务。其默认宿主端口为
`18095`，与 AgentScope 的 `18085` 隔离。

Helm chart 也包含同名的三个 workload，但 `services.knowledge-*.enabled` 默认全部为
`false`。显式开启后 query/ingest-api 会生成 ClusterIP Service，worker 只生成 Deployment；
三个角色复用 knowledge-service 镜像。S3 endpoint、bucket 与凭据必须通过环境专属 values 和
External Secrets 覆盖占位值。

原文凭据按角色最小化：

- ingest-api 使用 `knowledge-source-ingest`，只允许 `documents/*` 写入、multipart 和
  失败清理，不允许读取；
- worker 使用 `knowledge-source-worker`，只允许读取；
- query 不引用原文存储 Secret。

本地 MinIO profile 使用同样的两角色 policy。切流前运行
`deploy/test-production-cutover-config.sh` 与 `deploy/smoke-knowledge-s3-iam.sh`；
完整门禁见 `docs/平台工程/production-cutover-gates.md`。

## Rollout / rollback

1. 先在本地和 CI 验证 schema、租户隔离、JDBC 乐观锁与 S3 生命周期。
2. 只 shadow 提交 v2 job，不让其结果进入线上查询。
3. worker sink/reconcile 与 async-task 事件全部通过后，才允许测试租户 canary。
4. edge 默认 `/rag/**` 保持指向 `knowledge-service`，生产切流需要单独批准。
5. 回滚时停止 v2 提交与 worker，恢复整个 capability 到 `combined`；不做单请求混合回退。

本地 MinIO/Qdrant/ES/MySQL/Redis 环境已完成 IAM allow/deny、Qdrant 故障恢复、并发、
有界 soak 和 query/combined 切换回滚。目标云 IAM、真实节点容量、完整高峰周期 soak 和
生产 canary 仍必须在目标环境复验；本地结果不能替代生产放行。
