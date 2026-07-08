# 钉钉知识库客服接入指南

本指南面向要把「客服在钉钉群 @机器人 提问 → 平台查知识库 → 机器人在群里给出带出处的回复」这条链路落地的开发者。
它复用平台已有的两块能力：

- **渠道桥接**：`channel-service` 里已落地的「飞书事件桥」（入站消息 → `/chat` → 回复），钉钉桥就是把 `channel/feishu/` 那一包**镜像**成 `channel/dingtalk/`，只改「验签算法」和「回复方式」两处渠道差异。
- **知识库检索**：`conversation-service` 的 `/chat` 在 `CONVERSATION_RAG_ENABLED=true` 时会**透明**地先查 `knowledge-service` 的 `/rag/query`（向量 + 关键词混合检索，按租户隔离），把命中的知识块拼进 prompt 再交给 LLM。桥接层看到的只是「进一句话、出一句话」，不需要自己碰检索。

本文按你确定的两个取舍来写：**回复走「机器人发消息 API」**（需 access_token，支持延时/主动推送），并加上**「知识库无命中 → 转人工」的兜底逻辑**。

> **实现状态**：入站验签 + 兜底闸门 + 机器人发消息 API 回复已落地，代码在 `channel-service/src/main/java/com/lrj/platform/channel/dingtalk/`（`DingtalkInboundController`、`DingtalkEventCrypto`、`DingtalkMessageBridge`、`DingtalkKnowledgeClient`、`DingtalkConversationClient`、`HttpDingtalkReplyClient`、`DingtalkProperties`、`DingtalkConfig`），edge-gateway 白名单与 `channel-service/application.yml` 的 `app.channel.dingtalk.*` 也已就位，默认 `enabled=false`。单测见同包 `Dingtalk*Test`。剩余需你在钉钉侧完成：建企业内部机器人、配公网回调地址、填 `DINGTALK_APP_KEY/SECRET`。

> 端点约定：钉钉回调统一走 `edge-gateway`（`http://<公网域名>` → `:8080`）的 `/channel/dingtalk/events`，该路径需加入网关白名单（免 `X-Api-Key`，见 §4.1）。`channel-service` 自身监听 `:8087`，仅供服务间直连或本地调试。

---

## 1. 整体链路

```text
客服在钉钉群 @机器人 提问
      │  钉钉服务器 POST 回调（请求头带 timestamp / sign 签名）
      ▼
edge-gateway :8080
      │  /channel/dingtalk/events 在 isOpen() 白名单里 → 免 X-Api-Key，直接透传
      │  路由 /channel/** 已覆盖，无需改路由表
      ▼
channel-service :8087
  DingtalkInboundController    ── 验签(HMAC-SHA256) → 立即 ACK（钉钉要求 <3s 响应）
      │
  DingtalkMessageBridge        ── 异步 · 按 msgId 去重 · 设 TenantContext（配置里的租户）
      │
      ├─(兜底闸门) 先查知识库 /rag/query
      │     ├─ 命中不足（0 命中或最高分 < 阈值） ─▶ 走「转人工」话术 + @人工客服，结束
      │     └─ 命中充分 ─▼
      │
      ▼  RestTemplate 带 OutboundTenantForwarder，自动铸发内部 JWT
conversation-service :8081  POST /chat        ── CONVERSATION_RAG_ENABLED=true
      │  RagPromptAugmenter.augment() 透明增强（再查一次 /rag/query）→ LLM
      ▼
  reply 文本回到 Bridge
      │
  HttpDingtalkReplyClient      ── 机器人发消息 API：换 access_token → groupMessages/send 发回群里
```

租户身份：钉钉不认识平台的租户，因此**一个钉钉企业（机器人）静态绑定一个租户**（配置 `app.channel.dingtalk.tenant-id`）。检索天然按 `tenantId` 过滤，各企业只看自己的知识库。

---

## 2. 前置准备

### 2.1 平台侧：先把客服知识灌进知识库

钉钉回复「对不对」，根子在知识库里有没有对的内容。先按 [RAG 接入指南](rag-guide.md) 把客服文档（FAQ、话术、政策、操作手册）上传，建议统一打上一个类目（如 `category=客服`）便于隔离检索：

```bash
curl -s -X POST 'http://localhost:8080/rag/documents' \
  -H 'X-Api-Key: dev-key-acme-ingest' \
  -H 'Content-Type: application/json' \
  -d '{"title":"退款政策.md","text":"退款需主管审批，3 个工作日内到账……","category":"客服"}'
```

### 2.2 平台侧：打开 conversation-service 的 RAG 增强

在 `conversation-service` 开启 RAG，并（可选）把检索限定到客服类目，避免串味：

| 环境变量 | 建议值 | 说明 |
|---|---|---|
| `CONVERSATION_RAG_ENABLED` | `true` | 打开 `/chat` 的 RAG 增强（默认 `false`，关闭时等价裸 LLM） |
| `KNOWLEDGE_BASE_URL` | `http://knowledge-service:8084` | 知识库地址 |
| `CONVERSATION_RAG_TOP_K` | `5` | 召回条数 |
| `CONVERSATION_RAG_MIN_SCORE` | `0.5`（示例） | 相关性下限，过滤弱相关 |
| `CONVERSATION_RAG_CATEGORY` | `客服` | 只检索客服类目（留空=全部） |

知识库侧默认已开混合检索（`RAG_HYBRID_ENABLED=true`，向量 + 关键词一起召回），无需额外配置。

### 2.3 钉钉侧：创建企业内部机器人

在 [钉钉开放平台](https://open.dingtalk.com) → 应用开发 → 企业内部应用 → 创建应用 → 添加「机器人」能力：

1. 记录 **AppKey / AppSecret**（既用于验签，也用于换 access_token）。
2. 配置「消息接收模式」为 **HTTP 回调**，回调地址填 `https://<你的公网域名>/channel/dingtalk/events`。
3. 把机器人加进目标客服群；客服 **@机器人** 提问时，钉钉会把消息 POST 到回调地址。
4. （转人工用）收集需要被 @ 的人工客服的 **userId**，填进 §4.4 的 `human-agent-ids`。

> 说明：这里用的是「机器人消息接收回调」，签名是简单的 `timestamp/sign`，**不涉及**事件订阅回调那套 aes_key/encrypt 握手。若没有公网 IP，钉钉还支持 **Stream 模式**（应用维持长连接收事件，免暴露 webhook），但那与当前 edge-gateway 暴露 webhook 的架构不同，需另加常驻连接组件——建议先用 HTTP 回调与飞书对齐，Stream 作为备选。

---

## 3. 钉钉协议要点（与飞书不同的两处）

### 3.1 入站验签（`DingtalkEventCrypto`）

钉钉回调请求头带 `timestamp` 和 `sign`，验签算法：

```
sign == Base64( HmacSHA256( key = AppSecret, data = timestamp + "\n" + AppSecret ) )
```

与飞书的 `SHA256(timestamp+nonce+encryptKey+body)` 不同——这是 `DingtalkEventCrypto` 相对 `FeishuEventCrypto` 唯一要改的核心。验签失败返回 401。

### 3.2 入站消息体（群内 @机器人）

回调 JSON 的关键字段（用于解析成 `DingtalkInboundMessage`）：

| 字段 | 含义 |
|---|---|
| `msgtype` | 消息类型，文本为 `text` |
| `text.content` | 文本内容（含 @机器人 的文字） |
| `conversationId` | 群会话 ID，**回复群消息时作为 `openConversationId`** |
| `conversationType` | `2` = 群聊 |
| `senderStaffId` / `senderNick` | 发送人（客服）的 userId / 昵称 |
| `msgId` | 消息 ID，**用于去重**（钉钉可能重投） |
| `robotCode` | 机器人编码（一般等于 AppKey） |
| `sessionWebhook` | 临时回复地址（本方案不用它，用发消息 API） |

### 3.3 出站回复（机器人发消息 API）——本方案采用

两步，token 缓存写法直接照搬 `HttpFeishuReplyClient.tenantAccessToken()`（提前 60s 过期）：

**① 换 access_token**（缓存至过期前）：

```
POST https://api.dingtalk.com/v1.0/oauth2/accessToken
Body: {"appKey":"<AppKey>","appSecret":"<AppSecret>"}
Resp: {"accessToken":"...","expireIn":7200}
```

**② 往群里发消息**：

```
POST https://api.dingtalk.com/v1.0/robot/groupMessages/send
Header: x-acs-dingtalk-access-token: <accessToken>
Body:
{
  "robotCode": "<AppKey>",
  "openConversationId": "<回调里的 conversationId>",
  "msgKey": "sampleMarkdown",
  "msgParam": "{\"title\":\"客服助手\",\"text\":\"<答案，可含出处>\"}"
}
```

> 纯文本用 `msgKey=sampleText`、`msgParam={"content":"..."}`。要 **@人工客服**（转人工场景），用 `sampleMarkdown` 并在 `text` 里按钉钉当前 markdown 的 @ 语法带上人工客服的 userId。`msgParam` 各 `msgKey` 的字段和 @ 语法请以钉钉最新文档为准（这块 API 版本间会变），代码里集中在 `HttpDingtalkReplyClient` 一处，方便调整。

---

## 4. 平台侧改动清单

### 4.1 edge-gateway：两处白名单

钉钉回调没有 `X-Api-Key`，必须把路径加进白名单，否则网关直接 401。

- `edge-gateway/.../ApiKeyToInternalTokenFilter.java` 的 `isOpen()` 增加：
  ```java
  || path.equals("/channel/dingtalk/events")
  ```
- `edge-gateway/.../EdgeRateLimitFilter.java` 的 `isOpen()` 同样加一行（webhook 不计租户限流）。

路由无需改：`application.yml` 里 `Path=/channel,/channel/**` 已覆盖 `/channel/dingtalk/events`。

### 4.2 channel-service：镜像飞书包

在 `channel-service/src/main/java/com/lrj/platform/channel/dingtalk/` 下新建（对照 `feishu/`）：

| 新建文件 | 对应飞书文件 | 差异点 |
|---|---|---|
| `DingtalkInboundController.java` | `FeishuInboundController` | 端点 `POST /channel/dingtalk/events`；验签 → 立即 ACK |
| `DingtalkEventCrypto.java` | `FeishuEventCrypto` | 验签改为 §3.1 的 HMAC-SHA256 |
| `DingtalkInboundMessage.java` | `FeishuInboundMessage` | 解析 §3.2 的字段（含 `conversationId`、`senderStaffId`） |
| `DingtalkMessageBridge.java` | `FeishuMessageBridge` | 去重 → 设租户 → **兜底闸门** → `/chat` → 回复（见 §5） |
| `HttpDingtalkReplyClient.java` | `HttpFeishuReplyClient` | 换 access_token + `groupMessages/send`（§3.3） |
| `DingtalkProperties.java` / `DingtalkConfig.java` | 同名飞书 | 前缀 `app.channel.dingtalk.*`；`@ConditionalOnProperty(enabled)` 装配 |

`feishu/HttpConversationClient`（只是调 `/chat`，与渠道无关）可直接复用，或抽到 `channel` 公共包供两个桥共享。

### 4.3 兜底闸门需要的知识库客户端

`DingtalkMessageBridge` 要在调 `/chat` 前先查一次知识库（判断是否要转人工），因此给它一个指向 `knowledge-service` 的 `RestTemplate`（带 `OutboundTenantForwarder` + `OutboundTraceForwarder`，与 `conversation-service` 的 `knowledgeRestTemplate` 建法一致），POST `/rag/query`：

- 请求 DTO：`platform-protocol` 的 `KnowledgeQueryRequest(query, topK, minScore, category)`
- 响应 DTO：`KnowledgeQueryReply(query, tenantId, List<KnowledgeHit>)`

### 4.4 配置项 `channel-service/application.yml`

```yaml
app:
  channel:
    dingtalk:
      enabled: ${DINGTALK_ENABLED:false}          # 主开关，默认关闭
      tenant-id: ${DINGTALK_TENANT:default}         # 该钉钉企业绑定的平台租户
      app-key: ${DINGTALK_APP_KEY:}                 # AppKey（robotCode + 验签 + 取 token）
      app-secret: ${DINGTALK_APP_SECRET:}           # AppSecret
      api-base-url: ${DINGTALK_API_BASE_URL:https://api.dingtalk.com}
      rag-category: ${DINGTALK_RAG_CATEGORY:客服}    # 兜底闸门检索的类目，与 §2.2 对齐
      # —— 无命中转人工兜底 ——
      fallback:
        min-hits: ${DINGTALK_FALLBACK_MIN_HITS:1}           # 命中数下限
        min-score: ${DINGTALK_FALLBACK_MIN_SCORE:0.5}       # 最高分下限
        message: ${DINGTALK_FALLBACK_MESSAGE:知识库暂未收录该问题，已为您转接人工客服，请稍候。}
        human-agent-ids: ${DINGTALK_HUMAN_AGENT_IDS:}       # 需 @ 的人工客服 userId，逗号分隔
```

---

## 5. 无命中转人工兜底（设计核心）

**难点**：RAG 增强发生在 `conversation-service` 的 `/chat` 内部，桥接层只拿到最终 `reply` 字符串，**无法得知知识库到底命中没有**。`RagPromptAugmenter` 在 0 命中时会静默退回原始问题，让 LLM 裸答——客服场景这会「一本正经地瞎编」，正是要避免的。

**方案（推荐，改动全部落在新 dingtalk 包内）**：把「是否足够回答」的判断作为一道**前置闸门**放进 `DingtalkMessageBridge`——先自己查一次 `/rag/query`，据命中情况决定「转人工」还是「进 `/chat` 正常作答」。

```java
// DingtalkMessageBridge.process(msg) 骨架示意
TenantContext.set(new TenantContext.Tenant(tenantId, "dingtalk:" + senderStaffId, Set.of("chat")));

// 1) 兜底闸门：先查知识库
KnowledgeQueryReply kb = knowledge.query(
        new KnowledgeQueryRequest(msg.text(), topK, cfg.getFallback().getMinScore(), cfg.getRagCategory()));
long strongHits = kb.hits().stream()
        .filter(h -> h.text() != null && !h.text().isBlank())
        .filter(h -> h.score() != null && h.score() >= cfg.getFallback().getMinScore())
        .count();

if (strongHits < cfg.getFallback().getMinHits()) {
    // 2a) 命中不足 → 转人工：发话术 + @ 人工客服，不调用 LLM（省一次 LLM 成本）
    reply.replyAtUsers(msg.conversationId(), cfg.getFallback().getMessage(), cfg.getFallback().getHumanAgentIds());
    return;
}

// 2b) 命中充分 → 正常作答（/chat 内部会再做一次 RAG + LLM，命中相同知识块）
String answer = conversation.chat(msg.text(), senderStaffId);
if (answer != null && !answer.isBlank()) {
    reply.replyText(msg.conversationId(), answer);   // 可选：追加「出处：<displayName#index>」提升可信度
}
```

**为什么把闸门放在桥而不是 `/chat`**：

- 改动只落在新 `dingtalk` 包，**不动共享的 `/chat` 契约**，也不影响飞书路径和 L1 语义缓存的短路逻辑。
- 「置信阈值」和「转人工动作」本就属于渠道自己（是钉钉群要 @ 人、发话术），放一起最内聚。
- 桥直接拿到 `hits` 的分数和 `displayName`，既能判 gate，又能把**出处**拼进答案。
- 无命中时**直接转人工、不调 LLM**，省一次模型成本。

**已知取舍**：能答的问题会检索两次（闸门一次 + `/chat` 内部一次）。冷问题双检索，重复问题因 `/chat` 命中 L1 语义缓存而只有闸门这一次检索，成本可接受。
若想彻底避免冷问题双检索，备选方案是**让 `/chat` 回传 grounding 信号**（`RagPromptAugmenter.augment()` 额外返回命中信息，`/chat` 响应加 `grounded`/`sources` 字段，桥据此判 gate）——单次检索，但要改 `conversation-service` 共享代码，且与「RAG 前置语义缓存」的短路语义有交互，按需选用。

---

## 6. 端到端验证

1. **起服务**：`knowledge-service`、`conversation-service`（带 §2.2 环境变量）、`channel-service`（带 §4.4 环境变量 `DINGTALK_ENABLED=true` 及凭据）、`edge-gateway`（含 §4.1 白名单）。本地可用 `docker compose -f deploy/docker-compose.yml up --build`。
2. **灌知识**：按 §2.1 上传至少一篇 `category=客服` 的文档。
3. **模拟命中**（跳过钉钉，直接打网关的 `/chat` 验证 RAG 是否生效）：
   ```bash
   curl -s -X POST 'http://localhost:8080/chat' \
     -H 'X-Api-Key: dev-key-acme' -H 'Content-Type: application/json' \
     -d '{"message":"退款怎么审批？"}'
   # reply 应包含知识库内容（如「需主管审批」）
   ```
4. **模拟入站回调**：构造带正确 `timestamp/sign` 的 POST 打 `/channel/dingtalk/events`，验证：命中 → 群里收到答案；不命中（问一个知识库里没有的问题）→ 群里收到转人工话术 + @人工客服。
5. **真机联调**：在钉钉群 @机器人，观察机器人回复。

---

## 7. 测试建议（对照飞书测试）

纯 POJO 单测（JUnit5 + Mockito + AssertJ，`@AfterEach` 里 `TenantContext.clear()`），镜像 `feishu/*Test`：

- `DingtalkEventCryptoTest`：§3.1 验签正/负样例。
- `DingtalkInboundControllerTest`：验签通过/失败、非 text 消息过滤、立即 ACK。
- `DingtalkMessageBridgeTest`：**命中 → 走 `/chat` 并回复**、**无命中 → 转人工话术 + @人工客服、不调 `/chat`**、按 `msgId` 去重。用 `CapturingReplyClient` 和 mock 的 `KnowledgeClient`/`HttpConversationClient` 断言分支。

---

## 8. 生产注意事项

- **3 秒 ACK**：控制器解析 + 验签后立即返回，重活丢给 `executor` 异步跑（飞书桥已是此模式，直接沿用）。
- **多副本去重**：`FeishuMessageBridge` 的进程内 `ConcurrentHashMap` 去重是 best-effort；多副本部署需换 Redis/JDBC 去重（参考 `CHANNEL_DEDUP_STORE=jdbc`）。
- **token 缓存**：`HttpDingtalkReplyClient` 缓存 access_token 至过期前 60s，避免每条消息都换 token（照搬飞书）。
- **凭据管理**：`DINGTALK_APP_SECRET` 等走密钥管理（见 [部署指南](deployment-guide.md) 的 External Secrets），不要写进代码或明文配置。
- **多钉钉企业 / 多租户**：一个机器人 = 一个租户。要支持多个钉钉企业，扩成按 `robotCode → tenantId` 的映射表，或起多份 dingtalk 配置。
- **回复失败降级**：发消息 API 失败只记 warn、不抛断链路（照搬飞书 `replyText` 的 try/catch）。
