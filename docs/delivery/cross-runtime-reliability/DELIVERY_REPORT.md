# 跨运行时可靠性交付报告

## 结论

S1–S7的七类可靠性缺口已实现并通过本地验收; Python quality与Java cutover CI通过。
2026-10-02用户明确接受既有框架/依赖版本扫描结果并要求合并main; 本轮不执行框架升级。
Java SBOM 85条HIGH/CRITICAL仍为FAIL, 按MERGE_EXCEPTION.md作本次源码合并例外,
不降低CI门禁或声称漏洞已修复。S8已按Python→Java正常快进合并并推送main, 远程核对成功。
生产结论保持NO-GO。

## 交付内容

| 问题 | 实际结果 | 详细证据 |
|---|---|---|
| RAG租户漏检 | 缺失/null/空/不同tenant均拒绝, 匹配租户接受 | QA_REPORT, Python readonly_tools边界测试 |
| 渠道ACK后丢任务 | 先持久化inbox再ACK, 租约/epoch/有界重试/管理员重放 | S2_CHANNEL_INBOX |
| 双运行时预算分裂 | 共同原子预留/幂等结算, Java/Python模型出口完整接入 | S3_SHARED_BUDGET |
| 契约随最新/缺源绿 | producer完整Git SHA + manifest摘要 + 25个consumer契约 + DTO/CI门禁 | S4_IMMUTABLE_CONTRACTS |
| 退款未知结果 | 原幂等请求只读回执恢复, 不自动重发写操作 | S5_REFUND_RECEIPTS |
| 进程内异步任务 | 持久化只读分派, 独立worker恢复, 输入/版本与配置核对, 旧epoch拒绝 | S6_DURABLE_WORKER |
| 流式shadow与取消缺口 | 独立stateless候选, 有界SSE/背压/取消, 原生SDK句柄和HTTP关闭 | S7_STREAM_SHADOW |

默认兼容flag保持关闭, worker保留inline默认。功能启用条件、迁移与回退边界详见各切片文档。
不将至少一次、token估算、取消或接受时授权解释为端到端exactly-once、严格美元硬上限或权限实时撤销。

## 验证

- Java干净全reactor: 1406 tests, 0fail/0error, 13skip。
- Python干净全量: 518pass, coverage89.27%; Ruff/format/mypy PASS。
- 隔离MySQL: inbox10、readonly dispatch5、退款lost-response1 PASS; Flowable H2/JDBC15 PASS。
- Redis真实原子预算4项与5工厂出口HTTP/SSE计费PASS。
- 独立进程: API退出/worker崩溃接管/DAG/旧epoch、Java JWT→候选→提供方TCP取消PASS。
- 契约缺源/漂移故障4项与DTO14项PASS; 两仓Code Hygiene无blocking (Java静态工具配置限制已披露)。
- 对抗检查为主Agent自复审, 未声称独立审查人批准; 详见REVIEW_REPORT。

## Git与远程CI

任务分支均fix/cross-runtime-reliability。Java基线e8f11cb, Python基线c4fc90d。
Java当前运行提交deba7bc, Python34df909; 消费契约固定producer6f43ddf,
IAM SDK源码固定auth-platform929e9caf394b98b3030357746c0ff396cc0806db。
第三IAM仓只读, 未发布其用户改动。

| CI | revision | 实际结论 |
|---|---|---|
| [Python quality](https://github.com/lirji/platform-agentscope/actions/runs/36979153821) | 34df909 | SUCCESS, 包含审计/契约/测试/构建/镜像扫描 |
| [Java cutover](https://github.com/lirji/langchain4j-platform/actions/runs/36980014838) | deba7bc | SUCCESS, 包含固定契约/SDK、全reactor、Compose/安全配置/Helm校验; 已关闭早期Linux SIGPIPE误报 |
| [Java supply chain](https://github.com/lirji/langchain4j-platform/actions/runs/36978975220) | 1ac14a0 | 测试/打包通过, 聚合SBOM扫描FAIL, 85条HIGH/CRITICAL; 未进入18镜像扫描 |

用户本轮授权覆盖正常合并/push main和已披露版本扫描的合并例外; Python→Java为发布顺序。
未扩展框架迁移范围, 远程扫描FAIL与用户接受例外分开记录。
没有生产部署、tag/release、付费模型、benchmark模型执行或sandbox-smoke。

## 用户改动与目录

所有初始dirty贡献和intent-to-add保留, 当前任务提交通过内容扣除排除混合文件的既有改动。
Java19个、Python6个初始tracked dirty文件重建校验0偏差; 用户未跟踪治理目录/测试也保留。
Python原工作树新增未提交测试预期非规范JWT编码先decode成功; PyJWT安全补丁更早拒绝,
原工作树524pass/1fail, 该用户测试未被修改或混入当前提交。

仅两处本轮验证worktree保留: ~/.local/share/git-worktrees/{langchain4j-platform,agentscope-platform}/cross-runtime-verification。
用于干净验证与避免合并污染用户工作树; 未获清理授权, 不删除。
其他既有worktree和共享infra不清理。UUID试验库/key和本轮测试进程已清理。

实际Git交付: Python main正常快进c4fc90d→1a7ceec4e4b39e12b046a01b60a7ee731e1e4876,
随后Java main正常快进e8f11cb→8bf6ad6bfb37bac5c523db7631ea65ecbd5ddfdb。
两个远程main SHA均经git ls-remote确认; Java deba7bc、Python固定producer6f43ddf及全部任务提交
均为对应main祖先。后续仅此交付状态/进度文档闭合, 最终HEAD以git main/origin/main为准。
原项目目录保持任务分支和dirty文件; main检出在既有verification worktree, 没有stash/强推/清空。
恢复入口为两仓CODEX_PROGRESS与本目录DELIVERY_STATUS。

## 合并后CI快照

| CI | 首次main发布revision | 观察状态 |
|---|---|---|
| [Python quality](https://github.com/lirji/platform-agentscope/actions/runs/36983232314) | 1a7ceec | IN_PROGRESS |
| [Java cutover](https://github.com/lirji/langchain4j-platform/actions/runs/36983259278) | 8bf6ad6 | IN_PROGRESS |
| [Java supply chain](https://github.com/lirji/langchain4j-platform/actions/runs/36983259283) | 8bf6ad6 | IN_PROGRESS; 已披露版本扫描接受例外, 未预判PASS |
| [Java edge](https://github.com/lirji/langchain4j-platform/actions/runs/36983259389) | 8bf6ad6 | IN_PROGRESS |
| [Java tax](https://github.com/lirji/langchain4j-platform/actions/runs/36983259337) | 8bf6ad6 | IN_PROGRESS |

此前相同产品代码的Python quality/Java cutover已通过; 之后仅交付文档改变。
main最新文档提交会按现有工作流再次触发自动CI, 实际状态以Actions为准; 不以源码合并成功宣称CI全绿。
Git交付完成, 当前工程结论COMPLETED_WITH_ACCEPTED_FINDINGS; 版本技术债另行安排, 生产NO-GO保持。
