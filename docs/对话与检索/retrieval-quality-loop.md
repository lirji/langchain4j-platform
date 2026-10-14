# RAG 检索质量评测闭环

入口：能力控制台的RAG工作台 →「检索质量评测」。管理、执行、历史与门禁均要求已认证的`ingest`权限；服务端按租户隔离。评测使用调用者的真实用户/部门/权限，不提升为管理员检索。

## 操作流程

1. 在知识运营收件箱记录真实失败问题；评测面板「新建评测集」→「加载近期反馈」，选来源问题。
2. 人工标注相关文档ID及本租户/共享库范围。反馈中的关联文档不会自动认定为正确答案。
3. 设置K（1–20）、最低分、分类、允许质量绝对下降及P95增加毫秒；保存1–20道题的小批评测集。
4. 执行并检查逐题结果，确认标注正确后「将此运行设为基线」。首次完整运行显示尚无基线，不判为通过。
5. 修改知识、分块或检索配置后，在同一评测集再次运行；页面展示总体指标、基线差值和逐题召回退化。
6. 根据失败题回到已有知识反馈/修订/审核/发布流程；重新运行验证改善。评测不自动批准或发布知识。

修改评测集定义会清除当前基线，历史运行仍保留原始输入快照。基线只允许同一数据集定义和相同用户/租户/部门/scope上下文的完整成功运行；变更后需要重新评估可比性。

## 指标与证据边界

- Query返回的前K个chunk按`(visibility, docId)`去重，保留首次排名与版本。共享库同名文档不能顶替租户文档。
- Recall=命中的标注文档数/标注总数；Precision=命中的标注文档数/实际返回的不同文档数（不是固定除以K）。MRR为第一个相关文档去重后的倒数排名，Hit为至少命中一篇。
- 总体为逐题宏平均；P50/P95采用本次逐题端到端检索耗时的最近秩百分位。样本量较小，不声称生产SLO或检索性能达标。
- 总体Recall/Precision/MRR/Hit下降超过门槛，任一道题Recall下降超过门槛，或P95增量超限，即REGRESSION；恰好等于门槛不失败。
- 保存题目、标注、检索参数、命中文档及版本、权限摘要、运行说明、基线ID、指标与原因，不保存令牌或命中正文。
- 当前不会冻结整座知识库/索引、Embedding模型或远端授权策略；运行说明仅为人工备注，比较的是运行时实际检索结果。回答正确性、LLM评委、Token和费用未纳入本轮。

## API与自动门禁

路径均位于`/rag/operations/quality`，经现有ingest-api路由进入知识运营。

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET/POST | `/datasets` | 游标列表/幂等创建定义 |
| GET/PUT | `/datasets/{id}` | 详情/按expectedRevision修改，清除基线 |
| POST | `/datasets/{id}/baseline` | `{expectedRevision, runId}`选择服务端已完成结果 |
| GET/POST | `/datasets/{id}/runs` | 有界历史/`{expectedRevision,idempotencyKey,label}`执行 |
| GET | `/runs/{id}` | 读取固定运行结果 |
| GET | `/runs/{id}/gate` | PASS=200、REGRESSION=422、无基线或RUNNING=409、FAILED=503 |
| POST | `/runs/{id}/abandon` | 仅允许终止超过两分钟截止时间的RUNNING记录 |

发布自动化可在固定运行ID上执行以下只读门禁，HTTP非2xx必须停止，不得把查询成功当作评测通过：

```bash
curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer ${QUALITY_ACCESS_TOKEN}" \
  "${EDGE_BASE_URL}/rag/operations/quality/runs/${QUALITY_RUN_ID}/gate"
```

定义形状：

```json
{"idempotencyKey":"an-operator-generated-key","definition":{"name":"售后检索回归","cases":[{"id":"refund-policy","question":"退款需要多久","relevant":[{"visibility":"tenant","docId":"actual-document-id"}],"feedbackId":null}],"settings":{"topK":5,"minScore":0,"category":null,"allowedDrop":0,"allowedP95IncreaseMs":1000}}}
```

示例不是预置演示数据；必须替换为真实文档或通过实际反馈来源创建。

## 失败恢复与部署

同键同命令返回原运行，冲突命令409；每个评测集同时只能有一个活动运行，单实例最多两个运行。检索调用在数据库事务外，定义编辑与基线变更在运行期间拒绝。
网络响应丢失时刷新历史或重试同一请求，不要立即换键。进程崩溃后RUNNING保留，超过两分钟由操作员显式终止；旧执行器无法覆盖已保存终态。终止不撤销已经发生的检索调用。

新的V6迁移仅新增三张表（定义、运行、审计），复用knowledge_ingestion库及既有应用/迁移账号。没有运行时DDL，无新增依赖或中间件；必须先运行迁移再启动新应用。旧应用忽略新表，代码回滚不删除已产生评测数据。
query响应新增可空`version`字段：先部署query，再部署ingest-api；combined形态随同一服务更新。旧query缺版本时发布复查不通过。

历史运行持续保留，本轮没有自动清理策略；保留周期应按业务数据治理另行确定。

## 真实组件冒烟

共享 dev-infra 已启动且本地凭据已配置后，用已构建的固定知识服务镜像执行：

```bash
python3 deploy/dev-infra/smoke.py --quality \
  --image langchain4j-platform-knowledge-service:<source-commit>
```

采用固定镜像的发布包装脚本时，追加 `--compose <compose-wrapper.sh>`；该包装脚本也必须为迁移镜像固定相应版本。脚本创建随机租户和独立测试库，用 HashEmbedding 避免付费模型调用，但实际经过 MySQL、S3、Redis、Qdrant、Elasticsearch 与生产检索管线。验证完整入库、全文可见性、基线、幂等、PASS/200，然后仅删除本次新建文档并验证 REGRESSION/422。结束停止测试容器；独立测试库、S3测试原文和运行记录保留供核查，不清理业务数据。

原始证据在忽略的 `deploy/.dev-infra-state/quality-smoke.json`，不含调用令牌。它验证检索评测机制和真实组件集成，不代表正式知识库标注集质量或外部 Embedding 模型质量。
