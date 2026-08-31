# Design

## 关键决策

- Benchmark 使用不可变 PLAN digest、原子 checkpoint、串行 resume、3 次连续 infra 熔断和独立 timeout 状态。
- Codex 使用 ephemeral JSONL + workspace-write；后置验证使用 Docker。模型调用不进入 CI。
- Docker 使用只读 source、tmpfs work、断网、非 root、drop capabilities、no-new-privileges 和资源/时间上限；缺镜像不回退。
- Telemetry 使用前向 hash-chain，报告仅保留白名单字段、摘要和脱敏错误。
- Java graph 使用 JDK `JavacTask`/`TreePathScanner`，把可靠关系与 syntactic 候选分级。
- Dataset 保持 score schema v1 兼容，版本升为 2.0.0，增加 tier/difficulty/sourceCommitSubject 并把数量门槛提高到 50。

详细技术方案、回滚与威胁模型分别见交付计划、`coding-agent-sandbox.md` 和 `java-codegraph.md`。
