# 运行与配置手册

本手册面向本地起栈、部署与排障，覆盖前置依赖、启动命令、按能力分组的环境变量，以及常见问题。
所有配置项均以各服务 `src/main/resources/application.yml` 和 `deploy/docker-compose.yml` 源码为准；表格里的“默认值”就是 yml 里 `${ENV:default}` 的 default，未显式给出 `${ENV}` 包装的项会标注为「属性」（只能经 config-server 或 JVM 参数设置）。

平台由 **7 个共享库**（`platform-security` / `platform-observability` / `platform-gateway-client` / `platform-protocol` / `platform-audit` / `platform-metering` / `platform-eventbus`，无主类，靠自动装配自注册）加 **一批微服务**（`edge-gateway` / `config-server` / `auth` / `conversation` / `workflow` / `analytics` / `knowledge` / `agent` / `async-task` / `channel` / `interop` / `eval` / `vision` / `voice`）组成。另有前后端分离的 **`capability-showcase-frontend`**（:8093，Vue3 静态 SPA / nginx，独立部署，不属于 Maven 构建）。

两层网关：

- **`edge-gateway`（:8080）** —— 唯一对外入口。校验 `X-Api-Key`，换发短时内部 JWT（`X-Internal-Token`），按路径路由到各服务。
- **LiteLLM（:4000，外部，非 Java 模块）** —— LLM 网关。所有模型调用走一个 OpenAI 兼容端点，provider 路由/failover/模型名映射都在 `deploy/litellm/config.yaml` 里。

---

## 1. 本地前置条件

| 依赖 | 版本 / 说明 |
|---|---|
| JDK | 21（Spring Boot 3.3.5，Java 21） |
| Maven | 系统 `mvn`（无 wrapper） |
| Docker / Docker Compose | 起本地整网基础设施与服务镜像 |
| Ollama | 本机运行；LiteLLM 默认回连宿主机 Ollama。compose 的 RAG 语义 embedding 默认走 `nomic-embed-text`，故需 `ollama pull nomic-embed-text`（另可 `ollama pull llama3.1` / `qwen2.5vl` 供本地对话/视觉） |
| LiteLLM | 由 compose 提供；不用 compose 时需自行准备一个可达的 OpenAI 兼容端点，并把 `GATEWAY_BASE_URL` 指过去。compose 的 `chat-default` 默认映射 DeepSeek，需宿主环境 `DEEPSEEK_API_KEY` |

> **单测零外部依赖**：测试是纯 POJO（不加载 Spring context），`mvn test` 无需任何基础设施。
> **两套「默认」需分清**：① **application.yml 裸跑默认**——多数能力（对话记忆、agent 动作、DAG 重规划、事件总线、async/图谱/registry 的部分档）为内存/关闭；但 knowledge-service 的 application.yml 现已默认 `RAG_VECTOR_STORE_PROVIDER=qdrant`、`RAG_REGISTRY_STORE=redis`、`RAG_GRAPH_STORE=jdbc`、`RAG_ES_ENABLED=true`（仅 embedding 默认 `hash` 不真调），故**单跑 knowledge-service 期望 qdrant/redis/mysql/ES 可达**；要真正零依赖单跑需显式 `RAG_VECTOR_STORE_PROVIDER=in-memory RAG_REGISTRY_STORE=in-memory RAG_GRAPH_STORE=in-memory RAG_ES_ENABLED=false`。② **docker-compose demo 默认**——为一键体验把 RAG 持久化（qdrant/redis/jdbc/ES）、nomic 语义 embedding、hanlp 分词、多模态、登录 RBAC 等打开，并自带全部基础设施。

---

## 2. 起栈命令

```bash
# 1. 构建全部（platform-* 共享库必须先构建，package 会一并处理）
mvn -DskipTests package

# 2. 起整套本地栈（LiteLLM + Redis + MySQL + Kafka + Qdrant + Elasticsearch/Kibana
#    + config-server + 各服务含 auth + edge-gateway + 前端 nginx）
docker compose -f deploy/docker-compose.yml up --build

# 校验 compose 展开后的最终配置
docker compose -f deploy/docker-compose.yml config

# —— 推荐用一键脚本（自动本机端口重映射避开 apollo 占用的 8080/8090/3306）——
bash deploy/start-all.sh      # 全 docker（后端 + 基础设施 + 前端 nginx :8093）；网关落 :18080
bash deploy/start-dev.sh      # 后端 docker + 前端 vite dev(:5173, HMR)；日常改前端用
bash deploy/start-local.sh    # 仅后端应用服务（基础设施保持运行）；加 --all 连基础设施、--build 先打 jar

# 3. 不用 Docker 单跑某个服务（需本机有 LiteLLM 或把 base-url 指向可用 OpenAI-compat 端点）
mvn -pl conversation-service spring-boot:run   # :8081
mvn -pl edge-gateway spring-boot:run           # :8080

# 4. 演示 / 冒烟脚本
bash deploy/seed-kb.sh             # 灌 sample-docs 示例知识库并跑检索验证（--purge 先删 / --public 灌公共库）
bash deploy/rag-demo.sh            # RAG 单文档闭环演示（上传→列表→检索→查单，--with-llm 打 /chat /agent）
bash deploy/smoke-qdrant-rag.sh    # 纯向量 RAG 冒烟（断言 source 全为 vector）
bash deploy/smoke-es-hybrid-rag.sh # ES 全文混排冒烟（断言 hits 含 source∈{es,hybrid}）
bash deploy/smoke-rbac.sh          # 登录→角色展开→边缘换发内部 JWT→role-admin 门→409/403 护栏
bash deploy/smoke-a2a.sh           # A2A 对外冒烟（agent-card / message/send / deep-research 异步 Task）
bash deploy/smoke-nl2sql.sh        # NL2SQL / ChatBI 冒烟（走 edge-gateway 打 /chat/sql、/analytics/sql）
bash deploy/smoke-failover.sh      # LiteLLM 双上游故障转移冒烟（用 deploy/docker-compose.failover.yml，独立栈）
```

> `smoke-a2a.sh` / `smoke-nl2sql.sh` 不自起栈、默认打 `:18080`（对齐 `start-*.sh` 的网关端口约定）；若你的网关在别处，传 `BASE_URL=http://localhost:8080 bash deploy/smoke-a2a.sh` 覆盖。

测试命令：

```bash
mvn test                                    # 全量测试
mvn -pl knowledge-service test              # 单模块
mvn -pl agent-service -am test              # 模块 + 上游共享库
mvn -pl platform-security -Dtest=InternalTokenTest test   # 单类（务必带 -pl）
```

---

## 3. 服务端口一览

| 服务 / 组件 | 端口 | 经网关暴露的路径前缀 | 说明 |
|---|---:|---|---|
| edge-gateway | 8080 | —（对外入口本身） | 校验 api-key，换发内部 JWT |
| conversation-service | 8081 | `/chat`、`/chat/**` | Chat + 可选 RAG 增强、语义缓存、级联 |
| workflow-service | 8082 | `/workflow`、`/workflow/**` | Flowable 退款审批流 + outbox |
| analytics-service | 8083 | `/chat/sql`、`/analytics`、`/analytics/**` | NL2SQL / ChatBI |
| knowledge-service | 8084 | `/rag`、`/rag/**`、`/knowledge`、`/knowledge/**` | 混合 RAG + GraphRAG |
| agent-service | 8085 | `/agent`、`/agent/**` | ReAct + 多 Agent DAG |
| async-task-service | 8086 | `/async`、`/async/**` | 通用任务中心、SSE、webhook outbox |
| channel-service | 8087 | `/channel`、`/channel/**` | 渠道出站/回调 + 可选 Kafka 事件 |
| interop-service | 8088 | `/interop`、`/interop/**`、`/.well-known/agent-card.json` | A2A + MCP surface |
| eval-service | 8089 | `/eval`、`/eval/**` | 回归测试客户端 |
| vision-service | 8090 | `/vision`、`/vision/**` | 多模态图像描述（caption/describe） |
| voice-service | 8091 | `/voice`、`/voice/**` | ASR + 对话 + TTS 语音闭环 |
| auth-service | 8092 | `/auth`、`/auth/**` | 账号登录 / 注册 / 刷新 / RBAC 管理 |
| config-server | 8888 | 不经网关 | 集中配置分发（可选） |
| capability-showcase-frontend | 8093 | 不经网关（浏览器跨域直调） | 能力展示前端（Vue3 静态 SPA / nginx） |
| LiteLLM | 4000 | 不经网关 | LLM 网关 |
| MySQL | 3306（脚本重映射 13307） | — | 本地数据库（含 auth 库） |
| Redis | 6379 | — | RAG registry / metering / 限流 / 语义缓存 存储 |
| Qdrant | 6333(HTTP) / 6334(gRPC) | — | 向量库（默认向量后端） |
| Elasticsearch | 9200 | — | RAG 全文 BM25 混排索引（smartcn，默认开启） |
| Kibana | 5601 | — | ES 查询 UI（Dev Tools） |
| Kafka | 9092 | — | 事件总线 / channel 事件 broker |

> 网关路由表见 `edge-gateway/src/main/resources/application.yml` 的 `spring.cloud.gateway.routes`。`/.well-known/agent-card.json` 是 A2A 发现别名，在 `ApiKeyToInternalTokenFilter` 里免鉴权放行，其余 `/interop/**` 仍需鉴权。

---

## 4. 鉴权与租户

外部调用有**两种并存的凭据**（经网关 :8080，任选其一）：

1. **`X-Api-Key`**：网关按 api-key 查租户绑定，签发短时内部 JWT。
2. **`Authorization: Bearer <会话 accessToken>`**：先 `POST /auth/login` 拿会话令牌，网关 `SessionBearerAuthFilter`（order -110，早于 api-key filter -100）验签会话令牌后换发内部 JWT。

两条路径产出同形状的 `X-Internal-Token`，下游只认它、对登录 vs api-key 无感知（详见第 4.1 节）。

```bash
# 方式一：api-key
curl -s -X POST 'http://localhost:8080/chat?chatId=u1' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"message":"用一句话介绍你自己"}'
# 期望：{"reply":"...","tenantId":"acme","userId":"alice",...}

# 方式二：登录换会话令牌，再带 Bearer 调业务接口
TOKEN=$(curl -s -X POST 'http://localhost:8080/auth/login' \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"demo12345"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
curl -s -X POST 'http://localhost:8080/chat?chatId=u1' \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"用一句话介绍你自己"}'
```

### 内置开发 api-key（以 `edge-gateway` 的 `platform.security.api-keys` 为准）

| api-key | 租户 | 用户 | scopes |
|---|---|---|---|
| `dev-key-acme` | acme | alice | chat, ingest, approve, agent, channel, eval, vision, voice |
| `dev-key-globex` | globex | bob | chat |
| `dev-key-tenantA-admin` | tenantA | analyst-a | chat, analytics |
| `dev-key-acme-ingest` | acme | alice | chat, ingest |

> 下游服务各自也有一份小的 `platform.security.api-keys` 和 `allow-api-key-fallback: true`，仅用于**绕过网关直连调试**；生产可把 `allow-api-key-fallback` 关掉，令下游只信 JWT。

### 登录账号（auth-service demo 种子，口令 `AUTH_DEMO_PASSWORD`，默认 `demo12345`）

| 用户名 | 租户 | 角色 | 有效 scopes 要点 |
|---|---|---|---|
| `alice` | acme | admin | 全量 + `role-admin` + `public-ingest`（可进 RBAC 控制台） |
| `bob` | globex | viewer | chat |
| `analyst-a` | tenantA | analyst | chat, analytics |

> 登录账号与上表 api-key 的租户/scope 镜像对齐——「登录」与「手输 api-key」拿到一致身份。

### 4.1 登录、会话与 RBAC（auth-service，:8092）

- **会话令牌**：`SESSION_JWT_SECRET`（auth 签发 / edge 验签共用，≥32 字节，与内部密钥分离）。会话 accessToken 默认 60min（`SESSION_ACCESS_TTL`），刷新令牌默认 7d（`SESSION_REFRESH_TTL`，仅哈希存库，经 httpOnly cookie 收发）。
- **刷新/登出**：`POST /auth/refresh`（用刷新 cookie 一次性轮转）、`POST /auth/logout`（撤销会话）。跨域直调网关时刷新 cookie 需 `AUTH_COOKIE_SAME_SITE=None` + `AUTH_COOKIE_SECURE=true`（同源 nginx 反代可用默认 Lax/false）。
- **RBAC**：`AUTH_RBAC_ENABLED`（yml 默认 false / compose demo true）开启后 `/auth/admin/**` 管理面可用，需 `role-admin` scope；写端点再受 `AUTH_RBAC_ADMIN_WRITES_ENABLED`（关→503）与 `If-Match` 乐观锁（缺失 428 / 冲突 412）约束。`AUTH_RBAC_BOOTSTRAP_ADMIN_USERS`（默认 `alice`）指定初始 admin。
- **存储**：`AUTH_STORE`（yml `in-memory` / compose `jdbc`）；jdbc 档自动建 `USERS`/`USER_ROLE`/`ROLES`/`ROLE_SCOPE`/`AUTH_SESSION`（`AUTH_DB_URL`/`_USER`/`_PASSWORD`）。种子 `AUTH_SEED_ENABLED`（默认 true，生产建议 false）。自助注册 `AUTH_REGISTRATION_ENABLED`（默认 false，须与 RBAC 同开）。

### 内部 JWT 签名（`platform.security.*`）

| 属性 / 变量 | 默认值 | 说明 |
|---|---|---|
| `INTERNAL_JWT_SECRET`（→ `platform.security.jwt-secret`） | `dev-only-internal-secret-change-me-please-32b` | HS256 对称密钥，**必须 ≥32 字节**；生产走 Vault / K8s Secret |
| `platform.security.jwt.algorithm` | `HS256` | `HS256`（对称，用上面的 secret）或 `RS256`（非对称） |
| `platform.security.jwt.private-key` | 空 | `RS256` 时 edge-gateway 用它签发；PEM（含头尾）或纯 base64，PKCS#8 |
| `platform.security.jwt.public-key` | 空 | `RS256` 时下游用它验签；PEM 或纯 base64，X.509 |
| `platform.security.jwt-ttl` | `5m` | 内部 JWT 有效期（仅覆盖一次调用链） |
| `platform.security.internal-header` | `X-Internal-Token` | 内部 JWT 承载头名 |
| `platform.security.api-key-header` | `X-Api-Key` | 外部 api-key 头名（仅边缘识别） |

> `jwt.*` 三项在 application.yml 中未做 `${ENV}` 包装，需经 config-server 下发或 JVM 参数 `-Dplatform.security.jwt.algorithm=RS256` 等设置。切 RS256 时须同时给 edge-gateway 配 `private-key`、给所有下游配 `public-key`。

### 网关限流（`edge-gateway` 的 `app.rate-limit`）

默认开启（`enabled: true`，`store: redis`，`RATE_LIMIT_STORE=redis`；单机无 redis 时设 `in-memory`）。按 scope 每分钟配额：`chat=60`、`agent=20`、`stream=20`、`ingest=5`、`eval=5`、`default=120`；匿名请求乘以 `anonymous-multiplier=0.2`。

---

## 5. LLM 网关（所有调 LLM 的服务共用 `platform.gateway.*`）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `GATEWAY_BASE_URL` | `http://localhost:4000/v1` | LiteLLM / OpenAI 兼容 base-url（compose 内为 `http://litellm:4000/v1`） |
| `GATEWAY_API_KEY` | `sk-litellm-master` | 模型网关 key（compose 用 `LITELLM_MASTER_KEY`） |
| `GATEWAY_MODEL` | `chat-default` | 逻辑 chat 模型名（映射到 LiteLLM `model_list`） |

> Java 代码里没有任何 provider `switch` —— provider 路由/failover/模型名映射全在 `deploy/litellm/config.yaml`。`eval-service` 只配 `base-url` + `api-key`（judge/embedding 断言用）。

---

## 6. 集中配置（config-server，可选）

各服务通过 `spring.config.import: "optional:configserver:${CONFIG_SERVER_URI:http://localhost:8888}"` 接入。**刻意用 `optional`：config-server 不可达也不阻断启动**，各服务本地 `${ENV:default}` 兜底继续生效。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `CONFIG_SERVER_URI` | `http://localhost:8888` | 各客户端拉配置的地址 |
| `CONFIG_SERVER_PORT` | `8888` | config-server 监听端口 |
| `SPRING_PROFILES_ACTIVE` | `native` | `native`（读打包进 jar 的 `classpath:/config/`，即开即用）或 `git` |
| `CONFIG_SERVER_NATIVE_SEARCH_LOCATIONS` | `classpath:/config/` | native 后端搜索路径，也可指 `file:./config/` |
| `SPRING_CLOUD_CONFIG_SERVER_GIT_URI` | 示例占位 | 切 git 后端时的配置库地址 |
| `SPRING_CLOUD_CONFIG_SERVER_GIT_LABEL` | `main` | git 分支 |

> 密钥（如 `INTERNAL_JWT_SECRET`）不放 config-server，仍走各服务 `${ENV:default}` / Vault / K8s Secret。config-server 下发的键会被下游本地同名键覆盖（本地优先）。compose 中 config-server 不作为其他服务的 `depends_on`。

---

## 7. Conversation（`app.conversation.*` / `app.chat.*`）

### RAG 上下文增强（默认关）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `CONVERSATION_RAG_ENABLED` | `false` | `/chat` 前是否调 knowledge-service 做 RAG 增强 |
| `KNOWLEDGE_BASE_URL` | `http://localhost:8084` | knowledge-service 地址 |
| `CONVERSATION_RAG_TOP_K` | `5` | 检索条数 |
| `CONVERSATION_RAG_MIN_SCORE` | `0.0` | 检索最低分 |
| `CONVERSATION_RAG_CATEGORY` | 空 | 限定知识分类 |
| `CONVERSATION_RAG_MAX_CONTEXT_CHARS` | `4000` | 注入上下文字符上限 |
| `CONVERSATION_RAG_CONNECT_TIMEOUT` / `_READ_TIMEOUT` | `1s` / `3s` | HTTP 超时 |

### 多轮记忆（Chat Memory，默认开启内存滑窗，零外部依赖）

`/chat` 按 `chatId` 维护多轮上下文。默认开启、内存滑窗；`redis` 档可多副本共享且重启不丢。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `CONVERSATION_MEMORY_STORE` | `in-memory` | `in-memory` 或 `redis`（多副本共享 + 重启不丢） |
| `CONVERSATION_MEMORY_WINDOW_MODE` | `messages` | `messages`（最近 N 条）\| `tokens`（近似 token 窗）\| `summary`（旧消息 LLM 压缩为摘要，长对话保早期要点） |
| `CONVERSATION_MEMORY_MAX_MESSAGES` | `20` | `messages` 档窗口条数 |
| `CONVERSATION_MEMORY_MAX_TOKENS` | `2000` | `tokens` 档窗口 token 上限 |
| `CONVERSATION_MEMORY_TOKEN_MODEL` | `gpt-4o-mini` | `tokens` 档 tokenizer 依据 |
| `CONVERSATION_MEMORY_REDIS_TTL` | `P7D` | `redis` 档会话 TTL（ISO-8601，如 `P7D` / `PT12H`） |

### 长期画像（用户画像记忆，默认关）

跨会话抽取用户持久事实，对话前作为 context 新鲜注入。端点：`POST /chat/memory`、`GET/DELETE /memory/profile`。开启后每轮多一次 temp=0 抽取。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `CONVERSATION_MEMORY_PROFILE_ENABLED` | `false` | 长期画像开关 |
| `CONVERSATION_MEMORY_PROFILE_STORE` | `in-memory` | 画像存储（`in-memory`；redis 变体待补） |
| `CONVERSATION_MEMORY_PROFILE_MAX_ITEMS` | `50` | 每用户画像条目上限 |
| `CONVERSATION_MEMORY_PROFILE_RECALL_LIMIT` | `12` | 每轮注入的画像条数上限 |
| `CONVERSATION_MEMORY_PROFILE_ASYNC` | `true` | 抽取是否异步（不阻塞回复） |

### 对话护栏（PII / 提示注入，默认全关）

移植自单体 `PromptInjection` / `PII`。默认全关、零回归；生产建议开启（安全合规）。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `CONVERSATION_GUARDRAIL_INJECTION_ENABLED` | `false` | 提示注入检测开关 |
| `CONVERSATION_GUARDRAIL_INJECTION_MODE` | `block` | `block`（拒答）\| `sanitize`（剥离控制 token）\| `audit`（仅记日志放行） |
| `CONVERSATION_GUARDRAIL_PII_ENABLED` | `false` | 输出里的 email / 手机号 / 身份证就地脱敏 |

### 意图路由（LLM-as-Router，默认关）

分类 query（RAG / TOOL / CHAT）后分派 —— RAG 走检索、TOOL/CHAT 裸答。端点：`POST /chat/auto`。开启后每轮多一次 temp=0 分类调用。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `CONVERSATION_ROUTER_ENABLED` | `false` | 意图路由开关（驱动 `/chat/auto`） |

### 视觉对话（`/chat/vision`，默认关）

`/chat/vision` 把「图片 + 问题」委托给 vision-service（视觉能力在那边）。默认关。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `CONVERSATION_VISION_ENABLED` | `false` | 视觉对话开关（驱动 `/chat/vision`） |
| `CONVERSATION_VISION_BASE_URL` | `http://localhost:8090` | vision-service 地址 |
| `CONVERSATION_VISION_CONNECT_TIMEOUT` / `_READ_TIMEOUT` | `1s` / `60s` | HTTP 超时 |

### Token 流式（`/chat/stream`）

`platform.gateway.streaming.enabled` 装配 token 级 `StreamingChatModel`，供 SSE 端点 `POST /chat/stream`。默认开；无流式需求可置 `false` 省一个 Bean。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `GATEWAY_STREAMING_ENABLED` | `true` | 流式 `StreamingChatModel` bean 开关（驱动 `/chat/stream`） |

### L1 语义缓存（默认关，pre-RAG、租户桶、问题级）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `CONVERSATION_SEMANTIC_CACHE_ENABLED` | `false` | 开关；关时对 `/chat` 零影响 |
| `CONVERSATION_SEMANTIC_CACHE_THRESHOLD` | `0.95` | 命中相似度阈值 |
| `CONVERSATION_SEMANTIC_CACHE_MAX_ENTRIES` | `1000` | 每租户最大条数 |
| `CONVERSATION_SEMANTIC_CACHE_STORE` | `redis` | `redis`（默认，需可达 redis）或 `in-memory` |
| `CONVERSATION_SEMANTIC_CACHE_EMBEDDING_PROVIDER` | `hash` | 缓存向量 provider（`hash` 等） |
| `CONVERSATION_SEMANTIC_CACHE_EMBEDDING_MODEL` | `embedding-default` | 缓存 embedding 模型名 |
| `CONVERSATION_SEMANTIC_CACHE_REDIS_TTL` | `0s` | redis 档条目 TTL（0 = 不过期） |

### Model Cascade（便宜模型先答 → 低置信升级强模型，默认关）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `CHAT_CASCADE_ENABLED` | `false` | 开关；关时对 `/chat` 零影响 |
| `CHAT_CASCADE_CHEAP_MODEL` | 空 | 便宜模型逻辑名（留空退化为网关默认模型） |
| `CHAT_CASCADE_STRONG_MODEL` | 空 | 强模型逻辑名 |
| `CHAT_CASCADE_CONFIDENCE_THRESHOLD` | `0.6` | 升级阈值 |
| `CHAT_CASCADE_MIN_ANSWER_CHARS` | `8` | 便宜答案最短长度 |
| `CHAT_CASCADE_SELF_RATING` | `false` | 是否让模型自评置信 |

> `token-budget` 默认开启（计数默认持久化到 `redis`，`TOKEN_BUDGET_STORE=redis`；无 redis 时设 `in-memory`，时区 `Asia/Shanghai`）；`cost` 默认关（开启后 `COST_STORE` 同样默认 `redis`）。二者经 `ChatModelListener` 挂载，指标从 `/actuator/{tokenbudget,cost,prometheus}` 暴露。

---

## 8. Knowledge / RAG（`app.rag.*`）

### 向量库与租户隔离

| 变量 | 默认值 | 说明 |
|---|---|---|
| `RAG_VECTOR_STORE_PROVIDER` | `qdrant` | `in-memory` \| `qdrant`（yml/compose 默认）\| `pgvector` \| `milvus` \| `chroma` \| `doris`；真正零依赖单跑需显式设 `in-memory` |
| `RAG_VECTOR_STORE_ISOLATION` | `collection-per-tenant` | `collection-per-tenant`（强隔离，每租户独立 collection）或 `shared`（单 store + metadata filter） |
| `RAG_VECTOR_STORE_BASE_COLLECTION` | `knowledge_segments`（沿用 `QDRANT_COLLECTION_NAME`） | 所有 provider 通用的 collection/表基名，实际按租户拼成 `<base>_<tenant>` |
| `QDRANT_HOST` / `QDRANT_PORT` | `localhost` / `6334` | Qdrant gRPC 地址（compose 内为 `qdrant:6334`） |
| `QDRANT_COLLECTION_NAME` | `knowledge_segments` | collection 基名（`RAG_VECTOR_STORE_BASE_COLLECTION` 缺省时沿用它） |
| `QDRANT_USE_TLS` / `QDRANT_API_KEY` | `false` / 空 | TLS 与鉴权 |
| `QDRANT_TIMEOUT` / `QDRANT_HEALTH_TIMEOUT` | `10s` / `3s` | 超时 |
| `RAG_PGVECTOR_HOST` / `_PORT` / `_DATABASE` | `localhost` / `5432` / `postgres` | pgvector 档 PostgreSQL 连接 |
| `RAG_PGVECTOR_USER` / `_PASSWORD` | `postgres` / `postgres` | pgvector 档账号密码 |
| `RAG_PGVECTOR_USE_INDEX` / `_INDEX_LIST_SIZE` | `true` / `100` | 是否建 IVFFlat 索引及其 list 大小 |
| `RAG_PGVECTOR_SEARCH_MODE` | `VECTOR` | `VECTOR` 或 `HYBRID`（向量 + PG 全文，RRF 融合） |
| `RAG_PGVECTOR_TEXT_SEARCH_CONFIG` / `_RRF_K` | `simple` / `60` | HYBRID 档全文配置与 RRF 常数 |
| `RAG_MILVUS_HOST` / `_PORT` | `localhost` / `19530` | Milvus 连接 |
| `RAG_MILVUS_USERNAME` / `_PASSWORD` | 空 / 空 | Milvus 账号密码（可选） |
| `RAG_MILVUS_INDEX_TYPE` / `_METRIC_TYPE` | `FLAT` / `COSINE` | 索引类型（`FLAT` \| `IVF_FLAT` \| `HNSW`）与距离度量 |
| `RAG_CHROMA_BASE_URL` | `http://localhost:8000` | Chroma HTTP 端点 |
| `RAG_CHROMA_TENANT` / `_DATABASE` | 空 / 空 | Chroma tenant/database（可选） |
| `RAG_DORIS_JDBC_URL` | `jdbc:mysql://localhost:9030/demo` | Doris FE 的 MySQL 协议地址（自研 JDBC 实现，HNSW ANN） |
| `RAG_DORIS_USER` / `_PASSWORD` | `root` / 空 | Doris 账号密码 |
| `RAG_DORIS_METRIC` | `cosine` | 距离度量（`cosine` \| `l2`） |
| `RAG_DORIS_CREATE_TABLE` / `_BUCKETS` | `true` / `4` | 是否自动建表及分桶数 |
| `RAG_REGISTRY_STORE` | `redis` | 文档 registry：`redis`（默认，需可达 redis）或 `in-memory` |

### Embedding

| 变量 | 默认值 | 说明 |
|---|---|---|
| `RAG_EMBEDDING_PROVIDER` | `hash` | `hash`（确定性，本地/单测）、`openai`（走 LiteLLM/OpenAI-compat）或 `ollama` |
| `RAG_EMBEDDING_MODEL` | `embedding-default` | `openai` 档逻辑模型名 |
| `RAG_EMBEDDING_DIMENSIONS` | `0` | 0 = 由 provider 决定 |
| `RAG_EMBEDDING_TIMEOUT` / `_MAX_RETRIES` | `60s` / `3` | 调用超时与重试 |
| `RAG_EMBEDDING_OLLAMA_BASE_URL` | `http://localhost:11434` | `ollama` 档 Ollama 地址 |
| `RAG_EMBEDDING_OLLAMA_MODEL` | `nomic-embed-text` | `ollama` 档 embedding 模型 |

> `openai` 档复用 `GATEWAY_BASE_URL` / `GATEWAY_API_KEY`。application.yml 默认 `hash`（64 维，不真调 embedding）；**compose 默认 `ollama` + `nomic-embed-text`（768 维）**，并注入非对称任务前缀 `RAG_EMBEDDING_QUERY_PREFIX="search_query: "` / `RAG_EMBEDDING_DOCUMENT_PREFIX="search_document: "`（尾部空格必须保留，仅 ollama 生效）。切换 provider = 换向量维度，需删旧 collection 重灌。

### 混合检索与排序（四路 → RRF）

`/rag/query` 并行召回四路——vector（向量）、keyword（内存 BM25 近似）、es（Elasticsearch 真 BM25，见下）、graph（可选）——交 `HybridFusionService` 融合。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `RAG_HYBRID_ENABLED` | `true` | keyword hybrid 检索（默认开） |
| `RAG_HYBRID_TOKENIZER` | `simple` | keyword/graph 分支中文分词：`simple`（yml，bigram 零依赖）\| `hanlp`（compose 默认，更准）；不影响向量与 ES |
| `RAG_FUSION_STRATEGY` | 空 | `rrf` \| `weighted_max`；留空时——ES 参与查询则**有效默认 rrf**，否则 weighted_max |
| `RAG_FUSION_RRF_K` | `60` | RRF 常数 `1/(k+rank)` |
| `RAG_QUERY_TOP_K` / `RAG_QUERY_MIN_SCORE` | `5` / `0.0` | 查询默认条数与最低分 |
| `RAG_RANKING_VECTOR_WEIGHT` | `1.0` | vector 排序权重（weighted_max 下生效） |
| `RAG_RANKING_KEYWORD_WEIGHT` | `1.0` | keyword 排序权重 |
| `RAG_RANKING_ES_WEIGHT` | `1.0` | ES BM25 排序权重 |
| `RAG_RANKING_GRAPH_WEIGHT` | `1.0` | graph 排序权重 |

### ES 全文混排（BM25 + RRF，默认开）

Elasticsearch 真 BM25 全文分支：ingest 时把明文分块同步 upsert 进 `knowledge_segments_text`，查询时 `match+filter` 召回并入四路融合。自建 ES 镜像（`deploy/es`，8.15.x）装 `analysis-smartcn` 中文分析器。knowledge-service 用自研 `EsGateway`，故刻意排除 Spring 的 ES 自动配置/健康指示器（否则 ES 关闭时 readiness DOWN）。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `RAG_ES_ENABLED` | `true` | 总开关；false 时索引器为 Noop、不注册 ES 检索源 |
| `RAG_ES_INDEX_ENABLED` / `RAG_ES_QUERY_ENABLED` | `true` / `true` | 分别门控写索引 / 查询（均受总开关约束，可灰度「只写不查」） |
| `RAG_ES_URIS` | `http://localhost:9200`（compose `http://elasticsearch:9200`） | 逗号分隔多节点 |
| `RAG_ES_INDEX_NAME` | `knowledge_segments_text` | 全文索引名 |
| `RAG_ES_ANALYZER` | `smartcn` | 中文分析器；**不可用 `standard`**（按单字切，BM25 召回弱）；可选 `ik_smart`/`ik_max_word`（需装 analysis-ik） |
| `RAG_ES_USERNAME` / `_PASSWORD` / `RAG_ES_API_KEY` | 空 | 二选一鉴权（ApiKey 优先） |
| `RAG_ES_NORMALIZE_SCORE` | `true` | weighted_max 下归一 BM25（RRF 忽略分值只看名次） |
| `RAG_ES_FAIL_FAST` | `false` | 写失败：false=best-effort（不阻断 ingest）/ true=让上传失败 |
| `RAG_ES_CONNECT_TIMEOUT_MS` / `_SOCKET_TIMEOUT_MS` | `2000` / `5000` | ES HTTP 超时 |

> 开启 ES 后需重灌历史文档以填充 ES 索引（registry/向量库不受影响）：`bash deploy/seed-kb.sh --purge`。

### 公共/共享知识库

| 变量 | 默认值 | 说明 |
|---|---|---|
| `RAG_PUBLIC_ENABLED` | `false` | 开启后各租户查询在隔离查自己分区基础上并入 `__public__` 公共分区（向量/keyword/ES 三路并，graph 不并），命中标 `visibility=public` |
| `RAG_PUBLIC_TENANT_ID` | `__public__` | 保留公共租户分区名（禁止真实租户/注册用户占用） |

> 写共享库 `POST /rag/documents`（`visibility=public|shared`）需 `public-ingest` scope（否则 403）；`GET /rag/config` 回显 `publicKbEnabled` 供前端决定是否展示共享库视图。详见 [RBAC 与公共知识库指南](../平台工程/rbac-and-public-kb.md)。

### RAG 检索增强（rerank / 查询扩展 / 上下文化，默认全关）

三项可选的 LLM 检索增强，默认全关、零回归。开启后共用 `RAG_LLM_*`（缺省复用 `platform.gateway.*`）网关模型。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `RAG_RERANK_ENABLED` | `false` | 召回后重排序开关 |
| `RAG_RERANK_TYPE` | `llm` | `llm`（复用共享 temp=0 ChatModel 打分）或 `jina`（Jina reranker 云 API） |
| `RAG_RERANK_CANDIDATE_MULTIPLIER` | `3` | 送入重排的候选倍数（先多召回再截断到 top-k） |
| `RAG_RERANK_JINA_MODEL` | `jina-reranker-v2-base-multilingual` | `jina` 档 reranker 模型（配合 `JINA_API_KEY`） |
| `RAG_QUERY_EXPANSION_ENABLED` | `false` | 查询扩展开关：把 1 query 扩成 N 变体多路召回 |
| `RAG_QUERY_EXPANSION_MAX_VARIANTS` | `4` | 扩展变体上限 |
| `RAG_CONTEXTUAL_ENABLED` | `false` | Contextual Retrieval：入库时逐 chunk 加文档级上下文前缀再嵌入（每 chunk 一次 LLM 调用） |
| `RAG_CONTEXTUAL_MAX_DOC_CHARS` | `8000` | 生成上下文前缀时读取的文档字符上限 |

> keyword hybrid 检索的中文分词可切 `RAG_HYBRID_TOKENIZER=hanlp`（默认 `simple` 零依赖；`hanlp` 更准，需 HanLP 词典，随 knowledge 依赖引入）。

### GraphRAG（默认关，确定性三元组抽取）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `RAG_GRAPH_ENABLED` | `true` | GraphRAG 抽取与 `/rag/graph/**` 接口开关（yml/compose 默认开） |
| `RAG_GRAPH_STORE` | `jdbc` | `in-memory` 或 `jdbc`（MySQL `knowledge_graph`，默认 jdbc） |
| `RAG_GRAPH_INCLUDE_IN_QUERY` | 跟随 `RAG_GRAPH_ENABLED` | graph hit 是否融合进 `/rag/query` |
| `RAG_GRAPH_QUERY_TOP_K` | `20` | graph 查询返回上限 |
| `RAG_GRAPH_MAX_HOPS` | `2` | 邻居扩展跳数 |
| `RAG_GRAPH_MAX_TRIPLES` / `_PER_CHUNK` | `20` / `12` | 三元组上限 |
| `RAG_GRAPH_RELATION_WHITELIST` | 空 | 关系白名单，逗号分隔（如 `隶属于,使用`） |
| `RAG_GRAPH_ALIASES` | 空 | 实体别名映射（如 `张三经理=张三`） |
| `RAG_GRAPH_ASYNC` | `false` | 抽取是否异步 |
| `RAG_GRAPH_DB_URL` / `_USER` / `_PASSWORD` | MySQL `knowledge_graph` 库 / `root` / 空 | `jdbc` 档数据源 |

> 无 Flyway/Liquibase：JDBC store 靠 `Jdbc*Store` 类里的 `CREATE TABLE IF NOT EXISTS` 自建表。

### 图片多模态 embedding（CLIP，`RAG_MULTIMODAL_*`，默认关）

原生 CLIP / jina-clip 多模态 embedding：图片直接向量化，存入**独立的 image collection**（基名 `knowledge_images`，
每租户 `knowledge_images_<tenant>`，与文本集合 `knowledge_segments` 物理/维度隔离）。**默认关闭**，此时上传图片会返回
明确 400（提示需开启此开关），不再静默转文字。

> ⚠️ 破坏性变更：旧的「图 → 文字（caption/OCR）」路径（`RAG_IMAGE_TEXT_*` / `ImageTextProvider`）已整体移除，
> 上传图片不再接受 `caption` / `ocrText` 字段。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `RAG_MULTIMODAL_ENABLED` | `false` | 图片多模态开关；关闭时上传图片返回 400 |
| `RAG_MULTIMODAL_BASE_URL` | `http://localhost:8000/v1` | OpenAI 兼容 `/embeddings` 端点（指向 vLLM/TEI/云 jina） |
| `RAG_MULTIMODAL_API_KEY` | 空 | 端点鉴权 key（可选） |
| `RAG_MULTIMODAL_MODEL` | `jinaai/jina-clip-v2` | 多模态 embedding 模型 |
| `RAG_MULTIMODAL_DIMENSION` | `1024` | 图片向量维度 |
| `RAG_MULTIMODAL_BASE_COLLECTION` | `knowledge_images` | 图片 collection 基名（按租户拼 `<base>_<tenant>`） |
| `RAG_MULTIMODAL_IMAGE_INPUT_FORMAT` | `data-uri` | 传给端点的图片格式（`data-uri` \| `base64`） |
| `RAG_MULTIMODAL_TIMEOUT_SECONDS` / `_MAX_RETRIES` | `60` / `2` | 调用超时与重试 |
| `RAG_MULTIMODAL_MAX_IMAGE_BYTES` | `10485760`（10MB） | 单图字节上限 |
| `RAG_MULTIMODAL_TOP_K` / `_MIN_SCORE` | `5` / `0.0` | `/rag/image-search` 默认条数与最低分 |

---

## 9. Agent（`app.agent.*`）

### 核心与工具开关

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AGENT_ENABLED` | `true` | Agent 服务总开关 |
| `AGENT_MAX_STEPS` | `8` | ReAct 最大步数 |
| `AGENT_MAX_WALL_CLOCK_MS` / `AGENT_MAX_TOKENS` | `0` / `0` | 0 = 不限 |
| `AGENT_ALLOW_DELEGATION` / `AGENT_MAX_DEPTH` | `true` / `1` | 子 Agent 委派与深度 |
| `AGENT_ANALYTICS_ENABLED` | `true` | 是否允许 `analytics_sql` 动作 |
| `AGENT_RAG_TOP_K` / `AGENT_RAG_CATEGORY` | `5` / 空 | `rag_search` 参数 |
| `KNOWLEDGE_BASE_URL` / `ANALYTICS_BASE_URL` | `:8084` / `:8083` | 下游动作目标 |

### code-exec（默认关，**非强隔离沙箱**，不可信输入务必保持关）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AGENT_CODE_EXEC_ENABLED` | `false` | 开关 |
| `AGENT_CODE_EXEC_SANDBOX` | `subprocess` | 执行方式 |
| `AGENT_CODE_EXEC_TIMEOUT_MS` | `3000` | 超时 |
| `AGENT_CODE_EXEC_MAX_OUTPUT_CHARS` / `_MAX_SOURCE_CHARS` | `2000` / `4000` | 输出/源码截断 |
| `AGENT_CODE_EXEC_BLOCK_UNSAFE_APIS` / `_MAX_HEAP_MB` | `true` / `64` | denylist 与堆上限 |

### MCP client（默认关）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AGENT_MCP_ENABLED` | `false` | `mcp_call` 动作开关 |
| `AGENT_MCP_TRANSPORT` | `stdio` | `stdio` 或 `http`（compose 覆盖为 `http`） |
| `AGENT_MCP_HTTP_URL` | `http://localhost:3001/mcp` | http transport 端点 |
| `AGENT_MCP_STDIO_COMMAND` | 空 | stdio transport 启动命令 |

### browser-use + vision（默认关）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AGENT_BROWSER_ENABLED` | `false` | Playwright 浏览器动作（`browser_open/click/type/screenshot`）；首次需装 chromium 二进制 |
| `AGENT_VISION_ENABLED` | `false` | 视觉后端；`browser_see` 双门控在 browser + vision 同时 true |
| `VISION_BASE_URL` | `http://localhost:8090` | vision-service 地址 |

### DAG 重规划（Critic/Replanner，默认关）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AGENT_DAG_MAX_TASKS` | `6` | 自动规划 DAG 最大子任务数 |
| `AGENT_DAG_REPLAN_ENABLED` | `false` | 质量闭环开关 |
| `AGENT_DAG_REPLAN_THRESHOLD` | `0.75` | 综合分阈值 |
| `AGENT_DAG_REPLAN_MAX_REPLANS` | `1` | 最大重规划轮数 |

### Reflexion / Voting / Prompt Chaining

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AGENT_REFLEXION_THRESHOLD` / `AGENT_REFLEXION_MAX_ATTEMPTS` | `0.75` / `2` | 单答案自省环（`POST /agent/reflexive`） |
| `AGENT_VOTING_N` / `AGENT_VOTING_STRATEGY` / `AGENT_VOTING_MIN_AGREEMENT` | `3` / `majority` / `0.5` | 并行投票（`POST /agent/vote`；`majority` 仅适合离散题，自由文本用 `synthesis`） |
| `app.agent.chaining.steps` | `[]`（属性） | Prompt Chaining（`POST /agent/chain`）；steps 为空则端点返回 400 |

### 异步任务与 webhook

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AGENT_TASK_WEBHOOK_ENABLED` | `true` | 终态本地 webhook 投递 |
| `AGENT_TASK_WEBHOOK_MAX_ATTEMPTS` / `_BACKOFF` | `3` / `250ms` | 重投参数 |
| `AGENT_TASK_WEBHOOK_CONNECT_TIMEOUT` / `_READ_TIMEOUT` | `1s` / `3s` | 超时 |
| `AGENT_ASYNC_EXTERNAL_ENABLED` | `false` | 是否镜像任务到 async-task-service |
| `AGENT_ASYNC_EXTERNAL_AUTHORITATIVE` | `false` | 是否以 async-task-service 为权威存储 |
| `AGENT_ASYNC_EXTERNAL_MIRROR_WEBHOOK` | `false` | 权威档下是否把 webhook 交中心投递（避免重复回调） |
| `AGENT_ASYNC_WORKER_ID` / `AGENT_ASYNC_LEASE_SECONDS` | `agent-service` / `300` | worker 认领 lease |
| `ASYNC_TASK_BASE_URL` | `http://localhost:8086` | async-task-service 地址 |

---

## 10. Async Task（`app.async-task.*`）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `ASYNC_TASK_STORE` | `in-memory` | `in-memory` 或 `jdbc`（自动建 `ASYNC_TASK` / `ASYNC_TASK_WEBHOOK_OUTBOX` 表） |
| `ASYNC_TASK_DB_URL` / `_USER` / `_PASSWORD` | MySQL `async_task` 库 / `root` / 空 | `jdbc` 档数据源 |
| `ASYNC_TASK_TTL` | `PT24H` | 任务保留时长 |
| `app.async-task.webhook.transport` | `http`（属性） | `http`（HTTP outbox 直投）或 `kafka`（改发 `platform.asynctask.lifecycle` 事件，由 channel-service 消费回推；HTTP 通道自动让位） |
| `ASYNC_TASK_WEBHOOK_ENABLED` | `true` | 终态 webhook |
| `ASYNC_TASK_WEBHOOK_MAX_ATTEMPTS` / `_BACKOFF` | `3` / `250ms` | 重投参数 |
| `ASYNC_TASK_WEBHOOK_POLL_INTERVAL_MS` / `_BATCH_SIZE` | `30000` / `50` | outbox 调度 |
| `ASYNC_TASK_WEBHOOK_DELIVERED_RETENTION` | `P7D` | delivered outbox 保留期，0 或负值关闭清理 |

> `transport=kafka` 要求 store=jdbc（事务性 outbox）并开启事件总线（见第 13 节）。

---

## 11. Workflow / Analytics

### Workflow（`app.workflow.*`）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `WORKFLOW_ENABLED` | `false`（compose 中设 `true`） | workflow 服务开关 |
| `WORKFLOW_DB_URL` / `_USER` / `_PASSWORD` | MySQL `flowable` 库 / `root` / `root` | Flowable datasource |
| `WORKFLOW_AI_CLIENT_MODE` | `http` | `http`（经 tenant/trace 传播调 conversation-service，推荐 prod）或 `local`（本地 ChatModel 兜底） |
| `CONVERSATION_BASE_URL` | `http://localhost:8081` | `http` 档 conversation-service 地址 |
| `WORKFLOW_TERMINAL_NOTIFICATION_MODE` | `local` | `local`（本地 `WF_OUTBOX`）、`async-task`（交 async-task-service webhook outbox）或 `kafka`（发终态事件，由 `WorkflowTerminalEventRelay` 走事件总线） |
| `WORKFLOW_TERMINAL_ASYNC_TASK_KIND` | `workflow.terminal` | async-task 档任务 kind |
| `WORKFLOW_TERMINAL_FALLBACK_LOCAL` | `true` | 非 local 档失败时是否回退本地 outbox |
| `ASYNC_TASK_BASE_URL` | `http://localhost:8086` | async-task 档目标地址 |
| `WORKFLOW_OUTBOX_HMAC_SECRET` | compose 中 `dev-secret-change-me` | outbox 投递签名密钥 |

### Analytics / NL2SQL（`app.nl2sql.*`）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `NL2SQL_ENABLED` | `false`（compose 中设 `true`） | NL2SQL 开关 |
| `NL2SQL_DB_URL` / `NL2SQL_DB_READONLY_URL` | MySQL `nl2sql_demo` 库 | admin / 只读数据源 |
| `NL2SQL_DB_ADMIN_USER` / `_PASSWORD` | `root` / `root` | admin 账号（建库/种子） |
| `NL2SQL_DB_READONLY_USER` / `_PASSWORD` | `nl2sql_ro` / `nl2sql_ro` | 执行 NL2SQL 的只读账号 |
| `NL2SQL_SEED_SCRIPT` | `db/nl2sql-demo.sql` | 种子脚本 |

---

## 12. Channel（`app.channel.*` + Kafka）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `CHANNEL_OUTBOUND_ENABLED` | `false` | 是否真实 POST 出站渠道消息 |
| `CHANNEL_OUTBOUND_SIGNATURE_ENABLED` / `_SECRET` | `false` / 空 | 出站签名 |
| `CHANNEL_INBOUND_SIGNATURE_ENABLED` / `_SECRET` | `false` / 空 | 入站签名校验 |
| `CHANNEL_VOICE_PROVIDER_URL` | 空 | voice 渠道默认 HTTP provider URL（也可由 `metadata.providerUrl` 覆盖） |
| `CHANNEL_EVENTS_ENABLED` | `false` | 是否把 channel 出/入站事件发布到 Kafka |
| `CHANNEL_EVENTS_TOPIC` | `platform.channel.events` | channel event topic |
| `CHANNEL_CONNECT_TIMEOUT` / `_READ_TIMEOUT` | `1s` / `10s` | 出站 HTTP 超时 |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | Kafka broker |
| `CHANNEL_DEDUP_STORE`（→ `platform.eventbus.processed-event-store`） | `memory` | 消费终态/生命周期事件的幂等去重存储：`memory`（重启失忆）或 `jdbc`（跨重启，需 `channel.dedup.datasource.*` 指向 MySQL `channel` 库） |

---

## 13. 事件总线（`platform.eventbus.*`，共享库 `platform-eventbus`）

被 `async-task-service`、`channel-service`、`workflow-service` 依赖。**全部默认关闭 / 内存，保证 dev/test 零外部依赖**。

| 属性 | 默认值 | 说明 |
|---|---|---|
| `platform.eventbus.enabled` | `false` | 总开关；`false` 时走 `NoopEventPublisher`，无任何 Kafka 依赖 |
| `platform.eventbus.processed-event-store` | `memory` | 消费幂等去重：`memory` 或 `jdbc`（跨重启） |
| `platform.eventbus.producer.idempotence` | `true` | 幂等生产者 |
| `platform.eventbus.producer.send-timeout` | `10s` | 同步发布等 broker ack 超时 |
| `platform.eventbus.consumer.concurrency` | `1` | 监听并发度 |
| `platform.eventbus.consumer.retries` / `.retry-backoff-ms` | `3` / `500` | 消费重试（超过进 `<topic>.DLT`） |

> 这些键在 application.yml 中未做 `${ENV}` 包装（channel 的 `processed-event-store` 例外，由 `CHANNEL_DEDUP_STORE` 驱动）。要打开事件总线（例如配合 `async-task webhook.transport=kafka` 或 `workflow terminal-notification.mode=kafka`），需经 config-server 下发或 JVM 参数设置 `platform.eventbus.enabled=true`，并保证 Kafka 可达。

---

## 14. Interop / Eval / Vision / Voice

### Interop（`app.interop.*`）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AGENT_BASE_URL` | `http://localhost:8085` | 代理 agent 能力的目标 |
| `INTEROP_DISCOVERY_ENABLED` | `false` | live 能力发现；关时用静态 registry（零下游依赖） |
| `INTEROP_CAPABILITY_TTL` | `60s` | 能力缓存 TTL |
| `INTEROP_A2A_AGENT_NAME` / `_BASE_URL` / `_VERSION` | `langchain4j-platform` / `http://localhost:8080` / `0.1.0` | A2A agent-card 字段 |

### Eval（`app.eval.*`）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `EVAL_TARGET_BASE_URL` | `http://edge-gateway:8080` | 默认回归目标 |
| `EVAL_API_KEY` / `EVAL_API_KEY_HEADER` | 空 / `X-API-Key` | 调目标时带的 key |
| `EVAL_JUDGE_ENABLED` / `EVAL_JUDGE_MIN_SCORE` | `false` / `0.7` | LLM judge 断言 |
| `EVAL_EMBEDDING_ENABLED` / `EVAL_EMBEDDING_MIN_SCORE` | `false` / `0.75` | 语义相似度断言 |
| `EVAL_GATE_PASS_RATE_TOLERANCE` / `_AVERAGE_SCORE_TOLERANCE` | `0.05` / `0.05` | 双跑门禁容差 |
| `EVAL_GATE_MIN_AGREEMENT` / `EVAL_GATE_RUNS` | `0.6` / `1` | 一致性阈值与每 case 重复次数 |
| `EVAL_BASELINE_DIRECTORY` / `_REPORT_DIRECTORY` / `_SNAPSHOT_DIRECTORY` | 空 | baseline / 报告 / 快照目录 |

### Vision（`app.vision.*`）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `VISION_ENABLED` | `false` | 总开关；关时 `VisionModel` / `VisionController` 全不装配 |
| `VISION_MODEL` | 空 | 视觉逻辑模型名（LiteLLM `model_list` 里的多模态模型）；**开启但留空 → 启动 fail-fast** |
| `VISION_TEMPERATURE` | `0.2` | 看图转写偏确定性 |
| `VISION_MAX_IMAGE_BYTES` | `10485760`（10MB） | 单图字节上限 |
| `VISION_ALLOWED_MIME` | `image/png,image/jpeg,image/webp,image/gif` | 允许的 MIME |
| `VISION_CAPTION_CACHE_SIZE` | `256` | caption 缓存条数（按图内容 SHA-256 去重），0 = 关缓存 |
| `VISION_MAX_UPLOAD` | `12MB` | multipart 上传上限 |

### Voice（`app.voice.*`，:8091，默认关）

语音闭环：ASR（转写）→ 转发 conversation-service `/chat` 取回复 → TTS（合成）。端点：`POST /voice/chat`（multipart audio → 音频/JSON）、`POST /voice/chat/stream`（SSE 流式）、`POST /voice/transcribe`（仅转写）。**总开关默认关 → voice 相关 Bean 全不装配，零依赖、零网络。**

单跑：`mvn -pl voice-service spring-boot:run`（:8091）。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `VOICE_ENABLED` | `false` | 总开关；关时不装配任何 voice Bean |
| `VOICE_PROVIDER` | `openai` | ASR/TTS provider（目前仅 openai 兼容协议） |
| `VOICE_BASE_URL` | `https://api.openai.com/v1` | provider base-url（可指云 OpenAI / Azure / 本地 whisper+tts 网关） |
| `VOICE_API_KEY` | 空（回退 `OPENAI_API_KEY`） | provider 鉴权 key |
| `VOICE_ASR_MODEL` | `whisper-1` | 语音转写模型 |
| `VOICE_TTS_MODEL` | `tts-1` | 语音合成模型 |
| `VOICE_TTS_VOICE` | `alloy` | TTS 音色 |
| `VOICE_TTS_FORMAT` | `mp3` | TTS 输出音频格式 |
| `VOICE_LANGUAGE` | 空 | ASR 语言提示（留空自动识别） |
| `VOICE_TIMEOUT_SECONDS` | `30` | provider 调用超时（秒） |
| `VOICE_STREAM_MIN_CHARS` | `8` | 流式档单句最小字符数（攒够再合成，`/voice/chat/stream`） |
| `VOICE_MAX_AUDIO_BYTES` | `26214400`（25MB） | 单条音频字节上限 |
| `VOICE_MAX_UPLOAD` | `25MB` | multipart 上传上限（含请求体） |
| `VOICE_CONVERSATION_BASE_URL` | `http://localhost:8081` | 「大脑」= conversation-service 地址（转写文本经此对话取回复） |
| `VOICE_CONVERSATION_CONNECT_TIMEOUT` / `_READ_TIMEOUT` | `1s` / `60s` | 调 conversation-service 的 HTTP 超时 |

---

## 15. 健康检查与可观测

各服务暴露 Spring Boot actuator health（`/actuator/health`，直连服务端口）：

```bash
curl -s http://localhost:8081/actuator/health   # conversation
curl -s http://localhost:8084/actuator/health   # knowledge
curl -s http://localhost:8086/actuator/health   # async-task
```

- `edge-gateway` 额外暴露 `gateway` 端点；`conversation` / `agent` / `vision` 额外暴露 `prometheus,tokenbudget,cost`；多数服务暴露 `prometheus`。
- traceId 由 `platform-observability` 的 `TraceIdFilter` 生成并跨服务透传。

---

## 16. 常见排障

### 下游服务返回 401

绕过 `edge-gateway` 直连服务但没带 `X-Internal-Token`。调试业务 API 优先从 `localhost:8080` 走。若确需直连，可依赖下游的 `allow-api-key-fallback: true` + `X-Api-Key`（生产建议关闭该回退）。

### 内部 JWT 校验失败 / 启动报密钥太短

- `INTERNAL_JWT_SECRET` 必须 ≥32 字节，且 edge-gateway 与所有下游一致。
- 若切了 `platform.security.jwt.algorithm=RS256`，确认 edge-gateway 配了 `private-key`、所有下游配了对应 `public-key`（PEM 或纯 base64）。

### RAG 查询没有结果

- 上传与查询用同一租户 key、同一 `category`。
- `RAG_VECTOR_STORE_PROVIDER`（`in-memory` \| `qdrant` \| `pgvector` \| `milvus` \| `chroma` \| `doris`）是否符合预期；对应 provider 档确认其连接项（如 Qdrant 的 `QDRANT_HOST` / `QDRANT_PORT` / collection）。
- `collection-per-tenant` 隔离下，跨租户不可见属正常。

### GraphRAG 没有图命中

- `RAG_GRAPH_ENABLED=true`；文档含 `subject|relation|object` 格式（换行或分号分隔多条）。
- 想让 `/rag/query` 混入 graph hit 还需 `RAG_GRAPH_INCLUDE_IN_QUERY=true`。
- 查询能否被实体 linker 链到图谱实体（必要时配 `RAG_GRAPH_ALIASES`）。

### Agent 异步任务没有 webhook

- `webhookUrl` 是否传入；`AGENT_TASK_WEBHOOK_ENABLED` / `ASYNC_TASK_WEBHOOK_ENABLED` 是否开。
- authoritative 档下若期望中心投递，需 `AGENT_ASYNC_EXTERNAL_MIRROR_WEBHOOK=true`，否则 agent 本地 notifier 跳过、中心也不投。

### Kafka 事件不流转（channel 收不到 / 终态事件不发）

- 生产端 `platform.eventbus.enabled=true` 且 Kafka 可达（`KAFKA_BOOTSTRAP_SERVERS`）。
- `async-task webhook.transport=kafka` 要求 `ASYNC_TASK_STORE=jdbc`（事务性 outbox）。
- 跨重启去重需消费端 `processed-event-store=jdbc`（channel 用 `CHANNEL_DEDUP_STORE=jdbc` + `channel.dedup.datasource.*`）。

### Vision 启动即失败

`VISION_ENABLED=true` 但 `VISION_MODEL` 留空会 fail-fast（刻意不静默降级）。配一个 LiteLLM `model_list` 里的多模态模型名。

### config-server 不可达

不影响启动 —— `spring.config.import` 用了 `optional:`，各服务 `${ENV:default}` 兜底继续生效。仅集中下发的非密文项会缺失。

### knowledge-service 起不来 / readiness 卡住

- compose 里 knowledge-service 对 `elasticsearch: condition: service_healthy` 是**硬依赖**——ES 未就绪就不启动（首次拉 ES 镜像 + 健康探针需等一会）。用 `docker compose logs -f elasticsearch` 看 ES 是否 green。
- 单跑（非 compose）时 knowledge-service application.yml 默认期望 qdrant/redis/mysql/ES；要零依赖单跑，显式 `RAG_VECTOR_STORE_PROVIDER=in-memory RAG_REGISTRY_STORE=in-memory RAG_GRAPH_STORE=in-memory RAG_ES_ENABLED=false`。

### ES 检索没有 `es`/`hybrid` 命中

- `RAG_ES_ENABLED=true` 且 `RAG_ES_QUERY_ENABLED=true`；ES 可达（`RAG_ES_URIS`）。
- 历史文档需在 ES 开启后重灌才会进 `knowledge_segments_text`（`bash deploy/seed-kb.sh --purge`）。
- 分析器须 `smartcn`（中文），用 `standard` 会按单字切、召回弱。

### 会话 Bearer 401 / 登录后仍鉴权失败

- `SESSION_JWT_SECRET` 必须在 auth-service（签发）与 edge-gateway（验签）一致且 ≥32 字节。
- `/auth/me` 需带 `Authorization: Bearer`（不在免鉴权放行清单）；会话 accessToken 默认 60min 过期，需 `POST /auth/refresh` 轮转。

### 跨域直调网关登录 cookie 不下发 / 刷新失败

- 浏览器从异源直调网关时，刷新 cookie 需 `AUTH_COOKIE_SAME_SITE=None` + `AUTH_COOKIE_SECURE=true`（HTTPS）；同源 nginx 反代 `/auth/*` 可用默认 `Lax`/`false`。
- 网关 CORS 需放行前端 origin（`GATEWAY_CORS_ORIGINS`）且 `allowCredentials=true`（已默认）。

### RBAC 管理面 403 / 428 / 503

- 403：调用方缺 `role-admin` scope（仅 `admin` 角色经登录会话获得，api-key 不含）。
- 428/412：写端点需带 `If-Match`（先 GET 拿 `ETag`）；版本冲突返 412。
- 503：`AUTH_RBAC_ADMIN_WRITES_ENABLED=false`（写被二级开关关闭）。
