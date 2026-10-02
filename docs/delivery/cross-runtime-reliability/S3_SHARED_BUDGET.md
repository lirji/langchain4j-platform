# 跨语言租户日预算

## 所有权与兼容

Java BudgetLedger 是预算预留/结算权威；native gateway factory 与 Python 模型工厂都在真实调用前准入。
Java 通过模型 Decorator SPI 接入，gateway 模块不反向依赖计量实现。主模型、JSON、确定性、cascade、
流式出口统一装饰；开启模式后排除旧 TokenBudgetChatModelListener，避免重复记账。
补齐 workflow/analytics/eval/tax 的计量依赖；其余 conversation/agent/knowledge/vision 已有。
不改变它们既有的内部 JWT 权限配置。Embedding/独立 rerank 的供应商费用仍不属于 Chat token 日预算。

日额度沿用 TokenBudgetProperties 与已有 Redis used key，不引入第二份 Python Redis 权威。
共享同一 Redis/前缀、额度配置与显式日时区是部署前置条件，未配置时区时拒绝启用。
单 JVM 内存实现仅供开发；多副本必须使用 Redis。当前多 key Lua 适用已有单 Redis 主节点，
不宣称兼容 Redis Cluster。保留既有入口预检，模型边界预留负责并发准入。

## 预留与结算

操作绑定 tenant/user/operation/day；日以准入日为准。重复同额度预留返回同一 OPEN 操作，
同键异参数或已结算后重新执行返回冲突。实际 input+output usage 完整才结算，重复相同 usage 不重复计费。
预留在 used+held+request<=budget 时原子准入；结算原子 used+=actual、held-=reserved。
错误、取消、缺少 usage 保持 held，不自动按零消耗释放。未知结果会减少当日可用额度，需要运维核对。
预留/收据保留至准入日后第 3 个午夜；次日使用新的日账本。跨午夜结算不能释放新日额度。

文本按规范化 UTF-8 字节、角色/工具封装余量和显式最大输出预留；图片使用单独 allowance，
不把 base64 字节当作文本 token。默认 output 4096、input 131072 bytes、media allowance 16384。
这是保守准入估算，不能证明每个提供商/视觉模型绝不超估算。若实际 usage 超预留，记录全量，
后续调用按已用量拒绝；不会截断实际账本伪造达标。它控制 tokens/day，不承诺美元成本或提供商账单上限。
Java 模型异常不隐式重试预留；Python shared budget 要求 model max_retries=0。
RPC 只重试相同签名/参数/operation 的预留或结算；不重试模型请求。

## RPC 与凭据

conversation authority 可选提供 POST /internal/metering/reservations、/settlements。
Java 本地消费者直接使用同一 Redis 权威，Python 只消费 RPC，不知道私有 Redis 键结构。
专用 HS256 service JWT：iss/sub=agentscope-platform、aud=platform-metering、kid=metering-v1、
token_use=tenant_budget、act=reserve/settle、tenant/actor_uid、operation、额度/日/usage、jti、iat/exp。
TTL 30s（最多 5s 时钟偏差）；令牌参数必须与 HTTP body 一致。普通用户 JWT 不能结算。
原租户/用户绑定阻止另一用户释放 held；服务凭据只授予这两个预算 RPC，不授予其他业务操作。
独立 >=32-byte secret，只给 authority 和 Python；不复用用户、worker、确认或下游 tool 签名密钥。
网关与其他 Java 服务只需账本访问，不持该 secret。生产使用现有受控 Secret/ESO，仓库保持空占位。

native HTTP 明确返回 429（额度不足）、409（操作冲突）、503（账本故障）；异常正文不对外暴露。
Python 将准入或结算失败转为模型调用错误，取消仍透传，持有的预留不会被吞异常释放。
planner、reviewer、analytics planner、text generator 的实际调用局部绑定可信 context，退出时还原。
AgentScope ChatResponse 的 finished_reason 用 DictMixin get 读取，避免 dataclass 默认属性掩盖 INTERRUPTED。

## 启用、回滚和证据

Compose 用一个 TOKEN_BUDGET_RESERVATIONS_ENABLED 同时开启 Java 模型准入、authority API 和 Python。
同时注入 TOKEN_BUDGET_SERVICE_SECRET；TOKEN_BUDGET_TIMEZONE 默认 Asia/Shanghai，store=redis。
独立 Python Compose 使用 TOKEN_BUDGET_ENABLED、TOKEN_BUDGET_BASE_URL（宿主地址对应 DOCKER_*）。
Helm 保持三个开关默认 false；启用时同时设置 ConfigMap 的 Java/Python flag 和 conversation env 的 API flag。
未启用时兼容旧行为，旧消费路径不提供严格并发额度保证。

先升级全部 Java 消费者再开关灰度，确认相同 Redis namespace/时区/额度，再开启 Python。
回滚同时关闭三个开关，保留 used/held/收据，不删除账本；旧记账恢复后应核对未决 reservation 与实际费用。
日 token budget / tokenbudget actuator 的 used 快照只显示已结算量；不能把 held 未决当成零消耗。
本地测试不调用真实模型，不部署业务容器或改共享数据。

已验证：内存并发/owner/幂等/未知预留、原生同步/流式准入与捕获身份、JWT RPC 参数/租户/用户绑定；
Redis 7 独立 UUID namespace 下双 ledger 共享额度、20 并发只有 10 个准入、跨午夜与重复结算（4 项）。
Python budget/模型链路聚焦 38 项、全量 492 项通过；Java 全量与部署门禁以 DELIVERY_STATUS 的最新证据为准。
