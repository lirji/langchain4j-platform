# Coding Agent Engineering Kit Delivery Plan

## Requirement

基于当前 Java AI 平台，补齐招聘要求中最优先的 Coding Agent 工程能力：仓库级 Skill、研发型 SubAgent、可重放 Coding GoldenCase、确定性评测、CI 门禁、团队规范与培训材料。目标不是新增业务 Agent，而是形成一套可执行、可验证、可推广的研发工作流。

搜索、地图、离线导航和 C++/JNI 是下一条独立交付线，本计划不把它们混入首期实现。

## Repository Evidence

- 根目录已有 `AGENTS.md`、`CLAUDE.md`，包含项目结构、构建、测试、安全和长任务规则，但尚无仓库级 `.agents/skills`。
- `docs/平台工程/长任务处理指南.md` 已沉淀计划先行、进度落盘、小步提交和子代理分工原则，可作为 Coding Agent 工作流基础。
- `docs/架构边界/evaluation-control-plane.md` 已定义 dataset/version/report digest/toolset 版本和 fail-closed 门禁，可复用到 Coding GoldenCase。
- `docs/qa/resume-metrics-0727-1539/datasets/` 已有 RAG/NL2SQL 黄金集，但它们评测业务 AI，不评测代码修改能力。
- `.github/workflows/` 已采用 GitHub Actions、最小权限、固定 action SHA 和路径过滤，可沿用同一 CI 风格。
- 当前工作区已有用户未提交的 README、部署脚本、Compose 和 operations 文档变更；本交付不得覆盖或夹带这些改动。
- OpenAI 官方文档确认：仓库级 Skills 位于 `$REPO_ROOT/.agents/skills`，项目级自定义 Agent 位于 `.codex/agents`；并行 SubAgent 优先用于探索、评审和测试等读密集任务，写密集任务应避免冲突。

## Feasibility

- Verdict: go
- Constraints:
  - 本交付只创建开发工具、数据集、CI 和文档，不修改在线业务行为、部署拓扑或数据库。
  - CI 必须完全确定性，不调用付费模型、不依赖 API Key、不自动提交、推送或部署。
  - GoldenCase 基准使用历史提交和临时 git worktree；评分器不以“与 oracle patch 完全相同”作为正确性标准。
  - Skill 与 SubAgent 不得扩大当前会话权限；危险或外部副作用仍需显式授权。
- Dependencies:
  - Node.js 20（零第三方依赖的 `.mjs` CLI 与 `node:test`）。
  - Git；完整历史仅在手工准备历史 Case 时需要，CI 的静态校验不要求完整历史。
  - 本机 Skill 语法验证使用系统 `skill-creator/scripts/quick_validate.py`；CI 由仓库内校验器复核必要不变量。
- Risks and mitigations:
  - Skill 数量过多造成触发冲突：使用 `platform-*` 前缀、互斥描述和单一职责。
  - SubAgent 并行写冲突：项目自定义 Agent 默认只读；实现工作由主 Agent 或单一 worker 串行接管。
  - GoldenCase 只测格式不测能力：评分器必须实际检查 diff 范围并执行 case verification commands；种子 Case 来自真实历史改动。
  - Case 命令成为任意代码执行入口：manifest 只接受参数数组，执行前校验命令 allowlist、工作目录和路径边界；运行历史 Case 仍视为本地开发操作。
  - 历史 commit 在浅克隆缺失：CI 只做 schema/静态验证；`prepare` 在缺 ref 时给出可操作错误，不自动联网 fetch。

## Product Design

- Actors and goals:
  - Java 工程师：调用聚焦 Skill 完成需求、遗留维护、线上排障、评审、QA 和 PR 打包。
  - 技术负责人：用统一的交付证据、风险门禁和报告判断 Agent 产出是否可接受。
  - 平台/质量工程师：维护 GoldenCase、回放历史任务、比较模型或工作流版本。
  - 团队讲师：使用标准化 playbook 和培训提纲推广 AI-Native 开发方式。
- Scope:
  - 6 个仓库级 Skills。
  - 4 个项目级只读/评审型自定义 SubAgents。
  - Coding GoldenCase v1 schema、20 个真实历史种子 Case、CLI、确定性评分和汇总报告。
  - 单元测试、GitHub Actions 门禁、开发手册、培训提纲和文档入口。
- Out of scope:
  - 自动调用 Codex/Claude API、自动创建 PR、commit、push 或部署。
  - 对当前业务模块做功能修改。
  - 在线排行榜或 Web UI。
  - 搜索/导航/C++ 示例工程。
  - 宣称模型带来的生产率提升；首期只提供度量方法和可复现基线工具。
- Business rules:
  - Skill 产出必须带代码证据和实际命令结果，不得把计划或推断写成“测试通过”。
  - 研究、评审、QA 可并行；同一文件集合的实现任务不得由多个 Agent 并行修改。
  - GoldenCase 的 correctness 以构建、测试、边界约束和 case rubric 为准，oracle diff 只用于参考。
  - 数据集、case、评分报告和工具版本必须可追踪；字段缺失或 schema 不兼容时 fail closed。

## Acceptance Criteria

| ID | Observable behavior | Priority | Verification |
| --- | --- | --- | --- |
| AC-01 | `.agents/skills` 包含 6 个职责互斥、可发现且通过 Skill 校验的 `platform-*` Skills | Must | `quick_validate.py` + 仓库校验器 |
| AC-02 | `.codex/agents` 包含 investigator、architect、reviewer、qa 四个项目 Agent，均有明确输入输出、停止条件和只读边界 | Must | TOML 静态校验 + 内容审查 |
| AC-03 | Playbook 明确定义需求→探索→设计→实现→评审→QA→文档→PR 的交接合同、并行规则和人工审批点 | Must | 文档审查 + 链接校验 |
| AC-04 | GoldenCase v1 至少包含 20 个唯一、版本化、来自真实历史的种子 Case，覆盖 feature、legacy、bug、security、review/doc/safety | Must | `node tools/coding-agent-eval/cli.mjs validate` |
| AC-05 | CLI 支持 `validate`、`list`、`prepare`、`score`、`report`，错误输入返回非零退出码和明确错误 | Must | `node --test` + CLI 冒烟 |
| AC-06 | `score` 能检查变更文件范围、禁止路径、验证命令结果和 rubric 权重，并生成机器可读 JSON | Must | fixture 单元/集成测试 |
| AC-07 | 测试覆盖重复 ID、未知 schema、路径逃逸、非法命令、缺失 git ref、超范围 diff、失败命令和成功评分 | Must | `node --test tools/coding-agent-eval/test/*.test.mjs` |
| AC-08 | GitHub Actions 在相关文件变化时以只读权限运行 Skill/Agent/Case 校验和 Node 测试，不需要秘密或模型调用 | Must | workflow 审查 + 本地执行底层命令 |
| AC-09 | 团队文档包含使用示例、指标定义、风险分级、培训提纲和 30→50 Case 扩展方法 | Should | 文档审查 |
| AC-10 | 最终 diff 不包含或覆盖进入本任务前已有的 README、deploy、operations 用户改动 | Must | 前后 `git status --short` 与 scoped diff |

## UI/UX Design

- Applicability: Not applicable。本交付面向 Codex CLI/IDE、GitHub Actions 和工程文档，不新增用户界面。
- Flow and component map: CLI 命令与报告格式在技术方案中定义。
- State matrix: CLI 对合法、非法、缺 ref、验证失败和部分得分分别返回确定性状态与退出码。
- Responsive and accessibility behavior: Not applicable。

## Technical Solution

- Chosen approach:
  - 使用官方支持的 `.agents/skills/<skill>/SKILL.md` 承载仓库级工作流。
  - 使用 `.codex/agents/*.toml` 承载项目级研发角色；写操作仍由主 Agent/单一 worker 负责。
  - 使用零依赖 Node.js CLI 管理历史 Coding GoldenCase，Case manifest 为 JSON。
  - CI 只验证工具、配置与数据集；真实模型 benchmark 在受控本地/独立 CI job 手工触发。
- Alternatives rejected:
  - 单一“大而全” Skill：描述容易误触发，条件分支过多，不利于岗位展示和独立演进。
  - 立即打包 Plugin：官方建议 repo-scoped workflow 直接使用 Skill；跨仓库分发稳定后再插件化。
  - 新增 Maven 业务模块：会污染产品 reactor，Coding Agent 评测不是在线业务域。
  - CI 直接调用模型：非确定、需凭据并产生费用，无法作为基础质量门禁。
  - oracle patch 完全匹配：会错误拒绝语义正确但实现不同的方案。
- Modules and file map:
  - `.agents/skills/platform-java-feature/{SKILL.md,agents/openai.yaml}`
  - `.agents/skills/platform-legacy-maintenance/{SKILL.md,agents/openai.yaml}`
  - `.agents/skills/platform-prod-debug/{SKILL.md,agents/openai.yaml}`
  - `.agents/skills/platform-diff-review/{SKILL.md,agents/openai.yaml}`
  - `.agents/skills/platform-qa/{SKILL.md,agents/openai.yaml}`
  - `.agents/skills/platform-pr-package/{SKILL.md,agents/openai.yaml}`
  - `.codex/agents/{platform-investigator,platform-architect,platform-reviewer,platform-qa}.toml`
  - `tools/coding-agent-eval/cli.mjs`
  - `tools/coding-agent-eval/lib/{manifest,git,scorer,report}.mjs`
  - `tools/coding-agent-eval/test/`（测试及临时 fixture）
  - `docs/qa/coding-agent-golden/manifest.json`
  - `docs/qa/coding-agent-golden/cases/*.json`（20 个）
  - `docs/平台工程/coding-agent-playbook.md`
  - `docs/平台工程/ai-coding-training-outline.md`
  - `.github/workflows/coding-agent-kit-ci.yml`
  - `AGENTS.md`、`docs/README.md`（仅增加入口和适用规则）
  - `docs/delivery/coding-agent-engineering-kit/*`
- Contracts and data:
  - Dataset manifest：`schemaVersion`、`datasetId`、`version`、`toolVersion`、case 列表和内容 digest。
  - Case：`id/kind/title/baseRef/oracleRef/prompt/riskTags/allowedPaths/forbiddenPaths/verification/scoring`。
  - Verification command 采用字符串参数数组，不接受 shell 拼接字符串。
  - Score report：case/dataset/tool 版本、workspace/base、changed files、checks、各维分数、总分、verdict、时间戳。
- Security and reliability:
  - CLI 不执行 commit/push/deploy，不读取或打印环境秘密。
  - `prepare` 只在显式命令下创建临时 worktree；不自动 fetch，不覆盖当前工作树。
  - path 先做 realpath/仓库边界校验；清理仅针对 CLI 自己创建并登记的临时目录。
  - 命令 executable allowlist 初始限定为 `mvn`、`node`、`npm`、`bash` 和 `git` 的只读子命令；非法 manifest fail closed。
  - SubAgent 继承父会话权限；项目 Agent 指令不能绕过审批或扩大权限。
- Observability:
  - CLI 使用结构化 JSON report；控制台只输出 case、阶段、耗时、分数和失败摘要，不输出业务秘密。
  - 报告保留 dataset/tool/case 版本，便于不同模型和工作流横向比较。
- Compatibility and migration:
  - 不改变 Maven、服务 API、数据库、部署和现有 CI。
  - Skills 为纯加法；若当前 Codex 会话未刷新发现列表，重启 Codex 后生效。
  - 历史 Case ref 缺失时返回 blocked，不修改 ref 或自动联网。

## Implementation Sequence

1. 建立共享 playbook、6 个 Skills 和 4 个只读 Agent；完成 AC-01～AC-03。
2. 定义 GoldenCase v1 manifest/case/report 合同，建立 20 个历史种子 Case；完成 AC-04。
3. 实现 CLI 的 validate/list，再实现安全的 prepare/score/report；完成 AC-05～AC-06。
4. 增加正常与对抗性 fixture 测试，修复评分器问题；完成 AC-07。
5. 接入 GitHub Actions，更新 AGENTS/docs 入口和培训提纲；完成 AC-08～AC-09。
6. 对最终实际 diff 做安全、正确性、可维护性评审与本地 QA；完成 AC-10 并生成交付报告。

## Verification Plan

| AC/Risk | Test level | Case or command | Required evidence |
| --- | --- | --- | --- |
| AC-01 | Static | 对 6 个目录运行 `quick_validate.py` | 每个 exit 0 |
| AC-01/02 | Static | `node tools/coding-agent-eval/cli.mjs validate-kit` | Skill/Agent 必填字段和名称通过 |
| AC-04 | Dataset | `node tools/coding-agent-eval/cli.mjs validate` | 20 Case、唯一 ID、digest/schema 通过 |
| AC-05/06/07 | Automated | `node --test tools/coding-agent-eval/test/*.test.mjs` | 全部测试通过 |
| AC-05 | Smoke | `node .../cli.mjs list`、在临时 fixture 执行 prepare/score/report | 退出码、JSON 和清理证据 |
| Command injection/path escape | Negative | 非数组命令、`../` 路径、未允许 executable fixture | fail closed |
| Missing history | Negative | 不存在的 `baseRef` | blocked/non-zero，不自动 fetch |
| AC-08 | CI | 本地运行 workflow 的底层命令，检查 permissions/action SHA | 命令通过、无 secrets/model call |
| AC-10 | Diff | `git status --short`、`git diff -- <本交付路径>`、`git diff --check` | 无既有改动混入、无 whitespace 错误 |

## Documentation Plan

- 新增 Coding Agent Playbook：角色、工作流、风险等级、交接合同、并行边界、指标和操作示例。
- 新增培训提纲：60～90 分钟课程、演示 Case、练习和推广度量。
- 更新 `docs/README.md` 增加入口。
- 更新根 `AGENTS.md`，说明何时使用仓库 Skills/Agent，以及 GoldenCase 变更必须运行的命令。
- 最终报告引用 OpenAI 官方 Skills、SubAgents 和 `AGENTS.md` 文档作为目录设计依据。

## CI Plan

- 新增 `Coding Agent Kit CI`，仅监听 `.agents/**`、`.codex/agents/**`、`tools/coding-agent-eval/**`、`docs/qa/coding-agent-golden/**`、相关文档和自身 workflow。
- `permissions: contents: read`，固定 action commit SHA，不上传秘密，不运行模型。
- 使用 Node 20，执行 kit 校验、dataset 校验、Node tests 和 CLI list smoke。
- CI 不准备历史 worktree、不执行 Maven 全仓测试；本交付不改变 Java 产品代码。

## Rollout And Rollback

- Rollout:
  1. 合并后由工程师在新 Codex 会话检查 6 个 Skills 和 4 个 Agent 是否可发现。
  2. 先选 3 个历史 Case 做人工 benchmark，再扩大到 20 个。
  3. 收集完成率、首次验证通过率、人工介入、耗时和成本；没有基线前不宣称效率提升。
  4. 稳定后将 Case 扩到 30～50，并考虑打包为团队 Plugin。
- Rollback:
  - 删除/回退 `.agents/skills` 和 `.codex/agents` 即停止自动发现。
  - 回退独立 CI workflow 不影响现有产品 CI。
  - 评测工具和数据集均与在线服务解耦，无数据库或运行时回滚步骤。

## Assumptions And Open Decisions

- 假设首期目标是补齐 P0 Coding Agent 工程能力，而非同时实现导航引擎。
- 假设使用 Codex 官方 repo-scoped 目录，并保持 Claude Code 通过现有 `CLAUDE.md` 获得基础兼容；首期不复制一套 Claude plugin。
- 假设初始 20 个 Case 可从本仓库现有历史 commit 中选择，且不需要公开业务秘密。
- 假设项目级自定义 Agent 均以读密集角色为主；不新增并行写 Agent。
- 导航/C++ 能力将在本交付完成后建立单独计划，避免污染当前 Java 平台仓库；可选在同级新仓库实现。

## Approval

- Status: approved
- Approved scope: P0 Coding Agent Engineering Kit 的 Skills、SubAgents、GoldenCase、确定性评测、CI、团队规范与培训材料。
- Evidence: 用户于 2026-08-31 明确回复“批准该计划”。
