# AI 框架选型与 LangChain4j 评估

讨论日期：2026-10-20。仓库核对基线：`7bb3a6536a73afb2008a60c466f8730cd43883e0`。

本次讨论回答两个问题：基于 LangChain4j 建设的平台是否技术落后，以及还有哪些 AI 应用与 Agent 框架值得关注。范围是大模型应用开发和 Agent 执行，不涉及模型训练框架。

**LangChain4j 仍在持续演进，对本项目的 Java / Spring 服务仍是合理选择。当前更值得评估的是依赖升级、模型能力适配和 Agent 执行可靠性，没有充分依据仅因框架名称而整体重写平台。**

本项目已经形成 Java 业务服务、LangChain4j 模型接入和独立 AgentScope 编排服务协作的结构。后续比较应基于实际默认调用链，而不能只看仓库名称或保留的旧 Java Agent 实现。本文保留讨论结论并补正这一架构事实；升级和迁移均为后续候选事项。

## 当前项目使用情况

| 事项 | 仓库事实 | 依据 |
| --- | --- | --- |
| Java 与 Spring Boot | Java 21、Spring Boot 3.3.5 | [根 POM](../../pom.xml) |
| LangChain4j | 主版本 `1.13.1`，MCP 模块 `1.13.1-beta23` | [根 POM](../../pom.xml) |
| Java 模型接入 | `GatewayChatModelFactory` 构建 LangChain4j 模型，接入 LiteLLM/OpenAI-compatible 网关，并组合租户归因和监听器 | [模型工厂](../../platform-gateway-client/src/main/java/com/lrj/platform/gateway/GatewayChatModelFactory.java) |
| 默认 Agent 编排 | Compose 中 `AGENT_URI` 与 `AGENT_BASE_URL` 默认均为 `http://agentscope-orchestrator:8085`；Java `agent-service` 保留作显式整服务回滚目标 | [Compose](../../deploy/docker-compose.yml)、[切换与回滚手册](../Agent编排/agentscope-full-cutover.md) |

上述路由是仓库默认配置，实际部署可通过环境变量覆盖。默认 Agent 路径不会把未迁移的高风险动作静默回退到 Java；具体工具覆盖与切换规则沿用[现有手册](../Agent编排/agentscope-full-cutover.md)。

Java `agent-service` 中仍保留以下实现，可用于理解既有设计和评估回滚路径：

- [DeepAgentService](../../agent-service/src/main/java/com/lrj/platform/agent/DeepAgentService.java)：ReAct 决策与工具执行循环、子 Agent 委派、预算限制、超时、循环检测和笔记压缩。
- [AgentDagService](../../agent-service/src/main/java/com/lrj/platform/agent/dag/AgentDagService.java)：按依赖分层执行子任务、同层并行、结果汇总，以及可选的评审后重新规划。
- [AgentConfig](../../agent-service/src/main/java/com/lrj/platform/agent/AgentConfig.java)：通过 LangChain4j `AiServices` 装配决策、规划和评审等模型接口。

这些代码说明平台曾自行实现较多 Agent 编排能力，但代码保留不等于当前默认请求仍执行这些实现。

## 如何判断 LangChain4j 是否落后

### 框架仍在更新

2026-10-20 查询时，官方发布页将 `1.22.0 / 1.22.0-beta32` 标记为最新发布，仍在更新模型适配、工具调用、MCP 等能力。项目的 `1.13.1` 有升级评估空间；版本差距本身不能证明选型已经失效。后续升级时应重新核对发布记录及各模块版本，不能将这个日期快照当作长期版本目标。[官方发布记录](https://github.com/langchain4j/langchain4j/releases)

### 基础能力与 Agent 编排需要分别评估

LangChain4j 覆盖模型调用、RAG、工具调用、结构化输出、流式响应等应用能力，也提供多 Agent、人工介入和状态恢复相关抽象。不过，官方仍将整个 `langchain4j-agentic` 模块标为实验性，其 API 和兼容性需要单独评估。不能把基础模块的使用经验直接当作所有 Agent 功能的成熟度证明。[官方 Agent 文档](https://docs.langchain4j.dev/tutorials/agents/)

本项目 Java Agent 的主要编排逻辑来自自身实现，不能将这些能力全部归因于 `langchain4j-agentic`。

### 系统效果取决于完整调用链

框架负责模型接入、上下文组织、检索、工具调用和执行控制。回答质量与任务成功率还取决于模型、数据、提示词、工具契约和评估机制，更换框架不会自动提高同一模型的效果。

结合本项目模型工厂与网关结构，最新模型能力是否可用还需要核对 LangChain4j 适配器、LiteLLM 转发和目标模型接口。只比较框架名称无法定位能力瓶颈。

## 值得关注的框架

下表同时包含较新的 Agent 框架和持续演进的成熟方案。它们承担的职责不同，不能直接按发布时间排列优劣；各语言 SDK 的功能和版本也不保证同步。表中“主要生态”用于帮助筛选，不是完整语言支持清单。

| 框架 | 主要生态 | 关注点与适用场景 |
| --- | --- | --- |
| [Spring AI](https://docs.spring.io/spring-ai/reference/) | Java / Spring | 模型接入、RAG、工具调用、MCP、可观测性与自动配置；适合比较 Spring 集成和维护成本。 |
| [Spring AI Alibaba](https://github.com/alibaba/spring-ai-alibaba) | Java | 在 Spring AI 基础上提供 Agent、图编排、状态持久化、人工介入和上下文管理；适合评估复杂 Agent 流程。 |
| [AgentScope Java](https://github.com/agentscope-ai/agentscope-java) | Java | 长时间运行的 Agent、多 Agent 协作、权限控制、沙箱与会话恢复；适合关注 Java Agent 执行能力。 |
| [LangGraph / Deep Agents](https://docs.langchain.com/oss/python/langgraph/overview) | Python 等 | LangGraph 提供状态图、持久化和中断恢复；Deep Agents 在其上增加规划、子 Agent、文件工具和上下文管理。 |
| [Google ADK](https://adk.dev/) | Python、Java、TypeScript 等 | Agent 开发、工具接入、多 Agent 工作流及评估；选型时需核对目标语言 SDK 的具体覆盖。 |
| [OpenAI Agents SDK](https://openai.github.io/openai-agents-python/) | Python / [TypeScript](https://openai.github.io/openai-agents-js/) | Agent 执行循环、任务交接、工具、护栏、追踪和人工介入；适合构建以 Agent 为核心的应用。 |
| [LlamaIndex](https://developers.llamaindex.ai/python/framework/) | Python | 数据连接、索引、检索及围绕私有数据构建 Agent；适合重点研究知识库和 RAG。 |
| [Pydantic AI](https://pydantic.dev/docs/ai/overview/) | Python | 类型约束、结构化输出、依赖注入、工具校验和评估；适合强调代码可维护性的团队。 |
| [CrewAI](https://docs.crewai.com/) | Python | 用角色、任务和流程组织多 Agent 协作；适合研究协作式业务自动化。 |
| [Microsoft Agent Framework](https://learn.microsoft.com/en-us/agent-framework/) | 以 .NET / Python 为主 | 企业 Agent、工作流、状态和恢复；微软将其定位为 Semantic Kernel 与 AutoGen 的后继框架。 |

微软框架的继承关系见[官方概览](https://learn.microsoft.com/en-us/agent-framework/overview/agent-framework-overview)。本项目已经接入的独立 `agentscope-platform` 与表中的 AgentScope Java 不能直接视为同一运行时；Java 版本是否适合迁入，需要另外比较依赖、契约和功能覆盖。

## 值得借鉴的技术方向

从上述官方能力可以归纳出一个共同方向：为 Agent 的持续执行提供完整支持。对本项目，值得关注的具体问题包括：

- **持久化与恢复**：进程重启、工具超时或用户离开后，任务能否继续；恢复时是否会重复产生副作用。
- **人工介入与工具权限**：任务能否在关键动作前暂停，经批准后从正确位置继续。
- **上下文管理**：长任务如何压缩历史、保留关键事实，并限制 Token 成本。
- **可观测与评估**：能否追踪执行步骤，解释失败原因，并以固定任务集发现退化。

这些是比较能力的维度，不代表本项目全部缺失。已有能力应先复用，缺口应由源码、测试或运行证据确认。

## 对本项目的建议

1. **保留现有 Java 服务中的 LangChain4j，先评估版本升级收益。** 重点核对模型接口、MCP、流式工具调用、结构化输出和向量库客户端兼容性，再选择升级目标。此处没有修改任何依赖。
2. **以当前 AgentScope 默认路径作为 Agent 比较基线。** 将旧 Java Agent 明确作为回滚和历史实现参考，避免误把重新接入已使用的 AgentScope 当成新演进方向。
3. **Java 学习与候选方案优先看 Spring AI、Spring AI Alibaba、AgentScope Java。** Spring AI 用于比较模型与 Spring 集成；后两者用于比较 Agent 执行和编排成本。LangGraph 可作为状态、持久化与恢复设计的参考。
4. **选一个真实业务场景做受控比较，再决定局部替换。** 尽量固定模型、数据、工具和预算，比较任务成功率、延迟、Token 成本、失败恢复、租户边界及维护代码量。先验证新增方案的实际收益，再决定是否承担迁移成本。

学习新框架可以与现有平台维护并行推进。现阶段优先积累可重复的评估证据，后续选型围绕明确的能力缺口展开。
