# QA Report

## Environment Profile

- Target: 本地仓库与隔离临时 git worktree
- Version or commit: `main` / `88b7d4bf96359bfda58b019eb7822a9629d2edf2` + 当前未提交交付 diff
- Runtime: macOS、Node.js `v24.12.0`、Git、本机 Maven/JDK 环境；CI 目标 Node.js 20
- Test data: `langchain4j-platform-history` v1.0.0，20 个真实历史 Case，digest `sha256:a2ddf57e4ad64171571229311ecc8b7e52ed37cca3263c77b98243876a128271`
- Known environment limitations: 未触发远端 GitHub-hosted Node 20 runner；本交付没有 Java 产品代码，不运行全仓 Maven reactor。

## Cases

| ID | AC/Risk | Setup and steps | Expected | Actual/evidence | Verdict |
| --- | --- | --- | --- | --- | --- |
| QA-SKILL-01 | AC-01 | 用 `/usr/bin/python3` 对 6 个 Skill 运行官方 `quick_validate.py` | 6 个均合法 | 6 次 `Skill is valid!` | pass |
| QA-KIT-01 | AC-01/02 | `node tools/coding-agent-eval/cli.mjs validate-kit` | 6 Skill、4 Agent | JSON: `skills=6, agents=4, status=valid` | pass |
| QA-DATA-01 | AC-04 | `node tools/coding-agent-eval/cli.mjs validate` | schema、唯一 ID、类型、命令、digest 全部合法 | `cases=20`，digest 匹配 | pass |
| QA-HISTORY-01 | AC-04/06 | 对所有 Case 检查 base/oracle ref，并比对 oracle diff 与 allow/forbid matcher | 40 个 ref 存在，20 个 oracle diff 不越界 | `historical refs and oracle scopes: 20 valid` | pass |
| QA-CLI-01 | AC-05/07 | Node test 执行 validate/list/score/report 及非法参数 | 正常为 0；非法为 2；缺 ref 为 3；失败评分为 4 | 自动化测试覆盖四类退出码 | pass |
| QA-SEC-01 | AC-06/07 | 路径逃逸、Case symlink、cwd symlink、非法 executable/Node/npm/Maven/git flag | 全部 fail closed | manifest/scorer 对抗测试通过 | pass |
| QA-SCORE-01 | AC-06/07 | 临时 git repo 中测试合法 diff、越界 untracked file、失败命令 | 100/pass、fail、fail | scorer 自动化测试通过 | pass |
| QA-ORACLE-01 | AC-05/06 | `prepare --oracle` 回放 `frontend-health-endpoint`，再 score/report | 路径、命令、rubric 均通过并可汇总 | 单 Case `total=100, verdict=pass`，汇总 `passRate=100` | pass |
| QA-REPORT-01 | AC-06/07 | 汇总合法报告与 stale dataset 报告 | 合法汇总；stale digest 拒绝 | 两项自动化测试通过 | pass |
| QA-CI-01 | AC-08 | Ruby YAML parse + 审查 permissions/action SHA + 本地运行底层命令 | 语法合法、只读、不需 secret/model | YAML valid；`contents: read`；底层命令通过 | pass |
| QA-DOC-01 | AC-03/09 | 检查 playbook、培训提纲、AGENTS/docs 入口 | 流程、指标、风险、培训、30→50 方法齐全 | 文档与入口存在，命令和退出码与代码一致 | pass |
| QA-DIFF-01 | AC-10 | 前后 `git status --short` 与 scoped diff | 原 6 个用户改动未被本任务覆盖 | 原文件仍仅显示原有 modified；任务改动位于独立路径及两个入口文件 | pass |
| QA-STATIC-01 | 全局 | Node `--check`、JSON parse、TOML/YAML parse、secret/TODO/trailing whitespace scan | 全部无错误 | 所有检查通过 | pass |

## Defects And Retests

- 修复 verification cwd symlink 逃逸、Case symlink 逃逸、报告 dataset 串用、完整环境继承和危险命令参数；对应对抗测试加入回归集并通过。
- `quick_validate.py` 在 PATH 默认 Python 3.14 下缺 `PyYAML`；改用系统 `/usr/bin/python3`（已有 PyYAML）执行同一官方脚本，6 个 Skill 均通过，无需联网安装。

## Automated Regression

- `node --test tools/coding-agent-eval/test/*.test.mjs`：最终 23/23 通过（以 Gate B 最终重跑为准）。
- `validate-kit`、`validate`：通过。
- 真实 oracle worktree prepare→score→report：通过，临时 worktree 已解除注册，临时目录已移入系统废纸篓。

## Blocked External Checks

- GitHub-hosted `Coding Agent Kit CI` 首次远端运行需在 push/PR 后由 GitHub 执行；本地已验证 YAML 与全部底层命令。

## Verdict

pass
