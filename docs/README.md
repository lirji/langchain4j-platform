# Project Documentation

这组文档面向新接手项目的工程师、架构评审者和集成方，用来快速了解平台能力、服务边界、部署方式和接口入口。

## 推荐阅读顺序

1. [能力文档](参考/capabilities.md)：先看平台已经具备哪些业务和工程能力。
2. [架构文档](参考/架构文档.md)：理解微服务拆分、调用链路、共享库、事件流和数据边界。
3. [运行与配置手册](参考/operations.md)：本地启动、环境变量、验证命令和排障入口。
4. [接口与集成速查](参考/api-reference.md)：按服务查看主要 HTTP API、鉴权方式和典型请求。
5. [开发者指南](参考/developer-guide.md)：新增服务、跨服务 DTO、测试和提交建议。
6. [演进路线](迁移/migration-roadmap.md) / [迁移收尾方案与排期](迁移/migration-remaining-plan.md)：历史、已落地项与剩余待办。

## 专题接入指南

> 大部分专题文档由冻结单体 `LangChain4j_project/docs/` 的深度设计文档迁移并适配到微服务架构而来（端点走边缘网关 :8080、配置改环境变量、按服务启动）。

**场景导航**
- [业务场景总览](scenarios.md)：知识库问答 / 智能客服(NL2SQL+工作流+渠道+语音) / 深度 Agent / 多模态 / 互操作——各场景怎么拼、入口端点、跑到什么程度。

**对话与检索**
- [RAG 接入指南](对话与检索/rag-guide.md)：文档/图片上传、向量库 provider、collection-per-tenant 隔离、embedding（hash/openai/ollama）、混合检索、检索增强（rerank/查询扩展/上下文分块/HanLP 分词）、GraphRAG、Obsidian 导入、语义缓存 L1。
- [记忆指南](对话与检索/memory-guide.md)：多轮短期记忆窗口（messages/tokens/summary）+ 长期用户画像（`/chat/memory`、`/memory/profile`）。
- [语义缓存指南](对话与检索/semantic-cache.md)：L1 语义响应缓存、阈值与 embedding 独立性、按租户桶、`DELETE /chat/cache` 失效、文档变更跨服务失效。
- [模型级联指南](对话与检索/model-cascade.md)：便宜模型先答 → 置信门 → 升级强模型（`/chat/cascade`）；cheap/strong 逻辑模型经 LiteLLM 映射。
- [NL2SQL / ChatBI 指南](对话与检索/nl2sql-guide.md)：自然语言 → SQL → 执行 → 解读的受控链路、六层 SQL 安全护栏、数字 grounding、多租户库隔离。

**Agent 与编排**
- [Agent 能力与编排指南](Agent编排/agent-guide.md)：深度 Agent(ReAct)、DAG、reflexion/voting/chaining、cascade 模型级联、各 ReAct 动作与开关。
- [工作流指南](Agent编排/workflow-guide.md)：两条「workflow」线——Flowable 退款审批业务引擎（`/workflow/**` + 三种终态通知）与 LLM 编排 5 模式（chain/routing/DAG/voting/reflexion，映射到 `/agent/**`）。
- [Code Interpreter 动作指南](Agent编排/code-exec.md)：agent `code_exec` 动作（默认关）；默认 subprocess 子进程隔离 + 可选 JShell、denylist/超时/输出截断与威胁模型。

**多模态与语音**
- [视觉指南](多模态语音/vision-guide.md)：vision-service `/vision/caption`·`/describe` + 对话入口 `/chat/vision` + agent `browser_see`；图像描述模型、大小/MIME 护栏。
- [语音指南](多模态语音/voice-guide.md)：voice-service 语音闭环 `/voice/transcribe`·`/voice/chat`(+`/stream`)，ASR(whisper)→对话大脑→TTS。

**互操作与渠道**
- [A2A 接入指南](互操作渠道/a2a-guide.md)：agent-card 发现、`/interop/a2a` JSON-RPC task 协议、`message/stream` 真流式 + push 中继、代理到 agent、live discovery。
- [MCP 接入指南](互操作渠道/mcp-guide.md)：agent 作 MCP client（`mcp_call`）与 interop 的 MCP tool surface。
- [钉钉知识库客服接入指南](互操作渠道/dingtalk-guide.md)：钉钉群 @机器人 → 查知识库 → 机器人回复；镜像飞书事件桥、机器人发消息 API 回复、无命中转人工兜底。

**平台工程（横切）**
- [事件总线与终态可靠投递(EOS)指南](平台工程/eventbus-guide.md)：事务性 outbox + relay + 消费侧去重 = effective exactly-once（workflow/async-task 两侧）。
- [可观测性指南](平台工程/observability-guide.md)：跨服务 traceId 透传、OTel GenAI span（Spring Boot 原生 tracing 开关）、Prometheus 指标、`/actuator/{tokenbudget,cost}`。
- [成本归因与配额指南](平台工程/cost-attribution.md)：per-tenant USD 成本归因 + token 预算，redis 默认的分布式计数（水平扩容正确性）、`/actuator/{tokenbudget,cost}`。
- [评测指南](平台工程/eval-guide.md)：eval-service `/eval/**` 回归客户端、检索召回评测（Recall@k/MRR/Hit@k）、baseline suite、对冻结单体双跑 oracle 门禁。
- [部署指南](平台工程/deployment-guide.md)：本地 docker-compose、k8s/Helm 伞状 chart、External Secrets、Service DNS、Config Server。
- [能力展示与试用控制台](平台工程/能力展示控制台.md)：前后端分离的独立前端 `capability-showcase-frontend/`（Vue3 静态 SPA，可独立部署）；direct mode 带 X-Api-Key 跨域直调业务能力、能力五态诚实呈现、catalog 静态数据驱动；后端仅 edge-gateway 加 CORS。
- [能力前端模块拆分建议](平台工程/能力前端模块拆分建议.md)：平台能力 → 可独立拆出的前端模块（Chat/RAG/Agent/Async/Analytics/Workflow/Multimodal/Interop-Eval/Channel）的边界、优先级与拆分性。
- [数据存储清单](参考/databases.md)：MySQL/Redis/Qdrant/Kafka/H2 的端口、账号密码、所属服务与落库开关；含可选向量库（pgvector/milvus/chroma/doris）。

## 当前定位

`langchain4j-platform` 是从原单体 `LangChain4j_project` 拆分出来的微服务平台。业务 API 统一从 `edge-gateway` 进入（api-key→内部 JWT），所有 LLM 调用统一走 LiteLLM/OpenAI-compatible 网关。当前覆盖 conversation、knowledge、agent、analytics、workflow、async-task、channel、interop、eval、vision、voice 等限界上下文，配套 config-server 配置中心与 platform-eventbus 事件总线（Kafka 事务性 outbox + relay 的可靠终态投递）。

## 文档维护原则

- 只把已经落地并有代码支撑的内容写入“当前能力”。
- 计划中或未完成的能力写入“限制与后续演进”。
- 服务间请求/响应 DTO 优先引用 `platform-protocol`。
- 配置项以各服务 `application.yml` 和 `deploy/docker-compose.yml` 为准。
