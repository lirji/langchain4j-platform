# Investigation

- 仓库是 Java 21 / Spring Boot 3.3 多模块 Maven 平台；最终扫描 1,044 个 Java 文件。
- P0 已有 6 Skills、4 个只读 Agent、20 Case 和基础评分 CLI，可在其上增量扩展。
- 本机具备 JDK 21、Maven 3.9.12、Node 24、Docker daemon、Codex CLI 0.151.0；本地有 `busybox:latest` 和 `eclipse-temurin:21-jdk`，没有 Node 评测镜像。
- 历史提交足以挑选 30 个新增真实 Case；每条固定单父提交 base/oracle SHA、路径范围和验证命令。
- Maven 有自定义本地仓库；Docker runner 必须解析有效仓库路径，但不能挂载可能含凭据的用户 settings。
- 进入本轮前已有 README、deploy 和 operations 用户修改，均列为保护对象。
