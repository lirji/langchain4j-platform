# Diff Review

## Scope

按 `platform-diff-review` 对实际 P0+P1/P2 diff 及可达执行链评审；重点检查命令执行、凭据、fail-open、恢复、清理、超时、数据集和 CI。评审期间执行 Node tests、Docker smoke、oracle/candidate benchmark、历史 audit、代码图 fixture/全仓扫描和 secret/whitespace 检查。

## Findings and resolution

| Severity | Failure scenario and evidence | Resolution |
| --- | --- | --- |
| High | Case 标为 `running` 后原先未立即写 checkpoint；进程中断会以 attempt 1 重跑，而已有 raw attempt-1 文件使恢复变为 infra error。证据：`tools/coding-agent-eval/lib/benchmark.mjs:154` 的状态转换链。 | 在调用 candidate 前原子写入 `running/attempts/startedAt`（`:154-159`）；新增测试在 candidate stub 内读取磁盘 checkpoint。 |
| High | timeout 发出 SIGTERM 后的 2 秒 SIGKILL timer 原先不会在子进程提前退出时取消，PID/进程组被快速复用时可能误杀无关进程。证据：`tools/coding-agent-eval/lib/candidate.mjs:64-72`。 | 保存 hard-kill timer，并在 close 时清除；stdin error 也被消费，避免 EPIPE 终止 runner。 |
| High | Codex plan 的 `model:null` 可随用户配置静默变化，且候选会读取全局 Skill/config，不适合后续可比基线。证据：`benchmark.mjs:27-50`、`candidate.mjs:32-39`。 | 新计划强制显式 `--model`；候选增加 `--ignore-user-config`、shell 环境零继承和禁止读取 worktree 外文件。已完成的 20-call 初始报告保留 `model:null` 并明确标注限制，未伪造模型字段。 |
| Medium | `cleanupWorktree` 只信任可伪造 marker，若被库调用者传入构造 metadata，理论上可递归删除任意 marker 父目录。证据：`tools/coding-agent-eval/lib/git.mjs:88-111`。 | 目标必须是 `realpath(os.tmpdir())` 下、名称匹配 Case 前缀且 workspace 固定为子目录 `worktree`；增加拒绝仓库内目标测试。 |
| Medium | 汇总器原先吞掉损坏/缺失 score report，只降低 coverage，可能让被篡改 run 仍生成“完整”摘要。证据：`tools/coding-agent-eval/lib/benchmark.mjs` 的 `generateBenchmarkReport`。 | 对所有带 score 的终态强制读取对应报告；缺失或非法 JSON 直接失败。 |
| Medium | Java scanner 的 `Files.isRegularFile` 默认跟随 symlink，可能读取仓库外 `.java`；同名同参数个数的 overload 还会产生重复 method ID。证据：`tools/java-codegraph/CodeGraphCli.java:136-144,248-259`。 | 排除 symbolic link；method ID 纳入参数类型签名；fixture 增加 overload 唯一性断言；输出 root 改为 `.` 避免泄露绝对路径。 |
| Medium | 宿主只读 `git grep --open-files-in-pager=<cmd>` 的等号形式未被阻断，可启动外部 pager。证据：`tools/coding-agent-eval/lib/manifest.mjs:83-94`。 | 改为前缀阻断并增加对抗测试。 |
| Low | 脱敏仅覆盖 sk/Bearer/AKIA，遗漏 GitHub、Slack、Google、JWT 和字符串内 `password=...`。 | 扩充 value/inline assignment 规则并新增回归断言。 |

## Rejected suspicions

- Maven 用户 settings 泄露：未发现。Docker 只挂载 Maven 安装目录、有效本地 repository 和仓库内无凭据 settings；用户 `settings.xml` 未挂载。
- Docker host fallback：未发现。镜像缺失测试确认 fail closed；唯一宿主检查是 allowlist 内只读 Git，并在报告中标为 `host-readonly`。
- 原有 README/deploy/operations 变更被覆盖：未发现。本轮未编辑进入任务前已有的 `README.md`、三个启动脚本、compose 和 operations 修改；它们仍按原状态保留。
- 实际 baseline 有凭据读取：raw command audit 发现候选读取了全局 Skill 和本地 Maven jar，但未发现 `.ssh`、云凭据、token 或 `printenv` 读取。未来 adapter 已进一步隔离全局配置与 shell 环境。

## Gate result

代码级 High/Medium findings 均已修复并有测试或可复现证据。唯一保留限制是首轮 20-call PLAN 未固定模型名；因授权调用已用满，不重跑或伪造，报告已清晰披露，未来计划会 fail closed。
