# 知识库前端、解析可视化与默认 OCR

## 当前状态

用户目标：将解析优化涉及的前端改造，展示页码/行号/OCR 告警，并默认开启 OCR。**实现、必要验证、本机 Docker 部署全部通过。** Git 按持续授权交付；推送后的结果以本节恢复入口与 refs 复核。

- 仓库 `/Users/liruijun/personal/LLM/langchain4j-platform`，任务分支 `feat/knowledge-ingestion-ui`，起点 `cd23f5d`，初始干净。
- 完整逻辑提交：`e8936f3` 网关幂等头；`d956891` 默认 OCR；`c1eedd1` 前端；`1c75103` 必要的历史库 collation 修复。交付材料另行提交。
- 持续授权包括实现/测试、正常合入推送 main；用户最新 OCR 要求取代原“不开 OCR”边界。沿用本机既有部署，不执行生产部署，不清数据或重建索引。
- 使用 frontend-design、task-git-delivery、deployment-execution、platform-legacy-maintenance。采用架构规范的建模、并发安全、接口、配置、持久化、容量和发布治理；前端偏好及网关个人规范 G1–G7。

## 已完成

1. 异步上传、原幂等身份恢复、任务错误/阶段/重试、身份隔离；共享/JSON/Obsidian 保留兼容路径。
2. 文档列表/详情/新版本、检索来源与解析告警可视化、质量评测引导、三档布局与北京时间。
3. OCR 默认 true，标准镜像包含本地中英引擎；纯查询/受理角色 slim+false，可显式关闭；资源上限沿用已验证的 OCR 预算。
4. 前端 603 项、网关及上游 139 通过/2 跳过、知识服务及上游 732 通过/12 跳过；类型检查、构建、4 组浏览器场景和 7 类真实 OCR 通过。细节见 [VALIDATION](VALIDATION.md)。
5. 本机 3 个应用部署 `c1eedd1`。日志发现原 V10 collation 不兼容后追加独立 V11；两方向 MySQL 先红后绿，H2 回归、4 项真实任务领取和持久化门禁通过。
6. 停知识服务、私有备份后，固定 migrator `1c75103` 完成 V10→V11；34 READY 保留，关联成功。恢复后 14 个 Java readiness UP，OCR/资产/CORS/镜像版本验证通过，领取异常 0，其他容器未改。见 [DEPLOYMENT_RESULT](DEPLOYMENT_RESULT.md)。

## 恢复入口与证据

- 本机部署与备份：`deploy/.dev-infra-state/knowledge-ui-ocr/`（0700）；`compose.sh` 叠加原固定镜像和本次目标；`image-manifest.json`、`runtime-evidence.json`、`migration-after.txt`、`migrate-v11.log` 是已完成证据。**不重复执行迁移、备份或重建已验证镜像。** `pre-v11.sql`（0600）保留，不提交。
- 测试/截图：`capability-showcase-frontend/target/knowledge-ingestion-ui/`（忽略）。最终日志名与边界见 VALIDATION。`file-fingerprints.json` 覆盖 34 个源码/配置/测试文件，摘要 `97013ccfc8dead09c43315adbbd00520b9f2918c7100dbd2a6ed9fb29773b5ee`；交付文档不计入。
- Git 收尾：读取同一测试目录的 `delivery.json` / `remote-checks.json`，核对 `git status`、`main` / `origin/main` 是否包含上述提交和交付材料。成功记录存在且 refs 一致时无需再提交/推送或复跑有效测试；未完成则从该步骤继续。
- 临时 Vite、构建、测试、迁移均已结束；运行中的本机应用容器属于预期结果。没有新建工作树。旧的 `/private/tmp/lc4j-integrate-main` prunable 记录和其他任务工作树未清理，避免删除未确认内容。
- 生产供应链 HOLD 沿用前任务，不把本地验收扩大为生产发布。当前前端已更新到本机 8093，不再是前任务旧前端镜像。
