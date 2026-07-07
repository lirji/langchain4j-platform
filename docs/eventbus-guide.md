# 事件总线与终态可靠投递（EOS）指南

本文档说明 `platform-eventbus` 共享库，以及 workflow / async-task / channel 三方围绕它构建的
**「事务性 outbox + at-least-once relay + 消费侧 eventId 去重」** 终态可靠投递链路。

> 一句话总览：`DB → Kafka → HTTP` 这条跨系统链路，用「写侧事务性 outbox + 投侧至少一次 + 收侧幂等去重」
> 得到**效果等价 exactly-once（effective exactly-once）**，而不是靠纯 Kafka 原生事务。
>
> **默认全关、零外部依赖**：`platform.eventbus.enabled=false`（默认）时只有 `NoopEventPublisher` +
> 内存去重存储，全链无任何 Kafka 依赖，dev/test 正常启动。docker-compose 默认也**不**走 Kafka 档
> （`WORKFLOW_TERMINAL_NOTIFICATION_MODE=async-task`、`ASYNC_TASK_STORE=in-memory`）。

---

## 1. 为什么不用纯 Kafka 事务，而用 outbox + relay + 去重

要投递的终态通知，源头是**数据库里的业务终态**（Flowable 流程实例终态、async-task 任务终态），
最终要落到**外部 HTTP 渠道**（channel-service 回推 Feishu/webhook）。中间跨了 DB、Kafka、HTTP 三个系统。

纯 Kafka 原生事务只能保证 **Kafka 分区之间**、以及「消费—处理—提交 offset」在 Kafka 内部的原子性；
它**无法**把「MySQL 里业务终态已提交」和「事件已进 Kafka」这两个**异构系统**的写操作纳入一个原子单元
（那需要把 Flowable/JDBC 事务与 KafkaTransactionManager 用 `ChainedTransactionManager` 强耦合，运维复杂、
回归面大）。同样，Kafka→HTTP 那一跳落在 Kafka 事务之外，HTTP 侧仍可能重复收到。

因此本平台选择跨系统链路的标准形态，拆成三段各自可靠：

| 段 | 机制 | 保证 |
|---|---|---|
| **写侧**：业务终态 ⇔ 事件行 | 事务性 outbox：事件行与业务终态**写同一个 DB 事务** | 原子——「终态已提交 ⇔ outbox 有 PENDING 行」，消除「终态提交后、通知未落库就崩溃」的丢失窗口 |
| **投侧**：outbox → Kafka | `@Scheduled` relay 扫 PENDING 行，同步等 broker ack 后才标 `DELIVERED`；失败按退避重投、耗尽进 `DEAD` | **至少一次**（at-least-once）——不丢，但可能重复 |
| **收侧**：Kafka → HTTP | 消费者按稳定 `eventId` 去重：**先查 → 处理 → 成功后标记** | **幂等**——重复投递被 `ProcessedEventStore` 拦掉 |

三段叠加 = **effective exactly-once**：写侧不丢、投侧不丢（可能重）、收侧去重把「重」吸收掉。

### 收侧去重的正确顺序（关键修复点）

`ProcessedEventStore` 的语义是「**先查 `isProcessed` → 业务处理 → 成功后 `markProcessed`**」：

- 处理成功前不标记 → 处理抛异常时消息重投会**再次进入**（不丢）；
- 已完成的事件在重投时被 `isProcessed` 检查跳过（去重）；
- 同一 `eventId` 恒落同一分区（key=tenantId 保证同租户有序、单分区消费串行），故「查后标记」无并发竞态。

这修复了「先标记再处理」在瞬时失败时会**丢事件**的旧 bug。

### 稳定 eventId 约定

| 域 | eventId 格式 | 说明 |
|---|---|---|
| workflow 终态 | `workflow:<instanceId>` | 每个流程实例终态唯一 |
| async-task 生命周期 | `asynctask:<taskId>:<status>` | 同一任务同一终态仅一次 |

---

## 2. `platform-eventbus` 共享库

包 `com.lrj.platform.eventbus`，通过 `AutoConfiguration.imports` 自注册（`PlatformEventbusAutoConfiguration`）。

### 2.1 EventPublisher SPI

```java
void publish(String topic, String key, Object payload); // key 约定为 tenantId
```

| 实现 | 装配条件 | 行为 |
|---|---|---|
| `NoopEventPublisher` | **默认** | 只 `debug` 记录，不投递，零 Kafka 依赖 |
| `KafkaEventPublisher` | `platform.eventbus.enabled=true` 且 classpath 有 `KafkaTemplate` | JSON 序列化后 `send(topic, key, json)`，**同步阻塞等 broker ack（acks=all）**后返回；序列化/发送/超时抛 `IllegalStateException` 交由 relay 重投 |

> `KafkaEventPublisher` 的**同步等待 ack** 是刻意设计：供 outbox relay「确认落 broker 再 `markDelivered`」，
> 避免异步 fire-and-forget 把未确认的发送误标已投。超时由 `platform.eventbus.producer.send-timeout`（默认 10s）控制。

### 2.2 ProcessedEventStore（消费幂等去重）

| 实现 | 装配条件 | 存储 |
|---|---|---|
| `InMemoryProcessedEventStore` | **默认** | 进程内，重启失忆 |
| `JdbcProcessedEventStore` | `platform.eventbus.processed-event-store=jdbc` 且 classpath 有 `JdbcTemplate` | MySQL `PROCESSED_EVENT` 表（`CREATE TABLE IF NOT EXISTS` 自建，靠 PK 冲突判重）；**跨重启去重** |

### 2.3 Kafka 生产/消费基础设施（仅 `enabled=true` 时装配）

- **生产者**（`KafkaProducerConfig`）：`enable.idempotence=true`（默认）、`acks=all`、`max.in.flight<=5` 的**幂等生产者**。
  `platform.eventbus.producer.transactional-id-prefix` 非空时才升级为事务性生产者并暴露 `KafkaTransactionManager`
  ——**默认前缀为空 = 仅幂等、不开事务**（事务骨架为端到端 exactly-once 预留，当前链路不依赖它）。
- **消费者**（`KafkaConsumerConfig`）：`ConcurrentKafkaListenerContainerFactory`（bean 名 `eventbusKafkaListenerContainerFactory`）
  + `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`；`isolation.level=read_committed`。
  重试耗尽后自动投递到 `<topic>.DLT`。

### 2.4 配置项（`platform.eventbus.*`）

| 属性 | 默认 | 说明 |
|---|---|---|
| `platform.eventbus.enabled` | `false` | 总开关。false=Noop 零依赖；true 且有 Kafka=启用 `KafkaEventPublisher` + 生产/消费基础设施 |
| `platform.eventbus.processed-event-store` | `memory` | `memory`（内存）\| `jdbc`（`PROCESSED_EVENT` 表跨重启去重） |
| `platform.eventbus.producer.idempotence` | `true` | 幂等生产者 |
| `platform.eventbus.producer.max-in-flight` | `5` | in-flight 上限（幂等要求 ≤5） |
| `platform.eventbus.producer.send-timeout` | `10s` | publish() 等 broker ack 的超时 |
| `platform.eventbus.producer.transactional-id-prefix` | `""`（空） | 非空才开事务性生产者（当前链路不需要） |
| `platform.eventbus.consumer.concurrency` | `1` | 监听容器并发度 |
| `platform.eventbus.consumer.retries` | `3` | 投递失败重试次数（超过进 `.DLT`） |
| `platform.eventbus.consumer.retry-backoff-ms` | `500` | 重试间隔 |
| `platform.eventbus.consumer.group-id` | `channel-service` | 消费组（channel 监听器上 `${platform.eventbus.consumer.group-id:channel-service}`） |

> 在 `channel-service` 的 `application.yml` 中，`processed-event-store` 已绑定为 `${CHANNEL_DEDUP_STORE:memory}`。
> `platform.eventbus.enabled` 各服务 yml 均**未**提供 `${}` 占位，需以 property 或等价 env（松绑定
> `PLATFORM_EVENTBUS_ENABLED=true`）显式开启。

---

## 3. Topic 与死信（DLT）

集中定义在 `platform-protocol` 的 `com.lrj.platform.protocol.event.EventTopics`。主 topic 按 `tenantId` 分区（key=tenantId，保同租户有序），每个域配对一个 `.DLT` 死信。

| 域 | 主 topic | 死信 topic | 事件契约（record） | 状态 |
|---|---|---|---|---|
| 工作流终态 | `platform.workflow.terminal` | `platform.workflow.terminal.DLT` | `WorkflowTerminalMessage` | ✅ 已接线（workflow→channel） |
| 异步任务生命周期 | `platform.asynctask.lifecycle` | `platform.asynctask.lifecycle.DLT` | `AsyncTaskLifecycleMessage` | ✅ 已接线（async-task→channel） |
| 审计事件 | `platform.audit.events` | `platform.audit.events.DLT` | `AuditEventMessage` | ⚠️ 契约已定义，**尚未接线任何 publisher/consumer**（预留） |
| 用量/计费 | `platform.metering.usage` | `platform.metering.usage.DLT` | `UsageEventMessage` | ⚠️ 契约已定义，**尚未接线任何 publisher/consumer**（预留） |

- 死信后缀常量：`EventTopics.DLT_SUFFIX = ".DLT"`，与 `DeadLetterPublishingRecoverer` 默认目的地解析一致。
- 事件契约为**一 topic 一固定类型 + `schemaVersion`**（当前均为 `CURRENT_SCHEMA_VERSION = 1`），避免多态反序列化。
- 审计/用量域按 B1 设计定位为 fire-and-forget（可丢，不进 outbox）；当前仅有契约与 topic 常量，**业务侧未接生产者/消费者**，启用前需自行接线。

---

## 4. 生产侧一：workflow-service 终态事件

### 4.1 链路

1. **写 outbox（Flowable 事务内，原子）**：`refund-approval.bpmn20.xml` 的 BPMN `end` 事件配了
   `<flowable:executionListener event="end" delegateExpression="${workflowTerminalOutboxListener}"/>`。
   `WorkflowTerminalOutboxListener` 随流程到达 end、在 Flowable 引擎命令的**同一事务**内执行；
   `mode=kafka` 时调 `WorkflowTerminalEventOutbox.enqueue(...)` 往 `WF_TERMINAL_EVENT_OUTBOX` 表 INSERT
   （用同一 `workflowDataSource`，经 `DataSourceUtils` 取到线程绑定的同一连接）→ 事件行与 `ACT_*` 终态、
   `WF_REPLY` **同事务提交**。非 kafka 档（local/async-task）直接 no-op。
   > 该监听器 bean 在 `app.workflow.enabled=true` 时**始终装配**（否则流程到 end 解析不到 bean 会失败），
   > 但仅 kafka 档才落库。
2. **relay 到 Kafka（至少一次）**：`WorkflowTerminalEventRelay`（`@Scheduled`，**仅 `mode=kafka` 装配**）
   定时扫 `WF_TERMINAL_EVENT_OUTBOX` 的到期 PENDING 行，重建 `WorkflowTerminalMessage`
   （`reply` 从 `WorkflowReplyStore` 取，`eventId=workflow:<instanceId>`），经 `EventPublisher`
   发往 `platform.workflow.terminal`（key=tenantId），成功标 `DELIVERED`，失败按退避重投、耗尽进 `DEAD`。
3. **kafka 档跳过旧路径**：`WorkflowService.onTerminal` 里 `useKafkaNotification()` 为真时**不**调
   `enqueuePush`（即不写本地 `WF_OUTBOX`、不走 async-task 通知），避免与 Kafka 事件双投。

> `WF_TERMINAL_EVENT_OUTBOX` 与 HTTP-webhook 专用的 `WF_OUTBOX`（`WorkflowOutbox` + `WorkflowOutboxDispatcher`）
> 是**两张独立表**：后者投递目标是 URL、前者是 topic，字段与语义不同，隔离以不污染已验证的 HTTP 路径。

### 4.2 终态通知模式

`app.workflow.terminal-notification.mode`（env `WORKFLOW_TERMINAL_NOTIFICATION_MODE`，默认 `local`）：

| 模式 | 行为 |
|---|---|
| `local`（默认） | 终态入本地 `WF_OUTBOX`，`WorkflowOutboxDispatcher` HTTP 重投到实例 `webhookUrl` |
| `async-task` | 终态通知交 async-task 中心 outbox 投递；失败可回退本地 `WF_OUTBOX`（`fallback-to-local-outbox`，默认 true） |
| `kafka` | 走本文的事务性 outbox + relay + Kafka 事件（4.1） |

relay 复用 `app.workflow.outbox.*` 的调度/退避参数：`poll-interval-ms`（默认 30000）、`batch-size`（默认 50）、
`max-attempts`（默认 6）、`base-backoff-ms`（默认 5000）。

---

## 5. 生产侧二：async-task-service 生命周期事件

与 workflow 对称，收口了 async-task 版的同类两段式缺口（原先是 `store.update` 提交**之后**的 `@EventListener` 直发，kafka 档无 DB 兜底）。

### 5.1 链路

1. **写 outbox（JDBC 事务内，原子）**：`JdbcAsyncTaskStore.update` 在「本次由非终态转为终态」时，于**同一 JDBC 事务**内
   调 `AsyncTaskLifecycleOutbox.enqueue(...)` 往 `ASYNC_TASK_LIFECYCLE_OUTBOX` 表写一条**已序列化的
   `AsyncTaskLifecycleMessage` 快照**（`PAYLOAD_JSON`，终态时自足）。「终态提交 ⇔ 事件行已写」原子成立。
2. **relay 到 Kafka（至少一次）**：`AsyncTaskLifecycleRelay`（`@Scheduled`）扫到期 PENDING 行，反序列化快照，
   经 `EventPublisher` 发往 `platform.asynctask.lifecycle`（key=tenantId，`eventId=asynctask:<taskId>:<status>`），
   成功标 `DELIVERED`，失败退避重投、耗尽 `DEAD`。
3. **提交后直发让位**：`AsyncTaskKafkaNotifier`（`@EventListener`，transport=kafka 时装配）在检测到存在
   `AsyncTaskLifecycleOutbox`（即 store=jdbc）时**跳过**，避免与 outbox+relay 双投；仅 store=memory 的 dev/test
   才退化为提交后 best-effort 直发。

> outbox + relay bean 仅在 `store=jdbc` **且** `transport=kafka` 时装配（`AsyncTaskJdbcConfig`）。
> store=memory 时没有事务性 outbox，无法保证原子性——**生产 Kafka 档必须 `ASYNC_TASK_STORE=jdbc`**。

### 5.2 传输模式

`app.async-task.webhook.transport`（默认 `http`）：

| 模式 | 行为 |
|---|---|
| `http`（默认） | 终态走既有 webhook outbox/notifier，HTTP POST 直投 `webhookUrl` |
| `kafka` | 终态改发 `platform.asynctask.lifecycle` 事件，由 channel-service 消费回推；既有 HTTP 通道自动让位 |

> `transport` 在 `application.yml` 中**未提供 `${}` env 占位**（默认来自 `AsyncTaskWebhookProperties.transport="http"`），
> 需以 property `app.async-task.webhook.transport=kafka` 或等价松绑定 env 显式设置。
> relay 复用 `app.async-task.webhook.*` 的 `max-attempts`（默认 3）、`backoff`（默认 250ms）、
> `poll-interval-ms`（默认 30000）、`batch-size`（默认 50）。

---

## 6. 消费侧：channel-service 回推

两个 `@KafkaListener`，均 **`@ConditionalOnProperty(platform.eventbus.enabled=true)`**（默认不装配、不加载任何监听容器），
消费组 `${platform.eventbus.consumer.group-id:channel-service}`，容器工厂 `eventbusKafkaListenerContainerFactory`：

| 监听器 | 消费 topic | 回推逻辑 |
|---|---|---|
| `WorkflowTerminalKafkaListener` | `platform.workflow.terminal` | 去重后经 `ChannelCallbackService.handleCallback`，与 HTTP `POST /channel/callbacks/workflow` 走**同一 accept 逻辑** |
| `AsyncTaskLifecycleKafkaListener` | `platform.asynctask.lifecycle` | 去重后回推，与 HTTP `POST /channel/callbacks/async-task` 同一 accept 逻辑 |

两者 `handle(...)` 均严格「**先 `isProcessed` → `handleCallback` → 成功后 `markProcessed`**」（见 §1）。
即便生产侧未来升级为 Kafka 原生事务，这层 `ProcessedEventStore` 去重仍作重启/重投的二次兜底。

### 消费侧跨重启去重（`CHANNEL_DEDUP_STORE`）

- 默认 `memory`：重启失忆，dev/test 零 SQL 依赖（`ChannelServiceApplication` 已排除 `DataSourceAutoConfiguration`）。
- 生产 Kafka 部署设 `CHANNEL_DEDUP_STORE=jdbc` → `platform.eventbus.processed-event-store=jdbc` →
  `ChannelDedupConfig` 建独立 `channel-dedup` DataSource（`channel.dedup.datasource.url/username/password/driver-class-name`，
  默认指向 `mysql:3306/channel`），供 `JdbcProcessedEventStore` 的 `PROCESSED_EVENT` 表跨重启去重。

Kafka 连接：`channel-service` 的 `spring.kafka.bootstrap-servers` 绑定 `${KAFKA_BOOTSTRAP_SERVERS:kafka:9092}`。

> channel 相关端点（含 `/channel/callbacks/**`）经 edge-gateway（:8080）路由：`Path=/channel,/channel/**`。

---

## 7. 端到端启用清单（生产 Kafka 档）

要真正跑通 `workflow/async-task → Kafka → channel 回推`，**每个参与服务**都要开总开关并能连到 Kafka——
只翻 `WORKFLOW_TERMINAL_NOTIFICATION_MODE=kafka` / `transport=kafka` 而不开 `platform.eventbus.enabled`，
relay 的 `EventPublisher` 仍是 `NoopEventPublisher`，事件会被静默丢弃。

**公共（每个参与服务）**

```bash
platform.eventbus.enabled=true            # 松绑定 env：PLATFORM_EVENTBUS_ENABLED=true
spring.kafka.bootstrap-servers=kafka:9092 # workflow/async-task 的 yml 未内置该占位，需显式配置
```

**workflow-service**

```bash
WORKFLOW_TERMINAL_NOTIFICATION_MODE=kafka
# platform.eventbus.enabled=true + spring.kafka.bootstrap-servers
```

**async-task-service**

```bash
ASYNC_TASK_STORE=jdbc                      # 事务性 outbox 前提，缺则无原子性保证
app.async-task.webhook.transport=kafka     # yml 无 ${} 占位，property/松绑定 env 设置
ASYNC_TASK_DB_URL=...  ASYNC_TASK_DB_USER=...  ASYNC_TASK_DB_PASSWORD=...
# platform.eventbus.enabled=true + spring.kafka.bootstrap-servers
```

**channel-service**

```bash
PLATFORM_EVENTBUS_ENABLED=true             # 装配两个 @KafkaListener
CHANNEL_DEDUP_STORE=jdbc                   # 跨重启去重（+ channel.dedup.datasource.* 指向 MySQL）
KAFKA_BOOTSTRAP_SERVERS=kafka:9092         # 已内置该占位
```

> ⚠️ docker-compose **默认不是** Kafka 档：`WORKFLOW_TERMINAL_NOTIFICATION_MODE=async-task`、
> `ASYNC_TASK_STORE` 默认 `in-memory`。需要 Kafka EOS 链路时按上表覆盖 env。

---

## 8. 集成测试

两组集成测试用 `@Tag` 标记，**默认 `mvn test` 套件不加载**（各模块 surefire 用 `excludedGroups` 排除），
需显式 profile 触发：

```bash
# EmbeddedKafka 端到端：证明「重复投递去重 + 瞬时失败不丢不重复」
mvn -Pkafka-it -pl platform-eventbus test
# 对应 EventbusExactlyOnceKafkaTest（@Tag("kafka-it")）

# 嵌入式 Flowable(H2, setDatabaseType("mysql")) 原子性：
# 证明 end 监听器写事件 outbox 与引擎终态同事务（正常结束行存在；后置监听器抛异常时 outbox 行随历史实例一起回滚）
mvn -Pflowable-it -pl workflow-service test
# 对应 WorkflowTerminalOutboxAtomicityTest（@Tag("flowable-it")）
```

其余逻辑（relay 路由/重试/DLQ、消息重建、消费去重顺序、生产者映射）由纯 POJO 单测覆盖，随默认套件执行，例如：

```bash
mvn -pl platform-eventbus test        # ProcessedEventStoreTest / KafkaEventPublisherTest / EventMessageContractTest
mvn -pl workflow-service test         # WorkflowTerminalEventRelayTest / WorkflowTerminalOutboxListenerTest
mvn -pl async-task-service test       # AsyncTaskLifecycleRelayTest / AsyncTaskLifecycleEventPublisherTest
mvn -pl channel-service test          # WorkflowTerminalKafkaListenerTest / AsyncTaskLifecycleKafkaListenerTest
```

---

## 9. 关键类索引

| 层 | 类 | 位置 |
|---|---|---|
| SPI / 配置 | `EventPublisher` / `NoopEventPublisher` / `KafkaEventPublisher` | `platform-eventbus` |
| SPI / 配置 | `ProcessedEventStore` / `InMemoryProcessedEventStore` / `JdbcProcessedEventStore` | `platform-eventbus` |
| SPI / 配置 | `EventbusProperties` / `PlatformEventbusAutoConfiguration` / `KafkaProducerConfig` / `KafkaConsumerConfig` | `platform-eventbus` |
| 契约 | `EventTopics` / `WorkflowTerminalMessage` / `AsyncTaskLifecycleMessage` / `AuditEventMessage` / `UsageEventMessage` | `platform-protocol` 的 `event` 包 |
| workflow 生产 | `WorkflowTerminalOutboxListener` / `WorkflowTerminalEventOutbox` / `WorkflowTerminalEventRelay` | `workflow-service` |
| async-task 生产 | `AsyncTaskLifecycleOutbox` / `AsyncTaskLifecycleRelay` / `AsyncTaskLifecycleEventPublisher` / `AsyncTaskKafkaNotifier` / `JdbcAsyncTaskStore` | `async-task-service` |
| channel 消费 | `WorkflowTerminalKafkaListener` / `AsyncTaskLifecycleKafkaListener` / `ChannelDedupConfig` | `channel-service` |
