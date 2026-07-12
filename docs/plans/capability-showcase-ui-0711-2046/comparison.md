# Comparison

## plan-judge 视角

评分：5 分最好，1 分最差。复杂度、测试难度、回滚成本按“分数越高越好”理解，即 5 = 简单/易测/易回滚。

| 方案 | 正确性 | 改动风险 | 复杂度 | 可维护性 | 扩展性 | 测试难度 | 回滚成本 | 总分 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A 静态 SPA + 静态 manifest | 3 | 5 | 5 | 2 | 2 | 4 | 5 | 26 |
| B 独立 Showcase BFF + SPA | 4 | 4 | 3 | 4 | 4 | 3 | 4 | 26 |
| C 网关内嵌 Console | 3 | 2 | 4 | 2 | 2 | 3 | 2 | 18 |
| D 全服务统一能力注册 | 5 | 1 | 1 | 5 | 5 | 2 | 1 | 20 |

## 评分说明

### A 静态 SPA

- 优点：几乎不碰后端，最快验证 UI 模块拆分。
- 弱点：能力目录手写，和 controller/config 漂移不可避免；如果不同源部署，会遇到 CORS 待验证问题。
- 适用：一次性 demo、PoC、内部截图。

### B 独立 BFF

- 优点：改动面独立，能同源托管、聚合部分 live discovery、屏蔽不同端点的响应差异，后续可演进成 registry。
- 弱点：多一跳代理，SSE 和 multipart 代理复杂；初期 catalog 仍有静态成分。
- 适用：本任务首期最佳平衡点。

### C 网关内嵌

- 优点：天然同源，部署最少服务。
- 弱点：污染 `edge-gateway` 职责；展示页迭代会提高网关发布频率；静态资源开放规则可能要改 `ApiKeyToInternalTokenFilter.isOpen(...)`。
- 适用：非常短期的本地演示，不适合长期。

### D 统一能力注册

- 优点：长期正确性和扩展性最高；可以成为平台治理能力。
- 弱点：触达所有服务和 `platform-protocol`，首期成本和风险过高；容易为了展示页过度设计。
- 适用：二期治理，不适合作为第一步。

## 组合结论

不机械选择单一方案。最终建议采用：

- 首期采用 Solution B 的独立 Showcase BFF + SPA。
- 吸收 Solution A 的静态 manifest 作为 BFF 初始 catalog 数据源，避免一次性修改所有业务服务。
- 吸收 Solution D 的 descriptor 思路，但只在 BFF 内部定义 catalog schema；后续再迁移到 `platform-protocol`。
- 不采用 Solution C 的网关内嵌 UI，但需要在 `edge-gateway` 增加一条 `/showcase/**` 路由。

## 对最终选择的反偏差检查

- 不能因为 B 看起来折中就忽略复杂点：SSE/multipart 代理和 API Key 脱敏必须单独验收。
- 不能假设所有能力都可 live discovery：当前代码只有 `AgentCapabilitiesController`、`ChannelController.capabilities()`、`EvalController.capabilities()`、`InteropController.tools()` 等局部能力发现。
- 不能为了“能力展示”新增数据库：MVP 不持久化用户运行历史，避免数据迁移与隐私风险。
- 不能把危险端点默认做成可点击按钮：`WorkflowController.purge(...)` 等删除能力应只展示，不默认开放执行。
