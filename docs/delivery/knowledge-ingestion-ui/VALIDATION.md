# 知识库前端与默认 OCR 验收

日期：2026-10-19。任务起点 `cd23f5d`，任务分支 `feat/knowledge-ingestion-ui`。功能、本地验证和本机 Docker 部署 **PASS**。

## 功能结果

| 功能 | 结果与证据 |
| --- | --- |
| 文件上传 | 当前租户使用真实 `POST /rag/ingestions` multipart，包含文档 ID、版本、Idempotency-Key；202 仅受理，READY 后刷新文档。支持格式与后端对齐。 |
| 异步恢复 | 断网、408、停止等待或损坏回执保留原提交；同一意图复用原键，支持原编号恢复。未知重试结果须先查询；轮询有界，允许手动恢复。 |
| 任务展示 | 状态、阶段、失败码、安全处理建议、重试次数/时间和人工重试；身份切换隔离迟到响应，文件与凭证不持久化。 |
| 文档管理 | 字段分列表格、分页与语义化详情，当前租户“上传新版本”沿用 ID 并递增已发布版本；权限和最终版本由服务端校验。 |
| 解析可视化 | 检索命中展示页码、章节、工作表、逻辑行/原文行范围、提取方法、OCR 置信分、分块/增强状态及文档级解析告警。未知内部字段不直接展开；历史缺失字段如实提示。 |
| OCR 默认开启 | Java、application.yml、combined 和解析 worker 默认 true；默认 runtime 镜像包含 Tesseract/chi_sim/eng/osd。纯查询/受理角色使用 slim 并显式关闭；管理员仍可设置 false。原生文字页不强制 OCR。 |
| 兼容入口 | 共享文件、JSON、Obsidian 保留同步入口；目录 83 条能力；没有新增不存在的 OCR 配置、全量任务列表或取消任务 API。 |
| 跨域 | 网关允许 Idempotency-Key；Authorization 与 API Key 两类预检通过，来源白名单和身份策略不变。 |
| 运行兼容 | 修复实际本机库 V10 轮转表与历史任务表 collation 不同导致的 MySQL 1267；V11 仅对齐轮转租户列，保留既有任务与比较语义。 |

## 验证

原始证据位于 `capability-showcase-frontend/target/knowledge-ingestion-ui/`（忽略）；早期失败与最终结果分开保留。

| 检查 | 最终结果 | 证据 |
| --- | --- | --- |
| 前端 `npm test -- --run` | 75 文件 / 603 项通过 | `full-test-delivery.log` |
| 前端 `npm run type-check`、`npm run build` | 通过；最终 OCR 提示变更后构建通过 | `type-check-review.log`、`build-ocr-default.log` |
| `mvn -B -o -pl edge-gateway -am test` | 141 项：139 通过、2 条件跳过 | `edge-tests.log` |
| `mvn -B -o -pl knowledge-service -am test` | 744 项：732 通过、12 条件跳过；其中知识服务 564 项 | `knowledge-tests-final.log`、`knowledge-test-counts.json` |
| 浏览器回归 | 4 组场景通过，1440/1024/414 三档、15 张截图、0 页面异常；主要截图已人工核对 | `browser-ocr-final.log`、`browser/result.json`、`browser/*.png` |
| 真实默认镜像 OCR | 7 类 PDF 全部通过；无网络、非 root、只读容器，3g / 2 CPU / 256 pids | `ocr-probe.log`、[OCR_EVIDENCE](OCR_EVIDENCE.json) |
| Compose 与资源策略 | 默认/显式 false/显式 true 三组解析通过；runtime-hardening gate 通过 | `ocr-compose-matrix.json`、`runtime-hardening.log` |
| 迁移模块 | 5 项 H2 回归通过；真实 MySQL 条件测试另行执行，两种 collation 方向均先红后绿，数据保留与幂等通过 | `migration-tests.log`、`collation-before/`、`collation-after/` |
| 真实任务领取 | `JdbcIngestionJobStoreTest` 4 项真实 MySQL 回归通过 | `jdbc-claim.log`；详情 `knowledge-service/target/knowledge-parsing-hardening/knowledge-ui-collation/mysql-s3.log` |
| 迁移/持久化门禁 | 数据库迁移配置、MyBatis 边界均通过；新增独立 Java migrator 经逐文件审查登记 | `migration-config.log`、`mybatis-boundary-final.log` |
| 构建与差异 | Java 包构建、4 个固定镜像构建、`git diff --check` 通过 | `package-ocr.log`、`migration-package.log`、私有构建日志 |
| 本机运行 | 14 个 Java readiness UP；前端/静态资产、授权只读接口、OCR 依赖、网关预检、镜像 ID 与版本一致；重启后领取异常 0 | [RUNTIME_EVIDENCE](RUNTIME_EVIDENCE.json)、[部署结果](DEPLOYMENT_RESULT.md) |

条件跳过包括真实 Casdoor 令牌及外部存储等原有环境条件；没有把跳过标记为通过。知识库统计使用本轮 Maven 汇总，排除 target 内陈旧报告。迁移 MySQL 测试在普通模块回归中跳过，但在两个独立随机 MySQL 库实际通过。前端最后仅 OCR 说明文案与浏览器指针复位调整，构建和浏览器复验已覆盖。

34 个实现/配置/测试/脚本文件的 SHA-256 清单为 `file-fingerprints.json`，摘要 `97013ccfc8dead09c43315adbbd00520b9f2918c7100dbd2a6ed9fb29773b5ee`；Markdown/JSON 交付材料不计入该源码摘要。

## 证据范围

- 主 Agent 已自审实际差异、未知结果/重入、身份隔离、共享路径、来源转义、状态文案、CORS、OCR 默认与回退、DDL 边界。未把实施自审标记为独立评审。
- 浏览器使用隔离 HTTP 夹具；真实 OCR 使用合成 PDF。它们不代表生产语料准确率或付费模型效果。OCR 引擎置信分不是实测准确率，文档级告警也不能归为当前命中页独有。
- 七类 OCR 样本约 2.8–5.5 秒/份，容器峰值内存 508985344 字节，仅描述该样本与约束；不承诺生产并发容量。部署镜像与 OCR 通过镜像的运行文件层相同，仅版本标签不同。
- 构建仍提示单 JS chunk 超过 500 kB（585.95 kB / gzip 192.28 kB）；本轮未扩大为全站代码拆分。
- 当前本机环境已更新；生产供应链发布 HOLD 沿用前任务记录。没有重建历史索引、写入真实业务样本、调用付费模型或执行生产部署。

Git 推送和远程 CI 最终记录在忽略证据目录的 `delivery.json` / `remote-checks.json`，并以 `main`、`origin/main` 和对应提交检查复核。复现命令见 [前端 README](../../../capability-showcase-frontend/README.md#测试) 与 [可靠入库接入](../../对话与检索/可靠入库接入.md)。
