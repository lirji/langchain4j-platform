# 最新代码本地 Docker 部署

## 结果与入口

2026-10-19，按用户“将最新代码部署到docker上”的授权，将当前最新已提交版本部署到本机 Docker Desktop `desktop-linux` 的 `langchain4j-platform` Compose 项目。**PASS（本地部署、就绪检查、可靠入库与检索冒烟）**。复用现有 dev-infra 和 auth-platform，保留业务数据与其他项目。

- 前端：[http://localhost:8093](http://localhost:8093)
- 网关：[http://localhost:18080](http://localhost:18080)
- Casdoor 登录：[http://localhost:8000](http://localhost:8000)

维持 Casdoor-only 鉴权、combined 知识服务及现有语义向量配置；没有切换向量空间、重建历史索引或启用新功能开关。

## 实际版本

- 本仓库 Java、前端、迁移：`5e8c4d986e3c411b5df5037c41f8993c58e88551`，部署时与 origin/main 一致，镜像标签 `5e8c4d9`。最后一次执行代码变更为 `ef9b70c`，`5e8c4d9` 是其最终验收文档。
- AgentScope：保留 `agentscope-platform:3cb39f6`，OCI revision `3cb39f6e125a17d5be40430d85dc3aea39bad045`，真实镜像 ID `sha256:8bd78ad6daa069c82ec08c9988398c3720fcb917721d02c865c4e6d3d9d7a046`。该镜像的执行代码与独立仓库最新 `7738cb7` 相同，差异仅文档；没有降级诊断能力或修改该仓库。
- LiteLLM 使用现有固定版本镜像。全部 26 个固定项目容器的实际 image ID 与本轮镜像清单一致；自建镜像同时核对 image 的 OCI revision，而非只检查容器标签。
- 版本、健康、迁移、备份摘要和冒烟结果见 [RUNTIME_EVIDENCE.json](RUNTIME_EVIDENCE.json)。本次部署记录的后续文档提交不改变上述运行代码版本。

## 验证结果

| 检查 | 实际结果 |
| --- | --- |
| 源码验证依据 | `ef9b70c` 的解析优化最终回归 1045 项，0 失败、0 错误、17 环境条件跳过；本次未修改源码，沿用有效证据，详见[解析优化进度](../knowledge-parsing-hardening/PROGRESS_STATE.md) |
| 构建 | `mvn -B -o package -Dmaven.test.skip=true` 全模块成功；Compose config 与全部默认应用/迁移镜像构建成功 |
| 启动 | 固定镜像 `up -d --no-build --wait --wait-timeout 240` 退出 0 |
| 固定容器 | 17 个应用容器 running，9 个迁移容器 exit 0；检查时无重启；历史验证容器与临时 one-off 迁移单独识别 |
| 实际就绪 | 14 个 Java `/actuator/health/readiness` 及 AgentScope `/readiness` 全部 HTTP 200 / UP |
| 前端与认证 | 页面、JS/CSS 资源、Casdoor OIDC discovery 均 200；匿名网关来源列表请求 401；临时内部身份访问知识来源与评测集列表均 200 |
| 数据库迁移 | 入库库 V8→V10，应用 V9/V10；其余 8 库无新版本，全部迁移成功。迁移前备份 48340 字节，校验和见证据；业务库原有 34 个任务前后均 READY |
| 可靠入库冒烟 | 最新知识服务镜像在独立随机测试库/租户中，真实使用 MySQL、S3、Redis、Qdrant、Elasticsearch；重复上传返回同一 jobId，任务 READY，混合检索与独立 ES 分支均检出测试文档 |
| 新准备协议 | 已查询测试库，确认准备快照落库，`schemaVersion=1`、`processingVersion=knowledge-structured-v5` |
| 模型预检 | 百炼模型目录包含 `qwen3-vl-plus`；没有发起付费模型推理 |

入库冒烟使用 hash embedding，关闭非必要的图谱、重排等可选链路；证明实际存储、准备快照与检索链路正常，不代表语义模型效果或可选图谱链路已验收。测试容器已停止，测试库/租户数据保留用于审计；未触及业务文档。

## 当前边界

PDF 选择性 OCR 代码已包含在新版中，但本轮保持 `RAG_PARSING_OCR_ENABLED=false`；当前标准镜像没有启用专用 OCR 运行环境。后续启用时应采用已提供的 OCR 镜像及对应资源配置。

本轮未做浏览器登录后的完整业务回归、真实企业知识源采集、付费模型推理或生产容量压测。来源与评测集列表为空是现有数据状态。

最新源码的 [Java supply chain 39198357117](https://github.com/lirji/langchain4j-platform/actions/runs/39198357117) 仍在 `Scan aggregate dependency SBOM` 失败，镜像扫描与 attestation 未完成，**生产发布门禁保持 HOLD**。本次用户授权的是本地 Docker 运行，没有改动或绕过 CI，也不以本轮运行成功替代供应链验收。

## 本机证据与恢复

私有目录 `deploy/.dev-infra-state/latest-5e8c4d9/`（Git 忽略、目录 0700）保存固定镜像包装脚本、image manifest、原容器清单、数据库备份、构建/迁移/启动日志及冒烟详细结果。凭据、临时内部 JWT 和 SQL 数据没有进入提交；检查脚本只输出安全摘要。

```bash
# 在本机现有配置上启动已验证镜像，不重新构建。
bash deploy/.dev-infra-state/latest-5e8c4d9/compose.sh up -d --no-build --wait --wait-timeout 240
python3 deploy/.dev-infra-state/latest-5e8c4d9/runtime-check.py
```

本轮无回滚。旧镜像和备份保留；V9/V10 已生效，不能直接恢复不识别准备快照/准入约束的旧 worker 继续领任务。故障处理优先修复/前滚，回退前需核对代码与 schema 兼容；不自动反向迁移或覆盖数据库。

未创建新 worktree，未清理历史工作树、验证容器、镜像或忽略文件。私有配置、备份和测试证据需保留；历史工作树归属其他任务，本轮不判断或执行其删除。仅部署文档进入 `chore/deploy-latest-docker-20261019`，按持续 Git 授权正常合并并推送 main，交付结果保存到本机 `delivery-result.json`。
