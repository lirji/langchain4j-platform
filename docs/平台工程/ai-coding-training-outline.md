# AI Coding 团队培训提纲

## 目标与形式

建议用 90 分钟完成一次讲解、演示和练习。参加者应能选择正确 Skill，把读/写角色分开，生成可复现交付证据，并用 GoldenCase 识别越界修改和“测试幻觉”。

培训前置：JDK 21、Maven、Git、Node.js 20+；无需模型 API Key，也不启动平台服务。

## 课程安排

| 时间 | 内容 | 演示/产出 |
| --- | --- | --- |
| 0～10 分钟 | 为什么需要工程化 Coding Agent | 从“会写代码”转为“可验收、可回放、可审计” |
| 10～25 分钟 | 6 个 Skill 的触发边界 | 同一需求分别判断 feature、legacy、debug、review、QA、PR package |
| 25～35 分钟 | 4 个只读 Agent 与并行边界 | investigator/architect 并行读，主 Agent 单一写入 |
| 35～50 分钟 | Java 平台关键不变量 | tenant/dept、共享 DTO、LLM gateway、conditional bean、migration |
| 50～65 分钟 | GoldenCase 现场演示 | validate→list→prepare→score→report |
| 65～80 分钟 | 小组练习与对抗样例 | 越界文件、非法命令、失败测试、缺 ref |
| 80～90 分钟 | 指标、推广和复盘 | 首次通过率、介入率、时长、成本、越界率基线 |

压缩到 60 分钟时，可把小组练习改为讲师演示，但不能省略权限边界和失败证据说明。

## 讲师演示脚本

1. 执行 `node tools/coding-agent-eval/cli.mjs validate-kit` 与 `validate`，解释 fail-closed 和 digest。
2. 用 `list --kind bug` 选择 `agent-json-mode`，展示 prompt、路径范围和验证命令。
3. 用 `prepare --case agent-json-mode` 创建 base worktree，强调它不污染主工作树。
4. 演示三种结果：合法修改、增加越界 `README.md`、让验证命令失败；对比 JSON 中 scope/checks/verdict。
5. 演示 `prepare --oracle` 只用于校准，并说明 oracle patch 非唯一正确答案。
6. 用 `$platform-pr-package` 的输出要求检查一份“只说测试通过但没有命令”的反例。

## 练习题

### 练习 A：路由与交接

给出三个任务：新增只读接口、重构旧 JDBC Store、分析线上 401。学员选择 Skill，写出 investigator 到 main agent 的交接合同，并标出需要人工确认的动作。

通过条件：不把 debug 自动升级为修复；数据库结构变化明确走 migration；所有任务说明租户和验证边界。

### 练习 B：评审发现

给出一个同时修改 `platform-security` 和业务 Controller 的 diff。学员只报告能构造失败场景且有 `file:line` 的发现，删除纯风格意见。

通过条件：覆盖身份传播、TenantContext 清理、兼容和测试缺口；不声称未执行的测试通过。

### 练习 C：GoldenCase 对抗

分别构造路径逃逸、`node -e`、写入型 git 命令、缺失 ref 和越界文件。记录 CLI 退出码与错误摘要。

通过条件：非法输入为 `2`、缺 ref 为 `3`、评分 hard gate 为 `4`，且工具没有联网或修改主工作树。

## 推广节奏

- 第 1 周：核心维护者用 3 个历史 Case 校准 prompt、环境和报告字段；
- 第 2～3 周：固定当前 20 Case 做 shadow benchmark，只收集基线、不阻断合并；
- 第 4 周：评审首次通过率、人工介入率、耗时、成本和越界率，淘汰漂移 Case；
- 后续：按手册扩到 30～50 Case。只有稳定、可复现并经团队批准的阈值才进入 PR 门禁。

复盘必须区分模型问题、Skill/流程问题、Case 设计问题和环境问题；不能只按总分排名，也不能把 oracle 实现泄漏给候选 Agent。
