# Codex Progress

## 任务目标
实现节点级持久恢复：中央契约、Python DAG接管、隔离演练及正常合入推送两仓main；不部署。

## 已完成
- S1：中央V5检查点、专用JWT、父任务行锁/租约epoch/CAS、同事务事件和逻辑删除；v1/v2领取隔离。
- S2：计划（含安全回退）、节点、汇总、评审/重规划持久化；输入身份版本绑定；跨重启预算和deadline；默认关闭。
- S3：Python584项/89.71%，Ruff/format/Mypy/契约/Shadow/离线构建/Compose通过；Java全量1524项/0失败/22条件跳过；隔离MySQL通过。
- 真实跨进程SIGKILL恢复通过：首节点不重做，未知次节点重做，累计4次模型预留，旧epoch被拒；本地恢复16.00秒，不承诺生产RTO。
- 计划、报告、验证和问答同步在docs/delivery/dag-recovery/及docs/delivery/dag-recovery/CENTRAL_CONTRACT.md；之前进度保留PREVIOUS_PROGRESS.md。

- Python源码c5b6cef、Java源码555c1d2及门禁修复8f50ad6已正常合入推送两仓main，未强推；并发ETL提交完整保留。
- Python CI38538810884、Java Cutover38540055512及Tax AI通过；Java供应链38540055244在依赖SBOM扫描失败。详情见DELIVERY_RESULT.md。

## 已修改文件
- Python application/dag_recovery及dag/planning/async_task、domain检查点协议、worker/HTTP/模型预算适配、配置和契约。
- 对应行为/契约测试、跨进程演练脚本、README/指南/问答/交付文档。
- Java改动位于独立工作树：~/.local/share/git-worktrees/langchain4j-platform/dag-node-recovery；原目录知识ETL改动未触碰。

## 未完成
- 本次实现、验证、Git交付已完成；未执行生产启用/部署。Java依赖SBOM扫描未通过，后续依赖整改单独处理，不宣称生产发布就绪。

## 当前问题
- 无实现阻塞。PyPI临时超时后离线构建成功；Code Hygiene仅有formatter自动发现限制，无阻断。
- 工具节点及依赖链跨接管重做；写节点恢复、真实付费模型双跑、生产启用和灾备承诺不在本次交付。
- 保留历史工作树、私有配置、测试缓存/日志；未清理用户数据或部署。

## 下一步建议
1. 按docs/delivery/dag-recovery/CENTRAL_CONTRACT.md规划后续启用；生产发布前单独处理Java依赖安全扫描，不重复实施本次功能。

## 恢复 Prompt
本轮已交付。读取docs/delivery/dag-recovery/DELIVERY_RESULT.md核对源码、CI和边界；不要重复实现、部署或清理历史工作树/忽略文件。恢复默认关闭，Java依赖安全扫描仍未通过。

---
以下为合入的知识ETL任务及历史进度，保留其交付事实：

# Codex Progress

## 任务目标

实现ETL数据接入工作台与持久化同步运行记录，验证后正常合并推送main，不部署。

## 已完成

- S1/S2：V8新增来源控制与运行记录；同租户显式operator-user-ids、暂停/恢复控制CAS、手动轮询和原任务重试、运行历史分页与逐条结果。
- S3：RAG数据接入工作台接真实接口，身份/来源切换取消与清空，冲突恢复、失败明细、桌面与390px容器验证。
- 最终验证：后端完整回归569项/0失败/0错误/9条件跳过；最终聚焦41项；真实MySQL28项；前端585项及最终RAG32项；类型/构建与Chrome桌面、390px容器检查通过。

## 已修改文件

- knowledge-service/ingest/sync及对应测试、platform-protocol的KnowledgeSyncOperations、platform-audit枚举。
- database-migrations的V8、deploy配置示例与隔离MySQL验证脚本。
- 前端knowledgeSync API、KnowledgeSyncPanel与测试、RagWorkspaceView挂载点。
- HTTP接入指南、docs/delivery/knowledge-etl-workbench/、本文件。

## 未完成

- 开发、测试与源码交付均已完成。c0d3091（后端）和fed773e（前端）已正常合并推送origin/main；本次仅同步最终交付记录。

## 当前问题

- 实际业务源仍未配置且功能默认关闭；浏览器用隔离测试夹具，后端用隔离MySQL，不等于真实业务接口已联调。
- 升级前关闭全部旧协调器；旧版本不理解暂停/运行记录，不能混跑。运行历史尚无自动清理，需按实际容量与保留政策治理。

## 下一步建议

1. 本轮已交付；入口为RAG工作台的数据接入分区，证据见docs/delivery/knowledge-etl-workbench/TEST_RESULT.md。
2. 实际接源前配置来源和运营用户、按指南完成V8迁移及协调器升级；部署另行授权。

## 恢复 Prompt

本轮工作台与运行记录已合入推送main，读取docs/delivery/knowledge-etl-workbench/DELIVERY_RESULT.md核对事实。不要重复实现或部署，不清理其他任务工作树。

---
以下为历史记录：

# Codex Progress

## 任务目标

完成用户图片所示知识接入ETL：HTTP产品说明/FAQ增量采集、质量校验、新增更新、删除传播、来源追溯和可靠恢复，并正常合并推送main。

## 已完成

- S1：HTTP连接器、受控来源配置、MySQL检查点与记录映射、定时协调、状态/手动轮询/追溯API，复用既有入库worker。
- S2：文档Registry原子墓碑、撤权恢复、防迟到worker复活、删除后新生命周期ID；原文和历史投影保留，不冒充物理擦除。
- 最终模块及上游回归558项/0失败/0错误/9条件跳过；真实MySQL18项、Redis与配置4项通过。
- 接入文档、默认关闭配置模板、隔离MySQL验证脚本和证据齐备。见docs/delivery/knowledge-http-sync/TEST_RESULT.md。

## 已修改文件

- knowledge-service的ingest/sync、DocumentRegistry实现与DocumentInfo、相应单元/HTTP/集成测试。
- platform-protocol的KnowledgeSourceFeed；knowledge-ingestion的V7迁移。
- deploy/knowledge-http-sync.example.yml与test-knowledge-http-sync-mysql.py。
- docs/对话与检索/http-knowledge-sync-guide.md、knowledge-operations-guide.md和docs/delivery/knowledge-http-sync/。
- CODEX_PROGRESS.md。

## 未完成

- 功能与验证已完成，源码提交c78c449已推送任务分支并正常快进合入远程main；当前仅同步最终交付记录。未执行部署。

## 当前问题

- 无代码或验证阻塞。实际HTTP源地址/鉴权/样例未提供，功能默认关闭；需按文档配置后才能连接实际业务系统。
- 全部knowledge角色需先升级再开启；生成墓碑后不可回退到不识别墓碑的旧worker。
- 既有工作树、忽略配置、业务数据与服务保持原状。

## 下一步建议

1. 本次实现已交付；源码、验证与权限边界见docs/delivery/knowledge-http-sync/DELIVERY_RESULT.md。
2. 后续按真实接口契约配置来源，部署须另行授权。

## 恢复 Prompt

本轮代码已合入推送main，读取docs/delivery/knowledge-http-sync/DELIVERY_RESULT.md核对交付事实；后续按实际HTTP接口配置来源。不要重复实现、部署或清理已有业务数据/工作树。

---
以下为历史记录：

# Codex Progress

## 任务目标

重做 RAG 工作台的「知识运营闭环」与「检索质量评测」界面，使其符合项目现有主题、控件和布局，并更新本地前端。

## 已完成

- 使用现有 WorkbenchSection、InfoNote、form-control、btn 与主题 token，统一卡片、表单、操作层级、列表、指标和状态提示。
- 知识运营桌面双栏，窄屏单列；评测提供流程引导、分组编辑、样本卡片与六项指标展示。
- 保留既有 API、权限、租户切换、取消确认和失败恢复逻辑。
- RAG 27 项测试、vue-tsc、前端 Docker/Vite 构建通过；Chrome 实际桌面、编辑表单、390×844 窄屏检查通过。
- 全量前端 580 项测试通过；源码 4362e16 已正常合入推送 main。本地前端固定镜像 4362e16 运行，HTTP 200、浏览器刷新确认新页面；其余服务未重启。

## 已修改文件

- capability-showcase-frontend/src/modules/rag/KnowledgeOperationsPanel.vue
- capability-showcase-frontend/src/modules/rag/RetrievalQualityPanel.vue
- capability-showcase-frontend/src/modules/rag/rag-governance.css
- docs/delivery/rag-operations-design/DELIVERY_RESULT.md
- CODEX_PROGRESS.md

## 未完成

本次界面与本地更新目标已完成，无待实现事项。远程前端 CI 38513364785 SUCCESS；通用 Java 供应链运行不计为本次 UI 验收。

## 当前问题

本次无阻塞。全局移动端顶栏存在既有拥挤，本次范围仅上述两块；未扩大修改导航壳层。历史 Java 供应链问题仍按前一任务记录保留。

## 下一步建议

1. 本轮已完成，按后续用户反馈继续局部优化。
2. 使用 http://localhost:8093/m/rag；截图与运行记录保留在 deploy/.dev-infra-state/rag-design-release/evidence/。

## 恢复 Prompt

本轮已完成。读取本文件与 docs/delivery/rag-operations-design/DELIVERY_RESULT.md 核对交付；不重复实现或重启后端，保留已有工作树、数据与忽略配置。

---
以下为历史记录：

# Codex Progress

## 任务目标

完成发布复查版本校验、GC公平推进和RAG检索质量闭环，并按用户授权在本地Docker发布启动。用户确认只覆盖检索质量/延迟，并确认修复发布独立AgentScope仓库。

## 已完成

- S1发布复查版本12cf1a2；S2公平GC与Redis检查点160f6bb；S3/S4评测后端及运营前端cfa1d5b；Docker真实组件验证脚本bf33151。均已正常合入推送main。
- Maven1483项、前端580项、类型/构建、真实MySQL/Redis、配置门禁通过。
- Python探针支持独立Java管理地址22ba8db，JavaCompose适配f89cee0；两仓正常合入推送main。Python全量530项、覆盖率89.38%、类型/格式/契约/Shadow通过；源码远程CI38511738475含镜像扫描SUCCESS。
- 固定Java应用镜像cfa1d5b与Python22ba8db已发布本地Docker。整栈up --wait退出0，14个Javareadiness和AgentScope均UP；17个常驻容器运行，9个迁移exit0；前端8093返回200，网关18080匿名评测401，认证质量列表200。
- 真实MySQL/S3/Redis/Qdrant/ES检索评测：无基线→建立基线→PASS/200→仅移除测试文档→REGRESSION/422。
- 当前本地运行验收PASS；详细证据docs/delivery/rag-quality-loop/{TEST_RESULT,DEPLOYMENT_RESULT,RUNTIME_EVIDENCE.json}。

## 已修改文件

三项功能提交、Docker冒烟脚本、AgentScope配置/探针/测试和两仓部署文档。Python源码22ba8db，Java应用源码cfa1d5b，JavaCompose源码f89cee0；后续文档提交以Git HEAD为准。

## 未完成

本次功能和本地部署目标已完成，无待实现事项。浏览器工具超时，视觉验收UNVERIFIED；Java历史供应链扫描失败未整改，不宣称生产发布就绪。

## 当前问题

- Java源码cfa1d5b远程功能CI通过但SBOM失败；追加Compose源码f89cee0的Cutover/Tax CI通过，供应链38511783086仍在SBOM失败；Python修复源码CI及镜像扫描通过，两者不可混同。
- 保留async-task恢复表RECOVERY_DISPATCH_LOCK_20261014和私有备份，未更改旧任务数据/已执行迁移。最终迁移全部成功。
- 本地凭据、固定镜像包装脚本与恢复证据在deploy/.dev-infra-state/rag-quality-release/及忽略配置文件中；旧镜像保留。
- 既有worktree/用户未跟踪文件未清理；冒烟容器停止，测试库/S3原文/结果保留供核查。

## 下一步建议

直接使用http://localhost:8093；网关http://localhost:18080。运维可用deploy/.dev-infra-state/rag-quality-release/compose.sh查看固定版本栈；独立标准AgentScope local标签已更新为已验证镜像。后续真实标注集运营、视觉验收和Java依赖整改分别处理，不重做本次实现或扩大到生产部署。

## 恢复 Prompt

本轮已完成。读取本文件和docs/delivery/rag-quality-loop/核对实际状态；保留现有服务、数据、工作树和供应链失败事实，不重复部署或重新实现已通过功能。

---
以下为历史记录：

# Codex Progress

## 任务目标

langchain4j-platform 全部自有关系业务持久化切换 MyBatis-Plus，删除统一使用 DELETED：0正常、1已删除。Python agentscope-platform 不属于此次 Java 迁移。

## 已完成

- MP0–MP3：共享事务装配和22个类型化XML Mapper，检查点92eb562/fdcbf79/54793f1/97106e6。
- MP5：8个新Flyway迁移覆盖33表，正常查询/更新过滤，删除改条件UPDATE；实体/成员关系显式重建版本不回退，工作流已删除幂等键不可复用。
- 已验证墓碑存留、租户隔离、旧CAS拒绝、迟到回调不复活、Outbox查询、任务逻辑级联、事件序号/去重。
- 全Maven1470项，0失败/0错误/17跳过；Flowable profile含依赖209项，0失败/4条件跳过，其中引擎专项10项全通过；全量打包通过。
- 真实MySQL：认证3、订单4、入库4、图谱4、记忆17、知识11、调度6、Inbox10、工作流回执/并发/逻辑删除各1，均通过。记忆/知识应用账号无DELETE权限。
- 22XML边界、5配置门禁、diff检查通过；Code Hygiene无阻断，限制为无formatter。新增MyBatis坐标OSV无匹配条目，不代表全仓漏洞扫描通过。
- 临时数据库/账号已清理，MySQL恢复原停止状态。未部署或修改现有业务数据库，未创建worktree。
- 报告与可追溯证据：docs/delivery/mybatis-plus-migration/。

## 已修改文件

Java八个持久化模块及platform-persistence、父/模块POM、8个增量迁移、行为测试、deploy验证脚本、CI与相关指南。完整源码指纹见VERIFICATION_EVIDENCE.json。

## 未完成

功能与验证已提交并正常推送远程main（03944b1），逻辑删除提交2db7a04。交付记录同步后切回main。远程功能CI全部通过，供应链扫描失败（基线同一步也失败），不扩大本任务到历史漏洞债务修复；详情见DELIVERY_RESULT.md。

## 当前问题

- 逻辑删除保留正文并持续占用空间，不表示物理擦除；产生墓碑后不能回退到忽略DELETED的旧代码。
- Flowable内部表、Flyway历史、NL2SQL/Doris/Redis/ES等专用适配器不套自有业务表规则。
- 无生产DDL/容量测试；历史供应链漏洞债务未扩大修复。
- 保留既有三个worktree及忽略的本地配置、依赖和构建缓存，没有清理授权，不删除。

## 下一步建议

无待实现功能；main已发布，部署按新增迁移与兼容边界执行，不隐含生产部署授权。

## 恢复 Prompt

读取docs/delivery/mybatis-plus-migration/和Git事实，完成未完成的Git收尾，不重做已验证实现，不要求逐批确认。

---

以下为之前任务记录：

# Codex Progress

## 任务目标

按批准规划实现可治理长期记忆与知识运营闭环；不升级Java/依赖，不部署生产。

## 已完成

- 记忆治理、候选确认、过期/固定、遗忘、停用、审计与旧抽取防回填：fb9a917。
- 知识反馈→草稿→内容契约检查→审核→真实入库→检索复查→新版本回滚：b305433。
- RAG运营界面与异步/冲突/身份隔离：bbeb5d2。
- 任务分支和main已正常推送；产品源码97205f2已用远程refs核验，无强推/部署。
- 全Maven1462 tests/0fail/0error/17skip；前端576tests、类型/构建；MySQL记忆17项、知识11项；五项配置门禁；Code Hygiene无阻塞。

## 已修改文件

conversation治理与V2迁移、knowledge operations与V4迁移、前端chat/rag、网关路由/拆分配置、对应测试脚本和指南。详细证据见docs/delivery/memory-knowledge-operations/。

## 未完成

- 最新远程CI结果尚未全部完成；Git源码已正常合并推送main（97205f2），最终文档同步后核对HEAD。
- 浏览器工具不可用，桌面/窄屏视觉验收未验证；不能用组件测试冒充截图验收。
- 远程基线依赖SBOM失败保留；本轮不处理依赖债务，不宣称生产可发布。

## 当前问题

Python原目录历史dirty未动。临时测试库/用户已清理、MySQL容器恢复停止、Vite预览已停止。既有工作树和本地环境/验证日志保留。

## 下一步建议

核对远程CI结果；在可用浏览器环境补验视觉。生产发布和依赖整改均不在本轮范围。

## 恢复 Prompt

读取本文件及docs/delivery/memory-knowledge-operations/PROGRESS_STATE.md，核对当前Git事实继续剩余交付，不重做已通过实现。保留Python用户改动和历史工作树，不强推、不部署。

---

以下为历史交付记录：

# Codex Progress

## 任务目标

提交原目录既有整改并合入长期画像 MySQL 持久化，推送远程 main，原项目目录切换到 main。

## 已完成

- 原有 23 个变更文件和 3 个整改文档按功能提交；画像分支无冲突合入。
- 集成代码 344245f 已正常推送远程 main；本轮交付文档随后同步。
- 合并后全 reactor 1441 tests / 0 failures / 0 errors / 17 skipped。
- 真实 Redis 4 项补跑通过；五项部署配置门禁通过；Redis 已恢复原停止状态。
- 详细提交、授权、验证和保留目录见 docs/delivery/pending-reliability-delivery/DELIVERY_RESULT.md。

## 已修改文件

- edge-gateway、platform-metering、conversation-service 的整改与测试。
- 门户 nginx、部署门禁、历史整改记录。
- 已合入的画像持久化存储、数据库迁移、运行配置与文档。

## 未完成

- 无待实现的本次交付功能；最新 main 远程 CI 结果需以实际运行记录为准。
- Java 依赖漏洞整改、完整记忆治理和知识运营属于后续独立任务。

## 当前问题

- 已知供应链 SBOM 扫描失败仍保留；用户在获知该事实后明确要求本次合并推送，不代表扫描通过。
- 旧内存数据无自动迁移；清空后的旧抽取防回填保护尚属后续治理。
- 未部署生产，未清理本地环境、忽略文件或独立工作树。

## 下一步建议

1. 以原目录 main 为后续工作入口，核对远程 CI 状态。
2. 按已排定优先级继续长期记忆治理和知识运营；不自动扩展版本债务整改。

## 恢复 Prompt

读取本文件及 docs/delivery/pending-reliability-delivery/DELIVERY_RESULT.md。
本轮代码已推送 main；勿重复提交历史整改或重新实现画像持久化。保留扫描失败及未部署事实。

---

以下为历史任务进度：

# Codex Progress

## 任务目标

按Claude Engineering Skill修复Java/AgentScope七类可靠性缺口, 完成验证与正常Git交付。
规范状态: langchain4j-platform/docs/delivery/cross-runtime-reliability/DELIVERY_STATUS.md。
两仓任务分支fix/cross-runtime-reliability; 用户持续授权正常合并/push main, 不含生产部署。

## 已完成

- S1–S7全部实现并按逻辑单元提交/推送任务分支; 详见DELIVERY_REPORT和各切片文档。
- 干净Java1406tests/0fail/0error/13skip, Python518pass/89.27%; 真实MySQL/Redis/独立worker/SSE/TCP验证PASS。
- 两仓Code Hygiene无blocking; 主Agent对抗自复审与QA报告已准备。
- Python cryptography50.0.2/PyJWT2.15.1/urllib3 2.8.0审计0漏洞, runtime PCRE2补丁后远程CI36979153821 SUCCESS。
- Java固定IAM SDK源码929e9ca安装, CI补ripgrep/隔离供应方目录; 固定契约producer6f43ddf。
- S8: Python main1a7ceec先推送, Java main8bf6ad6后推送, git ls-remote和祖先关系核对成功; 最终文档HEAD以main为准。
- 初始dirty和intent-to-add保留; Java19/Python6文件重建0偏差, 第三IAM仓只读。

## 已修改文件

- RAG/inbox/预算RPC和模型适配/退款回执/只读调度与worker/流式候选和取消/固定契约。
- 相关迁移、聚焦测试、Compose overlays、CI、文档与进度; 完整路径见任务分支diff。
- 原有用户贡献均未提交, 不以当前工作树干净作为验收要求。

## 未完成

- 当前七项修复和S8正常main合并交付已完成, 无待实现事项。
- Java85条既有版本记录仍FAIL, 用户2026-10-02已接受本次源码合并例外; 后续整改另行安排。
- 合并后自动CI仍在运行, 不宣称最新main CI全绿; 运行链接见DELIVERY_REPORT。

## 当前问题

- 已披露版本扫描仅作为本次源码合并例外; CI保持原失败结论和门禁, Java镜像扫描尚未进入。
- 原Python未提交新增测试有1个旧decode预期, 新PyJWT更早拒绝非规范JWT; 保留该测试, 不放宽安全验证。
- 生产runbook仍NO-GO, 不伪造真实模型、容量、恢复或历史ESS成功。

## 下一步建议

1. 本轮已交付; 如需查看后续CI结果, 读取DELIVERY_REPORT中的Actions链接及当前main SHA。
2. 未来框架/客户端升级复用SECURITY_MIGRATION_PROPOSAL, 本轮不实施。
3. 原有dirty任务贡献与verification worktree保留, 不清理/丢弃用户内容, 不执行生产部署。

## 恢复 Prompt

请读取CODEX_PROGRESS与规范DELIVERY_STATUS/DELIVERY_REPORT; 当前七项修复和main交付已完成。
保护既有dirty贡献; 不重复7项分析, 不降低CI门禁; 本轮已获版本扫描合并例外, 不执行生产部署。

---

以下为历史任务记录，授权与当前状态以本文件上半部分和规范状态为准。

# Codex Progress

## 任务目标

Docker Desktop 虚拟盘清空后，恢复共享 `dev-infra`，并把能力门户 http://127.0.0.1:5274/ 里每个项目的 Docker 服务**逐个**拉起。内存不够时停掉上一批项目应用，只保留共享中间件与门户。随后按 `seed-mock` 给**除 langchain4j 外**的门户项目灌测试/演示数据（写入真实库表，不写死页面）。不要重跑 langchain4j `manage.py backup`。不提交、不推送。

## 已完成

- **项目能力/深度分析落盘（2026-09-16）**：只读扫描后写入 `.engineering/analysis/`（非产品代码）。四件套 + 能力报告：`PROJECT_DEEP_ANALYSIS.md`、`ARCHITECTURE.md`、`BUSINESS_FLOWS.md`、`EVIDENCE_INDEX.md`、`PROJECT_CAPABILITY_REPORT.md`。本机 `mvn test` 未跑（UNVERIFIED）。未改业务代码；分析产物已随本轮一起提交并推送。
- **能力演进探索落盘（2026-09-16，跨仓口径修订）**：按「本仓 + `agentscope-platform` 是同一产品两半、Agent 编排已迁出」重写 `.engineering/exploration/` 五件套（`CAPABILITY_MAP` / `CAPABILITY_GAPS` / `OPPORTUNITIES` / `EVOLUTION_ROADMAP` / `EVIDENCE_INDEX`），共 459 行、31 条证据。结论：P0 是①跨语言 boundary 契约在 Java 侧无校验且 cutover CI 无兼容门禁、②`/agent/**` 默认走 Python 后 Java 租户 token 预算/成本计量被绕过；P1 是退役门禁的两个 Java 侧机器检查与统一 Prometheus/告警面。7 项列入 Not Recommended Now（含现在删 `agent-service`、Chat 迁进 orchestrator、通用缓存）。只读产出，未改产品代码/配置/测试/依赖，未跑 `mvn test`（UNVERIFIED）；探索产物已随本轮一起提交并推送。
- **O1 跨语言 boundary 契约兼容门禁（2026-09-16，跨两仓）**：闭合「Agent 编排迁出后 Java 消费侧无契约护栏」的 P0 缺口。
  - agentscope 侧（producer）：`scripts/export_contracts.py` 新增 `contracts/manifest.json`，对 40 个 JSON 契约逐个固定 sha256（含手写 boundary schema），并纳入既有 `--check`；新增 2 个测试，其中一个证明手写 schema 漂移也会被抓到。
  - langchain4j 侧（consumer）：`deploy/sync-agent-contracts.sh`（verify/`--write`，无上游 worktree 时 skip）把 Java 真正消费/生产的 18 个契约 vendored 到 `platform-protocol/src/main/resources/contracts/agentscope/` 并固定 digest；新增 `AgentScopeContractManifestTest`(3) + `AgentScopeContractConformanceTest`(10) 做双向校验（生产方向 = Jackson 序列化过 schema；消费方向 = Java 读取字段仍被契约发布，允许只读子集）；root pom 新增 test-scoped `com.networknt:json-schema-validator` 1.5.1；`agentscope-cutover-ci.yml` 新增 `Verify agentscope contract compatibility` 步骤与 path filter。
  - 门禁有效性已用 3 次注入漂移验证（改 digest / 改 `schema_version` const / 把 `finalAnswer` 改名），三项分别被对应测试拦住并给出可定位报错，随后恢复复验为绿。
  - 验证：`mvn -B -pl interop-service,eval-service,conversation-service -am test` BUILD SUCCESS（platform-protocol 13 项全绿）；`deploy/test-{supply-chain,production-cutover,runtime-hardening,database-migration}-config.sh` 四项 PASS；agentscope `ruff` PASS、`mypy src` 87 files Success、`pytest` 472 项全绿。
  - **残留缺口（已写入 `docs/架构边界/ai-runtime-boundaries.md`）**：两仓 CI 都访问不到对方仓库，「本仓副本 == 上游最新」只在本地/跨仓联动时被证明；彻底闭合需把契约作为版本化制品发布给 Java 构建解析，属后续变更。
  - 已提交（`4cb82fc`）并推送。
- **F1 `mvn package` 反应堆修复（2026-09-16）**：`database-migrations` 的 Spring Boot `repackage` 默认让可执行 fat jar 顶替普通 jar，导致 `platform-eventbus` 的迁移测试在 `package` 阶段找不到 `com.lrj.platform.migrations`（`mvn test` 不跑 package，所以一直看不见）。改为 `<classifier>exec</classifier>` 让两种 jar 并存，同步 `database-migrations/Dockerfile` 与 `deploy/dev-infra/smoke.py` 的 jar 名；`deploy/test-supply-chain-config.sh` 新增两条静态门禁：Dockerfile 里 COPY 的 jar 名必须等于该模块按 classifier 真实产出的名字，且必须能被 `supply-chain.yml` 的上传通配符匹配到。后者当场发现连带缺口——CI 上传用 `*-0.1.0-SNAPSHOT.jar` 匹配不到新的 `-exec.jar`，而 `image-scan`/`release-images` 唯一的 jar 来源就是这个 artifact，已一并把上传与两处 attest 的通配符改成 `*-0.1.0-SNAPSHOT*.jar`。门禁按「退回旧通配符」注入验证会红。已提交（`05e06d0`）并推送；`mvn -o -DskipTests package` 全反应堆 BUILD SUCCESS。
- **F2 agentscope `mypy` 裸跑（2026-09-16）**：补 `src/agentscope_platform/py.typed`，`uv run mypy`（不带 `src`）不再因缺 marker 退出码 2。改动在 agentscope 仓，已提交（`bc1b854`）并推送。
- **F5 Java Agent 退役门禁的两个机器检查（2026-09-16）**：把门禁第 4、5 条从「人工翻库/翻日志」变成可判定。
  - `InteropToolDispatcher` 改为 fail-closed：agent 类工具只在 AgentScope live discovery 宣告了该能力时才代理，未宣告返回明确原因，PING 等本地工具不受影响。新增 `InteropToolDispatcherDiscoveryTest`（宣告则代理 / 未宣告拒绝 / discovery 不可达时全拒 / PING 始终可用 / 宣告了但 interop 不能代理的仍拒）。
  - 新增只读 `GET /async/drain-inventory`：按 kind + 状态 + 租约持有者盘点未完结 Agent 任务，内存与 JDBC 两套实现（JDBC 走 SQL 聚合，保证各副本看同一份中心队列），只报归属事实、由门禁脚本裁决哪个 leaseOwnerId 属于哪个运行时。新增 `AsyncTaskDrainInventoryTest`(5)。
  - 文档：`docs/架构边界/java-agent-retirement-gate.md` 补两条检查的执行方式，`docs/参考/api-reference.md` 与 `docs/平台工程/长任务处理指南.md` 收录新端点。
  - 已提交并推送：F5 拆两个提交（`b300c92` interop fail-closed、`40e5d47` drain inventory）。
- **F6 统一 Prometheus 指标面与关键告警（2026-09-16）**：此前 16 个 Java 服务里多数在 exposure 写了 `prometheus` 却没有 registry（端点 404），且 actuator 与业务接口同端口、被内部 JWT filter 保护——内部 JWT 只活 5 分钟，Prometheus 与 kubelet 都拿不到静态凭据，指标面事实上不可用。
  - 依赖：`micrometer-registry-prometheus` 收进 `platform-observability`（`runtime` 且**不加 `optional`**，否则不传递），`edge-gateway` / `config-server` 不依赖该库故各自直接声明。
  - 端口：按用户选定的方案 A，全部 16 个服务 actuator 迁到独立 management 端口（业务端口 +1000，`MANAGEMENT_PORT`）。Spring 的 management 子上下文不继承业务端口的租户 filter，运维面因此免鉴权、业务面鉴权不变；AgentScope 是单端口应用，`/metrics` 与既有 `/health`、`/readiness` 取同一姿态（去掉 `RunContextDependency`）。
  - 抓取与告警：新增 `deploy/prometheus/prometheus.yml`（14 个默认拓扑 Java 服务 + AgentScope，profile 化的 agent/eval 故意不列）与 `alerts.yml`（8 条）。规则只引用代码里确实注册过的序列，逐条核对过 Java Micrometer 名与 Python OTel 渲染名（渲染器补 `_total`、不追加 unit 后缀）；刻意不设延迟/TP99 门限——本仓没有正式性能验收目标。
  - 运行面：compose 加 `observability` profile 的 prometheus（UI `:19090`）并发布 smoke 脚本要用的 mgmt 端口；Helm `platform-lib` 渲染 `mgmt` 容器端口与 Service 端口、探针改打 `mgmt`，非 Spring 服务用 `managementPort: 0` 回落到 `http`（`ternary` + `hasKey`，因为 sprig 的 `default` 把显式 0 当空值）；`deploy/dev-infra/smoke.py` 与 5 个 `deploy/smoke-*.sh` 的健康等待改用 management 端口。
  - 防漂移：新增 `deploy/test-observability-config.sh`（少 `prometheus` / 少 management 端口 / 端口不等于业务口+1000 / registry 不在 classpath / 漏抓取目标 / 把 profile 服务写进默认抓取 / 告警缺 severity·summary·description / Helm values 端口与 `server.port` 不一致，共 8 类），接进 `supply-chain.yml`；8 项注入回归全部被抓到，还原后为绿。
  - 文档：`docs/平台工程/observability-guide.md` 重写 3.1/3.3/3.4/4/5/6/7 并新增 3.5 告警小节（删掉「registry 未引入、抓取会 404」的过时结论），`docs/参考/operations.md` 第 15 节、`docs/平台工程/cost-attribution.md`、`deploy/helm/README.md`、`docs/README.md` 同步端口口径。
  - 验证：`promtool check config` 通过（1 rule file / 8 rules，需挂到 `/etc/prometheus`）；`helm template` 渲染确认 14 个 Spring 服务 mgmt=业务+1000 且探针在 mgmt、AgentScope 无 mgmt 且探针在 http；`mvn -B -pl platform-observability,platform-security,async-task-service,knowledge-service,edge-gateway,config-server -am -DskipITs test` 全绿（platform-security 62 / platform-observability 7 / knowledge-service 303 / async-task-service 68 / config-server 1 / edge-gateway 58）。
  - 已提交（`4ab1b2a`）并推送。
- **F7 图三元组版本 provenance，解锁 query 角色图检索（2026-09-16）**：图命中此前不带 `docId`/`version`，两个后果——过期版本的三元组会被继续召回，且 enforce 档因为无 `docId` 把图命中整类 fail-closed 丢弃；`query` 角色索性在启动校验里禁掉图检索。
  - 新增 `GraphSourceId` 作为 `<docId>/v<version>/<name>#<index>` 的唯一定义：写入（`GraphIngestor`）、版本 GC 的前缀删除（`KnowledgeVersionGarbageCollector`）、查询侧还原（`GraphRetrievalSource`）此前各自拼/各自拆同一个字符串，任一侧改格式另两侧不会发现。`docId` 是 16 hex、`version` 是 `v<数字>`，故按前两个 `/` 切分即可，文件名含 `/` 也无歧义。
  - `GraphRetrievalSource` 把还原出的 `docId`/`version` 放进命中，图命中因此与向量/ES 命中走同两道过滤：按 Registry 当前版本丢弃过期命中，以及 enforce 档的文档级判权。融合去重键仍是三元组自身 id，不会被同文档的 chunk 命中吞掉。
  - 新增 `app.rag.graph.require-provenance`（`RAG_GRAPH_REQUIRE_PROVENANCE`，默认 `false`）：为 true 时丢弃还原不出版本归属的历史三元组——它们既证不明新鲜度也证不明可读性，放行等于绕过上述两道过滤。`combined` 保持默认 false（召回口径不变），`KnowledgeRuntimeBoundaryConfig` 把 query 角色的「禁用图检索」改成「开图检索必须 require-provenance=true」。历史数据不需要迁移脚本：旧三元组在该开关下不参与查询，文档下次入库即带 provenance，旧版本由 GC 按前缀清理。
  - 部署：`docker-compose.knowledge-split.yml` 的 `knowledge-query` 增加 `RAG_GRAPH_REQUIRE_PROVENANCE=true` 并把 `RAG_GRAPH_ENABLED` 变成可覆盖（默认仍关，属容量决定）；Helm `knowledge-query` 同步；production overlay 注明关闭是容量决定而非能力限制。
  - 验证：新增 `GraphSourceIdTest`(9)、`GraphRetrievalSourceTest`(5)、`KnowledgeGraphCommittedVersionTest`(3)，`KnowledgeQueryServiceAuthzTest` 补一条「带 provenance 的图命中在 enforce 下按文档判权，可读留、不可读过滤」，`KnowledgeRuntimeBoundaryConfigTest` 改为断言新规则；`mvn -B -o -pl knowledge-service test` 319 项全绿（4 skip）。compose config、`helm template`、`deploy/test-{observability,production-cutover}-config.sh` 均 PASS。
  - 文档：`docs/架构边界/knowledge-runtime-split.md` 新增「图三元组的版本 provenance」小节并改写 query 角色约束，`docs/对话与检索/rag-guide.md` 新增版本 provenance 与判权小节，`docs/参考/operations.md` 环境变量表与 `docs/对话与检索/可靠入库接入.md` 生产要求同步。
  - 已提交（`96298f6`）并推送。
- **F10 跨域统一幂等 / 限流接入范式（2026-09-16）**：O7 的触发条件已经真实发生——钉钉与飞书两个入站桥各自复制了一份进程内 `ConcurrentHashMap` 去重（两处 Javadoc 都写着「生产多副本需换 Redis/JDBC」），而同一个 `channel-service` 里两个 Kafka listener 早已在用 `ProcessedEventStore`。
  - **幂等**：新增 `InboundIdempotency`（platform-eventbus），把入站回调的正确顺序收在一处——`markProcessed` 的原子返回值**抢占** → 交渠道线程池异步处理 → 失败或线程池拒收时 `releaseClaim` **归还**。入站不能照抄 Kafka listener 的「先查后标记」：控制器 3s 内必须 ack、处理是异步的，两步之间的窗口会让并发重投在多副本上各处理一次。原先「先 put 进 map 再处理」更糟：处理抛异常时消息被永久标记为已处理，渠道重投也进不来 = 静默丢客服提问；两个 bridge 的 `process()` 因此改为向上抛异常。
  - `ProcessedEventStore` 新增 `releaseClaim`（内存 remove / JDBC DELETE），`InMemoryProcessedEventStore` 改为**有界窗口**（`LinkedHashMap` + `removeEldestEntry`，默认 10 万条 FIFO 淘汰）——入站回调持续产生新 id，原来的无界 map 等于内存泄漏。key 形如 `inbound:<source>:<messageId>`，前缀避免钉钉 msgId 与飞书 messageId 在同一张 `PROCESSED_EVENT` 表里互撞。多副本仍需 `CHANNEL_DEDUP_STORE=jdbc`（Helm values 已注明）。
  - **限流**：`EdgeRateLimitFilter` 此前对 `EdgeOpenPaths` 整体 bypass，于是 `/auth/login|register|refresh` 与 `/channel/{feishu,dingtalk}/events` 是公网上唯一无速率保护的面，而每条渠道事件都会触发下游检索 + LLM 花费。改为按**客户端 IP** 限桶：`auth=30`、`channel-callback=600`（`RATE_LIMIT_AUTH_QPM` / `RATE_LIMIT_CHANNEL_CALLBACK_QPM`），`/actuator/**`、`/health`、`/.well-known/**` 仍不限（限流探针会误伤存活检查）。默认**不信任** `X-Forwarded-For`（可伪造 = 每请求换个假 IP 就绕开），只有边缘前面确有会覆写该头的可信 LB 时才设 `RATE_LIMIT_CLIENT_IP_HEADER`。
  - **防漂移用结构而不是脚本**：`EdgeOpenPaths` 从一串 `||` 改成「路径 → 限流 family」的 `Map.of` 表 + 探针前缀白名单。`Map.of` 不接受 null value，因此「新增了免鉴权路径却忘了给限流」在结构上不可能发生。
  - 验证：新增 `InboundIdempotencyTest`(7)、`EdgeRateLimitFilterTest`(10)，`ProcessedEventStoreTest` +3（有界窗口 / 两种实现的归还语义），`EdgeOpenPathsTest` +2，两个 bridge test 各 +2（多副本共享 store 去重、处理失败后重投能重来）。`mvn -B -o -pl platform-security,platform-eventbus,edge-gateway,channel-service,auth-service -am test` 全绿（platform-security 62 / platform-eventbus 20 / auth-service 95 / channel-service 69 / edge-gateway 70）；`helm template`、`docker compose config`、`deploy/test-observability-config.sh` 均 PASS。
  - 文档：`docs/平台工程/eventbus-guide.md` 新增 2.2.1「入站回调的幂等」并补内存窗口口径，`docs/参考/operations.md` 网关限流一节补免鉴权 family 表与 XFF 说明，`docs/互操作渠道/dingtalk-guide.md` 改写去重与白名单段，`docs/参考/架构文档.md`、`docs/参考/api-reference.md` 同步。
  - 已提交（`49202ac`）并推送。
- **整改轮 R1–R5（2026-09-25）**：按 `.engineering/remediation/REMEDIATION_BACKLOG.md` 验证既有发现后执行 5 个切片（详见同目录 `REMEDIATION_REPORT.md`）。
  - R1（P0）edge：新增常驻 `InboundInternalHeaderStripFilter`（-140）；`ApiKeyToInternalTokenFilter` 改按 `EdgeAuthenticatedTenant` 判断已认证——此前 `EDGE_CASDOOR_ENABLED=false` 时客户端自带的内部头会被原样放行。
  - R2（P1）`platform-metering` 新增 `TokenBudgetGuardFilter`：用满日预算后，生成类 POST 返回 429 + Retry-After；`TOKEN_BUDGET_ENFORCE=false` 回滚。
  - R3（P2）`RedisSemanticCacheStore` 按 `max-entries-per-tenant` 原子 FIFO 淘汰（Lua + 顺序 ZSET），旧桶自动收敛。
  - R4 cutover 门禁：生产必须 RS256，且签名私钥只挂 edge-gateway。R5 修正 Casdoor 注释漂移。
  - 验证：全反应堆 1374/0 fail；5 个 deploy 门禁 PASS；注入回归均被拦截；真实 jar 冒烟（vision 429、edge 伪造头 401）。**未提交、未推送**。
- 共享栈已重建并在跑：MySQL 8.4 `:43306`、PostgreSQL 16 `:45432`、Redis 7 `:46379`、Kafka 3.8 `:49092`、MinIO `:49000`、Nacos 2.4.3。未起 RabbitMQ、marketing-obs、Kibana。
- 数据恢复：langchain4j `deploy/.dev-infra-state/20260908-110350`；workflow `pg_restore` 53 张表；risk MySQL dump 17 张表。
- 门户 10 个项目此前已逐个起过并探活通过（12GiB 不能并存）。
- **seed-mock（2026-09-14）**，优先复用各仓已有脚本：
  | 项目 | 做法 | 入库结果 |
  |---|---|---|
  | marketing | `scripts/seed.sh` | 3 活动、权益/受众/旅程闭环 mock |
  | wms | `test-data/init-test-data.sh` | Cell A/B 主数据 + 入库 6 / 出库 3 / 履约 3，`DATA_VERIFY=SUCCESS` |
  | transaction-center | `scripts/seed-demo-business.py` | 3 订单、2 支付、4 退款；wipe 后旧清单已挪到 `demo-business-manifest.json.pre-wipe-20260914` |
  | drools | console `ACTIVITY_MARKETING_SEED_CATALOG_DATA=true` | `__dev__` 2 店 6 商品、`acme` 2 店 4 商品、行政区 3212 |
  | recsys | `import-items` + `seed-ads --ads=200 --advertisers=20` | item 9742；广告主 20（分片 10+10）、广告 200、创意 600、竞价词 614 |
  | benefit | 新增幂等 `scripts/seed-mock.sql` | tenant=`dev-tenant`：2 SKU、2 发放单、1 待补发 |
  | recon | 新增幂等 `scripts/seed-mock.sql` | 场景定义 2；`recon_src_*` 各 2 行；现金 ODS 应发 1 |
  | workflow | 沿用 9 月 8 日 restore | 历史流程 4、流程定义 4（运行中待办 0） |
  | risk | 沿用 dump | 案件 2、决策 2、规则草稿 4；引擎内 `MARKETING_AWARD` 绑定不在 DB |
  | langchain4j | 跳过（已 restore） | — |
- **knowledge-service 百炼拉起（2026-09-15）**：先前 `RAG_EMBEDDING_BASE_URL` 为空导致 crash loop、检索 500。已 `source load-bailian-env.sh` 后 `--force-recreate knowledge-service`；embedding 指向百炼 `text-embedding-v4`。顺带拉起停着的 `lc4j-qdrant` / `lc4j-elasticsearch`。网关未登录打 `/rag/query` 现为 401（服务可达），不再 500。
- **侧栏子菜单不跳转（2026-09-15）**：上次「留在工作台只滚动」不符合预期。已改回与 Agent/工作流一致：点「上传文档」等子菜单整页进入该能力的 `CapabilityRunner`；对话模式仍留在控制台只切模式。相关 vitest 57 项通过。前端容器已按此行为重建，网关仍烘焙 `http://localhost:18080`。

## 当前运行

- 能力门户 `:5274` + Casdoor `:8000` + SpiceDB
- 共享 `dev-infra` core + **lc4j Qdrant / Elasticsearch**
- langchain4j 栈（含已起来的 `knowledge-service`、网关 `:18080`、前端 `:8093`）
- **交易** `trade-api`（演示库已有业务单）
- **Drools MySQL** `:3307`（目录数据在卷里；console 已停）
- **recsys Postgres** `:55432` + Redis `:56379`（电影/广告在卷里）
- WMS 整栈已停（种子在 `wms-local_*` 卷，可再起）

## 未完成 / 已知缺口

- 12GiB Docker VM **无法同时**跑满 10 个项目控制台。
- recsys `ad_embedding=0`：未跑 `backfill-embedding`（需 embedding provider）。
- risk 管理面 `source_rule_binding` 为空；发布绑定需走治理 API，不能拿 E2E 草稿当线上规则。
- workflow 无运行中待办；要新待办需起审批服务走真实发起，不能手插 Flowable 运行表。
- WMS 接口校验跳过：缺 `WMS_TEST_BEARER_TOKEN`，匿名被拒。
- marketing `offer-decision-service` 在 generation 未激活时仍 503。
- 9 月 8 日之后写进旧 Docker 卷的数据无法恢复。

## Git 发布

- `a5a5ef7` `fix(frontend): restore sidebar capability page navigation`
- `f8d0be6` `feat(knowledge): recover ingestion jobs and parallel retrieval`
- `d1548ec` `feat(deploy): add shared dev-infra compose entry`
- `488f122` `fix(runtime): isolate Redis keys and Kafka consumer groups`
- 远程 `origin/main` 已快进到 `488f122`。
- `f0ef44c` `feat(frontend): overlay RAG runtime honesty on the catalog`
- 远程 `origin/main` 已快进到 `f0ef44c`。本地工作树已无待提交代码。

## 下一步（一次只起一个控制台）

```bash
unset COMPOSE_PROJECT_NAME

# 交易控制台
cd /Users/liruijun/personal/LLM/transaction-center
docker compose --project-directory deploy -f deploy/compose.yaml --env-file deploy/.env.demo up -d --no-build --wait

# Drools UI（目录已在库）
cd /Users/liruijun/personal/LLM/drools-demo
docker compose -f deploy/docker-compose.yml up -d --no-build

# recsys UI（item/广告已在库；embedding 仍空）
cd /Users/liruijun/personal/LLM/recsys
scripts/dev-local.sh up

# WMS（种子已在隔离卷）
cd /Users/liruijun/personal/LLM/wms-platform
docker compose --env-file .env -f compose.yaml up -d

# 权益/对账幂等补种
bash /Users/liruijun/personal/LLM/benefit-center/scripts/seed-mock.sh
bash /Users/liruijun/personal/LLM/recon-platform/scripts/seed-mock.sh
```

`COMPOSE_PROJECT_NAME` 只在调用共享 `dev-infra/compose.yaml` 且 cwd 不是该目录时设为 `dev-infra`。不要重跑 langchain4j `manage.py backup`。

## 恢复 Prompt

请读取 CODEX_PROGRESS.md。共享库与门户项目测试数据已按 seed-mock 写入。继续时按「下一步」只起一个控制台；未经授权不提交或推送。
