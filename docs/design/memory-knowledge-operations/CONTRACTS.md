# 治理契约 v1

所有新路径要求有效内部身份；未知字段不得改变 tenant/user/scope。旧 /memory/profile GET/DELETE 和 /chat/memory 形状保留。

## 记忆

治理仅 JDBC 提供；内存显式开发模式不声称支持治理。GET /memory/governance 返回 enabled、generation、items（最多1000）。每项 recordId、id、text、type、sourceChatId、createdAtEpochMs、state(PENDING/ACTIVE)、version、pinned、expiresAtEpochMs。ACTIVE 且未到期、用户未停用才召回。

POST /memory/governance/{recordId}/decision：expectedVersion、action(APPROVE/REJECT/FORGET/UPDATE/PIN/UNPIN)、可选 text/type/expiresAtEpochMs。审核只能处理候选；UPDATE 使用户校正内容 ACTIVE，写入 version+1；删除/拒绝不保留正文。读写均限定 tenant+user。冲突409，缺失404，非法迁移409；同一版本重试不会重复生效。

PUT /memory/governance/settings：enabled，expectedGeneration；停用或启用递增代次。清空/删除也递增代次，模型/异步任务凭旧代次不能提交。GET /memory/governance/audit?beforeId=&limit=50：有界最多100，降序审计，不含记忆正文。

错误：{code,message}；禁用503，校验400，资源404，状态/版本409；系统异常不回传 SQL/正文。

## 知识运营

在 /rag/operations 下增加反馈、候选、评测、审核、发布/同步和效果查看入口。所有写操作使用稳定幂等键或 expectedVersion；发布键由候选ID/修订推导，重试不得创建第二任务。编辑后旧评测/审核失效。候选列表与详情均只允许同租户运营管理员读取正文；普通用户只能访问自己反馈，管理员需要 ingest。

评测记录绑定候选内容摘要和版本；必须有真实执行结果及来源引用，不能仅手工传 passed=true 绕过审核。审核和发布分别记录操作者。发布结果先为 PUBLISHING，只有现有 ingestion job READY 才为 PUBLISHED；失败显示 FAILED/可重试。回滚为旧内容的新版本发布，复用同一审核/评测链路。

以下DTO在K1实施前依据现有ingestion契约定义。发布必须连接真实入库。


## 知识运营具体契约（K1前冻结）

生产仅 JDBC；装配复用 ingestionJobDataSource，路由 /rag/operations/** 优先去 KNOWLEDGE_INGEST_URI（combined/ingest-api），不依赖 query 实例拥有数据库写凭据。管理员能力复用 ingest scope；现有文档编辑仍需 KnowledgeAuthz 授权。

- POST /rag/operations/feedback：{idempotencyKey,question,reason:NO_ANSWER|INCORRECT|HELPFUL,comment,documentId?}；认证用户可提交；最多 question2000/comment4000，返回本人记录。GET /feedback 返回本人分页；GET /feedback/inbox 需ingest，按租户分页。
- POST /rag/operations/candidates：{idempotencyKey,feedbackId?,draft:{title,content,category?,checks:[{question,requiredText}]}}。需ingest，来源必须同租户反馈；最多正文50000、标题200、20个用例、每个问题500/引用500。生成服务端文档ID，状态DRAFT。
- GET /candidates?before=&limit=20 和 GET /candidates/{id}：需ingest；分页1..100，before为服务端不透明ID游标，按ID倒序。
- PUT /candidates/{id}：{expectedVersion,draft:{title,content,category,checks}}；仅发布前可编辑，编辑清空旧评测/审核，版本递增并回DRAFT。
- POST /candidates/{id}/evaluate：{expectedVersion}；服务端执行内容契约回归，逐条检查 requiredText 是否存在于候选正文。返回绑定完整草稿SHA256的 CONTENT_CONTRACT 证据；这是内容覆盖检查，不宣称LLM事实正确或线上召回率。全部通过后 EVALUATED，否则 DRAFT。
- POST /candidates/{id}/approve：{expectedVersion}；必须EVALUATED且证据仍匹配内容摘要，记录审核者并进入APPROVED。
- POST /candidates/{id}/publish：{expectedVersion}；持久化PUBLISHING及当前registry版本+1，提交现有 IngestionSubmissionService。幂等键由candidateId固定生成；同文档版本唯一发布槽防止并发候选重复占用。不在事务内调用对象存储。跨用户恢复要求由首次发布者执行，防止伪造原始调用身份。
- POST /candidates/{id}/sync：{expectedVersion}；按已有job权威状态更新PUBLISHING/PUBLISH_FAILED/PUBLISHED；只有READY可PUBLISHED。GET不产生发布副作用。失败发布可由原发布者调用publish重试，复用job retry状态机；未知提交结果PUBLISHING可恢复同一幂等键。
- POST /candidates/{id}/probe：{expectedVersion}；PUBLISHED后通过真实KnowledgeQueryService逐个用例检查命中文档ID、发布版本与引用文本。保存RETRIEVAL_PROBE证据；不使用客户端传passed=true。
- POST /candidates/{id}/rollback-draft：{idempotencyKey}；将选定已发布旧内容复制成新候选，保留相同documentId和rollbackOf来源；重新评测、审核、发布新版本，绝不直接降低registry版本。创建的回滚草稿幂等。

Candidate包含id、tenantId、feedbackId、rollbackOf、documentId、draft、status、version、evaluation、release、probe、createdBy、updatedBy、createdAt、updatedAt；release包含documentVersion、publisher、reviewer、jobId，审核/发布只来自认证上下文。反馈输入可关联文档ID，但不是授权依据。所有管理读写受tenant谓词和ingest权限保护。错误code/message：400 bad_request、403 forbidden、404 not_found、409 conflict、503 unavailable。

状态提交与审计同一数据库事务。审计只存动作、ID、操作者与版本，不复制正文；索引保证租户游标查询。正式数据全部持久化；审计/反馈/发布内容保留与容量纳入运维记录，不擅自承诺法律删除或自动清理期限。

GET /candidates/{id}/history?limit=50：同租户ingest管理员读取最近有界操作审计，无正文。

GET /candidates/{id}/feedback-summary：按候选documentId聚合真实主动反馈，返回类别→数量的映射，空集合表示无反馈，不代表质量通过。跨版本的同文档反馈累计展示，不冒充发布前后对照实验。拆分探针显式配置KNOWLEDGE_OPERATIONS_QUERY_URL，经原入站JWT请求query角色，校验返回tenant及registry发布版本；不向ingest-api发放签名私钥。
