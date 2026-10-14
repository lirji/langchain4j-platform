# HTTP知识源 ETL

HTTP产品说明/FAQ增量源 → 拉取和质量校验 → 持久化待处理页 → 既有知识入库任务 → 解析/切分/向量化/索引 → 成功后推进来源游标。

协调器位于`knowledge-service/ingest/sync`，在`combined`或`ingest-api`角色中运行。`query`和`ingest-worker`不发起采集；worker继续处理已有入库任务。默认关闭，无新服务、中间件或依赖。

## 外部源接口契约

连接器调用固定配置端点：`GET /feed?limit=25&cursor=<URL编码的不透明游标>`。来源自行把业务字段映射成协议字段，不支持任意JSONPath或在平台执行映射脚本。

```json
{
  "items": [
    {"id": "faq-refund", "revision": 12, "title": "退款说明", "content": "订单支付后七天内可申请退款。", "deleted": false},
    {"id": "old-product", "revision": 7, "deleted": true}
  ],
  "nextCursor": "change-sequence-105"
}
```

- `id`：来源内稳定记录ID，非空、最多256字符。不同来源/租户可使用相同ID。
- `revision`：每条记录单调递增的正整数，不是数据库文档版本；不能用同版本发布不同内容。删除后重新出现也要递增。
- `title`：非删除记录必填，最多256字符；映射到文档展示名。
- `content`：非删除记录必填，最多100000字符；首尾空白去除后作为UTF-8纯文本入库。
- `deleted`：显式删除事件，可省略，默认false。列表缺项、空页、请求失败都不代表删除。
- `nextCursor`：必填，最多1024字符。非空页必须与请求游标不同；空页可保持原游标等待新变更。它始终是数据，不是下一页URL。
- 每页最多25条、同页ID唯一，响应最大1MiB。源需在同页合并同一记录的变更，并支持按未确认游标重放。首次游标为空字符串。
- 接口返回HTTP 200 JSON；非200、重定向、非法页、空正文等不会推进游标。整页先校验，再处理记录。
- 不自动完成首次全量快照与增量衔接：来源接口需要保证首次游标到后续游标无遗漏。服务没有真实业务源地址，尚未连接实际产品或客服系统。

协议类型在`platform-protocol/.../KnowledgeSourceFeed.java`，来源凭据只用于外部API，内部JWT不会转发给外部源。

## 配置与开启

先用独立迁移账号执行knowledge-ingestion的增量迁移`V7__knowledge_http_sync.sql`。应用账号仅需`SELECT/INSERT/UPDATE`。检查点使用既有入库MySQL，必须同时配置`app.rag.ingestion.store=jdbc`、`app.rag.source.store=s3`、`app.rag.registry.store=redis`。

复制`deploy/knowledge-http-sync.example.yml`到受控运行配置，设置来源端点、租户、执行用户、部门和外部Bearer token，再用Spring的`spring.config.additional-location`加载。Compose可把该文件只读挂入`knowledge-ingest-api`，将路径追加到现有额外配置位置，并显式传入相应环境变量；本次未改变运行中的Compose或部署服务。

全部knowledge角色升级到支持文档墓碑的版本后，设置`KNOWLEDGE_HTTP_SYNC_ENABLED=true`。只开启在入库API角色，worker/query保持原配置。启用时至少需要一个来源，最多20个。

- 来源绑定的服务用户由部署管理员授权；创建/更新/删除文档沿用已有知识授权规则。`enforce`模式需有效部门及编辑权限。
- 来源配置只能由服务端维护，不提供用户任意URL注册入口。HTTPS默认必需；本地测试HTTP须显式设置`allow-insecure-http: true`。
- 不允许URL自带查询参数、fragment或用户名密码；来源密钥应从受控环境/secret注入，不能写入Git。
- 端点、租户、用户、部门改变会触发配置指纹冲突。更换业务源应使用新source ID并评估历史知识归属；仅token轮换允许沿用游标。

## 同步、恢复与删除

每实例每30秒轮询配置来源，启动后10秒开始。每来源每轮最多处理一页；请求连接超时3秒，单次读超时5秒，响应读取另有5秒总时限检查。HTTP错误下轮重试，无紧密重试循环；错误后的来源退避60秒，其他轮询退避30秒。实际吞吐取决于来源数量、源响应与worker完成时间，不提供未经测量的容量承诺。

数据库以60秒租约和CAS控制多实例执行权。整页及固定入库版本先落盘，外部副作用在数据库事务外执行：

1. 读取来源记录的成功版本，过滤旧版本和重复内容。同版本不同内容报`SOURCE_REVISION_REUSED`。
2. 有内容变化时，使用来源ID、外部ID与来源版本派生固定幂等键提交入库。
3. 任务尚未`READY`时保持原游标，后续轮询复用快照和原任务。worker自动恢复仍由原有reconciler负责；`MANUAL_REVIEW`等状态通过既有`POST /rag/ingestions/{jobId}/retry`处理。
4. 每条成功映射与页内位置在同一短事务提交。整页完成才提交`nextCursor`并清除待处理正文；超时/崩溃/响应丢失不会跳过尚未完成的数据。
5. 显式删除：检查编辑权，按当前文档版本原子写入Registry墓碑，立即对后续RAG检索不可见，再撤销文档权限关系。撤权失败时保持原游标，重试幂等撤权。
6. 墓碑永久拒绝该文档ID后续版本提交，防止迟到worker复活。来源记录重新出现时分配新的文档ID，从版本1重新入库；旧删除重放不能删除重建文档。

删除是**退出知识检索及撤权**，不是数据物理擦除：历史向量、ES/图谱投影、S3原文、任务记录和墓碑按原有存储保留，物理清理与数据保留政策需单独制定。墓碑通过既有文档版本过滤使版本化命中不可见；不承诺撤回已返回的答案、在途查询或清除conversation已有语义缓存。外部删除源应使用该受治理链路，不再手工修改同步生成的文档。检测到外部文档版本变化时停止覆盖并报告`DOCUMENT_VERSION_CHANGED`。

## 管理接口

沿用现有`/rag/ingestions/**`网关路由和角色限制。每次必须具备`ingest` scope，且认证租户和用户与来源配置一致；其他用户/租户访问返回404。

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/rag/ingestions/sources` | 当前身份来源列表和脱敏状态 |
| GET | `/rag/ingestions/sources/{id}` | 状态、错误码、待处理数、等待的入库任务ID、下次轮询时间 |
| POST | `/rag/ingestions/sources/{id}/poll` | 手动推进一轮，仍遵守租约和退避；202不等于同步成功 |
| GET | `/rag/ingestions/sources/{id}/document?externalId=faq-refund` | 外部记录到文档ID、来源版本、知识版本和删除状态的追溯 |

成功入库/删除沿用平台`AUDIT`日志，带来源ID、来源版本和文档ID，不记录正文或令牌；恢复重放可能重复审计事件。

状态：`NOT_STARTED`、`SYNCING`、`IDLE`、`BLOCKED`。不返回凭据、URL、游标或原文。

`SOURCE_HTTP_ERROR`/`SOURCE_UNAVAILABLE_OR_INVALID`检查来源可用性与JSON；`INVALID_PAGE`/`INVALID_ITEM`修复来源格式；`SOURCE_CONFIG_CHANGED`核对来源身份；`INGESTION_REQUIRES_ATTENTION`按任务ID处理现有入库任务；`INGESTION_FORBIDDEN`核对知识编辑权。

## 发布与回退边界

V7是新增表，旧应用可在功能关闭时继续运行。先执行迁移、升级全部knowledge角色，再启用来源，避免旧worker忽略墓碑。若已产生墓碑，不得回退到不识别`deleted`的旧版本并继续写入；应先关闭来源并保留支持墓碑的应用版本。关闭同步只停止新采集，不撤回已经提交的知识变更或在途入库任务。

来源快照含待处理正文，MySQL权限及备份应与知识原文同级治理；成功后清空本页快照，失败页保留用于恢复。不自动删除任何已有生产数据，不执行全局清库或Redis清空。

## 验证

```bash
mvn -o -B -pl knowledge-service -am test
python3 deploy/test-knowledge-http-sync-mysql.py
```

MySQL脚本仅创建随机`lc4j_sync_it_*`临时库和分离的DML/迁移账号，验证结束只清理本次资源。HTTP端到端测试使用本地HTTP服务器和真实解析/worker/检索链路、离线HashEmbedding，不代表外部模型、S3、Qdrant、ES或真实业务接口已联调。
