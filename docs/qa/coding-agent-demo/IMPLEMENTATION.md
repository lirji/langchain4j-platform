# Implementation Evidence

## 交付切片

- `tools/coding-agent-eval`：plan/run/resume/report、Codex adapter、事件链、Docker runner、评分隔离和 34 条 Node 测试。
- `tools/java-codegraph`：单文件 JDK 21 CLI、fixture 和可重复测试脚本。
- `docs/qa/coding-agent-golden`：50 条真实历史 Case，20 core + 30 extended。
- `.github/workflows/coding-agent-kit-ci.yml`：固定 SHA action、最小权限、Node/JDK/dataset/codegraph/shell gate；无模型、无 Docker pull。
- `docs/qa/coding-agent-benchmark`：oracle 与真实 Codex 的脱敏报告。

## 实测结果

| Run | 结果 | First pass | 越界 | P50/P95 |
| --- | --- | ---: | ---: | ---: |
| oracle 20 core | 20 pass | 100% | 0% | 7.3s / 10.4s |
| Codex 20 core | 19 pass, 1 timeout | 95% | 0% | 138.6s / 358.2s |

唯一 timeout 是 `workflow-terminal-outbox`，在 480 秒被终止并与 infra/fail 分开记录。19 个可评分结果均为 100 分；费用事件未提供，因此报告为 `null`。

本次获批运行按计划使用当时的本地默认模型，PLAN 中 `model` 为 `null`；运行时配置观察值为 `gpt-5.6-sol`，reasoning 由 runner 固定为 `medium`，但事件流没有独立返回模型名。评审后创建新计划已强制要求 `--model`，并启用 `--ignore-user-config` 与 shell 环境零继承。保存的首轮报告用于运行基线，不作为跨模型可复现结论。
