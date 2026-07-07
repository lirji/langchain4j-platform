# Project Documentation

这组文档面向新接手项目的工程师、架构评审者和集成方，用来快速了解平台能力、服务边界、部署方式和接口入口。

## 推荐阅读顺序

1. [能力文档](capabilities.md)：先看平台已经具备哪些业务和工程能力。
2. [架构文档](architecture.md)：理解微服务拆分、调用链路、共享库、事件流和数据边界。
3. [运行与配置手册](operations.md)：本地启动、环境变量、验证命令和排障入口。
4. [接口与集成速查](api-reference.md)：按服务查看主要 HTTP API、鉴权方式和典型请求。
5. [开发者指南](developer-guide.md)：新增服务、跨服务 DTO、测试和提交建议。
6. [演进路线](migration-roadmap.md) / [迁移收尾方案与排期](migration-remaining-plan.md)：历史、已落地项与剩余待办。

## 专题接入指南

- [RAG 接入指南](rag-guide.md)：文档/图片上传、向量库 provider、collection-per-tenant 隔离、embedding（hash/openai/ollama）、混合检索、GraphRAG、语义缓存 L1。
- [Agent 能力与编排指南](agent-guide.md)：深度 Agent(ReAct)、DAG、reflexion/voting/chaining、cascade 模型级联、各 ReAct 动作与开关。
- [A2A 接入指南](a2a-guide.md)：agent-card 发现、`/interop/a2a` JSON-RPC task 协议、代理到 agent、live discovery。
- [MCP 接入指南](mcp-guide.md)：agent 作 MCP client（`mcp_call`）与 interop 的 MCP tool surface。
- [事件总线与终态可靠投递(EOS)指南](eventbus-guide.md)：事务性 outbox + relay + 消费侧去重 = effective exactly-once（workflow/async-task 两侧）。
- [部署指南](deployment-guide.md)：本地 docker-compose、k8s/Helm 伞状 chart、External Secrets、Service DNS、Config Server。

## 当前定位

`langchain4j-platform` 是从原单体 `LangChain4j_project` 拆分出来的微服务平台。业务 API 统一从 `edge-gateway` 进入（api-key→内部 JWT），所有 LLM 调用统一走 LiteLLM/OpenAI-compatible 网关。当前覆盖 conversation、knowledge、agent、analytics、workflow、async-task、channel、interop、eval、vision 等限界上下文，配套 config-server 配置中心与 platform-eventbus 事件总线（Kafka 事务性 outbox + relay 的可靠终态投递）。

## 文档维护原则

- 只把已经落地并有代码支撑的内容写入“当前能力”。
- 计划中或未完成的能力写入“限制与后续演进”。
- 服务间请求/响应 DTO 优先引用 `platform-protocol`。
- 配置项以各服务 `application.yml` 和 `deploy/docker-compose.yml` 为准。
