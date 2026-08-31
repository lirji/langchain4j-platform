# Coding Agent 评测沙箱

## 目标与边界

评测分为两层：Codex 候选在独立临时 Git worktree 中以 `workspace-write` 运行；候选结束后，Maven/Bash 验证在无网络 Docker 中重新执行。Docker 验证层不等于把 Codex 认证和模型连接搬进容器，两者必须分别记录。

沙箱保护的是宿主凭据、网络和共享写路径，不保证识别所有恶意内核利用。它只用于本地/CI 前的候选验证，不能承载生产密钥或 Docker socket。

## 强制合同

- `--pull=never`、`--network none`、`--read-only`、`--rm`；缺镜像或 daemon 异常时失败，不回退宿主执行。
- `--user 65532:65532`、`--cap-drop ALL`、`no-new-privileges`，并限制 CPU、内存、PID 和单命令时间。
- 候选 worktree 只读挂载到 `/source`；固定入口复制到 `/work` tmpfs 后执行参数数组，不拼接 shell 字符串。
- 不挂载 `$HOME`、`.ssh`、云凭据、Codex Home、Docker socket或 Maven `settings.xml`。
- Maven 只读挂载安装目录和有效本地仓库；仓库内的无凭据 `maven-settings.xml` 只匹配缓存来源 ID。容器始终断网。
- 只读 Git 检查是唯一显式宿主例外，报告标记 `host-readonly`；其他命令必须匹配 Docker profile。

当前 Java profile 使用本地已有的 `eclipse-temurin:21-jdk`。Node profile 指向预制的 `coding-agent-node:local`；镜像不存在时明确失败，不在线 pull。

## 验证

```bash
node --test tools/coding-agent-eval/test/*.test.mjs

# 只在本机已有 busybox:latest 且获准创建本地镜像/容器时运行
bash tools/coding-agent-eval/test/sandbox-smoke.sh
```

smoke 会验证源目录只读、UID 65532、无 Docker socket 和超时回收。构建命令使用 `--pull=false`；生成的 `coding-agent-sandbox-smoke:local` 会保留以便复现，容器均以 `--rm` 回收。

## 故障分类

- 镜像缺失、daemon/入口/挂载异常：`infra_error`；连续三条时 benchmark 停止。
- 候选超过 Case 上限：`timeout`，终止整个候选进程组，不计入 infra 熔断。
- 验证命令非零或越界改动：`fail`。
- 历史 ref 缺失：`blocked`，工具不自动 fetch。

原始 Codex JSONL 只存在临时 run 目录并有 1 MB 上限。纳入 Git 的只有脱敏 summary/report、plan digest 和事件链 digest；费用未由事件源提供时必须为 `null`。
