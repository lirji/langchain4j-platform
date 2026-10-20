# Codex Progress

- 当前任务：`knowledge-content-dedup`，异步文件内容去重、前端反馈、必要验证及本机部署已通过；Git 最终交付核对恢复入口和 refs。
- 唯一恢复入口：[PROGRESS_STATE](docs/delivery/knowledge-content-dedup/PROGRESS_STATE.md)。前任务[增强失败保护](docs/delivery/ingestion-context-failfast/PROGRESS_STATE.md)已交付至 main `5c1704f`；[知识库前端/OCR](docs/delivery/knowledge-ingestion-ui/PROGRESS_STATE.md)的验证与部署证据保留。
- 本机知识服务/前端/V12 migrator 运行 `6a562be`，网关保持 `c1eedd1`；OCR 仍默认开启，36 个原入库任务保留。
- 前任务 [Docker 部署记录](docs/delivery/docker-latest-20261019/PROGRESS_STATE.md) 保留；此次只更新知识服务、前端和知识库迁移器，其他原镜像/容器保持。
- 前任务 [解析优化状态](docs/delivery/knowledge-parsing-hardening/PROGRESS_STATE.md) 的生产供应链 HOLD 保持，不执行生产部署。
- [知识库文档支持范围](docs/对话与检索/知识库文档支持范围.md) 已同步默认 OCR；[业务评估检查点](docs/delivery/business-assessment-20261014/CODEX_PROGRESS.md) 独立归档，不续做相邻任务。
- 独立文档任务 `ai-framework-assessment-20261020`：[讨论记录](docs/平台工程/AI框架选型与LangChain4j评估-20261020.md)与文档索引已写入；9 个仓库链接、版本、默认 AgentScope 路由及差异格式核对通过，仅保存讨论，不执行升级或迁移。任务分支 `feat/ai-framework-assessment-docs`，基线 `7bb3a65`；Git 交付状态按该分支与 `origin/main` 的包含关系核对，不续做其他交付任务。

## 独立任务：README 个人作品展示（2026-10-20）

- 目标：更新根 README，展示本仓库与独立 AgentScope 项目的最新已实现能力、协作架构、工程亮点、运行和文档入口；公开内容不含本机路径或登录凭据。
- 范围与授权：仅文档；AgentScope 仓库只读取证。沿用独立任务分支、验证后正常合并推送 `origin/main` 的持续授权，不执行部署。
- 仓库与基线：当前仓库 `langchain4j-platform`，分支 `docs/readme-project-showcase`，起点 `919ec70`；AgentScope 取证基线 `7738cb7`，公开仓库名 `lirji/platform-agentscope`。开始时两个工作树均干净。
- 计划：核对两个仓库 → 重写展示首页 → 校验链接、默认配置、版本和 Markdown → 提交、合并、推送并核对。
- 已完成：两仓库作品定位、协作架构、能力表、近期迭代、技术栈、代码组织和启动入口；纳入可靠入库、默认 OCR、内容去重、记忆治理、DAG 恢复与诊断，并区分默认能力和可选扩展。仅改 `README.md`、`docs/README.md`、`docs/doc-map.md` 和本检查点。
- 验证：164 个链接/锚点检查通过，GitHub 三个关联仓库公开且基线匹配；Markdown 结构、两段 Bash 语法、Mermaid flowchart 解析和 `git diff --check` 通过；版本、路由、IAM 构建依赖、端口及恢复角色已核对。纯文档变更不重跑业务测试。
- 证据与恢复：`target/readme-project-showcase/` 保存校验、文件指纹与 Git 交付结果；Git 是否已完成以 `delivery.json` 及任务分支/main/`origin/main` 的包含关系核对，避免重复提交。无运行服务、无新建 worktree；预览和校验产物可清理，旧任务数据及其他工作树保留。
- CI 边界：基线 `919ec70` 的 Coding Agent Kit CI 通过、供应链工作流失败；本任务不改代码/流水线，不将文档验证视为生产发布准入。新提交 CI 实际状态记录于交付结果。
- 已核实两个展示仓库均为 GitHub 公开仓库；IAM SDK 尚未发布公共 Maven，首页已补固定版本依赖准备。无部署、模型调用或 AgentScope 仓库修改。规范采用根 AGENTS、`project-documentation`、`task-git-delivery`；旧任务恢复入口保持。
