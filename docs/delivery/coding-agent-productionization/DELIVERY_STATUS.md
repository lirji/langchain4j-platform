# Delivery Status

## Goal

完成 Coding Agent P1 实战化与 P2 企业化：20 core Benchmark、真实需求到 PR 演示、Java 代码图谱、Docker 强隔离、可观测性和 50 Case。

## State

- Phase: Gate B - delivery complete
- Status: complete with documented baseline limitation
- Last updated: 2026-08-31

## Completed

- Gate A 获批，并在授权上限内串行执行恰好 20 次 Codex 调用；3-case smoke 后续跑 17 条。
- Benchmark plan/run/resume/report、原子 checkpoint、超时进程组回收、3-infra 熔断、plan/dataset/event digest 和脱敏 telemetry 已交付。
- Oracle 20/20；Codex 19 pass + 1 timeout，completion 100%、first-pass 95%、越界率 0%、0 fail/infra。
- Docker 评分沙箱完成：pull-never、断网、只读、non-root、drop caps、no-new-privileges、CPU/内存/PID/超时和无 host fallback；本地 busybox smoke 通过。
- Java code graph 完成：JDK 21 零依赖 build/query、resolved/syntactic 分级、endpoint/影响/测试证据、歧义与 overload 处理。
- GoldenCase 扩展为 50 条：20 core + 30 extended、7 kind、3 difficulty；全部 base/oracle/direct-parent/scope audit 通过。
- 固定 SHA、最小权限的确定性 CI 已加入；明确不执行模型、Docker build/run/pull。
- 真实 requirement→investigation→design→implementation→review→QA→PR package→demo 文档链已完成。
- 按 platform-diff-review 修复 checkpoint、超时 PID、cleanup、score corruption、model pinning、全局 config/env、Git pager、symlink/overload 和脱敏问题。
- 按 platform-qa 完成 AC-01～AC-14 证据矩阵；按 platform-pr-package 生成 review-ready 包。
- 未编辑或覆盖任务开始前已有的 README、deploy 和 operations 用户修改；未 commit/push/open PR/merge/deploy。

## Changed Files

- .agents/skills/platform-*、.codex/agents/* - 6 Skills 与 4 个只读 Agent。
- tools/coding-agent-eval/** - evaluator、benchmark、telemetry、sandbox 与 38 条测试。
- tools/java-codegraph/** - JDK 21 code graph 与 fixture。
- docs/qa/coding-agent-golden/** - 50 Case dataset v2。
- docs/qa/coding-agent-benchmark/** - oracle/Codex 脱敏报告。
- docs/qa/coding-agent-demo/** - 完整真实交付演示、review、QA、PR package。
- docs/平台工程/**、AGENTS.md、docs/README.md - playbook、sandbox、codegraph、培训与入口。
- .github/workflows/coding-agent-kit-ci.yml - 确定性 CI。
- docs/delivery/coding-agent-{engineering-kit,productionization}/** - Gate A/B 交付记录。

## Verification Log

| Command or check | Result | Notes |
| --- | --- | --- |
| validate-kit | pass | 6 Skills / 4 Agents |
| validate | pass | dataset 2.0.0，50 Case，digest valid |
| audit --repo . | pass | 50 refs/parents/scopes；20/30 tier；7 kind；3 difficulty |
| Node test suite | pass | 38/38 |
| Java codegraph fixture | pass | endpoint、关系、歧义、overload、digest |
| Java codegraph full scan | pass | 1,044/1,044，0 failed，7,266 nodes / 62,481 edges |
| Docker sandbox smoke | pass | read-only source、UID 65532、no socket、timeout；0 container leak |
| Oracle benchmark | pass | 20/20，first-pass 100% |
| Codex benchmark | pass with timeout | 19 pass、1 timeout、0 fail/infra；first-pass 95% |
| Event chain/report | pass | plan/event digest valid，cost unknown/null |
| CI/YAML/bash/static scans | pass | 固定 SHA、contents:read、无模型/Docker CI 命令、无 secret/home/whitespace |
| Diff review | pass after fixes | High/Medium findings resolved |
| QA AC-01～AC-14 | pass | 远端 CI 和全 Maven reactor 未执行，理由已记录 |

## Decisions And Deviations

- 首轮获批 benchmark 按计划使用当时本地默认模型，PLAN 为 model:null；观察配置为 gpt-5.6-sol，reasoning 固定 medium，但事件流未独立返回模型名。
- 评审后未来 Codex plan 强制 --model，candidate 忽略用户 config、shell 环境零继承；授权 20 次已用满，因此没有重跑或伪造首轮模型字段。
- Maven Java 验证使用本地 JDK image + 只读 Maven 安装/有效 repository + 无凭据 settings；未挂载用户 settings。
- Node profile 镜像不存在时 fail closed；遵守禁止 pull 的授权边界，没有运行 Node container smoke。
- 代码图是静态导航证据，不宣称覆盖 Spring 反射/AOP/运行时路由。
- P1/P2 只改工具/文档/CI，未运行完整 Maven reactor；20 core 编译已在离线 Docker 执行。

## Residual Risks

- 首轮报告不能作为跨模型严格比较；后续必须显式固定 model 后创建新 run。
- GitHub Actions 远端尚未触发；本地已执行全部确定性底层命令。
- coding-agent-sandbox-smoke:local 按授权保留供复现；无残留 container。
- 工作树仍包含用户原有修改和本次未提交成果，提交时必须按 PR package checklist 分组暂存。

## Next Action

由仓库所有者审阅 docs/qa/coding-agent-demo/PR_PACKAGE.md，按文件组暂存并排除既有 README/deploy/operations 修改；如需 commit/push/open PR，需另行明确授权。
