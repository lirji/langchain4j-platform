# HTTP知识源ETL验证结果

状态：PASS。范围：HTTP知识源采集、新增/更新/删除传播、恢复检查点与管理API；未执行部署、真实业务接口联调或付费模型调用。

## 已执行验证

| 检查 | 结果 |
|---|---|
| `mvn -o -B -pl knowledge-service -am test` | 558项，0失败、0错误、9条件跳过；knowledge-service为379项、5跳过 |
| `python3 deploy/test-knowledge-http-sync-mysql.py` | 18项通过；真实dev_infra MySQL 8.4随机隔离库，迁移/DML账号分离；表及字段注释完整，应用DDL被拒绝 |
| `DEV_INFRA_SMOKE=true mvn -o -B -pl knowledge-service -am -Dtest=DevInfraRedisIntegrationTest,KnowledgeSyncConfigTest -Dsurefire.failIfNoSpecifiedTests=false test` | 4项通过；真实Redis随机租户key验证Lua版本CAS、墓碑、重启、防复活；使用本机既有凭据，未输出凭据 |
| `git diff --check` | PASS |

测试日志摘要和变更源码指纹见`VERIFICATION_EVIDENCE.json`。最终代码包含一次自审修复：幂等键加入原始租户摘要，防止既有入库表默认大小写不敏感排序规则使同名来源互相冲突；新增真实MySQL用例证明`tenant`和`TENANT`可独立入库。

## 验收映射

- HTTP请求、opaque cursor编码、Bearer独立凭据、不跟随重定向、响应大小上限、非法正文/版本/游标/重复ID：`HttpKnowledgeSourceTest`。
- 默认关闭、query/worker无采集器、启用缺持久化配置启动失败、真实配置绑定、authz disabled兼容：`KnowledgeSyncConfigTest`。
- ingest权限、租户/用户404隔离、状态和手动轮询、来源映射追溯：`KnowledgeSyncControllerTest`。
- 租约单赢家、旧执行器拒绝、配置变更、凭据轮换、事务回滚、重启、大小写租户隔离：`KnowledgeSyncStoreTest`。
- READY前不推进、丢失提交响应重放、同内容跳过embedding、A→B→A新版本、整页完成才推进、坏版本、失败任务恢复、上下文清理：`KnowledgeSyncServiceTest`。
- 删除编辑权校验、墓碑不可见、撤权失败保留游标、后续幂等恢复、迟到提交不能复活、删除后重建新ID：同上及`DocumentVersionCommitTest`、真实Redis测试。
- 本地HTTP服务器→真实关系库→实际Tika解析/切分→既有worker→离线HashEmbedding/内存向量库→真实RAG检索：`KnowledgeHttpSyncPipelineTest`，新增可查、更新替换、删除后无命中。
- 旧JSON没有`deleted`仍可读取；既有知识模块回归通过。源码未升级依赖。

## 自审与边界

- SQL只在XML；关系库检查点和映射短事务原子更新，网络副作用在事务外。数据库CAS和Registry原子墓碑分别防止旧协调器推进游标、旧worker恢复可见性。
- 端点仅来自部署配置，默认HTTPS，无用户任意URL入口，无重定向；内部JWT不向外转发，外部token不落库/日志。
- 删除使用原子墓碑和权限撤销，满足后续RAG检索不可见；历史投影、原文和任务未物理删除。没有宣称撤回历史回答/在途查询/语义缓存。
- V7扩展迁移兼容功能关闭时旧应用；所有knowledge角色升级后才允许启用。产生墓碑后不能直接回退至不识别墓碑的旧worker。
- 实际业务接口地址/鉴权未提供，未宣称已连接实际产品/客服系统。没有外部模型、真实S3/Qdrant/ES联调或容量测试；测试没有冒充这些验证。
- MySQL脚本只清理本次随机临时库/用户，Redis测试只清理随机测试租户key。未触碰既有业务数据、服务或工作树。
