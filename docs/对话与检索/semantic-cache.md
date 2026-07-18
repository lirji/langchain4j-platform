# L1 语义响应缓存（Semantic Response Cache）

`conversation-service`（`:8081`）的**应用侧、问题级、租户桶、pre-RAG** 缓存，位于 `/chat` 的
**RAG + LLM 之前**。用向量相似度把「意思等价但字面不同」的重复提问归并，命中即返回历史答案、
**0 LLM token**，直接砍掉重复问答的检索与生成成本。

**默认开启**（`CONVERSATION_SEMANTIC_CACHE_ENABLED=true`）；置 `false` 关闭时 `/chat` 行为与无缓存完全一致，零回归。
所有 curl 走边缘网关 `http://localhost:8080` + `X-Api-Key`；`conversation-service` 自身 `:8081` 仅供直连调试。

代码：`conversation-service` 的 `com.lrj.platform.conversation.cache.*`（`SemanticCache` 编排器 +
`SemanticCacheStore` 存储 + `SemanticCacheEmbedder` 向量化）。失效端点 `DELETE /chat/cache` 在
`ConversationController`。

---

## 1. 解决什么问题：语义缓存 ≠ 逐字缓存

对话流量里存在大量**语义等价、字面不同**的重复提问：

- 「怎么退款」/「退款流程是啥」/「我要退货怎么弄」
- 「你们几点上班」/「营业时间」/「什么时候营业」

逐字缓存（精确 key match）在这种场景命中率极低。**语义缓存**用 query 向量的余弦相似度做近似匹配：
只要新问题与某条历史问题相似度 `>= threshold`（默认 `0.95`），就判命中、直接复用历史答案，
不再触发检索 / 生成。

一次 miss 的额外成本只是**一次 embedding 调用**（远比一次 chat completion 便宜）；命中率越高越划算。

> 命中对调用方**完全透明**：`/chat` 命中与未命中返回体结构一致（都是
> `{"reply","chatId","tenantId","userId"}`），**没有** `cached` 标志位。命中只体现在延迟骤降与
> `SemanticCache` 的 `debug` 日志（`semantic cache hit tenant=... score=...`）。

## 2. 与其它对话能力的关系

| 维度 | 关系 |
| --- | --- |
| 多轮记忆（ChatMemory，`<tenantId>::<chatId>`） | **正交**。缓存是跨会话 / 跨用户的「同租户问答复用」，**命中不落记忆**、也不参与上下文拼接 |
| RAG 增强（`CONVERSATION_RAG_ENABLED`） | **前置短路**。缓存在 RAG 检索之前；命中即不再检索/组装/生成，未命中才跑 RAG |
| Grounding 事后校验（`CONVERSATION_GROUNDING_ENABLED`） | **正交**。命中即短路，不再校验；未命中时回填的是**校验后**的答案 |
| PII 输出脱敏（`CONVERSATION_GUARDRAIL_PII_ENABLED`） | 回填发生在脱敏**之后** —— 缓存里存的是已脱敏答案，命中复用不会泄露原始 PII |
| token 预算 / 成本归因 | **互补**。缓存把重复问答的 token 成本降到 0，减轻预算压力 |
| History-aware 检索压缩 | 缓存键用**原始问题**（pre-压缩、pre-RAG）；压缩后的 query 只用于未命中路径的检索 |

## 3. 工作原理

`/chat` 通过 `SemanticCache.getOrCompute(question, supplier)` 接入，`supplier` 里裹着 RAG 检索、
LLM 生成、grounding 校验、PII 脱敏的整条链：

1. **lookup**：`embed(原始问题)` → 在**当前租户桶**里逐条算余弦 → 取最高分。`>= threshold` 即命中，
   直接返回缓存回复、短路 `supplier`（省下 embedding 之外的一切）。
2. **put**：未命中则执行 `supplier`，拿到（校验 + 脱敏后的）答案后回填 `(原始问题, query 向量, 答案)`。
   空白问题或空答案不回填。

关闭（默认）或问题为空时，`getOrCompute` 直接执行 `supplier`，等价于没有缓存这一层。

### 3.1 租户隔离（per-tenant bucketing）

按 `TenantContext.current().tenantId()` 分桶，一个租户一份独立缓存 —— A 租户的答案**绝不会**命中到
B 租户的提问。内存实现是每租户一个 `Map`；Redis 实现是每租户一个 Hash key `conv:semcache:<tenantId>`，
tenantId 直接进 key，天然隔离。租户身份由内部 JWT 还原进 `TenantContext`，跨网络跳传播，缓存无需额外传参。

### 3.2 存储后端：默认 Redis，单机回落内存

`SemanticCacheStore` 遵循平台「接口 + `@ConditionalOnProperty` 多实现」惯例，二者按属性互斥、恒有其一：

| store | 类 | 特性 |
| --- | --- | --- |
| `redis`（**默认**） | `RedisSemanticCacheStore` | 每租户一个 Hash（field = `sha256(原始问题)`，value = 条目 JSON）。多副本共享、重启不丢；相似度检索用 `HVALS` 拉回本租户全部条目后在应用侧算余弦（无 RediSearch 依赖） |
| `in-memory` | `InMemorySemanticCacheStore` | 进程内 `Map`，重启即丢。单实例 / 本地开发 / 单测够用，零外部依赖 |

> **默认值即 redis**：`application.yml` 里 `store: ${CONVERSATION_SEMANTIC_CACHE_STORE:redis}`。
> 内存实现的 `@ConditionalOnProperty(havingValue="in-memory", matchIfMissing=true)` 只是「属性完全缺省」时的兜底，
> 而 yml 已把属性解析成 `redis`，所以**装配出来的是 Redis 实现**。
>
> **启动不需要 Redis**：store bean 构造期只打日志、不建连接。缓存关闭时（`CONVERSATION_SEMANTIC_CACHE_ENABLED=false`）`getOrCompute` 全程短路，
> 永不触达 store —— 因此即便 `store=redis`，**关着缓存也无需 Redis 在线**。
> 但**一旦开启缓存且用默认 `redis`，就需要一个可达的 Redis**（第一次 miss 的回填会连 Redis）。
> 单机 / 没有 Redis 时把 `CONVERSATION_SEMANTIC_CACHE_STORE=in-memory` 即可。

### 3.3 embedding provider：与 RAG embedder 相互独立

缓存用哪个向量模型，**与 knowledge-service 的 RAG embedding provider 完全无关**，各自配置、互不影响 ——
缓存跑在 conversation-service 内、RAG embedder 在 knowledge-service 内，是两套东西。`SemanticCacheEmbedder`
同样是「接口 + 条件多实现」：

| provider | 类 | 说明 |
| --- | --- | --- |
| `hash`（**默认**） | `HashSemanticCacheEmbedder` | 确定性 hash：token → sha256 散列到 **64 维**桶、累加 ±1 后归一化。纯本地、无外部依赖、结果确定，dev/test 零依赖即可跑通旁路逻辑 |
| `openai` | `GatewaySemanticCacheEmbedder` | 经 LiteLLM / OpenAI-compatible embedding 端点。默认复用 `platform.gateway.*` 的 base-url/api-key，也可用 `app.conversation.semantic-cache.embedding.*` 单独覆盖 model-name/dimensions/timeout 等 |

> **hash 是词袋、不是真语义。** 64 维 hash 捕捉的是**词面重合**，对同义改写（「几点上班」vs「营业时间」）
> 区分力有限；配合高阈值 `0.95` 实际近似**近精确去重**。想吃到真正的语义归并（同义、口语化改写命中），
> 需要 `CONVERSATION_SEMANTIC_CACHE_EMBEDDING_PROVIDER=openai` 接一个真的 embedding 模型。
>
> **换 embedder 会让旧向量失效。** 余弦在两个向量**维度不一致时返回 0**（视为不相似）。hash 是 64 维、
> 真模型通常上千维，切换 provider/模型后**已缓存的旧向量一律打 0 分 → 全 miss**。切换时建议先
> `DELETE /chat/cache` 清桶，避免陈旧条目白占容量。

### 3.4 容量与 TTL 淘汰

- **内存（容量上限）**：每租户桶容量上限 `max-entries-per-tenant`（**每租户**，非全局，默认 `1000`）。
  底层 `LinkedHashMap` + `removeEldestEntry`：超容量时丢**最旧插入**的一条。**内存实现无 TTL**，只靠容量淘汰。
- **Redis（整桶 TTL）**：可选 `redis.ttl` 给**整个租户桶**设过期（默认 `0s` = 不过期）。答案会随知识库 /
  时间漂移，给桶一个自然新鲜度上限；`0s` 时只靠「知识库更新触发失效」（见 §6）与写覆盖来保鲜。
  注意 Redis 档不按 `max-entries-per-tenant` 裁桶，容量控制交给 TTL 与显式失效。

### 3.5 韧性

余弦对**零向量 / 维度不一致**返回 0（判为不相似），从设计上避免误命中。embedding 后端故障时会向上抛
（未命中路径），不会把错误的命中塞给用户。缓存关闭时整条链短路，绝不因缓存层拖垮 `/chat` 主链。

## 4. 配置

`application.yml`（`app.conversation.semantic-cache.*`，实际由下表环境变量驱动）：

```yaml
app:
  conversation:
    semantic-cache:
      enabled: true             # 总开关，默认开
      threshold: 0.95           # 余弦命中阈值 [-1,1]，越高越保守（越不易误命中）
      max-entries-per-tenant: 1000   # 每租户桶上限（仅 in-memory 生效），超出丢最旧
      store: redis              # redis（默认）| in-memory
      embedding:
        provider: hash          # hash（默认，确定性 64 维）| openai（经 LiteLLM）
        model-name: embedding-default   # provider=openai 时的逻辑模型名
      redis:
        ttl: 0s                 # 仅 redis 生效，整桶 TTL；0s = 不过期
```

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `CONVERSATION_SEMANTIC_CACHE_ENABLED` | `true` | 总开关（默认开）。置 `false` 时缓存链全程短路，`/chat` 行为与历史完全一致 |
| `CONVERSATION_SEMANTIC_CACHE_THRESHOLD` | `0.95` | 余弦命中阈值。语义缓存最怕「意思其实不同却误命中返回错答案」，故默认偏保守，宁可 miss |
| `CONVERSATION_SEMANTIC_CACHE_MAX_ENTRIES` | `1000` | 每租户桶条数上限（**仅 in-memory 生效**），超出丢最旧插入的一条 |
| `CONVERSATION_SEMANTIC_CACHE_STORE` | `redis` | `redis`（默认，多副本共享 / 重启不丢，开启需 Redis 在线）\| `in-memory`（进程内，单机 / dev） |
| `CONVERSATION_SEMANTIC_CACHE_EMBEDDING_PROVIDER` | `hash` | `hash`（默认，确定性 64 维，本地零依赖）\| `openai`（经 LiteLLM 接真 embedding 模型） |
| `CONVERSATION_SEMANTIC_CACHE_EMBEDDING_MODEL` | `embedding-default` | `provider=openai` 时的逻辑模型名（LiteLLM `model_list` 里的名字） |
| `CONVERSATION_SEMANTIC_CACHE_REDIS_TTL` | `0s` | **仅 redis 生效**：整桶过期时长（`Duration`，如 `30m`/`2h`/`1d`）；`0s` = 不过期 |

## 5. 怎么跑

```bash
# 开启缓存 + 单机内存档（无 Redis）；想上 Redis 则去掉这行并确保 Redis 可达
export CONVERSATION_SEMANTIC_CACHE_ENABLED=true
export CONVERSATION_SEMANTIC_CACHE_STORE=in-memory
mvn -pl conversation-service -am spring-boot:run
# 或整套栈：docker compose -f deploy/docker-compose.yml up --build
```

```bash
# 第一次 = miss，跑 RAG+LLM
curl -s -X POST 'http://localhost:8080/chat?chatId=u1' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"message":"怎么申请退款"}'
# → {"reply":"...","chatId":"u1","tenantId":"acme","userId":"alice"}

# 语义等价的第二次 = hit（同租户即可，换 chatId/用户不影响命中）：返回体结构相同，但 0 LLM token、延迟骤降
curl -s -X POST 'http://localhost:8080/chat?chatId=u2' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"message":"退款流程是什么"}'
```

> 提示：默认 `hash` embedder 只认词面。上面两句共享「退款」，配合默认阈值可能命中，也可能不命中 —— 想稳定吃到
> 同义改写的命中，请把 provider 切到 `openai` 接真 embedding 模型。

## 6. 失效：显式端点 + 知识库联动

答案会随知识库更新而过期。缓存提供两种失效，都**只作用于调用方所在租户**（租户取自内部 JWT，清不了别人的桶）：

**端点 `DELETE /chat/cache`（conversation-service）**

```bash
# 清空整租户桶（该租户知识库整体更新后）
curl -s -X DELETE 'http://localhost:8080/chat/cache' -H 'X-Api-Key: dev-key-acme'
# → {"tenantId":"acme","scope":"tenant","cleared":<清除条数>}

# 定向失效单个原始问题（?question= 传原文，按精确原始问题匹配）
curl -s -X DELETE 'http://localhost:8080/chat/cache?question=怎么申请退款' -H 'X-Api-Key: dev-key-acme'
# → {"tenantId":"acme","scope":"question","removed":true|false}
```

缓存关闭时该端点为 no-op（`cleared:0` / `removed:false`）。

**知识库变更自动联动（松耦合，默认开）**

knowledge-service 在文档 upload / delete 成功后，可**尽力而为地**回调 conversation 的 `DELETE /chat/cache`
失效当前租户缓存，做到「知识库更新即答案新鲜」。默认开启；置 `RAG_CACHE_INVALIDATION_ENABLED=false` 则用 Noop 实现、对 ingest 零影响。要真正生效需
knowledge 与 conversation 都已就绪且 conversation 已开缓存才有意义。相关开关在 knowledge-service：

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `RAG_CACHE_INVALIDATION_ENABLED` | `true` | 开启后 upload/delete 成功即回调 conversation 失效当前租户语义缓存 |
| `RAG_CACHE_INVALIDATION_CONVERSATION_URL` | `http://conversation-service:8081` | conversation 服务地址（服务间直连，best-effort，失败仅告警不阻断 ingest） |

详见 [`rag-guide.md`](rag-guide.md)（§9 L1 语义缓存 / 文档生命周期）。

## 7. 装配

- `SemanticCache` —— `@Component` 编排器，注入 `SemanticCacheEmbedder` + `SemanticCacheStore`，
  读 `enabled`/`threshold`。**始终装配**，`enabled=false` 时 `getOrCompute` 直接跑原流程。
- `SemanticCacheStore` —— `RedisSemanticCacheStore`（`store=redis`，默认）/ `InMemorySemanticCacheStore`
  （`store=in-memory` 或缺省），按 `@ConditionalOnProperty` 互斥。
- `SemanticCacheEmbedder` —— `HashSemanticCacheEmbedder`（`provider=hash` 或缺省，默认）/
  `GatewaySemanticCacheEmbedder`（`provider=openai`），同样条件互斥。
- `ConversationController` 直接依赖 `SemanticCache`（非软依赖）：因为 bean 恒在，关掉只是短路，无需 `ObjectProvider`。

## 8. 注意 / 取舍

- **只挂 `/chat`（非流式）**：`/chat/stream` 的流式命中需要重新分块推送，收益边际，暂不接。
- **memory-agnostic / 跨用户复用**：两个用户问同一问题会拿到同一条缓存答案。若答案需 per-user 个性化，
  权衡后再开。缓存键是原始问题字符串（Redis 档 field = `sha256(问题)`），同租户内跨 chatId/用户共享。
- **hash 默认 ≈ 近精确去重**：真语义归并需 `provider=openai`（见 §3.3），换 provider 记得清桶。
- **PII / 合规删除**：`DELETE /chat/cache` 可挂到既有的租户数据清除路径，按租户或按问题清。
- **Redis 档无条数上限**：`max-entries-per-tenant` 只对 in-memory 生效；Redis 档靠 `redis.ttl` +
  知识库联动失效 + 写覆盖控制体积，长期运行建议给 `CONVERSATION_SEMANTIC_CACHE_REDIS_TTL` 设一个上限。
- **无专用命中率指标**：当前命中/未命中仅经 `SemanticCache` 的 `debug` 日志可见，未接 Micrometer counter。

## 9. 开关速查

| 环境变量 | 默认 | 作用域 |
| --- | --- | --- |
| `CONVERSATION_SEMANTIC_CACHE_ENABLED` | `true` | 总开关（conversation `/chat`） |
| `CONVERSATION_SEMANTIC_CACHE_THRESHOLD` | `0.95` | 余弦命中阈值 |
| `CONVERSATION_SEMANTIC_CACHE_MAX_ENTRIES` | `1000` | 每租户桶上限（仅 in-memory） |
| `CONVERSATION_SEMANTIC_CACHE_STORE` | `redis` | 存储后端：`redis` / `in-memory` |
| `CONVERSATION_SEMANTIC_CACHE_EMBEDDING_PROVIDER` | `hash` | 向量化：`hash` / `openai` |
| `CONVERSATION_SEMANTIC_CACHE_EMBEDDING_MODEL` | `embedding-default` | `openai` 档逻辑模型名 |
| `CONVERSATION_SEMANTIC_CACHE_REDIS_TTL` | `0s` | 整桶 TTL（仅 redis）；`0s`=不过期 |
| `RAG_CACHE_INVALIDATION_ENABLED`（knowledge） | `true` | 文档变更后自动失效 conversation 缓存 |
| `RAG_CACHE_INVALIDATION_CONVERSATION_URL`（knowledge） | `http://conversation-service:8081` | 联动目标地址 |

相关文档：[`rag-guide.md`](rag-guide.md)（RAG 增强 + 语义缓存联动）、[`operations.md`](../参考/operations.md)（运行配置）、
[`api-reference.md`](../参考/api-reference.md)（接口速查）。
```