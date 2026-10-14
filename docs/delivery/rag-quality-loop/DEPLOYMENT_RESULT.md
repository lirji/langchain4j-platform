# 本地 Docker 发布记录

目标：用户当前机器的 `langchain4j-platform` Compose 项目，复用 `dev-infra`；不是生产部署。时间：2026-10-14。当前结果 **PARTIAL**：当前仓库Java/RAG/前端更新与检索评测冒烟通过；独立Python AgentScope就绪检查仍失败。

## 实际版本和入口

- 源码 `cfa1d5b8a21b519ac6cf2407186bbd0e4aaaf019`；Java/前端镜像标签cfa1d5b、OCI revision标签对应源码；本地构建，没有推送镜像仓库。
- Knowledge实际镜像ID：`sha256:9905e2e6f483aeaab31ac62ec1dc4c1a1797241f03386429e2531f9884c229ab`。
- 前端 http://localhost:8093 ，网关 http://localhost:18080 。知识管理端口9084；网关管理端口19080。
- AgentScope保留已有镜像 `sha256:a3f55885f1cbb9b1ad9c7f803f029e3a957994f0a31e02b78718b7c326320603`。其探针在业务端口访问`/actuator/health/readiness`得到404，新Java使用独立管理端口；已请求跨仓修复授权，不关闭检查绕过失败。

## 发布与健康证据

- 所有9项数据库迁移容器最终exit0；知识库V6及异步任务V4成功。
- Java十二项readiness均HTTP200/UP：edge、conversation、workflow、analytics、knowledge、async-task、channel、interop、vision、voice、auth、order。
- 前端HTTP200；未认证的quality API经edge返回401，Casdoor only模式保持。
- Docker真实组件评测链路PASS，详见TEST_RESULT；测试不调用付费模型，固定HashEmbedding。
- `compose up --wait`因AgentScope unhealthy退出非0；不能把其他容器running误写成整栈部署成功。
- 浏览器Chrome工具超时，视觉验收UNVERIFIED。

## 恢复环境处理

旧ASYNC_TASK.TASK_ID使用utf8mb4_0900_ai_ci，库默认utf8mb4_unicode_ci导致V3新增调度外键失败。保存失败history与局部锁表副本，将仅含初始行的锁表改名为`RECOVERY_DISPATCH_LOCK_20261014`保留，库默认对齐旧表；Flyway repair移除1条失败记录、成功历史调整0条，再执行V3/V4成功。未改历史迁移文件或旧任务数据。

Conversation库之前缺本地应用/迁移凭据，只补缺失配置并创建对应数据库与最小权限账户，未轮换其他凭据。没有重跑全量备份恢复。

## 本地文件与回退边界

固定镜像Compose包装脚本、images.compose.json、before.json、恢复SQL副本在忽略目录`deploy/.dev-infra-state/rag-quality-release/`；不含凭据的源码与报告可提交，私有配置不提交。
当前代码新增质量表可与旧代码共存；回退应用不得删除评测数据，异步任务新增调度数据需结合原有迁移兼容边界处理。本次没有回退。保留旧镜像和恢复表，不因代码回退宣称数据库业务效果已撤销。

共享中间件和本地业务栈继续运行；其他项目未停机。独立冒烟容器停止，测试数据库/S3原文/结果供核查保留。三个既有worktree与用户忽略文件未清理。

## 限制

远程源码功能CI通过，SBOM依赖扫描失败，镜像扫描/attestation未完成；用户授权的本地启动不代表生产发布门禁通过。独立AgentScope修复未获范围确认前不修改Python仓库。
