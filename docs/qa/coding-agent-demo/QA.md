# QA Report

## Target and environment

- Target: Coding Agent Engineering Kit P0 + P1/P2 productionization, dataset digest `sha256:00a0c9553b95b75e53c355ce0aa81928f78885665eeb5a653de3af4019096840`。
- Environment: macOS / Asia-Taipei，JDK 21.0.11、Maven 3.9.12、Node 24.12、Docker 29、Codex CLI 0.151.0。
- Dependencies: 本地 Git 历史、本地 Maven repository、本地 `busybox:latest` 与 `eclipse-temurin:21-jdk`；未 Docker pull、未访问生产。
- Test data: 50 条单父历史 Case；20 core 用于 oracle/Codex，30 extended 做 schema/ref/scope audit。

## Acceptance matrix

| AC | Test / evidence | Actual result | Verdict |
| --- | --- | --- | --- |
| AC-01 | 38 条 Node unit/integration：plan digest、running checkpoint、resume、tamper、timeout、3-infra stop | 全部通过 | pass |
| AC-02 | `2026-08-31-codex-v2/SUMMARY.json` + event digest | 20 个唯一终态：19 pass、1 timeout、0 fail/infra；completion 100% | pass |
| AC-03 | `2026-08-31-oracle-v2/SUMMARY.json` | 20/20、first-pass 100%、越界 0% | pass |
| AC-04 | REQUIREMENT/INVESTIGATION/DESIGN/IMPLEMENTATION/REVIEW/QA/PR_PACKAGE/DEMO_SCRIPT | 证据链完整，未自动创建 PR | pass |
| AC-05 | fixture + 全仓 build | 1,044/1,044 Java，0 failed，7,266 nodes / 62,481 edges | pass |
| AC-06 | symbol/file/ambiguous/not-found/overload fixture；`OrderService` 全仓查询 | 状态与 file:line 证据符合预期 | pass |
| AC-07 | argv contract + 三次最终 smoke | pull-never、断网、只读 source、UID 65532、drop caps、资源限制、timeout 均通过 | pass |
| AC-08 | missing image、source write、timeout、容器残留检查 | 缺镜像不回退；写 source 失败；500ms 回收；0 残留容器 | pass |
| AC-09 | event chain/tamper/secret tests + 两份 summary digest | 篡改 fail closed；常见 secret/home path 脱敏；链有效 | pass |
| AC-10 | `validate` + `audit --repo .` | 50 unique；core/extended=20/30；easy/medium/hard=11/25/14；7 kind；全部 ref/parent/scope 有效 | pass |
| AC-11 | Node 38/38 + codegraph fixture | symlink、pager exec、cleanup scope、overload、checkpoint 等对抗场景通过 | pass |
| AC-12 | Workflow YAML parse、固定 action SHA、最小权限、底层命令本地执行、禁 model/Docker 命令扫描 | 本地通过；远端 GitHub job 未在本会话触发 | pass（本地证据） |
| AC-13 | playbook、sandbox、codegraph、demo、docs index | 链接与行为同步 | pass |
| AC-14 | `git status`、`git diff --check`、secret/absolute-home/trailing-whitespace scan | 无泄密/空白问题；任务前 6 个 README/deploy/operations 修改仍保留且未被本轮编辑 | pass |

## Exact verification

```bash
node tools/coding-agent-eval/cli.mjs validate-kit
node tools/coding-agent-eval/cli.mjs validate
node tools/coding-agent-eval/cli.mjs audit --repo .
node --test tools/coding-agent-eval/test/*.test.mjs
bash -n tools/coding-agent-eval/sandbox/entrypoint.sh \
  tools/coding-agent-eval/test/sandbox-smoke.sh \
  tools/java-codegraph/test/run-tests.sh
bash tools/java-codegraph/test/run-tests.sh
java tools/java-codegraph/CodeGraphCli.java build --root . --output <temp>/graph.json
bash tools/coding-agent-eval/test/sandbox-smoke.sh
git diff --check
```

另执行 workflow YAML parse、CI 禁模型/禁 Docker 命令扫描、secret/绝对 HOME/trailing whitespace 扫描、`git worktree list` 和 Docker container 残留检查，均通过。

## Limitations

- 已批准的 20-call 首轮 PLAN 使用当时本地默认模型并记录 `model:null`；观察配置为 `gpt-5.6-sol`，但事件源未独立返回模型名。未来计划已强制 `--model`，首轮不可用于跨模型精确比较。
- Codex run 的 cost 未由事件源提供，保持 `null`，未推算费用。
- Node Docker profile 镜像本机不存在；已验证缺镜像 fail closed，未获准 pull 因而未运行 Node 容器 smoke。
- 未运行 Maven reactor 全量测试，因为没有修改在线 Java 服务、API、数据库或部署拓扑；20 个 core 的 oracle/candidate Java 编译均已在 Docker 离线执行。
- GitHub Actions 远端执行未触发；本地已运行其确定性底层命令。

## Verdict

Gate B：pass with documented baseline limitation。产品/工具缺陷无未解决 High/Medium；首轮模型身份可比性限制已披露且未来 fail closed。
