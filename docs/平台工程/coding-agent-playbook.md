# Coding Agent 工程工作手册

## 1. 定位

本手册把仓库内的 Codex Skills、自定义只读 Agent 和 Coding GoldenCase 组合成可审计的研发流程。它约束的是研发协作，不改变任何在线服务，也不授权自动提交、推送、创建 PR、部署或访问生产。

首期衡量目标是“交付证据是否完整、结果是否可复现”，而不是在没有基线的情况下宣称效率提升。

## 2. 能力入口

| 场景 | Skill | 预期结果 |
| --- | --- | --- |
| 新增 Java/Spring 行为 | `$platform-java-feature` | 最小纵向切片、聚焦测试、文档同步 |
| 遗留代码兼容改造 | `$platform-legacy-maintenance` | 先有特征测试，再做行为保持修改 |
| 故障诊断 | `$platform-prod-debug` | 症状→证据→根因链；只有用户要求修复才改代码 |
| Diff 评审 | `$platform-diff-review` | 带失败场景和 `file:line` 的真实发现 |
| QA | `$platform-qa` | 验收标准到可复现证据的矩阵 |
| PR 交付包 | `$platform-pr-package` | 标题、摘要、测试、风险、发布与回滚说明 |

项目级 `.codex/agents` 提供四个读密集角色：

- `platform-investigator`：追踪调用链、配置、依赖和现状证据；
- `platform-architect`：把获批需求收敛为可实现的技术方案；
- `platform-reviewer`：对实际 diff 做对抗性评审；
- `platform-qa`：生成 QA 矩阵并核验已有证据。

这些 Agent 均配置为 `read-only`。主 Agent 或单一 worker 负责写入，避免多个 Agent 同时修改同一文件集合。

## 3. 标准交付流

| 阶段 | 输入 | 输出/交接合同 | 质量门 |
| --- | --- | --- | --- |
| 需求 | 用户目标、约束、非目标 | 可观察行为、范围、风险、缺失决策 | 会改变契约/数据/发布的选择需人工确认 |
| 探索 | 需求合同 | 现有调用链、`file:line` 证据、未知项 | 不以推断替代代码证据 |
| 设计 | 探索结果 | 文件图、契约、权限/租户、失败处理、验收测试 | 行为设计获批后再实现 |
| 实现 | 获批设计 | 最小纵向切片和聚焦测试 | 单一写入所有者；不夹带重构 |
| 评审 | 实际 diff | 严重级别、失败场景、证据、修复状态 | Critical/High 清零；Medium 修复或说明接受理由 |
| QA | 验收标准、构建产物 | happy/boundary/invalid/permission/recovery 证据 | 未执行和外部阻塞不得写成 pass |
| 文档 | 最终行为 | API、配置、运维、回滚的真实说明 | 每条声明可回溯到代码或命令 |
| PR 包装 | 最终 diff、测试日志 | 可评审摘要和 reviewer checklist | 不自动 commit/push/open/merge/deploy |

探索、架构评审、代码评审和 QA 设计可以并行；实现工作只在文件集合互不重叠且所有权明确时并行。认证、租户上下文、跨服务 DTO、数据库 migration 和发布配置默认视为跨模块共享边界，避免并行写入。

## 4. 风险与人工审批

| 风险级别 | 示例 | 必须动作 |
| --- | --- | --- |
| L0 只读 | 搜索代码、查看日志、评审 diff、运行静态校验 | 记录证据，通常无需额外审批 |
| L1 本地可逆 | 改源码/测试/文档、创建临时 worktree、运行模块测试 | 避开用户已有修改，检查 scoped diff |
| L2 共享状态 | 数据 migration、外部 API 写入、创建 PR、push、重启测试环境 | 执行前取得明确授权并给出目标与回滚 |
| L3 高风险 | 删除数据、生产变更、密钥轮换、强制覆盖或 Git 历史重写 | 单独批准、双重核对目标、执行后保留审计证据 |

Skill 或 Agent 指令不能扩大当前会话权限。任何密钥、token、PII、文档正文和生产日志在报告中必须脱敏。

## 5. GoldenCase 使用

数据集入口是 `docs/qa/coding-agent-golden/manifest.json`。每个 Case 固定 `baseRef`、参考 `oracleRef`、允许/禁止路径、参数数组形式的验证命令和 100 分 rubric。`oracleRef` 用于理解和校准，不要求候选 patch 与历史 patch 完全一致。

```bash
# 校验 Skills、Agent、数据集和内容 digest
node tools/coding-agent-eval/cli.mjs validate-kit
node tools/coding-agent-eval/cli.mjs validate

# 发现 Case
node tools/coding-agent-eval/cli.mjs list
node tools/coding-agent-eval/cli.mjs list --kind security --json

# 创建隔离的 base worktree；命令输出 workspace 路径
node tools/coding-agent-eval/cli.mjs prepare --case agent-json-mode

# 让候选 Agent 在 workspace 中完成任务后评分
node tools/coding-agent-eval/cli.mjs score \
  --case agent-json-mode \
  --workspace /tmp/coding-agent-agent-json-mode-xxx/worktree \
  --output /tmp/agent-json-mode-score.json

# 汇总一个目录内的 score JSON
node tools/coding-agent-eval/cli.mjs report \
  --input /tmp/coding-agent-scores \
  --output /tmp/coding-agent-benchmark.json
```

`prepare --oracle` 可创建参考 commit worktree，用于评分器校准。它不代表候选结果，也不得进入模型结果统计。

### 可恢复 Benchmark

真实 Benchmark 必须先固化计划，再运行或恢复；`candidate/model/workflow/dataset/isolation/timeout` 任一变化都要新建 run。`codex` candidate 还必须显式传入模型执行授权。

```bash
# Oracle 校准，不消耗模型调用
node tools/coding-agent-eval/cli.mjs benchmark plan \
  --candidate oracle --isolation docker --case-limit 20 \
  --timeout-seconds 480 --run-id oracle-calibration \
  --output /tmp/oracle-calibration
node tools/coding-agent-eval/cli.mjs benchmark run --run-dir /tmp/oracle-calibration

# 只有用户明确授权联网和额度后才能执行
node tools/coding-agent-eval/cli.mjs benchmark plan \
  --candidate codex --model <model-id> --isolation docker --case-limit 20 \
  --timeout-seconds 480 --run-id codex-baseline \
  --output /tmp/codex-baseline
node tools/coding-agent-eval/cli.mjs benchmark run \
  --run-dir /tmp/codex-baseline --max-cases 3 --allow-model-execution
node tools/coding-agent-eval/cli.mjs benchmark resume \
  --run-dir /tmp/codex-baseline --allow-model-execution
node tools/coding-agent-eval/cli.mjs benchmark report --run-dir /tmp/codex-baseline
```

状态分为 `pass/fail/blocked/timeout/infra_error`。连续三条 infra error 停止；timeout 是独立终态。原始事件不提交，Git 中只保留脱敏 summary/report 和 plan/event digest。Docker 威胁模型见 [评测沙箱](coding-agent-sandbox.md)。

退出码：`0` 成功，`2` 输入/schema/命令非法，`3` 本地缺少历史 ref，`4` 评分未通过。缺 ref 时工具不会联网 fetch；由操作者显式补齐历史后重试。

### 安全边界

- Case ref 必须是 40 位 commit SHA；manifest 与 Case 内容由 SHA-256 digest 绑定。
- 验证命令必须是字符串参数数组，禁止 shell 拼接；executable 仅允许 `mvn`、`node`、`npm`、直接仓库 `.sh` 和只读 `git` 子命令。
- `node -e/-p`、写入型 git 子命令、路径逃逸和非尾部通配符均 fail closed。
- `score` 只接受 git worktree 根目录，按 `baseRef` 统计 tracked 与 untracked 文件，禁止路径和越界文件构成 hard gate。
- 验证子进程只继承构建所需的有限环境变量，不传递 API token 等常见秘密；工具不自动 fetch/commit/push/deploy。临时目录元数据位于 worktree 外部，避免污染得分 diff。
- `score` 会执行候选 worktree 内的构建和测试代码；不可信 patch 必须放在无生产凭据、网络和共享写权限的临时容器/沙箱中评测，不能把参数数组白名单误当作代码沙箱。

## 6. 得分与报告解释

默认 Case 由路径范围 30 分、验证命令 50 分、rubric 证据 20 分组成，阈值 80。即使总分达到阈值，只要出现越界/禁止路径或任一验证命令失败，最终仍是 `fail`。

报告至少记录 dataset/tool/case 版本、digest、base/oracle ref、变更文件、每条命令的退出码与耗时、rubric 证据、总分和 verdict。命令输出会截断，避免把大日志或敏感信息写入报告。

团队基线指标：

- Case 完成率 = 产生可评分结果的 Case / 分配 Case；
- 首次验证通过率 = 第一次 `score` 为 pass 的 Case / 已评分 Case；
- 人工介入率 = 需要人工改变方案或修复的 Case / 已评分 Case；
- 平均交付时长 = 从 Case 分配到首次 pass 的墙钟时间；
- 单 Case 成本 = 模型/API/算力的可归因成本；本工具不自行采集模型账单；
- 越界变更率 = 出现 `outsideAllowed` 或 forbidden file 的 Case / 已评分 Case。

比较模型或工作流时必须固定 dataset digest、工具版本、代码历史可用性和资源上限。不同 digest 的结果不能直接合并为趋势。

## 7. 50 条 Case 的维护

1. 从已合并、可公开且验证路径稳定的历史提交选择候选，不使用包含密钥或个人数据的提交。
2. 覆盖 feature、legacy、bug、security、review、doc、safety，并增加 Java、多租户、异步、RAG、工作流、前端和发布边界的均衡样本。
3. 固定单父提交的完整 `baseRef` 和 `oracleRef`；先在 oracle worktree 运行验证命令。
4. 使用最小路径白名单和真实验证命令；不要用宽泛 `**` 或仅检查文字存在。
5. 更新 manifest 版本与 digest，运行全部 Node 测试，并由非作者复核 prompt 是否泄漏 oracle 实现。
6. 先新增到 shadow benchmark；积累稳定结果后再讨论质量门禁阈值，禁止一次性把未校准 Case 设为合并阻断。

当前 dataset 版本为 2.0.0，含 20 个 core 和 30 个 extended，并覆盖 easy/medium/hard。2026-08-31 基线：oracle 20/20；真实 Codex 为 19 pass、1 timeout、0 fail/infra，first-pass 95%、越界率 0%。详见 `docs/qa/coding-agent-benchmark/`，不同 digest 的结果不可横向合并。

## 8. 故障处理

- schema/digest 失败：先检查是否忘记同步 manifest，禁止跳过校验或手工改报告。
- 历史 ref 缺失：明确执行 fetch/unshallow 属于联网操作；当前工具不会代劳。
- verification 环境失败：报告为 blocked/失败证据，不修改 Case 让它“通过”。
- oracle 评分失败：说明 Case 已漂移，应修复 Case 或固定执行环境，不进入候选比较。
- 工作区包含无关修改：重新 `prepare`，不要在用户主工作树评分。
