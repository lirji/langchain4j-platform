# 文档同步地图

本表从本次明确涉及的区域开始维护，不代表已审查全仓库文档。同步基线：`6919c60` 后的 KDP-06-E；同步日期：2026-10-19，任务 `knowledge-parsing-hardening`（其他格式验收与最终支持范围）。

独立首页同步：2026-10-20，任务 `readme-project-showcase`；Java 仓库基线 `919ec70`、AgentScope 仓库基线 `7738cb7`。本次仅重写展示首页并更新文档导航，以下知识库专项记录保留原核对范围。

| 代码区域 | 权威文档 | 本次同步 |
| --- | --- | --- |
| 两仓库职责、默认 Agent 路由、近期能力、依赖及启动脚本 | [作品首页](../README.md)、[文档入口](README.md) | 公开 GitHub 仓库、协作架构、可靠入库/OCR/去重、记忆治理、DAG 恢复/诊断；版本以构建声明为准，区分 inline 与 api/worker、默认开关与可选扩展 |
| knowledge-service 文档提取、解析配置和任务错误分类 | [可靠入库接入](对话与检索/可靠入库接入.md)、[RAG 指南](对话与检索/rag-guide.md) | 字符预算、错误码、HTTP 行为及应用范围 |
| knowledge-service 分块及 CommonMark 依赖 | [可靠入库接入](对话与检索/可靠入库接入.md) | 唯一编号、真实标题、原文保真与升级恢复约束 |
| Tika 解析能力、格式样本与切分策略选择 | [文档支持范围](对话与检索/知识库文档支持范围.md) | 样本已验证与解析器已注册分开，标明 OCR/结构化输出和默认切分策略边界 |
| 知识库解析生产改进 | [生产优化方案](对话与检索/知识库文档解析生产优化.md) | 已证实问题及下一阶段排期、依赖、理由与验收；规划不代表已实现 |
| 当前修复的验证与交付 | [PROGRESS_STATE](delivery/knowledge-parsing-hardening/PROGRESS_STATE.md) | 唯一实施状态；根检查点仅保存入口 |
| 异步入库结果快照、V9、对象权限和升级 | [KDP-04 契约](delivery/knowledge-parsing-hardening/KDP-04.md)、[可靠入库接入](对话与检索/可靠入库接入.md) | 先持久化/绑定后 sink；重试复用、旧任务处置、私有对象预算与发布顺序 |
| 主格式合成基线与 CSV 依赖兼容 | [KDP-06-A](delivery/knowledge-parsing-hardening/KDP-06-A.md)、[支持范围](对话与检索/知识库文档支持范围.md) | 17 场景、内容/结构/定位和资源测量口径；已发现的 CSV 运行时缺类修复 |
| 文件解析进程、预算和运行边界 | [KDP-05-A](delivery/knowledge-parsing-hardening/KDP-05-A.md)、[可靠入库接入](对话与检索/可靠入库接入.md) | 真实 fork、异常/截止、Boot artifact 和受限 Linux 容器验证；未部署业务服务 |
| 结构解析、格式切分与检索来源 | [KDP-06-B](delivery/knowledge-parsing-hardening/KDP-06-B.md)、[结构报告](delivery/knowledge-parsing-hardening/KDP-06-B-structure.json) | 格式位置、表头同块率、告警、源文/增强分离、ES/API 加法字段及验证限制 |
| 流式上传、模型/索引分批与公平调度 | [KDP-05-B](delivery/knowledge-parsing-hardening/KDP-05-B.md) | B1/B2 验证通过；共享额度迁移、关闭、上下文与资源边界 |
| PDF 选择性 OCR 与本地引擎镜像 | [KDP-06-C](delivery/knowledge-parsing-hardening/KDP-06-C.md)、[可靠入库接入](对话与检索/可靠入库接入.md) | 选择/预算/来源/退出、默认开关及真实 Linux 验证范围 |
| 语义窗口/上下文/最终向量总预算与 metadata 放大 | [KDP-06-D](delivery/knowledge-parsing-hardening/KDP-06-D.md)、[固定对照](delivery/knowledge-parsing-hardening/KDP-06-D-cost.json) | 模型逻辑调用、估算 token、实际策略、父块重复量及保留缺省的理由 |
| DOC/XLS/PPT/PPTX/HTML/RTF/ODT 与 UTF-16 验收 | [KDP-06-E](delivery/knowledge-parsing-hardening/KDP-06-E.md)、[格式报告](delivery/knowledge-parsing-hardening/KDP-06-E-formats.json) | 旧 XLS 结构化、内容格式标记、真实 Boot/Linux、截断与超限，保留未验收变体 |

实际生产部署、吞吐及复杂版面质量不在合成验收范围；本阶段无数据库结构或线上数据变更。
