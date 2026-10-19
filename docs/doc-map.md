# 文档同步地图

本表从本次明确涉及的区域开始维护，不代表已审查全仓库文档。同步基线：`176d83b`；同步日期：2026-10-19，任务 `knowledge-parsing-hardening`（KDP-06-B 结构解析与来源）。

| 代码区域 | 权威文档 | 本次同步 |
| --- | --- | --- |
| knowledge-service 文档提取、解析配置和任务错误分类 | [可靠入库接入](对话与检索/可靠入库接入.md)、[RAG 指南](对话与检索/rag-guide.md) | 字符预算、错误码、HTTP 行为及应用范围 |
| knowledge-service 分块及 CommonMark 依赖 | [可靠入库接入](对话与检索/可靠入库接入.md) | 唯一编号、真实标题、原文保真与升级恢复约束 |
| Tika 解析能力、格式样本与切分策略选择 | [文档支持范围](对话与检索/知识库文档支持范围.md) | 样本已验证与解析器已注册分开，标明 OCR/结构化输出和默认切分策略边界 |
| 知识库解析生产改进 | [生产优化方案](对话与检索/知识库文档解析生产优化.md) | 已证实问题及下一阶段排期、依赖、理由与验收；规划不代表已实现 |
| 当前修复的验证与交付 | [PROGRESS_STATE](delivery/knowledge-parsing-hardening/PROGRESS_STATE.md) | 唯一实施状态；根检查点仅保存入口 |

实际部署、OCR 可用性、吞吐及复杂版面质量未在本轮核验。未改动数据库结构、认证、连接凭据、部署拓扑或线上数据。

| 异步入库结果快照、V9、对象权限和升级 | [KDP-04 契约](delivery/knowledge-parsing-hardening/KDP-04.md)、[可靠入库接入](对话与检索/可靠入库接入.md) | 先持久化/绑定后 sink；重试复用、旧任务处置、私有对象预算与发布顺序 |
| 主格式合成基线与 CSV 依赖兼容 | [KDP-06-A](delivery/knowledge-parsing-hardening/KDP-06-A.md)、[支持范围](对话与检索/知识库文档支持范围.md) | 17 场景、内容/结构/定位和资源测量口径；已发现的 CSV 运行时缺类修复 |
| 文件解析进程、预算和运行边界 | [KDP-05-A](delivery/knowledge-parsing-hardening/KDP-05-A.md)、[可靠入库接入](对话与检索/可靠入库接入.md) | 真实 fork、异常/截止、Boot artifact 和受限 Linux 容器验证；未部署业务服务 |
| 结构解析、格式切分与检索来源 | [KDP-06-B](delivery/knowledge-parsing-hardening/KDP-06-B.md)、[结构报告](delivery/knowledge-parsing-hardening/KDP-06-B-structure.json) | 格式位置、表头同块率、告警、源文/增强分离、ES/API 加法字段及验证限制 |
