# 全项目集中测试计划

## 目标

以 `langchain4j-platform` 为总入口，验证 Java 数据面与安全边界、Capability Showcase
前端、同级 `agentscope-platform` 推理编排层，以及 Compose/Helm 交付制品。

## 执行范围

1. Java 23 模块默认测试、Spring Cloud Contract profile 和可执行 JAR 打包。
2. Flowable/H2 与 Embedded Kafka 专用集成 profile。
3. Capability Showcase 前端依赖安装、Vitest、Vue/TypeScript 类型检查和生产构建。
4. AgentScope Python 依赖同步、ruff、mypy、契约导出一致性和 pytest 全量回归。
5. 全部 Compose 组合、Helm 默认/Knowledge split/legacy+eval 渲染和 shell 脚本静态检查。
6. 若本机运行环境可用，则从 edge-gateway 做 localhost HTTP/UI 黑盒回归。
7. 2026-07-30 用户追加授权真实付费模型，故 Chrome 黑盒阶段允许调用文本、embedding、
   rerank 与视觉模型；仍禁止真实渠道出站和破坏性删除。

## 通过标准

- 编译、静态检查、单元测试、契约测试、嵌入式集成测试和构建均为零失败。
- Compose/Helm 可解析，AgentScope 默认路由、Knowledge split 和回滚制品均可渲染。
- 需要 Docker、Casdoor 或真实模型的项目若环境不可用，必须明确标为阻塞，不以静态检查冒充
  端到端通过。

## 安全边界

- 应用入口只访问 localhost；模型请求由本地 LiteLLM 按已批准配置转发。
- 付费模型调用已获用户明确授权。
- 不启动或修改生产资源，不重置两仓既有未提交改动。
