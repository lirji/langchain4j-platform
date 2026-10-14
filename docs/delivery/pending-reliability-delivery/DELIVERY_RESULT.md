# 既有整改与画像持久化交付结果

日期：2026-10-14。仓库：langchain4j-platform。

## 授权与范围

用户在获知未提交整改、画像持久化分支及既有供应链扫描失败后，明确要求“提交并推送远程main分支，之后切换到main分支”。据此执行本次源码合并和正常推送；不修改扫描规则、不升级依赖、不进行生产部署。该授权不代表漏洞已修复，也不作为以后任务的自动例外。

范围：原目录 23 个变更文件与 3 个未跟踪整改文档，以及已提交的 M1-PERSIST。任务分支：`fix/pending-reliability-delivery`；基线：`38b3d80`。

## 提交与推送

| 提交 | 内容 |
|---|---|
| bb82e9f | 网关入站内部令牌剥离与可信认证状态 |
| b42ed9c | 租户日 Token 预算入口拦截 |
| f9ed766 | Redis 语义缓存按租户限容 |
| c362dc8 | 生产 JWT 算法及私钥挂载门禁 |
| cc2c057 | 门户健康检查改用管理端口 |
| e856861 | 历史整改文档及进度 |
| d8ce9d0 / 6d201d2 | 既有画像 MySQL 持久化实现及验证记录 |
| 344245f | 无冲突合并画像持久化分支 |

`git push origin HEAD:main` 已成功将远程 main 从 `38b3d80` 推进至 `344245f84e9718c6ee9aa49c1d82a42eace9d288`。本交付记录作为后续文档提交一并同步 main。未强推、未删除分支或工作树。

## 当前版本验证

- 原整改工作树全 reactor：1426 tests / 0 failures / 0 errors / 17 skipped。
- 真实 dev_infra Redis：4 项语义缓存集成测试通过（补跑上述条件跳过的 4 项）；测试使用随机租户键并清理。临时启动的 Redis 与 ACL 容器已恢复为停止状态。
- 合并 revision `344245f` 全 reactor：1441 tests / 0 failures / 0 errors / 17 skipped，BUILD SUCCESS。新增画像测试包含在本次运行中。
- 五项静态配置门禁通过：database-migration、runtime-hardening、production-cutover、supply-chain、observability；迁移脚本同时通过 3 项离线测试。
- 差异检查通过。门户 upstream 9080 与 edge 管理端口及 Compose 映射一致。
- 本地日志：`/tmp/lc4j-pending-delivery-tests.log`、`/tmp/lc4j-pending-redis-test.log`、`/tmp/lc4j-main-integration-tests.log`、`/tmp/lc4j-main-*.log`。
- 持久化的真实 MySQL 10 项验证及权限/注释检查沿用同一实现提交的既有证据，详见 `../persistent-profile-memory/TEST_RESULT.md`；本轮未重复运行 MySQL 验证。

## 已知限制与后续

本次 main 推送会触发远程工作流，本记录不宣称最新 main CI 全绿。画像提交的兼容性 CI 38491384085 成功，供应链 CI 38491391893 在 aggregate dependency SBOM 扫描失败；本次按用户知情后继续推送的明确指令交付源码，保留扫描失败结论，镜像/生产准入不因此成立。没有扩大到依赖债务或完整记忆治理/知识运营。

原目录的本地环境配置、IDE 文件、前端依赖与构建产物、各模块 target 均属于忽略文件，未提交也未删除。两个独立工作树仍保留验证日志：`cross-runtime-verification` 已解除 main 占用、停留在 38b3d80 detached HEAD；`persistent-profile-memory` 保留已合并任务分支。前者及后者的构建目录可在无需证据后清理，但本轮没有执行清理；已有 prunable 的 `/private/tmp/lc4j-integrate-main` 也未修改。最后在原目录切回 main，并核对本地 HEAD、origin/main 与远程 refs/heads/main。
