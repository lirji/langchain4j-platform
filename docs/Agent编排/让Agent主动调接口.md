# 让 Agent 主动调接口（工具调用 / 自定义动作接入）

> **要解决的问题**：在对话里问「帮我查订单 101 现在什么状态」，怎么让模型**自己决定**去调你的订单接口、把结果拿回来再回答，而不是靠模型瞎编？
>
> 这就是 **tool calling / function calling**。本平台里已经有**两套现成机制**，都不用改编排循环一行代码，写一个类注册进去即可。总览见 [agent-guide.md §7](agent-guide.md)。
>
> ✅ **本文的 `order_query` 例子已在本仓库落地为可跑实现**：新增了独立的 **order-service（:8093，持久化 MySQL）** 做下游、agent-service 加了 `order_query` 动作。下面既讲通用做法，也标注真实文件与开关，你可以直接跑（见 §2.4、§6）。

---

## 0. 两套机制,先选对

| | ① ReAct 动作 `AgentAction` | ② langchain4j 原生 `@Tool` |
|---|---|---|
| **入口** | agent-service `/agent/run`（深度 Agent） | 任意 `@AiService` / `AiServices.builder().tools(...)` 助手 |
| **写法** | 实现 `AgentAction` 接口 + `@Component` | POJO 方法上加 `@Tool` 注解 |
| **调度** | `DeepAgentService` 的 ReAct 循环手动选动作、`dispatch()` 调 `run()` | 模型走 OpenAI function-calling，框架自动调 |
| **能力** | 多步规划、可委派子 Agent、失败重试、scratchpad 记忆 | 单轮/少数几步、一个助手挂固定几个工具 |
| **典型例子** | `OrderQueryAction`、`AnalyticsSqlAction`、`RefundStartAction`、`McpToolAction` | `analytics-service` 的 `SqlQueryTool` |
| **什么时候用** | 需要「自主体」——多步骤、要在多个工具间自己抉择、要能失败换招 | 一个专用助手挂一两个确定性工具，链路简单 |

**结论先行：**
- 「在通用对话/Agent 里，问什么它自己判断该不该查订单」→ 用**机制①**，写一个 `OrderQueryAction`。**这是本文重点。**
- 「我要做一个只会查订单的专用小助手」→ 用**机制②**，给它挂一个 `@Tool` 方法即可。见 §4。

> **省事提醒**：如果订单数据本来就在业务库里、只想「自然语言问统计」，你**什么都不用写**——现成的 `analytics_sql` 动作（NL2SQL）已经能让 Agent 生成只读 SELECT 查 `orders` 表。`order_query` 与它的分工是：**`order_query` 是按订单号的确定性、参数化单条查询**（精确、快、天然防注入）；`analytics_sql` 是 LLM 生成 SQL 的统计/聚合查询（总额、趋势、top-N）。描述里要写清这个分工，引导模型选对。

---

## 1. 机制① 原理：描述驱动的动作注册表

ReAct 动作是「一个接口 `AgentAction` + `@ConditionalOnProperty` 可插拔实现」的注册表。核心就三个方法（`AgentAction.java`）：

```java
public interface AgentAction {
    String name();          // 动作名，模型用它来点名调用（如 "order_query"）
    String description();   // ★模型唯一能看到的「工具说明书」——决定它调不调、怎么填参
    String run(String input);  // 真正执行；input 是模型填的 actionInput
}
```

循环怎么跑（`DeepAgentService.java`）：

1. **收集**：Spring 把容器里所有 `AgentAction` bean 注入成 `Map<String, AgentAction>`（`DeepAgentService.java:28`）。
2. **描述**：`describeActions()` 把每个动作渲染成 `- name: description`，塞进 `AgentBrain` 的提示词（`DeepAgentService.java:180`）。
3. **决策**：`AgentBrain`（ReAct 系统提示词，`AgentBrain.java`）让模型每步只选一个动作名 + 填 `actionInput`，或选 `finish` 收尾。
4. **调用**：`dispatch()` 按名字取出动作 `action.run(input)`，把返回文本作为「观测」喂回下一轮（`DeepAgentService.java:169-174`）。

> **加一个新动作 = 只写一个 `@Component` 类**。循环自动发现、自动描述、自动调度——**不改循环一行代码**。这正是插件式动作设计的意义（`code_exec`/`refund_start`/`order_query` 都是这么加进去的）。

---

## 2. 手把手：新增 `order_query` 动作（订单在独立 REST 服务后面）

以「按订单号查订单详情」为例。agent 侧三步（§2.1–2.3）+ 下游服务（§2.4）。真实文件路径已标注。

### 2.1 写一个调订单服务的 client

`agent-service/.../agent/client/OrderClient.java`。仿 `WorkflowClient` 的约定：**错误进返回值而非抛异常**，避免打断 ReAct 循环。DTO 用 `platform-protocol` 里的共享 `OrderView`（跨服务契约，见 §2.4）。

```java
package com.lrj.platform.agent.client;

import com.lrj.platform.protocol.order.OrderView;

/** agent → order-service 客户端。错误进返回值（error 非空），不抛异常打断循环。 */
public interface OrderClient {
    Outcome getByNo(String orderNo);
    record Outcome(OrderView order, String error) {}
}
```

HTTP 实现（`HttpOrderClient.java`），经**带租户/trace 透传的 RestTemplate** 调下游；由 `app.agent.order.enabled` 门控（**本仓库默认开**；置 `false` 则由 `NoopOrderClient` 兜底）：

```java
@Component
@ConditionalOnExpression("${app.agent.enabled:true} and ${app.agent.order.enabled:false}")
public class HttpOrderClient implements OrderClient {

    private final RestTemplate restTemplate;

    public HttpOrderClient(@Qualifier("orderRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public Outcome getByNo(String orderNo) {
        try {
            OrderView v = restTemplate.getForObject("/orders/{no}", OrderView.class, orderNo);
            return new Outcome(v, v == null ? "empty order response" : null);
        } catch (HttpClientErrorException.NotFound nf) {
            return new Outcome(null, "订单不存在: " + orderNo);   // 404 归一
        } catch (RestClientException ex) {
            return new Outcome(null, ex.getMessage());           // 其它错误进返回值
        }
    }
}
```

配套兜底 `NoopOrderClient.java`（与 Http 互补，保证 `OrderClient` 恒有唯一实现，`OrderQueryAction` 注入不缺 bean）：

```java
@Component
@ConditionalOnExpression("${app.agent.enabled:true} and !${app.agent.order.enabled:false}")
public class NoopOrderClient implements OrderClient {
    @Override public Outcome getByNo(String orderNo) {
        return new Outcome(null, "order lookup disabled");
    }
}
```

> **为什么用 `@ConditionalOnExpression` 而不是 `@ConditionalOnProperty`**：要精确表达「`app.agent.enabled` 缺省即 true」且与 Noop 互补二选一。项目已废弃 `@ConditionalOnMissingBean`/`@ConditionalOnBean`（组件扫描下注册顺序不可靠，曾致随机启动失败），一律用表达式/属性门控。见 `NoopAnalyticsClient` 同款注释与回归测 `OrderClientWiringTest`。

> **跨服务 DTO 放哪**：`OrderView` 要在 order-service（响应）与 agent-service（client）之间共享，按 CLAUDE.md 约定放 `platform-protocol`（不可变 record），不在各服务重复定义。

### 2.2 写动作类

`agent-service/.../agent/actions/OrderQueryAction.java`，照 `AnalyticsSqlAction`/`RefundStartAction` 的样子。**只读无副作用，故动作本身在 `app.agent.enabled` 时即挂载**（`matchIfMissing=true`）；真正是否可用取决于注入的是 Http 还是 Noop client：

```java
@Component
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class OrderQueryAction implements AgentAction {

    private final OrderClient orders;
    public OrderQueryAction(OrderClient orders) { this.orders = orders; }

    @Override public String name() { return "order_query"; }

    @Override
    public String description() {
        // ★这段描述就是模型「决定要不要调、怎么填参」的唯一依据
        return "按订单号查订单详情（状态/金额/客户/下单日期）；actionInput 填订单号（如 101）。"
             + "只读、不做任何修改。业务统计类问题（总额/趋势/top-N）用 analytics_sql，不要用本动作。";
    }

    @Override
    public String run(String input) {
        if (input == null || input.isBlank()) {
            return "订单号为空：actionInput 请填要查的订单号。";
        }
        OrderClient.Outcome o = orders.getByNo(input.trim());
        if (o.error() != null) {
            return "查询失败：" + o.error();      // 失败/disabled 也返回文本，让模型下一轮换招
        }
        OrderView v = o.order();
        if (v == null) {
            return "未找到订单 " + input.trim();
        }
        return "订单号: " + v.orderNo()
             + "\n状态: " + v.status()
             + (v.amount() != null ? "\n金额: ¥" + v.amount() : "")
             + (v.customer() != null ? "\n客户: " + v.customer() : "")
             + (v.createdAt() != null ? "\n下单日期: " + v.createdAt() : "");
    }
}
```

### 2.3 注册一条带租户透传的 RestTemplate

在 `AgentConfig.java` 加一个 bean（照 `workflowRestTemplate` 抄，复用私有 helper `serviceRestTemplate`）；与动作的 client 同门控（`order.enabled`）：

```java
@Bean
@ConditionalOnProperty(name = "app.agent.order.enabled", havingValue = "true")
RestTemplate orderRestTemplate(RestTemplateBuilder builder,
                               OutboundTenantForwarder tenantForwarder,
                               OutboundTraceForwarder traceForwarder,
                               @Value("${app.agent.order.base-url:http://localhost:8093}") String baseUrl,
                               @Value("${app.agent.http.connect-timeout:1s}") Duration connectTimeout,
                               @Value("${app.agent.http.read-timeout:5s}") Duration readTimeout) {
    return serviceRestTemplate(builder, tenantForwarder, traceForwarder, baseUrl, connectTimeout, readTimeout);
}
```

对应 `agent-service/application.yml`：

```yaml
app:
  agent:
    order:
      enabled: ${AGENT_ORDER_ENABLED:true}      # 默认开；置 false 则走 NoopOrderClient
      base-url: ${ORDER_BASE_URL:http://localhost:8093}
```

**就这样。** `AGENT_ORDER_ENABLED=true` 且 order-service 可达时，模型看到 `order_query` 的描述后会在需要时点名调它。

### 2.4 下游 order-service（本仓库已内置）

真实下游是独立微服务 **order-service（:8093）**，用裸 `JdbcTemplate` 直连**持久化 MySQL**（共用平台 `mysql` 容器 + 独立 schema `order_service`）。关键点：

- **表结构在代码里演进**（无 Flyway/JPA）：`JdbcOrderStore.init()` 里 `CREATE TABLE IF NOT EXISTS orders/customers`，首启表空时插一批演示订单（tenantA 101–109 / tenantB 2001–2002，与 analytics 的 nl2sql-demo 对齐）。`ORDER_SEED_DEMO=false` 关种子（接真库时）。
- **租户隔离在 SQL 层**：`GET /orders/{orderNo}` 用参数化 `WHERE id = ? AND tenant_id = ?`，tenant 取自过滤器链还原的 `TenantContext`。别的租户就算知道订单号也查不到（0 行 → 404）——绑定参数的 PreparedStatement 天然防注入，不需要 analytics 那套给「LLM 生成 SQL」兜底的 `SqlGuard`。
- **零 Java 接线的鉴权**：只依赖 `platform-security`，`InternalTokenAuthFilter` 经 `AutoConfiguration.imports` 自注册，自动校验内部 JWT 并还原 `TenantContext`。controller/store 只读 `TenantContext.current().tenantId()`。
- **只读接口不设 scope 门禁**（对齐 vision/analytics 只读风格）：任何过了边缘鉴权的合法租户即可查。401 由 edge-gateway 兜（`/orders` 不在 open path）。

涉及文件：`order-service/`（`OrderController` / `JdbcOrderStore` / `OrderStore` / `application.yml` / `Dockerfile`）、`platform-protocol/.../order/OrderView.java`；接线在根 `pom.xml`、`edge-gateway` 路由（`/orders,/orders/**`）、`deploy/docker-compose.yml`（宿主机 `8094:8093`——8093 已被展示前端占用）、`deploy/helm/.../values.yaml`。

> 若你的订单在**别的**外部系统后面（不是本 order-service），把 `ORDER_BASE_URL` 指过去、让它返回 `OrderView` 契约即可，agent 侧代码一行不用改。

---

## 3. 四条不能省的约定（踩过的坑）

1. **`description()` 决定成败**——它是模型唯一能看到的「工具说明书」。写清楚三件事：**干什么**、**`actionInput` 填什么格式**、**什么时候别用它**（引导模型在多个工具间正确分流，如「统计类用 analytics_sql」）。这比调 prompt 更有效。

2. **按风险选门控**。只读、无副作用的查询（如 `order_query`）：动作用 `matchIfMissing = true` 默认挂载，用一个 feature flag（`app.agent.order.enabled`，本仓库默认开、可置 `false` 降级）+ Http/Noop 互补控制「真调还是降级」，避免去调可能不存在的下游。**有副作用的**（发起退款）：学 `RefundStartAction` 用双门控 `@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.workflow.enabled"}, havingValue = "true")`（本仓库亦默认开、生产可关），防误触发。**不可逆高风险操作（审批、支付、删除）根本不给动作**——`workflow` 就故意不提供 `workflow_complete` 审批动作，留给人在流程外做。

3. **租户隔离自动带,别手写**。agent-service 的 RestTemplate 都挂了 `OutboundTenantForwarder` + `OutboundTraceForwarder`（`AgentConfig.serviceRestTemplate()`）：当前线程 `TenantContext` 里的租户身份会被铸成内部 JWT 自动透传到 order-service，traceId 也一路带下去。你的 client 直接用这个 RestTemplate 即可,**不用碰鉴权头**。下游 order-service 只要依赖 `platform-security` 就自动还原租户。

4. **错误进返回值,不要抛异常**。`run()` 里查不到/调失败/被降级（Noop 的 `order lookup disabled`）都**返回错误文本**，让模型在下一轮自己换入参或换动作。抛异常会被 `dispatch()` 兜成 `action error: ...`，但主动返回可读中文更利于模型自修。

---

## 4. 机制②：给专用助手挂原生 `@Tool`

如果你要的不是「自主 Agent」，而是「一个只会查订单的对话助手」，用 langchain4j 原生工具更直接。参照 analytics 的 `SqlQueryTool` + `SqlAssistant`：

```java
public class OrderTool {
    private final OrderClient orders;
    public OrderTool(OrderClient orders) { this.orders = orders; }

    @Tool("按订单号查订单详情。参数 orderNo 是订单号。任何订单相关问题都必须调此工具查询，不要凭记忆回答。")
    public String queryOrder(@P("订单号，如 101") String orderNo) {
        OrderClient.Outcome o = orders.getByNo(orderNo);
        return o.error() != null ? "查询失败：" + o.error() : String.valueOf(o.order());
    }
}

// 绑定：程序化构建，不走 @AiService 自动装配
OrderAssistant assistant = AiServices.builder(OrderAssistant.class)
        .chatModel(chatModel)
        .tools(new OrderTool(orderClient))   // 挂工具
        .build();
```

> ⚠️ **别把 `@Tool` 类做成 `@Component`/`@Bean`**。langchain4j-spring-boot-starter 会把容器里所有 `@Tool` bean **自动挂到主 `Assistant`**，污染普通对话。正确做法是 `new` 出来只挂给指定助手——这正是 `SqlQueryTool` 类注释专门警告的坑（它故意不是 Spring bean）。

**想让普通对话也能调工具？** conversation-service 已有入口：`/chat/mcp`（挂 MCP 工具）、`/chat/auto`（意图路由到不同能力）。要把订单查询接进主对话流，走这两个入口比在主 `Assistant` 上直接堆工具更干净。

---

## 5. 测试（POJO 直测,不起 Spring）

沿用仓库范式（`AgentActionsTest`）：动作层直接 `new` + 桩 client 断言渲染（`OrderQueryActionTest`）；Http/Noop 门控用 `ApplicationContextRunner` 回归（`OrderClientWiringTest`）；下游 store 的租户隔离用**真 MySQL 集成测**（`JdbcOrderStoreMySqlIT`，`@EnabledIfEnvironmentVariable` 门控，无 `ORDER_IT_MYSQL_URL` 自动跳过——不引入 H2）。

```java
@Test
void rendersOrderDetail() {
    OrderQueryAction action = new OrderQueryAction(
        no -> new OrderClient.Outcome(
            new OrderView("101", "张三", "1200.00", "已支付", "2026-05-03"), null));
    assertThat(action.run("101")).contains("状态: 已支付").contains("金额: ¥1200.00");
}

@Test
void reportsErrorText() {
    OrderQueryAction action = new OrderQueryAction(
        no -> new OrderClient.Outcome(null, "订单不存在: 999"));
    assertThat(action.run("999")).contains("查询失败").contains("订单不存在: 999");
}
```

> 下游 store 的测试依赖 `TenantContext`，记得在 `@AfterEach` 里 `TenantContext.clear()`。

---

## 6. 跑起来验证

```bash
# 1) 起 MySQL（或用已有的），起 order-service（首启自动建表+种子）
docker compose -f deploy/docker-compose.yml up -d mysql
ORDER_DB_URL='jdbc:mysql://localhost:3306/order_service?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true' \
  mvn -pl order-service spring-boot:run    # :8093

# 2) 直连 order-service 冒烟（api-key 兜底 → 租户 tenantA）
curl -H "X-Api-Key: dev-key-tenantA-admin" http://localhost:8093/orders/101   # 200，已支付
curl -o /dev/null -w "%{http_code}\n" -H "X-Api-Key: dev-key-tenantA-admin" \
     http://localhost:8093/orders/2001                                        # 404（tenantB 的单，租户隔离）

# 3) 让深度 Agent 自己调（起 agent-service 时开开关；经 edge-gateway :8080）
#    AGENT_ORDER_ENABLED=true ORDER_BASE_URL=http://localhost:8093
curl -X POST http://localhost:8080/agent/run \
  -H "X-Api-Key: dev-key-tenantA-admin" -H "Content-Type: application/json" \
  -d '{"goal":"帮我看下订单 101 现在什么状态"}'
```

第 3 步返回的 `steps` 里能看到模型选了 `action=order_query`、`actionInput=101`、`observation=订单号: 101 ...`，最后 `finish` 给出中文答复。异步 + SSE + webhook 变体见 [agent-guide.md §1/§8](agent-guide.md)。

> 整套栈：`docker compose -f deploy/docker-compose.yml up --build order-service edge-gateway mysql`（先 `mvn -DskipTests package` 生成 jar，避免"拷旧 jar"坑）。order-service 容器内 8093，宿主机映射到 **8094**（8093 被展示前端占用）。

---

## 7. 一句话对照

- **通用对话/Agent 里让它自己判断要不要查订单** → 写 `AgentAction`（机制①，§2）。本仓库已内置 `order_query` + order-service。
- **只想自然语言问统计（总额/趋势）** → 零代码，`analytics_sql`（NL2SQL）已够用。
- **做一个只会查订单的专用助手** → 原生 `@Tool` + `AiServices.builder().tools()`（机制②，§4）。
- **有副作用/高风险** → 按风险选门控、默认关，不可逆操作根本不给动作（§3.2）。
