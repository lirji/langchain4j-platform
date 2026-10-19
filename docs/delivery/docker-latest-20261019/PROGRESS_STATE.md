# 最新代码本地 Docker 部署进度

## 目标与授权

- 任务 `docker-latest-20261019`；用户明确要求“将最新代码部署到 docker 上”。目标为当前 Docker Desktop `desktop-linux` 中既有 `langchain4j-platform` 项目，复用 `dev-infra` 和 auth-platform；本地开发/演示部署，不是生产发布。
- 仓库 `/Users/liruijun/personal/LLM/langchain4j-platform`，任务分支 `chore/deploy-latest-docker-20261019`；部署源码固定为部署时与 origin/main 一致的 `5e8c4d9`（最后执行代码 `ef9b70c`）。部署已验证通过，文档提交不改变运行版本。此前解析优化任务已完成，状态保留在 `docs/delivery/knowledge-parsing-hardening/PROGRESS_STATE.md`。
- 用户新授权允许本地部署及必要增量迁移/冒烟，取代此前“不部署”的任务边界。保留业务数据、凭据、旧镜像和其他项目，不执行 down -v、重建索引、全量资源迁移、强推或生产部署。文档变更按持续 Git 授权正常合入 main。
- 使用 deployment-execution 与既有部署入口；采用架构规范的建模、并发、安全、持久化、容量和发布治理章节。本地功能门槛沿用最终源码 1045 项回归、真实组件专项、AgentScope/Tax AI CI；供应链扫描失败保留为生产发布 HOLD，用户已知该限制后新授权本地 Docker 运行，未修改或绕过 CI。

## 执行清单

1. 已核对：原应用容器全部停止；共享 MySQL/Redis/S3/ES/Qdrant 与 Casdoor 正常。沿用 combined 知识服务、语义模型与 Casdoor-only，不切换拓扑或替换历史向量空间。
2. 验证通过：原镜像/容器状态已保存，凭据存在、端口无冲突；`mvn -B -o package -Dmaven.test.skip=true` 全模块成功，Compose 构建成功，17 个固定镜像的真实 image ID / OCI revision 已核对。AgentScope 复用现有 `3cb39f6` 镜像，其执行代码与该仓库最新 `7738cb7` 一致（差异只有文档）；Java 契约 manifest 的 `c5b6cef` 是导出来源，不作为外部应用降级依据。已取消该旧候选镜像构建，未部署过它。
3. 验证通过：知识入库库 V8→V10（V9/V10 增量），9 个迁移容器均 exit 0，其余 8 库无新版本；原有 34 个任务仍均 READY。迁移前单事务备份 48340 字节及 SHA-256 已保存（0600），旧 worker 全程停止。
4. 验证通过：固定镜像启动命令 exit 0；14 个 Java readiness 与 AgentScope readiness 均 HTTP 200 / UP；前端及 JS/CSS、Casdoor discovery、授权只读来源/评测集入口 200，网关匿名入口 401。17 个应用运行、9 个迁移退出 0、无重启；实际 image ID 与 OCI revision 符合清单。
5. 验证通过：隔离真实 MySQL/S3/Redis/Qdrant/ES 入库冒烟 exit 0，重复上传幂等、任务 READY、混合检索与 ES 分支命中；数据库查询确认准备快照版本 `knowledge-structured-v5`。业务库仍 34 个 READY。测试容器已停止，测试库/租户保留；未调用付费模型。
6. 已落盘：[DEPLOYMENT_RESULT.md](DEPLOYMENT_RESULT.md) 与脱敏 [RUNTIME_EVIDENCE.json](RUNTIME_EVIDENCE.json)，本次仅交付这两份文件、当前状态和根检查点。Git 交付按持续授权从任务分支正常合入并推送 main；实际交付结果以私有 `delivery-result.json` 和 Git 历史为准，不改变部署源码版本。

## 恢复与证据

- 本地私有证据与固定镜像包装脚本：`deploy/.dev-infra-state/latest-5e8c4d9/`（忽略、目录 0700）；日志不输出凭据，报告只提取安全字段。
- Maven、Compose 镜像构建、模型目录预检已成功，日志分别为 `package.log`、`build-apps.log`、`model-preflight.log`；取消的 AgentScope 旧候选构建记录在 `build-agentscope.log`，不会用于部署。`image-manifest.json` 保存目标镜像（含保留的 LiteLLM）的实际 ID / revision；Compose 配置校验通过。`migration-before.json`、`migration-after.json`、`migration-containers.json` 记录迁移成功；`up.log` 记录固定镜像启动成功。恢复先查容器、日志和命令状态，勿重复未知结果的迁移或启动。
- 初始启动期 readiness 未就绪证据保留 `startup-runtime-evidence.json`；随后 HTTP 全通过，但并行冒烟临时迁移容器被误计入固定容器数量，保留 `transient-runtime-evidence.json`，检查脚本已按 Compose `oneoff` 标签区分固定部署与临时验证容器。未改产品代码或容器健康配置。
- 最新源码供应链 CI `39198357117` 已结束，仍在 `Scan aggregate dependency SBOM` 失败，后续镜像/attestation 跳过；生产 HOLD 不变。
- 首次只读迁移版本查询使用了默认 Flyway 表名而失败；仓库实际表名 PLATFORM_SCHEMA_HISTORY，已按源码改正，未发生数据库修改，原错误保留 `inspection-initial-error.txt`。
- 回退策略：保留旧镜像/容器配置和本次数据库备份；若启动失败先停止本次新应用，保留排障现场。V9/V10 之后旧 worker 不得自动恢复领取；不自动反向迁移或覆盖库，修复/前滚优先。
- 没有运行中的构建/迁移/冒烟；运行中的 17 个应用容器是预期部署结果。Git 只处理本任务 4 个文档；未修改产品源码、测试或受版本管理的配置，未新建/删除 worktree。私有备份、凭据、日志与历史任务目录保留。
- 本地部署任务已达验收；OCR 默认关闭、真实付费模型效果与生产容量未在本次验证。生产发布仍须先处理供应链门禁，无自动生产部署步骤。
