# 候选方案对比、风险评审与评分

本文合并 `risk-reviewer` 与 `plan-judge` 两个视角：先主动寻找失败场景，再用统一量表评分。评分不是先选结论再补理由。

## 1. 方案速览

| 方案 | 租户 virtual-key 实现 | 长期 key 所在 | 主要新增状态 | 一句话特征 |
| --- | --- | --- | --- | --- |
| A 请求级包装 + 动态 header | 单 OpenAI delegate，每请求 custom Authorization | 应用 Secret/配置 | LiteLLM PG；无应用新库 | 最小改动、共享连接池 |
| B 每租户 delegate 池 | 每 tenant/model/temp 创建固定 apiKey delegate | 应用 Secret/配置 + JVM cache | LiteLLM PG + delegate registry | 规避动态 header，资源/轮换复杂 |
| C LiteLLM 自定义鉴权 | Java 发短时签名身份，LiteLLM plugin 映射策略 | LiteLLM/中央密钥系统 | PG + Python plugin + token 信任 | 长期 key 不落应用，插件耦合高 |
| D 控制平面闭环 | 平台自动 provisioning/distribution/reconcile | Secret store + 平台控制库 | PG + 应用领域库 + outbox/job | 最完整，也最重 |

四个方案的 PostgreSQL、Redis cache、正式 fallback、OTel callback 目标相同；差异在身份、key 生命周期和治理自动化边界。

## 2. 风险评审（risk-reviewer）

### 2.1 兼容性

#### 方案 A

- 默认 `none` 若仍经过 wrapper，必须覆盖 `chat(ChatRequest, ChatRequestOptions)`/流式等价方法并直接调 delegate；否则 LangChain4j 默认 listeners 会执行两次。
- `GatewayChatModelFactory` 的所有 build 路径都要包装；只改 Spring 全局 bean 会漏 `buildDeterministic` 和 cascade。
- `platform-security` 只能 optional；普通传递依赖会改变 `eval-service` 的 filter/Redis 自动装配。
- 动态 `Authorization` 依赖 1.13.1 custom headers 后写覆盖。已通过本地字节码核对，但升级仍须契约测试。
- 新增响应缓存会改变非确定温度请求的行为：相同 prompt 在 TTL 内返回第一次结果，这是需求内变更但必须告知。

#### 方案 B

- 固定 delegate 的 OpenAI 行为最接近当前工厂，header 兼容风险较低。
- 但 judge/cascade 会产生多种 model+temperature，每租户缓存键稍有遗漏就会串模型或参数。
- model 对象是否持有可关闭资源没有在仓库建立生命周期契约，淘汰兼容性不明确。

#### 方案 C

- 自定义 Python auth callback 与 LiteLLM 内部扩展 API强耦合；升级风险最高之一。
- 需要 platform-security 新 token audience，可能影响所有服务的 JWT 配置。
- 官方镜像变为自建镜像，供应链和 CVE patch 流程变化。

#### 方案 D

- 同时依赖 LiteLLM 管理 API、Secret connector、应用 DB、调度/outbox，跨模块兼容面最大。
- 现有仓库没有专用控制面服务，放在哪个服务都会改变职责边界。

### 2.2 事务、一致性与数据迁移

共同风险：

- LiteLLM 首次连接空 PostgreSQL 是从“无数据库”到“第三方 schema”的迁移；不能回填 Redis token/USD 当成历史 provider spend。
- LiteLLM schema 由镜像版本管理。浮动 `main-stable` 会让迁移不可重复，必须先 pin tag/digest。
- spend DB 与 `platform-metering` Redis 永不在同一事务。强求相等会把 cache/retry/fallback 的正常差异当故障。
- 数据库连接丢失时 LiteLLM 是否拒绝或退化，必须按固定镜像实测；资金硬保底场景应倾向 fail-closed。

方案差异：

- A/B 没有应用新表，迁移最简单；key 创建是独立运维事务。
- C 若请求时自动创建映射会遭遇重复创建竞态；应预配置，削弱其自动化价值。
- D 必须用 outbox/saga 连接平台 policy commit 与 LiteLLM provisioning，存在部分成功、补偿和重放。

### 2.3 并发与幂等

共同失败场景：

- 多个并发请求可同时通过预算检查并产生有限超额；必须设置 `max_parallel_requests`/TPM/RPM 限制并把可接受超额写进运营阈值，不能宣称绝对零超额。
- Java `maxRetries=3` × LiteLLM `num_retries=2` × fallback 可能造成长尾和重复 provider 尝试。应收敛一层重试并用总时限测试。
- cache miss 风暴会并发击穿 provider；本次至少监控，不臆造 LiteLLM 已有 single-flight。

方案差异：

- A 的 header supplier 必须每次新建 Map、只读当前 ThreadLocal；共享可变字段会发生跨租户 key 泄漏。
- B 的 `computeIfAbsent` 构造失败和 key rotation invalidation 是核心竞态；高基数会耗尽连接/线程资源。
- C 的 jti/nonce 重放控制与 token 签发时钟偏差新增并发状态。
- D 的 provisioning/reconcile 天生需要可重入 command、稳定 external id 和重复事件消歧。

### 2.4 性能

- PostgreSQL spend 写、virtual-key auth 校验和 OTel callback 都增加网关开销；Redis auth cache可降低 DB 读，但不能掩盖 DB 写延迟。
- Redis response cache降低 provider 延迟/成本，但和应用 token listener 的响应 usage 口径可能不一致。
- A 每请求有一次 tenant lookup、参数复制、小 Map 构造，成本低且连接池共享。
- B 热路径 lookup 快，但模型/连接池数量约为 tenant × model variant × sync/stream，最坏内存明显更高。
- C 多一次 token 验签/策略映射；Redis 命中时可控，miss 时查 DB。
- D runtime 可做得与 A 相近，但 control-plane 后台任务和对账成本高。

### 2.5 安全

共同风险：

- `/ui` 和 master key 是高权限管理面，不能借 Compose 端口直接公网暴露。
- PostgreSQL/UI/key 密码不可提交；错误与 debug log 不得输出 Authorization。
- Redis response cache存有模型输出，复用实例需要访问隔离、TTL、备份/清理策略；不能将 cache namespace 当真正 ACL。
- `user` 必须覆盖调用方字段，防止 spend 归因伪造。
- virtual-key 缺失必须 fail-closed，禁止退 master key。
- OTel 禁止默认采集 prompt/response body；tenant/key 高基数字段要限制。

方案差异：

- A/B 把长期 key 下发到应用，泄漏面较大；A 不把 key缓存进模型对象，轮换较快。
- B 旧 key 留在 delegate 内存，轮换/heap dump 风险更高。
- C 不下发长期 key，安全边界最好，但自定义 auth 一旦验签错误影响全平台。
- D 可接专业 Secret store，最终最好；控制面本身成为高价值攻击目标。

### 2.6 Redis 共享故障域

- 当前 Redis 已承载限流、token、成本、语义缓存、注册表和记忆。LiteLLM response cache加入后，容量/CPU/网络争抢可能影响更关键的限流和计数。
- 逻辑 DB/namespace 只隔离 key，不隔离内存和 CPU。
- 如果设置全局 eviction，可能把应用 token budget/rate-limit key 淘汰；如果不设置，缓存可能撑爆实例。
- 本地 Compose 用短 TTL + namespace/逻辑 DB。生产必须设置容量告警；资源不可接受时拆分物理 Redis，但这是部署拓扑演进，不改逻辑接口。

### 2.7 灰度与回滚

#### 方案 A

1. 先上线固定 LiteLLM + PostgreSQL，应用仍 `none`；确认 spend/UI。
2. 开 cache/OTel/fallback，每项独立观察。
3. 单服务/单租户切 `user`。
4. 预创建 key 并对少量租户切 `virtual-key`。
5. 回滚只需把属性改回 `none`；数据库和 key保留，不删除卷。

风险：切回 none 会绕过 per-tenant virtual-key 限额，只剩全局 hard guard；因此回滚前必须确认全局上限已配置。

#### 方案 B

与 A 相同，但从 virtual-key 回滚/轮换还要清 delegate cache或滚动重启。旧连接在流式请求结束前仍可能存在。

#### 方案 C

需要 Java 与 LiteLLM plugin 双端兼容窗口；回滚 plugin 不能先于应用，否则所有新 header 拒绝。需 dual auth mode，复杂且容易误配成 fail-open。

#### 方案 D

需要控制面 schema backward compatibility、outbox drain、job 停止和 Secret 回滚。外部 key 已创建不可简单数据库 rollback；必须状态机补偿。

## 3. 统一评分（plan-judge）

评分规则：每项 1–5，**5 最优**。对“复杂度、测试难度、回滚成本”按“低复杂度/易测试/低回滚成本得高分”计。权重反映本次目标是可执行、低回归的首轮架构改造。

| 维度 | 权重 | A | B | C | D |
| --- | ---: | ---: | ---: | ---: | ---: |
| 正确性 | 25% | 4.5 | 4.4 | 4.2 | 4.7 |
| 改动风险 | 20% | 4.3 | 3.4 | 2.1 | 1.8 |
| 复杂度（低为优） | 15% | 4.2 | 3.0 | 1.8 | 1.2 |
| 可维护性 | 15% | 4.1 | 3.0 | 2.6 | 3.0 |
| 扩展性 | 10% | 3.8 | 3.7 | 4.5 | 5.0 |
| 测试难度（易为优） | 10% | 4.0 | 3.1 | 2.0 | 1.4 |
| 回滚成本（低为优） | 5% | 4.5 | 3.4 | 2.0 | 1.3 |
| **加权总分 / 5** | **100%** | **4.24** | **3.60** | **2.70** | **2.72** |

### 3.1 评分依据与反确认偏差说明

- A 没有得满分：长期 key分发、shared Redis、custom header 升级契约和 trace 仅接当前请求 span 都是真弱点。
- B 的固定 key client 在协议纯度上略优于 A，因此正确性接近；但当前工厂有多 model/temperature 变体，资源与失效复杂性是真实扣分，不是为了衬托 A。
- C 的安全/扩展性好，但本轮需要从零建立 Python plugin 和 token audience；低分来自范围与升级耦合，而非否认长期价值。
- D 的目标态正确性/扩展性最高；总分低是首轮时机和工程量问题。若租户达到大规模、手工轮换成为事故源，权重会变化，D 可能胜出。

## 4. 综合结论

选择方案 A 作为主体，但吸收其他方案的优点：

- 吸收 B：把 virtual-key 获取抽成 resolver SPI，并保留“若 custom header 契约测试失败，切固定 delegate”回退路径。
- 吸收 C：virtual-key 模式永远 fail-closed、key 不写日志；把短时签名身份作为未来替代长期 key分发的演进项。
- 吸收 D：key alias/version、轮换顺序、对账差异指标先写进运维契约；本轮不建控制面和新业务表。

最终仍是 A，因为它在当前仓库的 `GatewayChatModelFactory` 收口、唯一 ChatModel bean、Listener SPI 和默认 none 约束下，能以最少新状态实现完整需求。
