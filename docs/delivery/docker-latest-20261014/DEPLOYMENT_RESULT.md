# 最新代码本地 Docker 部署

## 结果与范围

2026-10-14，用户明确授权“先把最新代码部署到docker上”。目标为本机 Docker Desktop 的 `langchain4j-platform` Compose 项目，复用现有 `dev-infra`。结果：**PASS（本地部署与运行冒烟）**。没有修改产品源码、删除卷、清空数据或生产部署。

- Java/前端：`3f31dfdb878416cc281848b8df9f3497e6918d9d`，部署开始时与远程 main 一致，镜像标签 `3f31dfd`，OCI revision 与提交一致。
- AgentScope：`69de7e98b3dcccf41980f184fbeb20430d6c84af`，与其远程 main 一致；使用 `git archive` 提取已提交源码构建，镜像 `agentscope-platform:69de7e9`。原仓库未提交的 DAG diagnostics 改动保留，未进入镜像。
- 固定镜像、容器实际 image ID、迁移和 HTTP 证据见 [RUNTIME_EVIDENCE.json](RUNTIME_EVIDENCE.json)。后续本次文档提交不改变上述已部署源码版本。

## 入口

- 前端：http://localhost:8093
- 网关：http://localhost:18080
- Casdoor 登录：http://localhost:8000

维持 Casdoor-only 鉴权。恢复了已停止的 authz-postgres、authz-casdoor、authz-spicedb，以及本项目专用 Qdrant、Elasticsearch、Redis ACL 容器；未启动 OA、商城等其他业务栈。

## 验证

- `mvn -o -B test`：1539 项，0 失败、0 错误、18 条件跳过。随后 `mvn -o -B package -Dmaven.test.skip=true` 成功。
- Compose config、前端/Java/迁移镜像构建、AgentScope 固定源码构建成功。
- `compose up -d --no-build --wait --wait-timeout 240` 退出 0。
- 14 项 Java 实际 `/actuator/health/readiness`：HTTP 200 / UP；AgentScope `/readiness`：HTTP 200 / UP。并非仅用容器 Running 判定。
- 前端、Casdoor OIDC discovery：HTTP 200。
- knowledge 授权 GET `/rag/ingestions/sources`：200、空列表；授权检索评测集列表：200。仅使用临时内部身份做只读冒烟，不保存令牌。
- edge 匿名 GET `/rag/ingestions/sources`：401。
- 17 个常驻项目容器运行，9 个迁移容器 exit 0；镜像 revision 全部与目标提交一致，无重启。历史 3 个独立 smoke 容器保留且不计入本轮运行目标。
- 知识入库库 V6→V8，执行 V7/V8；异步任务库 V4→V5；其余 7 项迁移无新版本，全部成功。
- 百炼模型目录校验找到 qwen3-vl-plus。本轮未发起付费模型推理，未执行真实知识源采集或浏览器登录后的完整业务回归。

## 功能与门禁边界

HTTP ETL 管理入口默认开启，实际来源尚未配置，因此空列表是预期行为，不能宣称真实业务源已接通。部署后的 AgentScope 实测 `async_task_enabled=true`、`async_task_dag_recovery_enabled=false`；本次没有额外启用节点恢复或变更任务运行角色。

Java 源码 d2d4043 的 AgentScope Cutover CI 与 Tax AI CI 已通过；3f31dfd 的 [Java supply chain 38573320856](https://github.com/lirji/langchain4j-platform/actions/runs/38573320856) 在 `Scan aggregate dependency SBOM` 失败，后续镜像扫描/attestation未完成。没有修改或绕过 CI，当前用户授权的本地运行不作为生产发布门禁通过证据。AgentScope 69de7e9 的 `CI and trusted release` 成功。本轮 Java 新构建镜像未独立完成供应链扫描。

## 本地证据、恢复与保留

本机固定镜像 Compose 包装脚本及构建/测试/启动日志：`deploy/.dev-infra-state/latest-3f31dfd/`（Git 忽略）。运行入口：

```bash
bash deploy/.dev-infra-state/latest-3f31dfd/compose.sh up -d --no-build --wait --wait-timeout 240
python3 deploy/.dev-infra-state/latest-3f31dfd/runtime-check.py
```

脚本沿用标准启动流程的模型凭据加载、中央前端端口、网关18080和现有 dev-infra 配置；报告不包含凭据。原镜像清单在 `before.json`，旧镜像保留，本轮未回滚。回退应用必须考虑 V7/V8 与墓碑兼容边界，不能让不认识墓碑/暂停的旧协调器或 worker 写入；代码回退不撤销已提交数据库效果。

不重跑旧全量备份/迁移脚本，不删除任何业务卷、历史测试容器或工作树。保留私有配置、构建产物、镜像、日志与 AgentScope 构建源码快照；本次未创建新 Git worktree。知识源接入与回答质量评测按用户本轮指示暂让位于部署，尚未实施。
