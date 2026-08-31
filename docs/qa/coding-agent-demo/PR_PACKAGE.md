# PR Package

## Proposed title

`feat(tooling): productionize repository coding-agent workflows`

## Problem and outcome

仓库原先缺少可复现的 AI Coding 工作流、实际基准、强隔离验证和 Java 影响分析。本变更交付 6 个仓库 Skill、4 个只读角色、可恢复 benchmark、Docker 验证沙箱、JDK 21 code graph、50 条历史 GoldenCase、真实 20-call 基线、确定性 CI、评审/QA/演示文档。

## Affected areas

- `.agents/skills/platform-*`、`.codex/agents/*`
- `tools/coding-agent-eval/**`、`tools/java-codegraph/**`
- `docs/qa/coding-agent-{golden,benchmark,demo}/**`
- `docs/平台工程/{coding-agent-playbook,coding-agent-sandbox,java-codegraph,ai-coding-training-outline}.md`
- `.github/workflows/coding-agent-kit-ci.yml`、`AGENTS.md`、`docs/README.md`

不修改任何运行时 Java 服务、HTTP API、DTO、数据库 migration、Compose/Helm 拓扑或在线配置。任务开始前已有的 `README.md`、`deploy/**` 和 `docs/参考/operations.md` 修改不属于本 PR，应在暂存/提交时排除或由其所有者单独处理。

## Design decisions

- plan/dataset/event digest + 原子 checkpoint；状态严格区分 fail/blocked/timeout/infra。
- Codex 候选只写临时 worktree；未来计划必须固定 model，ephemeral、忽略用户 config、shell 环境零继承。
- Maven/Bash 后置验证走 pull-never、network-none、read-only、non-root Docker；只读 Git 是显式标注的宿主例外。
- Java graph 零第三方依赖，resolved 与 syntactic 证据分级，不宣传完整运行时调用图。
- CI 只运行确定性校验，不含模型调用、Docker build/run/pull 或写权限。

## Compatibility and configuration

- 在线服务和 API：无影响；无需请求/响应示例。
- 数据库与租户隔离：无运行时变更；GoldenCase 中 tenant/security 样例只在历史 worktree 回放。
- 本地依赖：code graph 要求 JDK 21；Docker Java 验证要求预先存在 `eclipse-temurin:21-jdk` 和 Maven 本地缓存；Node 镜像缺失时失败。
- Docker smoke 会保留 `coding-agent-sandbox-smoke:local`，容器自动删除。

## Verification evidence

- Node：38/38 pass。
- Dataset：50/50 refs、direct parent、oracle scope 通过；20 core + 30 extended，7 kind、3 difficulty。
- Code graph：fixture pass；全仓 1,044/1,044、0 failed、7,266 nodes、62,481 edges。
- Docker：source read-only、UID 65532、无 Docker socket、timeout cleanup、缺镜像 fail closed；无残留 container。
- Oracle：20/20 pass，first-pass 100%。
- Codex：19 pass、1 timeout、0 fail/infra，first-pass 95%、越界 0%、P50/P95 138.6/358.2 秒。
- 静态：workflow YAML、bash syntax、diff whitespace、secret、absolute HOME、trailing whitespace 全部通过。

未执行：远端 GitHub Actions 和完整 Maven reactor；原因与风险见 QA limitations。

## Security, rollout, monitoring, rollback

- 不提交 raw JSONL、凭据或用户 settings；报告只保存脱敏摘要和 digest。
- 首先以 shadow benchmark 使用，不设合并阻断；监控 completion、first-pass、timeout/infra、out-of-scope、P50/P95、token/cost coverage。
- 回滚只需移除独立 tools/skills/agents/docs/workflow；不涉及服务回滚或数据恢复。
- 首轮 PLAN 的模型字段为 null，已明确披露；未来必须 `--model`，不得把首轮当跨模型对比。

## Reviewer checklist

- [ ] 检查 `candidate.mjs` 的进程组超时、model/config/env 限制。
- [ ] 检查 `benchmark.mjs` 的 plan digest、running checkpoint、resume 和 terminal 分类。
- [ ] 检查 `sandbox.mjs` 是否存在 host fallback、可写敏感 mount 或隐式 pull。
- [ ] 检查 Git command allowlist、cleanup temp namespace 和 telemetry redaction。
- [ ] 抽查 50 Case 的 prompt、base/oracle、allowed paths 与验证命令是否泄漏 oracle 实现。
- [ ] 复核 code graph 对 symlink、overload、歧义和 syntactic 边的处理。
- [ ] 确认 CI 不调用模型或 Docker，actions 均固定 SHA且 `contents: read`。
- [ ] 暂存时排除任务前已有的 README/deploy/operations 用户修改。

本文件只准备 PR 内容；未 commit、push、创建、合并或部署 PR。
