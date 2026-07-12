# 06 Solution D - Unified Capability Registry Across Services

## architecture-designer 视角

### 方案定位

引入统一能力注册契约：在 `platform-protocol` 定义 `CapabilityDescriptor`、`CapabilityEndpointDescriptor` 等 DTO，各业务服务暴露 `/capabilities` 或 `/internal/capabilities`，展示 UI 从注册中心聚合真实 live capabilities。该方案把“能力展示”做成平台治理能力。

### 架构

```
Browser SPA
  └─ capability-showcase-ui / registry BFF
       ├─ polls service capability endpoints
       ├─ merges config + live descriptors
       └─ renders modules / invokes APIs

Each service
  └─ GET /internal/capabilities
       └─ returns typed CapabilityDescriptor
```

### 模块职责

- `platform-protocol`：新增能力描述 DTO。
- 每个服务：新增 capabilities controller 或 service provider，声明本服务能力、端点、开关、scope、输入输出 schema、SSE/multipart 支持。
- Registry/BFF：聚合所有能力，标记健康、版本和启用状态。
- 前端：完全由 registry 驱动，减少静态硬编码。

### 核心流程

1. 服务启动时提供能力描述端点。
2. Registry 定期拉取或按需拉取各服务 capabilities。
3. UI 获取聚合目录。
4. 用户调用能力时，UI 根据 descriptor 生成表单、上传控件或 SSE 监听。

### 改动范围

- `platform-protocol` 新增 DTO。
- `conversation-service`、`knowledge-service`、`agent-service`、`analytics-service`、`workflow-service`、`async-task-service`、`channel-service`、`interop-service`、`eval-service`、`vision-service`、`voice-service` 均新增能力描述。
- `edge-gateway` 新增 registry/showcase 路由。
- 新增 UI/BFF 服务。
- 单元测试覆盖每个服务 descriptor 与 controller 映射一致性。

### 扩展性

- 长期最好：能力新增时服务自己声明，展示页自动更新。
- 可发展为插件化/治理平台，供 Interop/MCP/A2A 复用。
- 可支持能力版本、灰度状态、权限模型、示例请求、风险标记。

## risk-reviewer 视角

- 兼容性：触达所有服务与 `platform-protocol`，跨模块兼容风险最高。
- 事务：通常无新增业务事务，但若持久化 registry 状态则需新增表和迁移策略。
- 并发：registry 拉取全服务时要有超时、熔断、缓存，避免启动风暴。
- 幂等：能力描述接口只读，应可缓存；业务调用仍保持原接口语义。
- 性能：descriptor 较小，但聚合拉取需 TTL 与并发限制。
- 安全：capabilities 可能泄露未启用或内部能力，需区分 public/internal 字段和 scope。
- 数据迁移：若新增持久 registry 表，需迁移；MVP 可不落库。
- 灰度：可按服务逐个接入 descriptor；Registry 必须允许缺失服务回退静态配置。
- 回滚：因为改动广，回滚复杂；需保证新增 endpoint 不影响原有 controller。

### 失败场景

- descriptor 与真实 controller 不一致，造成“自动化假象”。
- 所有服务一次性改造导致长期分支和合并风险。
- capability schema 过度设计，拖慢前端原型验证。

### 实施成本

高。适合作为二期/三期治理方向，不适合作为首期能力展示页面的最短路径。
