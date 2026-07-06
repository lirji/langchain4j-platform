# Project Documentation

这组文档面向新接手项目的工程师、架构评审者和集成方，用来快速了解平台能力、服务边界、部署方式和接口入口。

## 推荐阅读顺序

1. [能力文档](capabilities.md)：先看平台已经具备哪些业务和工程能力。
2. [架构文档](architecture.md)：理解微服务拆分、调用链路、共享库和数据边界。
3. [运行与配置手册](operations.md)：本地启动、环境变量、验证命令和排障入口。
4. [接口与集成速查](api-reference.md)：按服务查看主要 HTTP API、鉴权方式和典型请求。
5. [开发者指南](developer-guide.md)：新增服务、跨服务 DTO、测试和提交建议。
6. [演进路线](migration-roadmap.md)：查看从单体拆分到当前状态的历史和剩余待办。

## 当前定位

`langchain4j-platform` 是从原单体 `LangChain4j_project` 拆分出来的微服务平台。业务 API 统一从 `edge-gateway` 进入，所有 LLM 调用统一走 LiteLLM/OpenAI-compatible 网关。当前项目已经覆盖 conversation、knowledge、agent、analytics、workflow、async-task、channel、interop、eval 等核心限界上下文。

## 文档维护原则

- 只把已经落地并有代码支撑的内容写入“当前能力”。
- 计划中或未完成的能力写入“限制与后续演进”。
- 服务间请求/响应 DTO 优先引用 `platform-protocol`。
- 配置项以各服务 `application.yml` 和 `deploy/docker-compose.yml` 为准。
