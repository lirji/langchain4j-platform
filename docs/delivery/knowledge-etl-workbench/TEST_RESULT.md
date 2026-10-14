# ETL工作台验证结果

结果：PASS。验证日期：2026-10-14；范围为HTTP知识同步运营控制、页运行记录与RAG数据接入面板。不包括部署或实际业务源联调。

| 检查 | 命令 / 方法 | 结果 |
|---|---|---|
| knowledge及上游完整回归 | `mvn -o -pl knowledge-service -am test` | 569项，0失败/0错误，9项既有环境条件跳过 |
| 最后控制参数校验修订后聚焦回归 | `mvn -o -pl knowledge-service -am -Dtest='KnowledgeSync*Test,HttpKnowledgeSourceTest,KnowledgeHttpSyncPipelineTest' -Dsurefire.failIfNoSpecifiedTests=false test` | 41项全部通过 |
| 最终真实MySQL | `python3 deploy/test-knowledge-http-sync-mysql.py` | 28项全部通过；表/字段注释齐全；应用DDL被拒绝；本次随机库/账号已清理 |
| 前端完整回归 | `npm --prefix capability-showcase-frontend test` | 585项全部通过 |
| 最终RAG聚焦回归 | `npm --prefix capability-showcase-frontend run test -- --run src/modules/rag` | 32项全部通过 |
| 类型与最终构建 | `npm --prefix capability-showcase-frontend run type-check`、`npm --prefix capability-showcase-frontend run build` | 通过；沿用既有bundle体积提示，不改变依赖 |
| 浏览器 | Chrome原生UI，真实Vue组件与主题，隔离接口夹具 | 来源列表→详情→运行历史→失败明细；1180px桌面容器及390px容器检查；修复窄屏标题溢出并复核 |
| 差异检查 | `git diff --check`及主Agent源码逐项复核 | 通过，无未解决的本范围阻断问题 |

## 可复现验收证据

- `KnowledgeSyncOperationsTest`：暂停撤销旧执行器检查点权限，不能越过暂停写映射；控制版本并发只有一个赢家；恢复同一运行；失败重试替换最新条目不重复计数；映射失败回滚运行/检查点；新增运行不扰动历史游标分页；租户隔离；抓取失败条数未知；已完成旧任务不能误领下一页；V7无action的页恢复不伪造历史分类。
- `KnowledgeSyncServiceTest`：真实关系库与真实提交器，来源快照恢复、worker完成时仍暂停、运营者以绑定执行用户重试及上下文恢复、旧版本/同内容跳过计数。HTTP pipeline使用本地源、真实解析/worker与离线HashEmbedding。
- `KnowledgeSyncControllerTest`：ingest scope、执行用户/显式运营名单与同租户校验、跨来源运行404、控制冲突409、缺失/负控制版本400、错误码不暴露内部配置。
- `KnowledgeSyncConfigTest`：默认关闭，query/worker不注册采集；显式运营列表的Spring绑定；既有持久化要求保持。
- `KnowledgeSyncPanel.test.ts`：控制版本、暂停禁用操作、受理≠完成、后端游标分页、失败详情、冲突需刷新、403/404与空态区分、身份与来源切换丢弃迟到响应。

## 实测边界与发布约束

- 浏览器验证为隔离UI夹具，不是数据库→HTTP→浏览器全链路验收；事务与真实MySQL由独立测试覆盖。未运行外部模型、实际HTTP产品/FAQ接口或生产环境。
- 暂停不能撤销已领取步骤/已提交worker的副作用，只保证后续领取被阻止及旧协调器不能推进检查点；已有任务完成后恢复可确认原结果。
- V8只扩展schema；升级/回退过程中全部旧同步协调器必须关闭，不能让不认识暂停和运行记录的旧实例混跑。
- 运行记录为每页最新结果，不是每次尝试日志；已确认旧数据无法分类时单独计数。成功空页也形成记录；当前无自动归档/删除，启用前需按实际容量和业务保留政策治理。
- 本地临时日志位于`/tmp/etl-workbench-*.log`与`knowledge-service/target/knowledge-http-sync-mysql.log`；持久化摘要及源码指纹见`VERIFICATION_EVIDENCE.json`。
