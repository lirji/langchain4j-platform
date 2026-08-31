# Delivery Report

## Outcome

已交付 P0 Coding Agent Engineering Kit：6 个仓库级 Skill、4 个只读研发 Agent、20 个真实历史 GoldenCase、零依赖 Node.js 评测 CLI、对抗性测试、只读 GitHub Actions、团队工作手册和培训提纲。没有改变 Java 在线业务、数据库或部署拓扑。

## Requirement Coverage

| AC | Implementation evidence | Verification evidence | Status |
| --- | --- | --- | --- |
| AC-01 | `.agents/skills/platform-*` 6 个独立 Skill 与 UI metadata | 官方 `quick_validate.py` 6/6；`validate-kit` | met |
| AC-02 | `.codex/agents/{investigator,architect,reviewer,qa}.toml`，均 read-only 且有输入/输出/停止边界 | TOML parse + `validate-kit` | met |
| AC-03 | `docs/平台工程/coding-agent-playbook.md` 的八阶段交接、并行和审批合同 | QA-DOC-01 | met |
| AC-04 | v1 manifest + 20 个历史 Case，覆盖七类 kind | dataset validate；40 ref 存在；20 oracle scope 匹配 | met |
| AC-05 | CLI `validate/list/prepare/score/report`，另有 `validate-kit` | CLI 正常/2/3/4 退出码与 oracle smoke | met |
| AC-06 | path hard gate、命令结果、rubric、结构化 JSON 与 digest-bound report | scorer/report 自动化测试 + oracle 100 分 | met |
| AC-07 | `node:test` 覆盖 schema、重复、逃逸、命令、ref、越界、失败与成功 | 最终 23/23 | met |
| AC-08 | `.github/workflows/coding-agent-kit-ci.yml` | YAML parse、固定 action SHA、`contents: read`、底层命令通过 | met（远端首跑待 push/PR） |
| AC-09 | playbook、培训提纲、指标、风险、30→50 扩展方法 | QA-DOC-01 | met |
| AC-10 | scoped 新增路径，仅更新 `AGENTS.md` 和 `docs/README.md` 入口 | status/scoped diff；原有 6 个用户改动保持 | met |

## Changed Files

- `.agents/skills/`：6 个 Skill 的 `SKILL.md` 与 `agents/openai.yaml`。
- `.codex/agents/`：4 个只读研发 Agent TOML。
- `tools/coding-agent-eval/`：CLI、manifest/git/scorer/report 库与 3 组测试。
- `docs/qa/coding-agent-golden/`：v1 manifest 与 20 个历史 Case。
- `.github/workflows/coding-agent-kit-ci.yml`：确定性只读 CI。
- `docs/平台工程/coding-agent-playbook.md`、`ai-coding-training-outline.md`：使用、风险、指标和推广材料。
- `AGENTS.md`、`docs/README.md`：仓库规则与文档入口。
- `docs/delivery/coding-agent-engineering-kit/`：计划、状态、评审、QA 和交付证据。

## Build And Test Results

- Node evaluator tests：最终 23/23 pass。
- Skill official validator：6/6 pass。
- Kit validation：6 Skills + 4 Agents pass。
- Dataset validation：20 Cases + SHA-256 digest pass。
- Historical ref/scope：20/20 pass。
- Real oracle smoke：prepare→score(100/pass)→report(100% pass) 成功。
- Node syntax、JSON、TOML、YAML、workflow、secret、placeholder、trailing whitespace：pass。

## Code Review And QA Verdicts

- Review: pass；审查中确认并修复 1 个 High、3 个 Medium，最终无未解决 Critical/High。
- QA: pass；远端 GitHub-hosted runner 首跑列为外部待办，不影响本地确定性结果。

## Documentation Changes

- 新增完整 Coding Agent 工作流、角色/Skill 路由、交接合同、风险审批、评测命令、报告解释、基线指标和 Case 扩展方法。
- 新增 60～90 分钟团队培训课程、演示脚本、对抗练习和推广节奏。
- 根 Agent 规范与文档索引增加维护入口。

## CI Changes And Validation

- 相关路径变化触发 `Coding Agent Kit CI`。
- GitHub 权限仅 `contents: read`；checkout 固定 SHA 且 `persist-credentials: false`；Node setup 固定 SHA。
- CI 只执行 kit/data validation、Node tests 和 list smoke，不读取 secret、不调用模型、不准备 worktree、不修改外部状态。

## Deviations From Plan

- 计划中的风险项在评审中进一步收紧：增加 symlink realpath 防护、报告 digest/case 去重、环境变量缩减、git/npm/Node/Maven 危险参数限制。
- 自动化测试从最低验收清单扩展为最终 23 条，并增加真实 oracle 闭环。
- 默认 PATH 中的 Python 缺 PyYAML，改用已安装 PyYAML 的系统 `/usr/bin/python3` 执行官方 Skill validator；未联网安装依赖。

## Rollout, Monitoring, And Rollback

- 合并后新开 Codex 会话确认 6 Skill 和 4 Agent 可发现；先用 3 个 Case 做 shadow benchmark，再扩大到 20。
- 固定 dataset digest 后收集完成率、首次通过率、人工介入率、交付时长、成本和越界率；没有稳定基线前不阻断合并。
- 回滚只需回退独立 Skill/Agent/tool/dataset/workflow 文件，不影响服务、数据库或部署。

## Remaining Risks Or External Actions

- 推送或创建 PR 后观察 GitHub Actions 的首次 Node 20 运行。
- 不可信候选 patch 必须在额外容器/沙箱中评分；本工具的命令白名单不等于代码执行隔离。
- 按真实使用反馈校准当前 20 Case，再扩展到 30～50；导航/C++ 能力仍属于独立后续交付线。
