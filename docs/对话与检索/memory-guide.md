# 记忆接入指南（多轮记忆 + 长期用户画像）

本指南面向要给对话加"记忆"的开发者，覆盖 `conversation-service`（`:8081`）里**两条正交的记忆轴**：

- **多轮记忆（会话内 / short-term）**：按 `chatId` 保留最近若干轮，换个会话就忘、跨会话不共享。
  **默认开启**、内存滑窗，零外部依赖；`redis` 档多副本共享 + 重启不丢。
- **长期用户画像（跨会话 / long-term）**：记住"关于这个**用户**、值得**跨会话**长期保留"的事实——
  偏好、稳定属性、反复诉求。下次该用户任意新会话进来被召回注入。**默认关闭**（mem0 式语义长期记忆）。

两者都遵循平台一贯的「接口 + `@ConditionalOnProperty` 多实现，默认内存/确定性」写法，本地运行和单测无需任何外部基础设施。

> 端点约定：业务接口都走 `edge-gateway`（`http://localhost:8080`，带 `X-Api-Key`）。网关校验 api-key → 签发短时内部 JWT → 转发下游。
> `conversation-service` 自身监听 `:8081`，仅供服务间直连或本地调试。下文 curl 统一走边缘网关，示例 key `dev-key-acme`（租户 `acme`，用户 `alice`）。
> 租户/用户身份随内部 JWT 传播，在下游还原进 `TenantContext`（见 `架构文档.md` 的两层网关设计）。

---

## 1. 两条记忆轴的区别

项目原有的多轮 `ChatMemory`（`messages` / `tokens` / `summary` 三种滑窗）是**会话内**记忆：按 `chatId`
保留最近若干轮，**换个会话就忘**、跨会话不共享。

长期画像补的是**另一条轴**：记住"关于这个**用户**、值得**跨会话**长期保留"的事实。

| | 多轮记忆（默认开） | 长期画像（默认关） |
| --- | --- | --- |
| 键 | `<tenantId>::<chatId>`（一次会话） | `(tenant, user)`（跨该用户所有会话） |
| 内容 | 最近 N 轮原始消息（或摘要） | 抽炼后的 durable 用户事实 |
| 生命周期 | 会话结束 / 滑出即忘 | 长期保留（带容量上限淘汰） |
| 注入 | 由 `@AiService` 自动进 prompt 历史 | chat 前召回、每轮新鲜拼进系统提示 `context` |
| 开关 | `CONVERSATION_MEMORY_*`（默认 in-memory + messages 档） | `CONVERSATION_MEMORY_PROFILE_*`（默认关） |
| 端点 | 内嵌在 `/chat`、`/chat/memory` 等所有走记忆的对话端点 | `/chat/memory`、`GET/DELETE /memory/profile` |

> `/chat/memory` 端点**同时用到两条轴**：它用同一个 `<tenantId>::<chatId>` 键拿到多轮窗口记忆，
> 又在对话前召回并注入该用户的长期画像、对话后异步更新画像。

---

## 2. 多轮记忆（会话内滑窗）

### 2.1 做什么 / 怎么工作

langchain4j 的 `@AiService`（`conversation/Assistant.java`）会自动发现唯一的 `ChatMemoryProvider`
bean（`memory/ChatMemoryConfig`）并注入，使 `Assistant.chat(@MemoryId chatId, …)` 具备**按会话隔离**的多轮记忆。

- **记忆键 = `<tenantId>::<chatId>`**：`ConversationController` 用租户前缀拼 `chatId`，同租户不同会话互不串，
  跨租户天然隔离（key 前缀不同）。请求里传 `?chatId=xxx` 指定会话，不传默认 `default`。
- **用户原始消息进记忆**：RAG 检索到的来源、长期画像前缀等都经系统提示 `context` **每轮新鲜注入**，
  **不落进历史**——避免多轮下 source/前缀在记忆里累积膨胀。

### 2.2 三种滑窗模式（`window-mode`）

| 模式 | 实现 | 行为 | 何时用 |
| --- | --- | --- | --- |
| `messages`（默认） | `MessageWindowChatMemory` | 保留最近 `max-messages` 条，更旧的**直接丢弃** | 通用、最省 |
| `tokens` | `TokenWindowChatMemory` + `OpenAiTokenCountEstimator` | 按 token 预算保留，超 `max-tokens` 丢最旧 | 想按上下文长度而非条数控窗 |
| `summary` | `SummarizingChatMemory`（本项目自研） | 溢出的旧消息经 LLM **压缩成一条系统摘要**而非丢弃，长对话仍保早期要点 | 长对话、早期上下文重要 |

> `summary` 档每次溢出多一次网关 `ChatModel` 调用（压缩），摘要系统消息始终置于最前、不计入 `max-messages`。
> `tokens` 档的 `token-model`（默认 `gpt-4o-mini`）只作 tokenizer 依据，与实际调用的模型无关。

### 2.3 配置（默认值以 `application.yml` 为准）

```yaml
app:
  conversation:
    memory:
      store: in-memory        # in-memory（默认，重启即丢）| redis（多副本共享 + 重启不丢）
      window-mode: messages   # messages（默认）| tokens | summary
      max-messages: 20        # messages/summary 档窗口条数
      max-tokens: 2000        # tokens 档 token 预算
      token-model: gpt-4o-mini   # tokens 档 tokenizer 依据
      redis-ttl: P7D          # redis 档会话 TTL（ISO-8601，如 P7D / PT12H；0s/负值 = 永不过期）
```

对应环境变量（起服务时设）：`CONVERSATION_MEMORY_STORE` / `_WINDOW_MODE` / `_MAX_MESSAGES` / `_MAX_TOKENS` /
`_TOKEN_MODEL` / `_REDIS_TTL`。

### 2.4 存储后端

- **`in-memory`（默认）**：`InMemoryChatMemoryStore`，进程内、重启即丢，零依赖，适合本地/单测。
- **`redis`**：`RedisChatMemoryStore`，按 `chat:mem:<memoryId>` 存 langchain4j 序列化的消息 JSON + TTL。
  **多副本共享同一份会话记忆、重启不丢**。需要一个可达的 Redis（`spring.data.redis.*`）。
  `redis-ttl` 为 `0s` 或负值时不设过期。

### 2.5 curl：多轮记忆

同一个 `chatId` 连续两轮，第二轮助手应"记得"第一轮说的名字：

```bash
# 第一轮：告诉助手一个上下文
curl -s -X POST 'http://localhost:8080/chat?chatId=s1' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"message":"我叫王五，在做退款对账。"}'
# → {"reply":"...","chatId":"s1","tenantId":"acme","userId":"alice"}

# 第二轮：同一 chatId，助手应记得"王五"
curl -s -X POST 'http://localhost:8080/chat?chatId=s1' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"message":"我刚说我叫什么？"}'

# 换个 chatId=s2 → 全新会话，助手不再记得（会话内记忆按 chatId 隔离）
curl -s -X POST 'http://localhost:8080/chat?chatId=s2' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"message":"我刚说我叫什么？"}'
```

---

## 3. 长期用户画像（跨会话记忆）

> 新项目里这是 `conversation-service` 的 `memory/profile/` 包，端点 `/chat/memory`、`GET/DELETE /memory/profile`，
> **默认关**（`CONVERSATION_MEMORY_PROFILE_ENABLED=false`）→ 对话链零变化。开启后每轮多一次 temp=0 抽取调用。

### 3.1 做什么

记住偏好（"偏好邮件联系"）、稳定属性（"是 Pro 套餐用户"）、反复诉求（"多次咨询退款政策"）这类
**跨会话 durable** 的用户事实。下次该用户任意新会话进来，这些画像被召回注入，助手"记得"他。

### 3.2 架构（`memory/profile/` 包）

```text
  chat 请求 ─► recall(tenant,user) ─► 注入"【关于该用户的长期记忆…】"到系统提示 context ─► Assistant.chat ─► reply
                                                                                            │
                          observe(原始 userMsg, reply) ──默认异步──► ProfileExtractor(temp=0)
                                                                          │ 抽 durable 事实
                                                                  UserProfileStore.add(去重 + 容量上限)
```

| 类 | 职责 |
| --- | --- |
| `MemoryItem` / `MemoryFact` / `ExtractedMemories` | 持久化记忆项 + 抽取的结构化输出（`MemoryFact` 带 `type`：preference/attribute/issue/other） |
| `ProfileExtractor` | `@AiService`（temp=0 判官模型，`GatewayChatModelFactory.buildDeterministic()`）：一轮对话 → durable 用户事实。prompt 强约束"只抽持久、跳过一次性、多数轮返回空"，含正/反例 |
| `UserProfileStore` + `InMemoryUserProfileStore` | 按 `(tenant,user)` 隔离存储；去重（归一相等 / 互为子串）+ 容量上限淘汰最旧 + per-key 锁串行化「读→去重→写」 |
| `UserProfileService` | `recall`（渲染最近 N 条 `- text` 项目符号块）+ `observe`（**默认异步**抽取入库，失败被吞） |
| `UserProfileChatService` | 包装 `Assistant.chat`：召回注入 + 异步观察（跟 RAG `context` 注入同范式，不改主 `Assistant`） |
| `MemoryProfileConfig` | `@ConditionalOnProperty(app.conversation.memory.profile.enabled)` 装配全部 + `profileExecutor` 线程池 |
| `MemoryProfileController` | `/chat/memory`、`GET/DELETE /memory/profile` |

**复用链**：多租户鉴权（`X-Api-Key` → tenant+user）/ 限流 / 配额 / 审计全走现有安全链；
`user` 取自 `TenantContext.current().userId()`（api-key 映射的用户），画像天然只操作调用者自己的那一份。

### 3.3 配置

```yaml
app:
  conversation:
    memory:
      profile:
        enabled: false        # 默认关 → 对话链零变化
        store: in-memory      # in-memory（默认，重启即丢）；redis 变体接口已留、待补
        max-items: 50         # 每用户记忆上限，超出淘汰最旧
        recall-limit: 12      # chat 前注入召回最近多少条
        async: true           # 观察（抽取 + 入库）异步，不阻塞 chat 响应
```

对应环境变量：`CONVERSATION_MEMORY_PROFILE_ENABLED` / `_STORE` / `_MAX_ITEMS` / `_RECALL_LIMIT` / `_ASYNC`。

### 3.4 端点 / 响应体

| 方法 | 路径 | 说明 | 响应体 |
| --- | --- | --- | --- |
| POST | `/chat/memory` | 带长期记忆的对话：召回注入 + 异步更新画像 | `{"reply","chatId","tenantId","userId"}` |
| GET | `/memory/profile` | 列出当前用户的长期记忆 | `{"count","items":[MemoryItem…],"tenantId"}` |
| DELETE | `/memory/profile` | 清空（PII 合规删除），返回清除条数 | `{"removed","tenantId"}` |

> 未开启时三个端点均返回 `{"error":"User profile memory not enabled. Set app.conversation.memory.profile.enabled=true.","tenantId":…}`。
> `MemoryItem` 字段：`id`（归一文本 hashCode 十六进制，稳定去重键）、`text`、`type`、`createdAtEpochMs`、`sourceChatId`（来源会话，便于溯源）。

### 3.5 怎么跑 / curl

起服务时开启画像（其余走默认）：

```bash
CONVERSATION_MEMORY_PROFILE_ENABLED=true \
  mvn -pl conversation-service spring-boot:run
# 或 docker compose -f deploy/docker-compose.yml up --build（在 compose 里设该环境变量）
```

```bash
# 第一轮：告诉助手一个偏好（会被异步抽进画像）
curl -s -X POST 'http://localhost:8080/chat/memory?chatId=s1' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"message":"以后有事发我邮箱就行，我不看短信"}'

# 稍等异步抽取入库后，换一个全新会话 chatId=s2：助手应"记得"偏好邮件（画像被召回注入）
curl -s -X POST 'http://localhost:8080/chat/memory?chatId=s2' \
  -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
  -d '{"message":"怎么联系我比较好？"}'

# 查看当前用户画像（透明可审）
curl -s 'http://localhost:8080/memory/profile' -H 'X-Api-Key: dev-key-acme'
# → {"count":1,"items":[{"id":"...","text":"偏好邮件联系","type":"preference","createdAtEpochMs":...,"sourceChatId":"s1"}],"tenantId":"acme"}

# 清空画像（PII 合规删除）
curl -s -X DELETE 'http://localhost:8080/memory/profile' -H 'X-Api-Key: dev-key-acme'
# → {"removed":1,"tenantId":"acme"}
```

> `async: true`（默认）下抽取入库在后台线程池（`profile-observe-*`，core 1 / max 2 / queue 128）跑，
> 所以第一轮返回后画像可能还没落库——演示时第二轮前稍等，或临时设 `CONVERSATION_MEMORY_PROFILE_ASYNC=false` 让观察同步完成。

---

## 4. 决策 / 坑 / 故意不做

**决策**（这些是老单体文档沉淀下来的设计洞察，迁移时原样保留）：

- **独立端点 `/chat/memory` 而非改主 `/chat`**：跟 `/chat/auto`、`/chat/sql` 一样，新能力走新端点 + 默认关，主链零回归。生产要全局开就把召回注入折进 `/chat`（按信号）。
- **异步观察**：抽取是额外一次 LLM 调用，投后台不拖慢响应（与 `summary` 档异步压缩同理）。
- **注入走系统提示 `context` 而非落历史**：每轮新鲜注入、对 guardrail/RAG 透明；观察用**原始**消息（不含注入前缀），让抽取看到干净输入、也避免前缀在多轮记忆里累积。
- **抽取走 temp=0 判官模型**：画像应稳定，不该同段对话每次抽出不同事实。

**坑**：

- **抽取宁缺毋滥**：prompt 强约束"多数轮返回空"——记忆库塞满噪声反而拖累注入质量、涨 token。
- **去重是 lexical（v1）**：归一（小写 + 去空白/标点）后相等或互为子串才算重复，能去掉「偏好邮件」vs「偏好邮件联系」，但语义近似非子串（「偏好邮件」vs「偏好通过邮件联系」）抓不到——要 embedding 消歧（后续）。
- **多副本**：`in-memory` 画像 store 是进程内 + per-key 锁（限单 JVM）；多轮记忆的 `in-memory` 档同理。多 pod 部署时：多轮记忆切 `CONVERSATION_MEMORY_STORE=redis` 即可共享；长期画像的 Redis 变体接口已留、尚未实现（`UserProfileStore` 只有内存实现）。

**故意不做**（决策记录）：

| 项 | 为什么 |
| --- | --- |
| embedding 语义检索 / 消歧 | v1 用 lexical 去重 + 全量召回（`recall-limit` 截断）够用；大画像库再上向量 |
| 记忆 update/forget（mem0 的改写/遗忘） | v1 只 add + 去重 + 容量淘汰；带冲突消解的 update 是后续 |
| 自动注入主 `/chat` | 默认关 + 独立端点更安全；按信号折入 |
| 长期画像 Redis 持久化实现 | 接口已留（`UserProfileStore`），按信号补（可参考多轮记忆的 `RedisChatMemoryStore`） |

---

## 5. 开关速查

多轮记忆（默认**开启**，内存滑窗）：

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `CONVERSATION_MEMORY_STORE` | `in-memory` | `in-memory`（重启丢）\| `redis`（多副本共享 + 重启不丢，需 Redis） |
| `CONVERSATION_MEMORY_WINDOW_MODE` | `messages` | `messages` \| `tokens` \| `summary`（旧消息 LLM 压缩为摘要） |
| `CONVERSATION_MEMORY_MAX_MESSAGES` | `20` | `messages`/`summary` 档窗口条数 |
| `CONVERSATION_MEMORY_MAX_TOKENS` | `2000` | `tokens` 档 token 预算 |
| `CONVERSATION_MEMORY_TOKEN_MODEL` | `gpt-4o-mini` | `tokens` 档 tokenizer 依据 |
| `CONVERSATION_MEMORY_REDIS_TTL` | `P7D` | `redis` 档会话 TTL（ISO-8601；`0s`/负值 = 永不过期） |

长期用户画像（默认**关闭**）：

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `CONVERSATION_MEMORY_PROFILE_ENABLED` | `false` | 总开关；关时 `/chat/memory`、`/memory/profile` 返回禁用提示 |
| `CONVERSATION_MEMORY_PROFILE_STORE` | `in-memory` | 目前仅内存实现（redis 变体待补） |
| `CONVERSATION_MEMORY_PROFILE_MAX_ITEMS` | `50` | 每用户记忆上限，超出淘汰最旧 |
| `CONVERSATION_MEMORY_PROFILE_RECALL_LIMIT` | `12` | chat 前召回注入最近多少条 |
| `CONVERSATION_MEMORY_PROFILE_ASYNC` | `true` | 抽取 + 入库是否异步（不阻塞 chat 响应） |

---

## 关联文档

- 两层网关 / 租户身份传播 → `架构文档.md`
- 对话侧 RAG 增强、L1 语义缓存、`/chat` 全貌 → `rag-guide.md`
- 运行配置 / 起栈 / Redis 等基础设施 → `operations.md`
- 接口速查 → `api-reference.md`
