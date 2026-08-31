# Code Review Report

## Scope And Diff Base

- Diff base: `HEAD` (`88b7d4bf96359bfda58b019eb7822a9629d2edf2`)
- Scope: `.agents/skills/**`、`.codex/agents/**`、`tools/coding-agent-eval/**`、Coding GoldenCase、两份平台工程文档、独立 CI、`AGENTS.md`/`docs/README.md` 入口及本交付文档。
- Excluded pre-existing user changes: `README.md`、`deploy/docker-compose.yml`、`deploy/start-{all,dev,local}.sh`、`docs/参考/operations.md`。
- Review method: final-file static inspection, command/schema adversarial tests, real historical oracle worktree smoke, secret/placeholder/whitespace scan, CI syntax and permission review.

## Confirmed Findings

| Severity | Finding | Failure scenario | Evidence | Resolution |
| --- | --- | --- | --- | --- |
| High | verification `cwd` 最初只做词法边界检查，未覆盖目录内 symlink | 被篡改 Case 可把 `cwd` 指向仓库内 symlink，再让 npm/bash 在工作区外执行 | `tools/coding-agent-eval/lib/scorer.mjs:33`；`tools/coding-agent-eval/test/scorer.test.mjs:80` | 对 workspace 与 cwd 使用 `realpath`，越界 fail closed；新增 symlink 对抗测试 |
| Medium | 汇总器最初没有绑定 dataset digest 与 case ID | 不同数据集或旧工具产生的高分报告可能被混入当前基线 | `tools/coding-agent-eval/lib/report.mjs:12` | 强制 toolVersion、dataset digest、已知/唯一 case ID、verdict/score 完整性；新增 stale digest 测试 |
| Medium | 验证子进程最初继承完整环境，git 只读子命令仍可使用输出/外部 helper 参数 | Case 命令可能暴露 API token，或用 `git diff --output` 写工作区外文件 | `tools/coding-agent-eval/lib/manifest.mjs:68`；`tools/coding-agent-eval/lib/scorer.mjs:14` | 环境缩减到构建所需白名单；禁止 git 重定向/helper、npm exec/prefix、Node inline eval、Maven install/deploy/exec |
| Medium | Case 文件路径最初只做 lexical path 检查 | dataset 内 symlink 可让 validator 读取目录外 JSON | `tools/coding-agent-eval/lib/manifest.mjs:237` | manifest 与 Case 使用 `realpath`，真实路径必须位于 dataset 根内；新增 Case symlink 测试 |

## Rejected Suspicions

| Suspicion | Why rejected | Evidence |
| --- | --- | --- |
| 评分器要求候选 patch 与 oracle 完全一致 | 得分只基于变更范围、验证命令和 rubric 证据；`oracleRef` 仅记录与校准 | `tools/coding-agent-eval/lib/scorer.mjs:24`、`:53` |
| CI 会调用模型、使用 secret 或写仓库 | workflow 只有 checkout、Node 校验/测试/list，权限为 `contents: read` 且 checkout 不保留凭据 | `.github/workflows/coding-agent-kit-ci.yml:31`、`:42`、`:50` |
| 自定义 Agent 可能并行覆盖源码 | 四个 TOML 均为 `sandbox_mode = "read-only"`，写操作在 playbook 中归属主 Agent/单一 worker | `.codex/agents/*.toml`；`docs/平台工程/coding-agent-playbook.md` |
| 历史 Case 的路径白名单与 oracle commit 不一致 | 对 20 个 base/oracle ref 实际执行 `git diff --name-only -z` 并按相同 matcher 检查，全部通过 | QA `QA-HISTORY-01` |

## Checks Rerun After Fixes

- `node --test tools/coding-agent-eval/test/*.test.mjs`
- `node tools/coding-agent-eval/cli.mjs validate-kit`
- `node tools/coding-agent-eval/cli.mjs validate`
- 20 个历史 ref 存在性与 oracle diff scope 脚本
- 6 个 Skill 的官方 `quick_validate.py`
- Node syntax、JSON、TOML、YAML、workflow、secret、placeholder、trailing-whitespace 检查

## Residual Risks

- `score` 会执行候选 worktree 中的构建/测试代码，命令白名单不是代码沙箱。手册已要求对不可信 patch 使用无生产凭据、网络和共享写权限的临时容器/沙箱。
- 本地只验证了 GitHub Actions 的 YAML 和底层命令，远端 runner 尚未实际执行。
- 20 个 Case 是首批基线，尚不能代表完整生产分布；应先 shadow 校准再扩大到 30～50。

## Verdict

pass
