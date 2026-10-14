# 可治理长期记忆与知识运营闭环

2026-10-14；用户已批准继续原计划。M1-PERSIST 已在 main，继续增量实现，Java 版本/依赖债务不在范围。

## 需求与技术未知项

| ID | 问题/现状证据 | 决策与风险 |
|---|---|---|
| U1 | JdbcUserProfileStore 仅追加/清空；旧异步抽取可跨清空回填 | 在所有者锁行增加 generation；对话前读取，落库时锁内校验。停止/清空/遗忘递增 generation；不在模型调用期间持有事务。 |
| U2 | MemoryItem 旧 DTO 只有文本/类型/会话 | 保留旧 DTO；独立治理 DTO 增加稳定 recordId、状态、version、expiresAt、pinned。新抽取为 PENDING，用户显式确认后 ACTIVE；迁移前记录保留 ACTIVE。 |
| U3 | 记忆来源和删除传播缺规则 | 仅存来源会话引用，不复制原始对话；审计只记动作与版本，不存正文；单条遗忘和清空物理删除正文，代次阻止旧抽取回填。停用阻止新抽取和召回。用户可自定有效期，未设置不编造保留期限。 |
| U4 | knowledge 已有 JDBC ingestion job、S3 原文、版本化索引和入库状态机 | 运营权威数据归 knowledge，复用 ingestion datasource/schema。反馈→候选修订→评测证据→审核→持久化入库→READY 后发布；失败仍可恢复，重试使用固定幂等键。 |
| U5 | 发布不是数据库单事务，索引可部分失败 | 运营发布状态先持久化，网络调用不占数据库事务；使用现有入库幂等与状态读取恢复。回滚用旧内容发布新版本，不降低 registry version。 |
| U6 | 原 Python 目录有其他用户未提交文件 | 当前治理写入和权威状态均归 Java 领域服务；优先保持现有语言中立 HTTP 协作，不复制到 Python。必要适配另隔离工作树。 |

## 选型 / ADR

沿用 MySQL 8.4、JdbcTemplate、TransactionTemplate、Flyway、已有知识入库服务和 Vue3 控制台。无需新依赖、服务、队列或向量数据库。另一方案引入独立 memory 产品会增加数据权威来源、迁移和部署成本，本轮不用。SQL 集中在存储类；知识与记忆不跨库写表。

技术资料：[MySQL 锁定读](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)、[Spring 编程式事务](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)。项目已有相同事务实现；锁覆盖检查和变更，数据库原子条件保护版本。成本以复用现有实例为边界，不虚构吞吐/SLA。

## 不变量与兼容

身份从 TenantContext 读取，记忆只归当前用户；知识运营管理员需 ingest scope，普通反馈仅限本人。资源读取按身份范围+ID，越界按404；错误用稳定 code。列表有界游标；写入版本冲突409。无效输入400，未启用503。

新迁移只扩展不修改已执行 V1。治理生效后禁止旧应用继续写画像，否则旧实现不能遵守状态和generation：部署先迁移，再停止旧写入实例后切换新实例；代码回滚先禁用画像写入，不把旧实例直接接回治理数据。知识不替换现有入库/检索契约。

## 范围与门禁

technology_selection=PASS（全部复用现有组件）；feature_architecture=PASS（业务所有权及一致性边界已明确）；impact_analysis=PASS（旧 DTO 保持、新治理状态改变抽取激活条件，需UI和迁移同步）。本结论仅允许实施，不代表验证通过。

真实 MySQL 用独立临时 schema 验证，禁止修改共享业务数据。保留期/备份销毁不做法律保证；在线删除与备份到期清除分开记录。无真实模型、生产切流或付费调用授权，不执行这些操作。

K1–K4 补充：使用knowledge-ingestion V4新增运营反馈、候选、审计和版本发布槽四表；草稿快照为语言中立JSON，不存SDK对象。服务端内容契约评测与发布后真实检索探针分开呈现；不以内容包含检查冒充回答质量。GET无发布副作用，publish/sync/probe由用户显式触发，可跨进程恢复。故障后由原发布者以固定幂等键恢复；不额外引入后台扫描器。
