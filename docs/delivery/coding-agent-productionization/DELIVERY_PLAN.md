# Coding Agent P1/P2 Productionization Delivery Plan

## Requirement

在已完成 P0 Engineering Kit 的基础上，连续完成：

- P1 实战化：20 个核心 Case 的真实 Coding Agent Benchmark、可复现指标报告、一个“需求→探索→设计→实现→评审→QA→PR 交付包”的真实演示。
- P2 企业化：Java AST/调用与影响图谱、Docker 强隔离验证沙箱、任务级可观测性、GoldenCase 从 20 扩展到 50、CI 与团队文档同步。

本交付不修改在线 Java 服务行为，不自动 commit/push/open PR/merge/deploy，不把模型调用放入 CI。

## Repository Evidence

- P0 已交付 6 Skills、4 个只读 Agent、20 个历史 Case、`validate/list/prepare/score/report` CLI 与 23 条测试；交付报告位于 `docs/delivery/coding-agent-engineering-kit/`。
- 当前仓库有 1,039 个 Java 文件、147 个非 merge 历史 commit，足以支撑代码图谱和 50 Case 分层数据集。
- 本机具备 JDK/Javac 21、Maven 3.9.12、Node 24、Git、Docker client/server 和 `codex-cli 0.151.0`。
- 本机已有 `eclipse-temurin:21-jdk`、`busybox:latest`、`alpine:latest` 等镜像，可以在不 pull 的情况下验证 Docker hardening；没有通用 Maven/Node 评测镜像，因此生产 profile 必须在镜像缺失时 fail closed，禁止偷偷联网拉取。
- `codex exec` 支持 `--ephemeral`、`--json`、`--sandbox workspace-write`、`-C <worktree>`；适合受控串行 Benchmark，但会联网调用已登录模型并消耗额度。
- 当前工作区包含 P0 未提交成果，以及进入 P0 前就存在的 README、deploy 和 operations 用户修改；P1/P2 必须在这些基础上增量工作，禁止覆盖或重置。

## Feasibility

- Verdict: conditional-go
- Conditions:
  - 工具、代码图谱、沙箱、50 Case、CI、文档和 oracle 校准均可本地完成。
  - 20 个真实 Codex candidate run 需要用户明确允许联网和额度消耗；未授权时只能完成 runner、oracle calibration 与 dry-run，不能把 candidate baseline 伪装成已完成。
  - Docker 本地集成测试只使用已有镜像并设置 `--pull=never`；如需新增 Maven/Node 镜像，必须另行明确允许 pull。
- Constraints:
  - 不调用生产服务、不读取项目业务密钥、不向报告写入原始凭据、完整环境变量或未脱敏模型事件。
  - 20 个 candidate run 串行执行，每 Case 最长 8 分钟；连续 3 个模型/网络/CLI 基础设施错误时停止，保留 checkpoint，避免无上限重试或额度消耗。
  - candidate 仅能写自己的临时 worktree；不用 bypass approvals/sandbox，不增加 writable directory，不自动 fetch/commit/push。
  - CI 只做确定性校验，不运行模型、不依赖 Docker Hub、不上传 raw prompt/event。
- Dependencies:
  - JDK 21 Compiler Tree API；不新增 JavaParser/Spoon 依赖。
  - Docker daemon 与本地镜像；默认禁止 pull。
  - 实际 candidate benchmark 使用本机已登录 Codex CLI；不读取或复制其认证文件。
- Risks and mitigations:
  - 历史任务过大导致 benchmark 失真：把 50 Case 分为 `core`/`extended` 与 small/medium/large；P1 的 20 个 core 先做 oracle 校准，prompt 不泄漏 oracle patch。
  - 执行候选代码：评分验证优先走 Docker；host 模式必须显式选择并标记 `isolation=host`，不得用于不可信 patch。
  - Docker 逃逸/资源耗尽：禁网、只读根文件系统、只读 source mount、tmpfs workdir、non-root、drop all capabilities、no-new-privileges、pids/memory/cpu/time limit。
  - 模型事件泄密：只保留结构化白名单字段和摘要 hash；raw JSONL 放临时目录且不纳入 Git。
  - 代码图谱误报：区分 `resolved` 与 `syntactic` edge，保留 file/line 证据，不把简单方法名匹配声明为精确调用关系。
  - dataset v2 使 v1 report 不兼容：保留 schema v1，提升 dataset 版本和 digest；报告按 digest fail closed。

## Product Design

- Actors and goals:
  - 候选人/讲师：用一个真实演示和量化报告说明 AI-Native 工程能力。
  - Java 工程师：查询类、接口、Spring Bean、HTTP 入口及可能影响文件，减少盲目文本搜索。
  - 质量/平台工程师：在可恢复 runner 中比较 candidate，使用固定 dataset/tool/workflow 版本判断趋势。
  - 安全负责人：确认候选代码的验证过程不接触网络、生产凭据或宿主写权限。
- Primary workflow:
  1. `codegraph build/query` 生成并查询当前代码结构。
  2. `benchmark plan` 固定 candidate、model/workflow、dataset digest、Case 顺序和超时。
  3. `benchmark run/resume` 为每个 Case 创建 worktree、运行 candidate、隔离评分并落盘事件。
  4. `benchmark report` 汇总完成率、首次通过率、越界率、耗时、token/成本（仅模型实际提供时）和基础设施阻塞。
  5. 从真实交付生成 investigation/design/review/QA/PR package 演示材料。
- Scope:
  - 20 core candidate benchmark + 20 oracle calibration。
  - 50 个真实历史 Case；现有 20 个保留并校准，再新增 30 个。
  - 离线 Java code graph、影响查询和确定性 JSON schema。
  - Docker sandbox runner、profile、entrypoint、dry-run、安全测试和已有镜像 smoke。
  - Hash-chained JSONL events、checkpoint/resume、sanitized report。
  - 真实 demo、面试版报告、操作/安全/扩集文档与 CI。
- Out of scope:
  - Web dashboard、在线排行榜、数据库存储、分布式 runner。
  - 自动创建或合并 PR、自动部署、生产 smoke、GitHub token。
  - Claude/OpenAI API 多厂商适配；首期 candidate adapter 只支持显式本地命令模板和 Codex CLI。
  - 完整 Java 类型求解器；无 classpath 时的调用边只标记 syntactic。
  - 在 CI 中运行 candidate 或拉取 Docker 镜像。
- Business rules:
  - oracle calibration 与 candidate benchmark 分开统计，绝不混为同一完成率。
  - `blocked`、`timeout`、`infra_error` 与 `fail` 分开；未执行不得记 0 分后伪装为模型失败。
  - token/cost 字段只有事件源提供时才记录；未知必须为 `null`，不得估算为实测。
  - resume 只接受相同 plan digest；candidate/model/workflow/dataset 任一变化必须新建 run。
  - Benchmark 默认 shadow，不成为合并门禁。

## Acceptance Criteria

| ID | Observable behavior | Priority | Verification |
| --- | --- | --- | --- |
| AC-01 | `benchmark plan/run/resume/report` 能固定 run plan、逐 Case checkpoint、从中断处恢复且拒绝 digest 漂移 | Must | Node unit/integration tests |
| AC-02 | 经授权后完成 20 个 core Codex candidate run，并生成区分 pass/fail/blocked/timeout/infra_error 的脱敏基线报告 | Must/conditional | 报告、events digest、20 Case 状态对账 |
| AC-03 | 对同一 20 core Case 完成 oracle calibration；oracle 异常会标记 Case 漂移而非归咎 candidate | Must | oracle run/report |
| AC-04 | 真实 demo 包含 requirement、investigation、design、implementation evidence、review、QA、PR package，声明与命令一致 | Must | 文档审查 + demo smoke |
| AC-05 | Java code graph 在当前仓库解析全部可读 Java 文件，输出 version/digest、模块/类型/方法/HTTP 入口与 resolved/syntactic edges | Must | fixture tests + current-repo build |
| AC-06 | `codegraph query --symbol/--file` 返回直接依赖、反向影响、相关测试和 `file:line` 证据，非法/歧义查询有明确状态 | Must | Java/CLI tests |
| AC-07 | Docker sandbox 默认 pull-never、network none、read-only、non-root、drop caps、no-new-privileges、pids/memory/cpu/time limit 和只读 source | Must | argv contract tests + local busybox smoke |
| AC-08 | Docker image 缺失、daemon 不可用、timeout、OOM/非零命令均 fail closed；不回退 host，除非操作者显式选择 host | Must | negative integration tests |
| AC-09 | 每个 run 产生 hash-chained、schema 化事件，包含 run/case/phase/duration/exit/score/isolation，敏感字段被拒绝或脱敏 | Must | telemetry tests + secret scan |
| AC-10 | GoldenCase dataset 至少 50 个唯一真实历史 Case，覆盖七类 kind、core/extended、三档难度，ref/oracle scope 全部可验证 | Must | dataset validator + git history audit |
| AC-11 | 测试覆盖 resume、重复 run、plan 漂移、超时、连续 infra stop、Docker flag 缺失、symlink、event tamper、codegraph ambiguity | Must | Node/Java tests |
| AC-12 | CI 使用固定 action SHA 与最小权限，运行 Node、dataset、codegraph fixture/current scan 和 sandbox contract test；无模型、无 pull | Must | workflow parse + local underlying commands |
| AC-13 | 文档包含 live demo、指标解释、沙箱威胁模型、50 Case 维护和招聘能力映射 | Should | doc/link review |
| AC-14 | 最终 diff 不覆盖 P0 前已有 README/deploy/operations 用户修改，不自动 commit/push/deploy | Must | scoped status/diff audit |

## UI/UX Design

- Applicability: Not applicable。P1/P2 只新增 CLI、JSON/Markdown 产物与 CI，不新增 Web UI。
- CLI states: `planned/running/pass/fail/blocked/timeout/infra_error/stopped`；控制台只输出进度、耗时、状态和下一步，不输出认证或 raw model event。
- Accessibility/responsive: Not applicable；Markdown 报告必须用可读表格和纯文本状态，不只用颜色表达。

## Technical Solution

- Chosen approach:
  - 在现有 `tools/coding-agent-eval` 上增加 benchmark/candidate/telemetry/sandbox 库，复用 manifest、worktree 和 scorer。
  - Java code graph 使用单独的零第三方 JDK 21 source tool，通过 `JavacTask`/`TreePathScanner` 解析，不加入 Maven reactor。
  - Docker runner 使用固定 entrypoint 将只读 source 复制到受限 tmpfs，再以参数数组 `exec`；默认 `--pull=never`。
  - Benchmark raw working data 放临时 run directory；Git 只保存脱敏 summary、指标和必要 evidence digest。
  - P1/P2 本身作为真实 requirement-to-PR demo，最终使用 `$platform-pr-package` 生成 PR package。
- Alternatives rejected:
  - JavaParser/Spoon：需新增/下载依赖并管理符号求解 classpath，首期不必要。
  - 把图谱做成在线服务/Neo4j：增加运行时、数据库和运维面，超出 Coding Agent 工具域。
  - `docker run sh -c <candidate string>`：扩大注入面；改用固定 entrypoint + 参数数组。
  - CI 调用 Codex：需要凭据、费用且非确定；candidate benchmark 只在明确授权的本地受控流程运行。
  - 直接并行 20 Case：难以控制额度、宿主负载与失败；采用串行 checkpoint/resume。
  - 把 oracle patch 完全匹配当正确性：继续以 path/test/rubric 为准。
- Anticipated file map:
  - `tools/coding-agent-eval/cli.mjs`
  - `tools/coding-agent-eval/lib/{benchmark,candidate,telemetry,sandbox}.mjs`
  - `tools/coding-agent-eval/test/{benchmark,telemetry,sandbox}.test.mjs`
  - `tools/coding-agent-eval/sandbox/{Dockerfile.smoke,entrypoint.sh,profiles.json}`
  - `tools/java-codegraph/CodeGraphCli.java`
  - `tools/java-codegraph/test/fixtures/**`、`tools/java-codegraph/test/run-tests.sh`
  - `docs/qa/coding-agent-golden/manifest.json`、`cases/*.json`（扩至 50）
  - `docs/qa/coding-agent-benchmark/<run-id>/{PLAN,SUMMARY,REPORT}.*`（只保存脱敏产物）
  - `docs/qa/coding-agent-demo/{REQUIREMENT,INVESTIGATION,DESIGN,REVIEW,QA,PR_PACKAGE,DEMO_SCRIPT}.md`
  - `docs/平台工程/{coding-agent-playbook,coding-agent-sandbox,java-codegraph}.md`
  - `.github/workflows/coding-agent-kit-ci.yml`
  - `AGENTS.md`、`docs/README.md`
  - `docs/delivery/coding-agent-productionization/*`
- Contracts and data:
  - Benchmark plan：schema、runId、dataset digest、candidate/model/workflow、case IDs/order、timeout、isolation、createdAt、planDigest。
  - Event：sequence、previousDigest、eventDigest、runId、caseId、phase、status、durationMs、exitCode、score、token/cost nullable、timestamp。
  - Summary：status counts、completion/first-pass/out-of-scope/infra rates、duration percentiles、token/cost coverage、dataset/tool/workflow versions。
  - Code graph：`coding-agent-codegraph/v1`，nodes、edges、evidence、resolution、content digest。
  - GoldenCase v2 additive fields：`tier`、`difficulty`、`sourceCommitSubject`；旧 score schema 保持兼容，dataset digest 自然变化。
- Security and reliability:
  - Codex candidate 使用 `--ephemeral --json --sandbox workspace-write -C <exact-worktree>`；禁止 danger bypass、add-dir、push/deploy 指令。
  - candidate timeout 终止进程树并记录 timeout；清理只针对 runner 登记的 worktree/container。
  - Docker 不挂载 Docker socket、SSH、Codex home、宿主 HOME 或 Maven settings；环境使用显式 allowlist。
  - raw stdout/event 有大小上限；报告执行 secret/absolute-home-path redaction，raw 文件不提交。
  - benchmark plan 与 event chain 防止静默续跑到不同 dataset/candidate。
- Compatibility and migration:
  - 不改变服务 API、数据库、Maven reactor、部署和业务 CI。
  - P0 CLI 命令保持兼容；新增命令和 Case 字段为加法。
  - v1 historical score 仍可单独读取，但不能与 v2 digest 汇总。

## Implementation Sequence

1. 扩展 schema、telemetry、plan/checkpoint/resume 和 contract tests（AC-01、09、11）。
2. 实现 JDK 21 code graph build/query、fixture 与全仓扫描（AC-05、06）。
3. 实现 Docker profile/entrypoint/runner、contract/negative/local smoke（AC-07、08）。
4. 实现 Codex/local-command candidate adapter、超时/连续错误停止与脱敏报告（AC-01、02、03、09）。
5. 审核历史并把 dataset 扩到 50，校准 20 core oracle（AC-03、10）。
6. 在用户允许模型调用后串行跑 20 core candidate，必要时 resume，生成基线和真实 demo/PR package（AC-02、04、13）。
7. 更新 CI、团队规范与文档，做实际 diff 评审、修复、QA 和 Gate B（AC-11～14）。

## Verification Plan

| AC/Risk | Test level | Case or command | Required evidence |
| --- | --- | --- | --- |
| AC-01/09/11 | Unit/integration | `node --test tools/coding-agent-eval/test/*.test.mjs` | resume/digest/event/timeout/stop tests pass |
| AC-05/06 | Java fixture | `bash tools/java-codegraph/test/run-tests.sh` | graph/query golden assertions pass |
| AC-05 | Repository integration | `java tools/java-codegraph/CodeGraphCli.java build --root . --output <tmp>` | 1,039 可读 Java 文件处理对账，JSON/digest valid |
| AC-07 | Static | sandbox Docker argv snapshot/invariant tests | 所有 hardening flag 必须存在 |
| AC-07/08 | Docker local | 以已有 `busybox:latest` build/run smoke；测试缺 image 与 timeout | 无网络、source 只读、tmpfs 可写、timeout fail closed |
| AC-02/03 | Benchmark | 20 core oracle + 20 core Codex candidate | 每 Case 唯一终态，summary 与 event chain 对账 |
| AC-10 | Dataset/history | `validate` + 50 ref/oracle diff scope audit | 50 unique，七类/两 tier/三难度覆盖 |
| Secrets | Negative | synthetic token/raw path/model event | sanitized artifact 不含 secret/home path |
| AC-12 | CI | YAML parse + 本地运行所有底层命令 | read-only、固定 SHA、无 model/pull |
| AC-14 | Diff | baseline status + scoped diff + secret/whitespace scan | 原用户修改保持且无额外副作用 |

## Documentation Plan

- 扩展 Coding Agent playbook：benchmark plan/run/resume/report、指标和 demo。
- 新增 Docker 沙箱威胁模型、镜像准备、fail-closed、资源限额和清理说明。
- 新增 Java code graph schema、精确/语法边界、build/query 示例和已知限制。
- 新增面试演示脚本、真实交付证据与 PR package。
- 更新 `AGENTS.md` 与 `docs/README.md` 的维护/验证入口。

## CI Plan

- 更新 `Coding Agent Kit CI`，继续 `permissions: contents: read` 和固定 action SHA。
- 增加 JDK 21，仅运行 codegraph fixture/current scan、Node tests、50 Case validate、sandbox contract tests和 `bash -n`。
- 不执行 `codex exec`、不构建/拉取 Docker image、不保存认证或 raw events、不上传写权限 artifact。

## Rollout And Rollback

- Rollout:
  1. 先完成 codegraph、sandbox、runner 的 deterministic gates。
  2. 对 20 core 做 oracle calibration，漂移 Case 先修复或移出 core。
  3. 获得模型调用授权后串行 candidate benchmark，先 3 个 smoke，无 infra 问题再继续到 20。
  4. 基线只做 shadow/简历证据，不成为 merge gate；积累多轮稳定数据后再讨论阈值。
- Monitoring:
  - completion、first-pass、out-of-scope、infra、timeout、human intervention、duration、token/cost coverage。
- Rollback:
  - runner/codegraph/sandbox 均为独立 tools，可回退而不影响在线服务。
  - dataset v2 可回退 manifest/cases；旧报告按 digest 保留，不强行迁移。
  - 停止 benchmark 只需终止当前 candidate；checkpoint 保留，临时 worktree 按登记清理。

## Assumptions And Open Decisions

- 假设“完成 P1/P2”包含：20 个 core 实际 Codex candidate baseline，而不是只实现 runner。
- 推荐模型执行策略：使用本机 Codex CLI 当前默认模型，reasoning effort=`medium`，串行 20 Case，单 Case 8 分钟，先跑 3 个 smoke，连续 3 个 infra error 停止；不使用 danger bypass。
- 推荐 Docker 策略：本轮只用已存在的 `busybox:latest` 做 hardening smoke，完整 Maven/Node profile 做 fail-closed contract；不联网 pull 新镜像。
- 50 Case 中只有 20 core 进入本轮模型 benchmark，另外 30 extended 完成 schema/ref/oracle 校准，不额外消耗 30 次模型调用。
- token/cost 如果 Codex JSONL 不提供权威字段则记 `null`，只报告耗时与状态，不推算费用。
- P0 与本轮文件尚未提交；本轮继续在当前工作树增量实施，不执行 commit。

## Approval

- Status: approved
- Approved scope: 上述 P1/P2 文件、行为、测试、CI、rollout 与外部副作用上限。
- External-effect authorization requested:
  - 允许本机 `codex exec` 联网串行调用最多 20 个 core Case，使用已登录账号额度；先 3 个 smoke，单 Case 8 分钟，连续 3 个 infra error 停止。
  - 允许 Docker daemon 创建/自动删除本地测试 container，并基于已经存在的 `busybox:latest` 构建一个本地 smoke image；禁止 pull 新镜像。
- Evidence: 用户于 2026-08-31 明确回复“批准计划，并授权20次Codex调用和Docker smoke”。
