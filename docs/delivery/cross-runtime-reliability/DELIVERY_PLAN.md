# 跨运行时可靠性整改计划

## 目标、授权与边界

用户在本会话批准对前一轮架构评估列出的七类问题按 Claude Engineering Skill 优化、修复。
本计划由 `engineering-workflows` 协调，产品实现使用既有 backend implementation / platform 功能流程；
不使用只允许 Runtime 文件修改的 remediation APPLY。当前任务包含 Java 与 AgentScope 两仓。
既有 ESS 历史 run 的门禁、误报及审批状态保留为历史证据，不伪造其 SUCCEEDED 状态。
本会话的新授权覆盖已审查的 RAG fail-closed 修复和本计划的工程实现、非生产本地验证。
用户 AGENTS.md 持续授权独立任务分支、分批提交、正常合并和推送 main；不授权生产部署。

Java 保持业务数据、任务和事务权威；Python 保持推理与编排。复用 Java 21/Spring/JDBC/Flyway、
Python/AgentScope/HTTP、现有 Redis/MySQL。保留兼容接口和 feature flags，不迁出普通 Chat 状态，
不删除 legacy Agent，不新增中间件、不修改真实凭据、不对共享业务数据执行迁移或故障注入。
现有未提交改动已备份至两仓 `.git/codex-cross-runtime-reliability-baseline/`，按路径/内容保护。

## 证据与技术决策

- RAG 工具只拒绝非空且不一致的 tenantId，缺失/null/空值在 MockTransport 实验中仍返回内容。
- channel 回调先写 PROCESSED_EVENT 后将消息放进进程内 executor，再 ACK；丢弃队列后重投被拒且业务执行零次。
- Java TokenBudgetTracker 已记账；Python 只有 per-run 限额和指标，无共同租户账本。
- Java vendored 18 个契约校验通过，但同步脚本无上游时成功跳过。
- Python 确认 grant 先消费后调用退款，Java 数据库幂等已实现；响应丢失后的结果查询仍缺失。
- Python async execution 为 request closure + local create_task；Java leaseEpoch fencing 已实现。
- Chat shadow 只有非流式 seam，既有 controller 未取得 SDK 取消句柄；S7 已验证 1.13.1 支持句柄取消，并修复迟到句柄窗口。

选择统一 Java 预算接口而不是 Python 直读 Java 私有 Redis 键；保留同一租户日预算权威。
预算使用原子预留/幂等结算，区分真实 usage 与未决 reservation；请求中止或 usage 缺失不能按零消耗释放。
渠道使用自有持久化 inbox，收到消息持久化后才 ACK；外部效果按至少一次和业务幂等验收，
不宣称远程回复与数据库状态天然 exactly-once。
worker 独立部署先支持可安全恢复的只读任务；有副作用执行需要新的有效确认，不自动重放写工具。
跨仓兼容针对明确的不可变 producer revision / 契约 digest，不能把“跟随最新”当发布兼容。
流式候选仅 shadow、独立进程；真实模型质量、费用、目标环境容量与生产切流不在本地工程验收内。

## 切片与可观察验收

| Slice | 目标与子结果 | 验收 | 依赖／规模 |
|---|---|---|---|
| S1 | RAG 边界 fail closed | 匹配租户接受；不同、缺失、null、空值拒绝且不返回片段；基线契约保持 | 无／S |
| S2 | 渠道 durable inbox：迁移、状态、领取/重试、桥与 ACK 接入 | ACK 前写入；同消息唯一；进程退出可重领；旧 lease 写回拒绝；有界重试/隔离；退款重放不重复创建 | 无／M |
| S3 | 统一预算：Java authority/API、模型边界、Python 接入 | 两语言共用额度；并发预留原子；tenant/user/operation 绑定；结算幂等；planner/reviewer/stream 覆盖；失败与未知 usage 显式 | S1／L |
| S4 | 固定契约版本组合与 CI | 必需上游缺失非绿；digest/版本/DTO 校验；明确 producer revision；不兼容组合失败；producer→consumer 发布顺序 | 协议稳定后／M |
| S5 | 退款未知结果恢复 | 已提交但响应丢失可按原幂等键读取原实例；同键异参数拒绝；跨租户/用户拒绝；确认不被无条件重放 | S1／M |
| S6 | 独立 worker：可信任务上下文、分派、恢复、部署 | API/worker 可分离；只读任务重启可接管；epoch 防旧写；租户并发有界；有副作用恢复要求新确认；保持 inline 兼容 | S3/S5／L |
| S7 | 流式 shadow seam 与隔离验证 | HTTP/SSE 顺序、终态、错误、背压、断连与候选取消可复验；候选不写 Java 状态、不影响 primary；独立 shadow 进程 | S3／M |
| S8 | 对抗复审、集成、文档、Git 交付 | 所有本地必需验收有证据；必要模块/全量回归；无无关 staged diff；文档/进度同步；正常远程 main 包含任务提交 | S1–S7／M |

S/M/L 为局部修复、跨模块切片、架构切片，不是工时承诺。大切片实现前逐项补充精确协议和文件边界。
在发现不能沿既有架构安全解决的实质取舍时，只暂停依赖该决定的工作，继续独立切片。

## 验证、文档及发布

基线：前一轮 Python 72 项、Java 60 项聚焦测试通过，18 份契约一致；本轮补完整基线。
每个切片先补失败/恢复用例，运行受影响模块/测试，再做与实现分离的对抗检查。
Java `mvn -o -B -pl <modules> -am test`；Python `uv run --no-sync ruff check .`、format check、
`mypy src`、`pytest`；契约 export/check、部署静态 gate、Code Hygiene Gate。
数据库并发语义在隔离本地 MySQL/Redis 上验证；不在共享业务库中创建、删除或覆盖测试数据。
文档更新归对应切片；本目录 DELIVERY_STATUS 为唯一聚合状态，两仓 CODEX_PROGRESS 指向此处。

工程完成和生产 GO 分开。真实模型、registry 签名/准入、目标集群 IAM/恢复/soak/canary 和生产部署
保持外部待验收；不将生产证据模板改成 GO。远程 CI 结果与本地通过分别记录。
