# EnterpriseFeatureDevelopmentReport

## featureSummary / requirements

按批准计划实现可治理长期记忆与知识运营闭环。记忆由抽取候选、用户确认/校正、固定/有效期、召回、停用和遗忘构成；知识由主动反馈、草稿修订、内容契约检查、人工审核、持久化入库、检索复查和新版本回滚构成。本轮不升级Java、框架或依赖，不部署生产。

## nonFunctionalRequirements

数据权威来源在已有Java领域服务和MySQL；明确用户/租户边界、代次/版本竞争、事务审计、发布幂等及失败恢复。分页最多100、记忆最多1000、检查用例最多20、正文最多50000字符、数据库事务/查询10秒、远端探针连接1秒/单请求读取3秒。未声明新的吞吐、可用性、RTO/RPO或法律保留期限；不是容量压测结论。

## technicalUnknowns / technologyResearch / technologyDecisions / adr

见 [ARCHITECTURE](../../design/memory-knowledge-operations/ARCHITECTURE.md) U1–U6与ADR。先检查已有JdbcTemplate/Flyway/入库和权限实现，再复用现有MySQL、事务和Vue3。不新增SDK、中间件或向量数据库。设计轨道技术选型/架构/影响门禁在实施前记录PASS。

## featureArchitecture / impactAnalysis

conversation拥有记忆，knowledge拥有运营及发布意图；Python AgentScope侧不复制领域写入。新HTTP DTO语言中立，旧画像DTO和入库/检索接口保留。新增conversation V2、knowledge-ingestion V4；网关运营路径优先进入ingest角色，query/worker禁止运营入口。前端复用原聊天工具区和RAG工作台，沿用凭证/续期/错误客户端。

治理改变新画像激活时机：抽取后先PENDING，确认才召回。记忆旧writer必须在治理启用前退出，不能只凭DDL兼容声称行为兼容。知识发布复用真实job和worker，READY之前不显示已发布。

## implementationPlan / changeSets / implementation

[IMPLEMENTATION_SLICES](../../design/memory-knowledge-operations/IMPLEMENTATION_SLICES.md)为唯一切片表。M1–M5以完整记忆能力批次提交fb9a917（迁移/接口/界面共同构成可用闭环）；K1–K6为知识能力批次。具体实现和操作见 [记忆指南](../../对话与检索/memory-guide.md) 与 [知识运营指南](../../对话与检索/knowledge-operations-guide.md)。不把未验证截图或远程失败扫描算作完成证据。

## testProtection / enterpriseCapabilities

测试先覆盖存储不变量，再覆盖代次竞态、乐观版本、HTTP身份、UI409/202、跨身份旧响应、真实MySQL和实际worker→检索路径。MySQL发现首屏哨兵字符排序与H2不同，已改为首屏不比较哨兵，并以MySQL复验。

记忆所有者行锁保护generation与写入，旧抽取不能跨清空/遗忘回填；运营CAS和审计同事务，发布槽唯一约束阻止重复占用，发布意图先落库后调用外部能力。响应丢失仍恢复原job，禁止换键重建。检索证据绑定当前版本，回滚不倒退文档版本。

## verification / evidence

详见 [TEST_RESULT](TEST_RESULT.md)。阶段日志位于 `/tmp/lc4j-*` 及模块target；验收范围和未验证项按报告记录。H2离线应用链路与真实MySQL事务测试分别报告，不用Mock证明数据库事务正确。

## compatibility

旧API和既有测试保留。新增生产数据不降级为内存；未启用JDBC时新接口503。所有管理接口限制认证上下文和ingest，匿名由已有内部认证filter拒绝。默认不自动发布候选或推生产流量。

## knownRisks / technicalDebt

浏览器CUA不可用/超时，当前版截图验收UNVERIFIED；组件与类型检查不能替代视觉验收。基线main供应链SBOM扫描失败仍为未解决依赖债务，本轮未绕过扫描/分支保护。前端构建仍有既有OIDC混合导入与chunk体积警告。未接真实付费模型、生产对象存储与向量索引做全栈演练。

审计/反馈增长及归档策略需后续运营确定；记忆文本可能仍含用户明确提供的敏感内容，当前不是自动DLP产品。备份中的历史内容与在线物理删除分开治理。知识并发外部发布冲突保留原发布槽供人工核对，不自动取消可能已生效的操作。

## rollbackPlan / nextMilestone

记忆回退先禁用画像写入/召回，保留新增结构，禁止旧writer直接操作治理数据。知识业务回滚用旧内容的新版本发布；代码回退保留V4和已提交任务。后续需要可用浏览器补验桌面/移动端，完成远程检查/交付；实际生产发布与依赖治理按独立授权处理。
