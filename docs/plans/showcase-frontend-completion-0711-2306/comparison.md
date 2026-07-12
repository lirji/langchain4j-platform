# Comparison

## plan-judge 视角

评分说明：5 分最好。`复杂度`、`测试难度`、`回滚成本` 分数越高表示越有利，即复杂度更低、测试更容易、回滚更便宜。

| 方案 | 正确性 | 改动风险 | 复杂度 | 可维护性 | 扩展性 | 测试难度 | 回滚成本 | 总分 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A Manifest-first 通用运行器增强 | 4 | 4 | 5 | 3 | 3 | 4 | 5 | 28 |
| B Catalog + 领域专用工作台 | 5 | 3 | 3 | 4 | 4 | 3 | 4 | 30 |
| C 插件化能力渲染器 | 4 | 2 | 2 | 4 | 5 | 2 | 3 | 26 |

### 逐项评估

#### 方案 A

- 正确性：能直接解决“占位无交互”和“高级能力无表单”，但对 Workflow/Channel/Voice 的复杂交互表达较弱。
- 改动风险：主要集中在 manifest 和通用请求装配，风险相对可控。
- 复杂度：最低。
- 可维护性：`capabilities.yml` 会明显膨胀，复杂 JSON 参数靠文本框维护，长期一般。
- 扩展性：对 REST 端点扩展好，对领域状态流扩展一般。
- 测试难度：纯函数和组件测试为主，较容易。
- 回滚成本：删除新增 manifest 条目或回退少量通用装配即可。

#### 方案 B

- 正确性：最贴合任务目标，不仅补表单，还补前端能力体验。
- 改动风险：新增多个模块视图，风险高于 A，但共享请求层可控。
- 复杂度：中等偏高。
- 可维护性：领域边界清楚，复杂流程不挤压通用 runner。
- 扩展性：后续能自然演进为独立模块或 micro-frontend。
- 测试难度：需要模块级测试和交互回归，比 A 更重。
- 回滚成本：专用视图可从 `ModuleHost.SPECIALIZED` 移除，回退到 GenericModuleView。

#### 方案 C

- 正确性：理论上覆盖面强，但首期需要先搭插件架构，容易偏离“补齐能力”的主目标。
- 改动风险：拆分 `CapabilityRunner` 会影响现有稳定路径。
- 复杂度：最高。
- 可维护性：如果 runner registry 设计成熟会很好，但当前需求量不足以证明这套抽象必要。
- 扩展性：最高。
- 测试难度：需要覆盖 registry、多个 runner、旧 runner 兼容。
- 回滚成本：如果中途发现抽象不合适，回滚较麻烦。

### 风险复核

## risk-reviewer 视角

### 兼容性

- A：扩展 `RequestKind` 可能影响已有判断，例如 `cap.requestKind === 'sse'` 的按钮文案和 `run.isSse`。如果新增 `multipart-sse`，所有 switch/if 都要同步。
- B：新增专用视图不破坏已有模块，但 `ModuleHost` 注册错误会影响路由。
- C：重构 runner 影响面最大。

### 事务与数据一致性

- 前端不直接参与后端事务。
- Workflow `complete`、`claim`、`purge` 依赖后端事务和 scope，前端必须把 409/403 作为正常业务反馈展示。
- Channel callback/outbound 可能触发真实副作用，不能自动重试用户提交。

### 并发

- Workflow 双人领取/审批可能 409；前端应展示冲突并建议刷新任务列表。
- Async/Agent/Voice SSE 需要 abort，现有 `streamCapability` 已支持 abort。
- 多次点击需由 `run.running` 禁用按钮；专用视图也应复用这个规则。

### 幂等

- Workflow start 支持 `dedupeId`，前端应提供字段并解释其用途。
- Async create 重复 `taskId` 返回 409，已有 catalog 描述。
- Eval run/gate 不应前端自动重复提交。
- Channel messages 是否幂等取决于后端和外部渠道，前端不能假设幂等。

### 性能

- `capabilities.yml` 扩大对首屏影响很小。
- 大 JSON 响应展示可能卡顿，`ResponseViewer` 当前直接展示 JSON；若 Eval 大结果过大，可后续加折叠或摘要。
- SSE 长连接数量应由用户显式开启，离开视图时 abort。

### 安全

- API Key 继续只在内存保存。
- curl 预览必须继续用 `$API_KEY` 占位。
- Header 参数支持不能覆盖 `X-Api-Key` 注入路径；应禁止 catalog header 参数名为 `X-Api-Key` 或后写入时不允许覆盖 session API Key。
- Channel 和 Workflow destructive 必须默认锁定。
- `/channel/inbound` 的签名头是敏感模拟能力，若签名校验开启且用户不会生成签名，应清楚展示失败原因。

### 数据迁移

- 三个方案均不需要数据库迁移。
- 不修改消息结构。

### 灰度与回滚

- 最小灰度：先合入 manifest 中低风险 GET/POST 能力，再开放 destructive 和 channel outbound。
- B 方案可以按模块注册专用视图逐个启用；没有专用视图时仍回退 generic。
- C 方案灰度最难，因为 runner host 是全局路径。

### 评分结论

推荐采用 B，但吸收 A 的低风险 manifest-first 顺序，暂不采用 C 的插件化重构。

最终策略：

- 第一阶段按 A 补齐 catalog 与共享请求能力。
- 第二阶段只为确有必要的模块引入 B 的专用工作台。
- 不做 C 的 `uiKind` / runner registry，除非后续出现 3 个以上重复的特殊 runner。

### 所选方案弱点

- B 的实施周期长于 A。
- 专用视图容易依赖未核验响应字段，必须实施前继续读对应 service record 或用运行时防御式解析。
- Voice 的 stream、A2A 的 message/stream、Channel inbound header 是三类共享装配短板，必须先解决，否则专用视图只是外壳。
