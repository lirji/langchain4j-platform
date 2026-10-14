# M1-PERSIST Progress

- 目标：用户长期画像内存存储改为 MySQL 持久化。
- 范围：仅当前存储切片；治理能力、知识运营、Java 版本与依赖债务仍为后续事项。
- 实施：DONE。
- 本地验证：PASS_WITH_LIMITATIONS，详见 TEST_RESULT.md。
- 任务分支：feat/persistent-profile-memory。
- 工作树：~/.local/share/git-worktrees/langchain4j-platform/persistent-profile-memory。
- 基线：38b3d80d0bdb47290302f00064873fd84eacea63。
- Git 交付：功能提交 d8ce9d0aef2e9e728896c1609e68e2c0f69a6190 已推送任务分支并经 ls-remote 核对；之后仅补交付文档。
- 远程兼容性 CI：[38491384085](https://github.com/lirji/langchain4j-platform/actions/runs/38491384085)，SUCCESS。
- 远程供应链 CI：[38491391893](https://github.com/lirji/langchain4j-platform/actions/runs/38491391893)，测试/打包成功，Scan aggregate dependency SBOM 失败，镜像扫描和发布跳过。
- main 合并：BLOCKED_SECURITY_GATE。main 仍为 38b3d80；没有本轮扫描失败合并例外，不合并、不推 main、不部署。
- 下一步：供应链门禁通过或获得本轮明确合并例外后，复核目标 main 并继续正常合并/推送；不重新实现已完成代码。
- 保留原项目所有未提交内容。验证工作树和 target 中的日志/产物保留，不自动删除工作树。
- 原项目仍为 23 个 tracked dirty 文件，744 行增加/34 行删除，与任务开始一致。Python 仓未修改。
- MySQL 临时库和 APP/MIGRATOR 账号已清理；共享 dev-infra-mysql84-1 已恢复为开始前的停止状态。
