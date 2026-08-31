# 10 分钟演示脚本

1. 用 `validate-kit` 和 `validate` 展示 6 Skills、4 Agents、50 Case 与 digest（1 分钟）。
2. 用 codegraph 查询 `OrderService`，解释 resolved/syntactic 和反向影响候选（2 分钟）。
3. 打开 sandbox 文档和 argv contract test，说明候选 worktree 与 Docker 评分两层边界（2 分钟）。
4. 展示 oracle 报告 20/20，再展示 Codex 报告 19 pass + 1 timeout；解释 first-pass、越界率、P50/P95、事件 digest 和 cost=null（2 分钟）。
5. 演示 `benchmark plan` 与 `resume` 的不可变摘要/断点语义，不再次调用模型（1 分钟）。
6. 展示 review、QA 和 PR package，强调没有自动 commit/push/deploy（2 分钟）。

推荐现场只运行确定性命令；真实模型与 Docker smoke 使用已保存的脱敏证据，避免演示时产生额度和环境波动。
