# Delivery Status

## Goal

交付仓库级 Coding Agent Engineering Kit，包括 Skills、研发型 SubAgents、Coding GoldenCase、确定性评测 CLI、CI 门禁和团队推广文档。

## State

- Phase: Gate B - complete
- Status: complete
- Last updated: 2026-08-31

## Completed

- 用户于 2026-08-31 明确批准 `DELIVERY_PLAN.md`，Gate A 通过。
- Slice 1（AC-01～03）：交付 6 个仓库级 Skill、4 个只读 Agent 和研发交接/审批规则。
- Slice 2（AC-04）：交付 20 个真实历史 GoldenCase 与版本/digest 合同，逐条核验 ref 和 oracle scope。
- Slice 3（AC-05～06）：交付 validate/list/prepare/score/report CLI、临时 worktree、hard gate、结构化评分和汇总。
- Slice 4（AC-07）：交付正常与对抗性 Node 测试，并在评审中补齐 symlink、报告串用、命令参数和环境泄露防护。
- Slice 5（AC-08～09）：交付只读 GitHub Actions、团队手册、培训提纲和仓库入口。
- Phase 6/7：完成实际 diff 评审、修复、真实 oracle 闭环和本地 QA；review/QA verdict 均为 pass。
- Gate B：23/23 自动化测试及全部静态、数据、历史 ref、Skill、CI 底层检查通过，10 项 AC 全部有证据。
- 完成仓库结构、项目指令、Git 历史、现有评测控制面、长任务协作规则和 GitHub Actions 约定盘点。
- 核对 OpenAI 官方 Codex Skills、SubAgents 和 `AGENTS.md` 的仓库目录与使用边界。
- 完成可行性、产品设计、技术方案、10 项验收标准、实现顺序、验证、CI、推广与回滚计划。
- UI/UX 判定为不适用：本交付不新增用户界面。

## Changed Files

- `docs/delivery/coding-agent-engineering-kit/DELIVERY_PLAN.md` - Gate A 交付计划与验收矩阵。
- `docs/delivery/coding-agent-engineering-kit/DELIVERY_STATUS.md` - 工作流状态和恢复入口。
- `.agents/skills/platform-*` - 6 个职责互斥的仓库 Skill 与 UI metadata。
- `.codex/agents/*.toml` - 4 个只读研发角色。
- `tools/coding-agent-eval/**` - 零依赖 CLI、库和测试。
- `docs/qa/coding-agent-golden/**` - manifest 与 20 个历史 Case。
- `.github/workflows/coding-agent-kit-ci.yml` - 只读确定性 CI。
- `docs/平台工程/coding-agent-playbook.md` - 工作流、风险、评测和指标手册。
- `docs/平台工程/ai-coding-training-outline.md` - 课程、演示、练习和推广提纲。
- `AGENTS.md`、`docs/README.md` - 维护规则与文档入口。
- `REVIEW_REPORT.md`、`QA_REPORT.md`、`DELIVERY_REPORT.md` - Gate B 证据。

## Verification Log

| Command or check | Result | Notes |
| --- | --- | --- |
| `git branch --show-current` | pass | 当前 `main` |
| `git status --short` | pass | 识别并记录 6 个任务前已有用户改动，实施时必须避开 |
| `rg --files .github` | pass | GitHub Actions 为现有 CI provider |
| OpenAI 官方 Skills 文档核对 | pass | repo Skill 使用 `.agents/skills` |
| OpenAI 官方 SubAgents 文档核对 | pass | project Agent 使用 `.codex/agents`；读密集并行优先 |
| 计划文件人工结构检查 | pass | 覆盖 delivery artifact contract 要求 |
| Gate A approval | pass | 用户明确回复“批准该计划” |
| 6 × official `quick_validate.py` | pass | `/usr/bin/python3`，6/6 valid |
| `node .../cli.mjs validate-kit` | pass | 6 Skills、4 Agents |
| `node .../cli.mjs validate` | pass | 20 Cases，digest 匹配 |
| Node evaluator tests | pass | 最终 23/23（以 Gate B 最终重跑为准） |
| Historical ref/oracle scope check | pass | 20/20 |
| Real oracle prepare/score/report | pass | `frontend-health-endpoint` 100/pass |
| Node/JSON/TOML/YAML/workflow static checks | pass | 语法与结构合法 |
| Secret/TODO/trailing whitespace scan | pass | 无发现 |

## Decisions And Deviations

- 将补强工作拆成两条交付线：本轮 P0 Coding Agent Engineering Kit；后续搜索/导航/C++ 独立规划。
- 选择 6 个窄 Skill，而非一个巨型 Skill。
- 选择 repo-scoped Skills，而非首期 Plugin。
- 选择零依赖 Node CLI，避免污染 Maven 产品 reactor。
- CI 不调用模型，真实 benchmark 作为手工或后续受控 job。
- 评审后增加 realpath、环境缩减、危险参数和 stale report 防护，未扩大公开范围。

## Blockers And Residual Risks

- 当前工作区仍有任务前用户未提交改动；最终 scoped diff 已确认未覆盖。
- GitHub-hosted Node 20 workflow 尚待 push/PR 后首跑。
- 对不可信候选 patch，`score` 必须在额外容器/沙箱执行；命令白名单不等价于代码沙箱。

## Next Action

合并后新开 Codex 会话确认 Skill/Agent 发现，并按 playbook 先运行 3 个 shadow Case；远端 GitHub Actions 首跑后归档结果。
