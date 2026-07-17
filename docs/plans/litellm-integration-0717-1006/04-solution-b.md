# 04 — 候选方案 B：每租户固定 Delegate 池

## 1. 核心思想

仍提供 `TenantAwareChatModel` 三档开关，但在 `virtual-key` 模式下不动态覆盖 header，而是按 tenant 懒创建一个 apiKey 固定的 `OpenAiChatModel`/`OpenAiStreamingChatModel` delegate，并缓存于并发 Map。`user` 模式使用共享 delegate 并改请求参数；`none` 使用当前 delegate。

## 2. 架构与模块职责

```text
TenantAwareChatModel
  NONE -> shared master-key delegate
  USER -> shared master-key delegate + user=tenant
  VIRTUAL_KEY
    -> ConcurrentMap<TenantModelKey, ChatModel>.computeIfAbsent
       key = tenant + logicalModel + temperature + streaming
       value = OpenAi model(apiKey = tenant virtual key)
    -> delegate.chat(user=tenant)
```

部署面的 PostgreSQL、Redis cache、fallback、OTel callback 与方案 A 一致。区别全部集中在 gateway-client 的 virtual-key 运行时模型：方案 A 是单 delegate + 动态 header；方案 B 是多 delegate + 固定 header。

## 3. 核心流程

1. wrapper 每请求读取 tenant。
2. `none` 原样走当前共享模型。
3. `user` 覆盖 OpenAI `user` 后走共享模型。
4. `virtual-key` 解析 tenant key，构造 `DelegateCacheKey(tenantId, modelName, temperature, streaming)`。
5. `computeIfAbsent` 用 key 构造 OpenAI model；后续复用连接池和 listeners。
6. virtual key 轮换时 resolver 版本号必须进入 cache key，或显式失效旧 delegate。

## 4. 改动范围

除方案 A 的文件外，需要更重的生命周期组件，例如：

- `TenantChatModelRegistry`：同步/流式 delegate cache。
- `VirtualKeyVersion` 或 resolver revision：解决轮换失效。
- cache size/expiry 配置和指标。
- 测试需验证并发只构造一次、旧 key 失效、模型温度组合不串用。

## 5. 并发、事务与幂等

- `ConcurrentHashMap.computeIfAbsent` 保证同一 JVM 同组合只生成一个 delegate，但构造失败的重试、递归和慢 resolver 需要谨慎。
- 多 pod 各自建 delegate，没有跨 pod 事务；这是连接对象本地缓存，合理。
- key 轮换不是原子过程：LiteLLM grace period、Secret 更新、registry 失效要按顺序执行。
- 高基数模型/温度/租户组合会产生大量 HTTP clients 与连接池；需要最大容量和淘汰策略。

## 6. 扩展性

- 每租户可进一步定制 timeout、model allowlist 或代理网络。
- 固定 apiKey 不依赖 custom header 覆盖语义，LangChain4j 升级风险较小。
- 适合租户数量小且属性差异大的 B2B 部署。

## 7. 实施成本与弱点

实施成本：中高。

弱点：

- 当前 `GatewayChatModelFactory` 被 judge、cascade 多次构造；再乘 tenant 数会显著放大对象和连接池数量。
- streaming 与 sync 是不同 client，资源翻倍。
- key 轮换需 cache invalidation；如果忘记，旧 key 会持续使用到进程重启。
- registry 的上限、淘汰和正在进行的流式请求使关闭资源复杂；LangChain4j model 未提供本仓库正在使用的统一 close 生命周期。
- 对当前需求而言，多对象换来的收益有限。

## 8. 适用结论

它规避动态 Authorization 覆盖的技术依赖，但用运行时资源和轮换复杂性换取。若固定版本 LangChain4j 的 header supplier 实测不可靠，可作为回退实现；不宜作为默认首选。
