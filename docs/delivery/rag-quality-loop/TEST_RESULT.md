# 验证记录

## S1 发布复查版本

- 先加入缺失/旧版本/新版本/空版本反例，原实现失败；修复后全部通过。
- `mvn -o -B -pl knowledge-service -am test` PASS。版本字段通过controller映射验证；原有发布前后registry检查保留。
- 兼容：KnowledgeHit.version为加法可空字段，旧Java九参数构造器保留；新复查面对旧query无version时拒绝通过。升级顺序query→ingest-api；普通查询无行为变化。
- 未运行真实模型或部署。

## S2 版本GC

- 首先加入batch=1且两个文档的回归，原实现失败（首文档重复、次文档未处理）。
- 公平推进、总版本预算、重启检查点、失败不推进且重试、不跨租户/保留窗口测试通过。
- 真实共享Redis随机前缀测试PASS：SCAN/HSCAN覆盖25文档/3租户、Lua条件推进、重新装配后进度存留、移除后重建拒绝旧writer。测试键已清理。
- `GC_REDIS_TEST=true mvn -o -B -pl knowledge-service -am test` PASS（密码仅进子进程环境）。
- 为验证暂启dev-infra Redis/MySQL；用户随后授权本地Docker发布，因此保留运行供目标栈使用。

## S3/S4 检索评测闭环

- `mvn -o -B test` PASS：1483 tests / 0 failures / 0 errors / 18 conditional skips。
- `python3 deploy/test-knowledge-operations-mysql.py` PASS：含检索质量并发/幂等/过期终止与旧writer保护；三张新表及字段注释、应用DDL权限限制通过。随机测试库/账号已清理。
- 真实应用管线使用HashEmbedding、生产query与registry版本过滤，发布v2→建立基线→移除文档→门禁REGRESSION，通过。此证据不冒充外部模型/向量组件验证。
- 前端580tests通过；vue-tsc和Vite构建通过（保留既有chunk体积/动态导入提示）。
- MyBatis边界23XML、observability、production-cutover、supply-chain配置、database-migration、runtime-hardening门禁PASS。不存在的历史脚本名称未作为PASS计入。
- 无真实模型评委调用。Docker运行验证见下；浏览器工具两次超时，视觉验收UNVERIFIED。

## Docker 检索闭环

- 固定镜像 `langchain4j-platform-knowledge-service:cfa1d5b`；`python3 deploy/dev-infra/smoke.py --quality --image langchain4j-platform-knowledge-service:cfa1d5b --compose deploy/.dev-infra-state/rag-quality-release/compose.sh` PASS。
- 实际 MySQL/S3/Redis/Qdrant/Elasticsearch + HashEmbedding；独立随机租户/库，不调用外部模型。入库所有必需sink成功，文档version=1可检索，ES来源实际命中。
- 无基线→BASELINE_REQUIRED；选择基线后→PASS/200；同键重复执行返回同一run；删除本次测试文档后Recall=0→REGRESSION/422。
- 首次尝试全文分支未命中；证据确认是ES刷新窗口，增加20次/每次间隔1秒的有界等待后通过，未放宽命中断言。
- Java十二项管理端口readiness均HTTP200/UP；前端HTTP200，匿名评测API经网关HTTP401。
- 冒烟租户 `infra-smoke-32bf704108d3`；记录 `deploy/.dev-infra-state/quality-smoke.json`（忽略的本地文件）；测试容器已停止，独立库/原文/记录保留。

## 远程 CI（源码 cfa1d5b）

- Coding Agent Kit、Frontend、Tax AI、AgentScope Cutover均SUCCESS。
- Java供应链 [38509783445](https://github.com/lirji/langchain4j-platform/actions/runs/38509783445) 在 `Scan aggregate dependency SBOM` 失败，后续attest/image-scan/release-images未执行。既有依赖债务保留，不宣称全CI通过、镜像安全扫描通过或生产发布就绪。
