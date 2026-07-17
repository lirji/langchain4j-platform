# 测试方案与验收标准（test-designer）

## 1. 测试策略

测试分五层：纯单元 → Spring 装配契约 → LiteLLM mock 集成 → Compose 冒烟 → 灰度回归。任何需要 DeepSeek/OpenAI 真实 key 的测试都不是必过门禁；核心能力必须用现有 mock-a/mock-b、PostgreSQL、Redis、Jaeger在本地确定性完成。

## 2. 单元测试

### 2.1 `TenantAwareChatModelTest`

| 用例 | 操作 | 明确断言 |
| --- | --- | --- |
| none 原样 | 构造含 messages/tools/response format/temperature 的请求 | delegate 收到同一语义参数；`user` 不新增；响应原样返回 |
| user 注入 | identity=acme，模式 USER | delegate 请求的 `OpenAiChatRequestParameters.user()` 为 `acme` |
| user 防伪 | 原请求 `user=globex`，identity=acme | 最终必须为 acme |
| 参数保留 | 原请求含 model、temperature、tools、custom params | 除 user 外全保留，不丢 tool/JSON schema |
| virtual-key 同时 user | 模式 VIRTUAL_KEY | 请求 `user=acme`，Authorization 在 header supplier 测试验证 |
| anonymous | provider 返回 anonymous | USER 写 anonymous；VIRTUAL_KEY 由 resolver 规则决定并缺失拒绝 |
| listener 一次 | delegate 带计量 listener | 一次 wrapper 调用 listener request/response 各恰好一次 |
| 错误透传 | delegate 抛异常 | 类型/原因不吞；listener error 一次 |
| options 保留 | 调 `chat(request, ChatRequestOptions)` | listener attributes/选项传给 delegate |
| capability 代理 | 查询 provider/default params/supported capabilities | 与 delegate 一致，避免 AiServices 特性退化 |

### 2.2 `TenantAwareStreamingChatModelTest`

- USER 与 VIRTUAL_KEY 注入 `user=tenantId`。
- token、complete、error handler 顺序原样透传。
- 两个并发流使用不同 tenant 时不串身份。
- 流开始后 caller ThreadLocal 清理，不应影响已在发送前解析出的 header；具体发送时点通过 fake delegate 控制。
- listeners 只运行一次。

### 2.3 `GatewayRequestHeadersSupplierTest`

| 场景 | 断言 |
| --- | --- |
| NONE/USER | 不返回 Authorization 覆盖，底层静态 key 保持 |
| VIRTUAL_KEY 命中 | `Authorization=Bearer <tenant key>` |
| VIRTUAL_KEY 缺失 | 抛 sanitized 异常；消息含 tenant，可排障，但不含 master/其他 key |
| resolver 异常 | fail-closed，不返回空 Map |
| trace 开启 | 使用 fake `Propagator` 注入 `traceparent`/`tracestate` |
| trace 关闭/无 context | 不生成伪 trace header |
| 合并 | VIRTUAL_KEY + trace 同时存在，三个 header 均保留 |
| 并发 1000 次 | acme/globex 交错调用，无一次 key 串租户 |

### 2.4 配置与 resolver 测试

- `TenantAttributionMode` 接受 `none`、`user`、`virtual-key`；非法值启动失败并列出合法值。
- 默认值严格为 NONE。
- `EnvironmentTenantVirtualKeyResolver` exact tenant 命中；缺值 empty；空白值视为缺失。
- tenant 含点、连字符、冒号的属性解析规则用实际 Spring `MockEnvironment` 固化；不能解析的形式在文档列为限制。
- secret 不出现在 `toString`、INFO/DEBUG日志。

## 3. Spring 装配测试

使用 `ApplicationContextRunner`：

1. 无 `platform-security` 运行时 + 默认 none：context 成功，只有一个 ChatModel 和最多一个 StreamingChatModel。
2. security 存在 + none：adapter 可存在但请求不改。
3. security 存在 + user：使用 TenantContext provider。
4. security 缺失 + user：使用 anonymous identity并只记录一次启动告警；security缺失 + virtual-key：只有显式存在anonymous virtual key才可调用，否则请求前fail-closed，绝不静默用master key。
5. 用户自定义 `TenantIdentityProvider`/`TenantVirtualKeyResolver` bean：`@ConditionalOnMissingBean` 退让。
6. streaming.enabled=false：不创建 StreamingChatModel。
7. 多个 ChatModelListener：底层工厂全部挂载，wrapper不重复。
8. `eval-service` 依赖树验证：optional security 不成为传递依赖；eval 默认启动测试通过。

## 4. 工厂与跨模块回归

### 4.1 工厂构造矩阵

逐一验证：

- `build()`
- `buildDeterministic()`（temperature=0）
- `build(modelName, temperature)`
- `buildStreaming()`
- `buildStreaming(modelName, temperature)`
- `CascadeChatModelFactory.buildRater()`
- `CascadeChatModelFactory.build()` 的 cheap/strong

每条都检查 wrapper 存在、model/temperature/listener 保留、tenant user正确。

### 4.2 Maven 回归

最小门禁：

```bash
mvn -pl platform-gateway-client test
mvn -pl platform-gateway-client -am test
mvn -pl conversation-service,agent-service,analytics-service,workflow-service,eval-service,vision-service -am test
```

最终门禁：`mvn test`。若全仓耗时过长，仍必须记录上述目标模块结果与未跑原因，不能只跑新测试。

## 5. LiteLLM 配置静态/启动测试

1. `docker compose -f deploy/docker-compose.yml config` 成功。
2. test-only compose config 成功，服务名/卷不和主栈冲突。
3. 固定 LiteLLM 镜像加载主 `config.yaml`，日志无未知字段或 fallback 自环。
4. `/health/liveliness`、`/health/readiness` 成功。
5. `/cache/ping` 显示 Redis healthy、namespace/DB符合预期。
6. `/ui` 返回登录页，错误凭据不可进入。
7. 配置或环境缺 DB 密码、UI 密码、master key 时按预期 fail-fast；本地默认与生产 Secret 文档区分。

## 6. PostgreSQL 与数据迁移测试

### 6.1 首次创建

- 从 test-only 空卷启动，readiness 成功。
- 只通过 `information_schema` 断言 public schema存在 LiteLLM 创建对象，不在脚本硬编码第三方表名。
- 用管理 API创建测试 virtual key；调用 mock 模型后查询官方 spend API/UI，spend 增加。

### 6.2 持久化与重启

- 记录 key ID/spend，`docker compose restart litellm`，断言仍存在。
- 完整停止再启动但保留 test PG volume，数据仍在。
- 测试脚本只在自己的 Compose project 上 `down -v`；不得指向主栈卷。

### 6.3 升级/回滚演练

- N 版本快照 → 升 N+1 固定镜像 → readiness、UI、key、spend 检查。
- 回滚前先恢复 N 快照，不假设 schema 可向后兼容。
- 从 DB-enabled 配置回到旧无 DB 配置时，LLM 转发可恢复；PG卷保留待调查。

## 7. 归因、预算与限流集成测试

用 mock upstream 记录请求 body/header；脚本生成而不是提交 virtual key。

### 7.1 user 模式

- acme/globex 各请求一次，mock 收到对应 user。
- 同 prompt、不同 tenant 时 cache key/命中归因不串；若固定镜像不把 user纳入 cache key，测试必须失败并触发 tenant namespace修正。
- 设置极低 `max_end_user_budget`，重复请求直到结构化预算错误；另一 tenant 仍可调用。

### 7.2 virtual-key 模式

- 通过 `/key/generate` 为 acme/globex 创建不同 key与不同 RPM/TPM/budget。
- 两租户请求分别累计到对应 key/user/team。
- 达到 acme 限额后 acme被拒，globex不受影响。
- 缺 key在 Java/mock client 侧失败，mock upstream请求计数不增加。
- key只允许 `chat-default` 时请求未允许模型被拒。

### 7.3 并发边界

- 用并发请求触发 RPM/TPM/max_parallel_requests，统计成功/拒绝数与可接受超额。
- 记录预算检查的并发超额上界；不得把最终一致检查写成“绝不多花一 token”。
- Redis/PG连接池耗尽测试应返回有界错误，不无限挂起。

## 8. 缓存测试

1. 清 test namespace。
2. 同 tenant/相同模型/messages/temperature请求两次；mock provider计数只加一，第二次响应有可识别 cache header。
3. TTL 到期后第三次 provider计数加一。
4. `no-cache`/`no-store`（若 gateway-client本轮暴露）按官方语义验证；未暴露则列后续，不伪造覆盖。
5. 工具调用、流式响应、错误响应分别验证是否缓存；固定镜像不支持的类型记录并从 acceptance排除。
6. Redis停止：验证 LiteLLM 是绕过缓存继续 provider还是失败；按最终决定冻结行为。资金硬保底不能因 cache失败绕过 PG预算。
7. 检查应用 `TokenBudgetEndpoint` 与 LiteLLM spend：缓存命中产生的预期差异被记录，不把它当单测失败。

## 9. Failover 测试

复用并扩展 `deploy/smoke-failover.sh`：

1. mock-a/mock-b在线，`chat-default` 返回 `reply-from-a`。
2. 停 mock-a，下一请求在总时限内返回 `reply-from-b`。
3. 停 mock-b，调用在总时限内返回非 2xx/结构化错误。
4. 确认没有 `chat-default -> chat-default` 循环。
5. 在主失败、备成功时查询 LiteLLM spend/log，记录对失败尝试和最终成功的实际口径。
6. 恢复 mock-a，cooldown后主路由恢复（具体等待由固定镜像配置决定）。
7. 正式 `config.yaml` 静态断言存在 `chat-default-fallback` 且 fallback目标正确；真实 Ollama只做可选验收。
8. virtual key只允许主逻辑模型和同时允许主/备两种配置都要测试，冻结固定LiteLLM版本的fallback授权语义；正式provisioning按实测结果配置。
9. 对备用Ollama跑普通对话、工具调用、JSON schema、流式和接近上下文上限的代表性请求；明确哪些能力允许降级，不能以`ping`代替兼容验收。

总延迟验收：值需由实施时确定，但必须小于 Java timeout；推荐先以 45 秒为上界试验，避免 LiteLLM 60 秒 × Java重试叠加。最终数值标记“待压测确定”。

## 10. OTel 测试

### 10.1 LiteLLM 单独

- test Jaeger启动后发送 mock chat。
- 轮询 Jaeger query API，找到 `service.name=litellm-proxy` 的 server/provider span。
- span 含 model/operation/error或token属性，不含明文 Authorization/prompt（默认内容采集关闭）。

### 10.2 Java→LiteLLM 同 trace

- Java测试应用开启 `MANAGEMENT_TRACING_ENABLED=true`、采样 1.0、同 Jaeger endpoint。
- 请求经过 `TenantAwareChatModel` 到 LiteLLM。
- Jaeger中 Java service span与 `litellm-proxy` span trace ID相同。
- 另从agent/workflow已存在的异步执行器触发LLM调用，记录Micrometer context是否随现有TaskDecorator传播；若没有，验收结果必须明确是LiteLLM新root，不能用MDC `traceId`相同冒充OTel同trace。
- 不把“LiteLLM必须是 `chat <model>` 直接子 span”作为本轮通过条件；若实际只是同 trace 下兄弟关系，记录已知弱点。
- tracing=false 时没有动态 trace header，现有 no-op listener测试继续通过。

## 11. 故障注入矩阵

| 故障 | 预期 |
| --- | --- |
| PostgreSQL停机 | readiness/管理/硬预算不能静默假装正常；具体启动/运行错误冻结到版本契约 |
| Redis停机 | cache/auth cache降级行为可解释；不能串租户或绕过 DB hard guard |
| Jaeger停机 | OTel导出丢失/重试但 chat主链路不被长期阻塞 |
| 主 provider 429/500/timeout | 有界 retry后 fallback |
| 主备都失败 | 有界错误，不缓存错误，不记伪成功 |
| virtual key撤销 | 新请求被拒；没有 master fallback |
| resolver返回空白 | 当缺失处理 |
| TenantContext异步丢失 | virtual-key拒绝并暴露指标/日志，user归 anonymous |
| cache数据损坏 | miss或有界错误；不能反序列化成他租户响应 |
| PG卷升级失败 | 停止发布，恢复快照+旧镜像 |

## 12. 最终验收清单

- [ ] 单元/装配/目标模块/全仓测试完成并记录。
- [ ] 主/测试 Compose与固定 LiteLLM版本配置加载成功。
- [ ] UI、spend、key持久化、重启成立。
- [ ] none/user/virtual-key同步与流式全部验收。
- [ ] budget/RPM/TPM硬拒绝与租户隔离成立。
- [ ] cache hit、TTL、跨租户、Redis故障成立。
- [ ] 主备成功/双失败/延迟上界成立。
- [ ] LiteLLM OTel与Java同 trace成立，敏感内容不采集。
- [ ] PostgreSQL备份恢复和配置回滚演练完成。
- [ ] 双轨差异有基线、告警阈值和解释，而非追求伪强一致。
