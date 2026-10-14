# 本地 Docker 发布记录

目标：用户当前机器的 `langchain4j-platform` Compose 项目，复用 `dev-infra`；不是生产部署。时间：2026-10-14。最终结果 **PASS（本地运行验收）**：新版Java/RAG/前端与AgentScope均已发布启动，整栈Compose等待成功，真实组件检索评测通过。供应链和视觉验收限制仍单独保留。

## 实际版本和入口

- 源码 `cfa1d5b8a21b519ac6cf2407186bbd0e4aaaf019`；Java/前端镜像标签cfa1d5b、OCI revision标签对应源码；本地构建，没有推送镜像仓库。
- Knowledge实际镜像ID：`sha256:9905e2e6f483aeaab31ac62ec1dc4c1a1797241f03386429e2531f9884c229ab`。
- 前端 http://localhost:8093 ，网关 http://localhost:18080 。知识管理端口9084；网关管理端口19080。
- AgentScope源码 `22ba8dbc483c10d39b2205b78952c52590b48623`，实际镜像 `sha256:330bef578f4b405374bb5ce862ff44f82da07c8635296a3b5192b1e35d0c6bd2`；由git archive固定提交构建，OCI revision已核对。`agentscope-platform:local`更新为该已验证镜像，旧镜像保留。
- Compose配置源码 `f89cee01dbe1815dc699c08c8ed99791a5dfd547`；本地包装脚本固定Java镜像cfa1d5b及AgentScope实际镜像ID。

## 发布与健康证据

- 所有9项数据库迁移容器最终exit0；知识库V6及异步任务V4成功。
- Java十四项readiness均HTTP200/UP：edge、conversation、workflow、analytics、knowledge、async-task、channel、interop、vision、voice、auth、order、tax、config-server。
- AgentScope `/readiness` HTTP200/UP，modelGateway、asyncTask、knowledgeService、analyticsService、workflowService、orderService、agentSessionStore全部UP。
- 认证后的知识评测列表HTTP200；26个项目容器核对：17个常驻运行，9个迁移exit0。
- 前端HTTP200；未认证的quality API经edge返回401，Casdoor only模式保持。
- Docker真实组件评测链路PASS，详见TEST_RESULT；测试不调用付费模型，固定HashEmbedding。
- 初次`compose up --wait`因旧AgentScope unhealthy失败；修复后`up -d --no-build --wait --wait-timeout 180`退出0。实际探针单独验证，证据见[RUNTIME_EVIDENCE.json](RUNTIME_EVIDENCE.json)。
- 浏览器Chrome工具超时，视觉验收UNVERIFIED。

## 恢复环境处理

旧ASYNC_TASK.TASK_ID使用utf8mb4_0900_ai_ci，库默认utf8mb4_unicode_ci导致V3新增调度外键失败。保存失败history与局部锁表副本，将仅含初始行的锁表改名为`RECOVERY_DISPATCH_LOCK_20261014`保留，库默认对齐旧表；Flyway repair移除1条失败记录、成功历史调整0条，再执行V3/V4成功。未改历史迁移文件或旧任务数据。

Conversation库之前缺本地应用/迁移凭据，只补缺失配置并创建对应数据库与最小权限账户，未轮换其他凭据。没有重跑全量备份恢复。

## 本地文件与回退边界

固定镜像Compose包装脚本、images.compose.json、before.json、恢复SQL副本在忽略目录`deploy/.dev-infra-state/rag-quality-release/`；不含凭据的源码与报告可提交，私有配置不提交。
当前代码新增质量表可与旧代码共存；回退应用不得删除评测数据，异步任务新增调度数据需结合原有迁移兼容边界处理。本次没有回退。保留旧镜像和恢复表，不因代码回退宣称数据库业务效果已撤销。

共享中间件和本地业务栈继续运行；其他项目未停机。独立冒烟容器停止，测试数据库/S3原文/结果供核查保留。三个既有worktree与用户忽略文件未清理。

## 限制

远程源码功能CI通过，SBOM依赖扫描失败，镜像扫描/attestation未完成；用户授权的本地启动不代表生产发布门禁通过。用户确认后已修复独立AgentScope仓库；Python源码22ba8db的远程CI [38511738475](https://github.com/lirji/platform-agentscope/actions/runs/38511738475) SUCCESS，包含镜像扫描。Java历史供应链失败仍不因此变为通过。

## AgentScope 收尾（用户已确认）

2026-10-14用户确认跨仓修复与发布。Python提交22ba8db新增可选management base URL，原业务地址保持；6项探针测试、全量530项和契约/静态验证通过。JavaCompose已配置五个管理地址并增加端口一致性门禁。固定源码镜像构建成功并已切换；整栈等待和实际readiness验证均PASS。未调用付费模型，未执行生产部署。
