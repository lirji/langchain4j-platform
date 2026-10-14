# 实施切片

各片串行执行；用户已授权全部计划，无需逐片等待确认。下一可执行片 M1。M1-PERSIST 已交付不重做。

| ID | 可观察结果 | 依赖 | Owner/路径 | 验收 | 状态 |
|---|---|---|---|---|---|
| M1 | 记忆状态/版本/代次与迁移 | 已有持久化 | conversation profile、conversation V2 | 租户隔离、并发校验、迁移兼容、过期过滤 | READY |
| M2 | 受控抽取与召回，清空不回填 | M1 | UserProfileService/ChatService | 异步跨清空/停用不写入；候选不召回 | TODO |
| M3 | 审核、编辑、固定、遗忘、开关、审计API | M1,M2 | memory governance | HTTP契约、越权、版本冲突、安全失败 | TODO |
| M4 | 现有控制台记忆治理入口 | M3 | chat Vue组件/API | 真实API操作、错误恢复、身份切换不漏数据 | TODO |
| M5 | 真实MySQL/回归/治理文档 | M4 | test/deploy/docs | 多连接、重启恢复、删除、全测试 | TODO |
| K1 | 反馈和运营候选持久化 | M5 | knowledge operations、knowledge-ingestion迁移 | 幂等、租户隔离、持久化重启 | TODO |
| K2 | 内容修订和绑定版本的评测 | K1 | knowledge operations | 修改使旧评测失效，结果可追溯 | TODO |
| K3 | 审核和真实入库发布闭环 | K2 | operations+existing ingestion | 失败重试、并发发布、READY才发布 | TODO |
| K4 | 效果反馈、回归与回滚路径 | K3 | operations+existing retrieval | 反馈关联发布、旧内容新版本恢复 | TODO |
| K5 | 现有RAG控制台运营入口 | K4 | rag Vue组件/API | 反馈→修订→评测→审核→发布→复查 | TODO |
| K6 | 端到端/数据库/回滚/交付 | K5 | docs/tests/runtime | 真实DB、前后端检查、配置门禁、git交付 | TODO |

不新建中间件。Runtime 仅在新迁移/路由接入时修改既有 Compose/Helm 门禁。接口细节缺口由设计轨道先补文档，再实施，不能由UI编造响应。
