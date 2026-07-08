# Model Cascade / 成本路由指南

> 新项目里这是 **conversation-service**（`:8081`）的 `POST /chat/cascade` 端点，默认**关闭**（`CHAT_CASCADE_ENABLED=false`）。开启后，同一个问题先用**便宜模型**作答，只有当答案被判「低置信」时才升级到**强模型**重答，从而在不牺牲难题质量的前提下压低整体 token 成本。

代码归属：级联的构造能力在 `platform-gateway-client`（`com.lrj.platform.gateway.cascade.*`），端点/绑定/指标接线在 `conversation-service`（`com.lrj.platform.conversation.cascade.*`）。开关默认关时整套 `CascadeConfig` 不装配、对现有 `/chat` 零开销。

---

## 1. 做什么 / 为什么

同一批线上问题里，绝大多数是简单的、便宜（小）模型就能答好；只有少数难题才值得付强（大）模型的钱。**Model Cascade** 把这个直觉工程化：

1. 先用**便宜模型**（`cheap-model`）作答；
2. `ConfidenceGate` 判定这条便宜答案是否「够可信」；
3. 够用 → 直接返回便宜结果（省钱）；不够用 → **升级**到**强模型**（`strong-model`）重答。

结果：大量简单问题被便宜模型消化，强模型只在需要时才被调用，整体 token 成本显著下降，质量兜底不塌（难题仍走强模型）。指标 `llm.cascade{served=cheap|strong}` 让「这次省没省钱」一眼可见。

**级联是纯「模型选择」层，与 RAG / 记忆 / 工作流正交。** `CascadeService` 不带 `ChatMemory`、不接检索——它只在「用哪个模型作答」这一层做决策，可与对话记忆、RAG 增强各自独立开关、互不干扰。

---

## 2. 端点

| 方法 | 路径 | 载体服务 | 默认状态 |
|---|---|---|---|
| POST | `/chat/cascade` | conversation :8081 | 默认关（`CHAT_CASCADE_ENABLED=false`） |

走 `/chat` 同一套鉴权链：经边缘网关 `:8080` 校验 `X-Api-Key`（需 `chat` scope）→ 签发内部 JWT → 多租户 `TenantContext` → 限流 + 每租户日 token 配额。底层 cheap/strong 两个模型都挂了全部 `ChatModelListener`，**token 照常计入审计、成本与当前租户配额，级联不绕过配额**。

### 请求体

```json
{ "message": "1+1 等于几？" }
```

`message` 为空 / 缺失 → `400 {"error":"message is required"}`。

### 响应体（`CascadeResult`）

```json
{
  "question": "1+1 等于几？",
  "answer": "1 + 1 = 2。",
  "served": "cheap",
  "cheapConfident": true,
  "tenantId": "acme"
}
```

| 字段 | 类型 | 含义 |
|---|---|---|
| `question` | string | 原问题（回显） |
| `answer` | string | 最终答案 |
| `served` | `"cheap"` \| `"strong"` | 谁最终作答——**成本可见的核心信号** |
| `cheapConfident` | boolean | 便宜模型是否被判置信（`false` = 发生了升级；`true` 时 `served` 必为 `cheap`） |
| `tenantId` | string | 发起租户（多租户成本归因） |

---

## 3. 置信判定（ConfidenceGate）

`ConfidenceGate` 决定便宜答案是否「够用」。默认是**纯确定性启发式**（无 LLM 调用，可单测、零额外成本）：

1. **空 / 过短**：答案 `strip()` 后长度 `< min-answer-chars`（默认 8）→ 低置信 → 升级。兜住空答、被截断的半句。
2. **命中不确定 / 拒答标记**：答案小写后包含任一 `uncertainty-markers`（如「我不确定」「无法回答」「资料里没有」「i'm not sure」「unable to」「as an ai」）→ 低置信 → 升级。中英混排，覆盖典型拒答措辞。

**可选增强：自评（`self-rating=true`，默认关）**。启发式通过后，再让**便宜模型**对自己的答案做一次 `temp=0` 自评，输出一个 `0–1` 置信分；低于 `confidence-threshold`（默认 0.6）也判低置信 → 升级。

- 自评是「精度换成本」的取舍：多花一次便宜模型调用换取更准的置信判断。启发式已能拦住绝大多数明显低质答案，所以默认关。
- 自评失败（解析不出分数 / 模型异常）**保守判 0.0（升级）**——宁可多花一次强模型，也不把不确定的便宜答案放行。
- **注意弱模型自评偏乐观**：让便宜模型给自己打分天然容易高估。若发现自评几乎从不触发升级，可考虑把阈值调高，或（未来项）改用独立小判官模型。
- 自评模型走 `CascadeChatModelFactory#buildRater` 出来的 `temp=0` 确定性便宜模型，**不是**注册的全局 `ChatModel` Bean（原因见 §5）。

**工具调用短路**：若便宜模型这一跳直接触发了工具调用（`hasToolExecutionRequests()`），此时没有可判的文本，级联直接返回便宜结果、交回上层工具循环，**不升级**。

---

## 4. cheap / strong 模型名如何经 LiteLLM 映射（关键适配）

这是从老单体迁移到本平台最重要的一处差异，务必理解：

- **老单体**里 cheap/strong 是「同一个 provider 下的两个 model-name」，切 `app.llm.provider` 时级联跟随，Java 侧带 provider 分支。
- **本平台没有任何 provider `switch`。** `cheap-model` / `strong-model` 是两个**逻辑模型名**，`GatewayChatModelFactory.build(modelName, temperature)` 构造一个指向 **LiteLLM** base-url 的 `OpenAiChatModel`，把该逻辑名作为 OpenAI 协议的 `model` 字段发给 LiteLLM。**provider 路由 / 跨 provider failover / 真实模型映射全在 LiteLLM 的 `deploy/litellm/config.yaml` 里集中管理。**

所以要真正省钱，你要在 LiteLLM 的 `model_list` 里为 cheap / strong 各定义一个逻辑名，指到不同档位的底层模型：

```yaml
# deploy/litellm/config.yaml —— 在 model_list 里加两档
model_list:
  - model_name: chat-default          # 平台默认逻辑名（platform.gateway.model-name）
    litellm_params:
      model: ollama/llama3.1
      api_base: http://host.docker.internal:11434
  - model_name: chat-cheap            # 便宜档
    litellm_params:
      model: ollama/qwen2.5:3b        # 生产可换 openai/gpt-4o-mini、anthropic/claude-haiku-* 等
      api_base: http://host.docker.internal:11434
  - model_name: chat-strong           # 强档
    litellm_params:
      model: ollama/qwen2.5:14b       # 生产可换 openai/gpt-4o、anthropic/claude-sonnet-* 等
      api_base: http://host.docker.internal:11434
```

然后应用侧只填**逻辑名**：`CHAT_CASCADE_CHEAP_MODEL=chat-cheap`、`CHAT_CASCADE_STRONG_MODEL=chat-strong`。换 provider、加 failover 都改 LiteLLM 配置，应用侧无需改动。

**留空的退化行为**：`cheap-model` / `strong-model` 任一留空，就回退到网关默认模型名 `platform.gateway.model-name`（默认 `chat-default`）。若两者都留空 → cheap 与 strong 指向同一模型，级联退化为「便宜=强」——功能能跑通、指标也在打点，但**并不省钱，仅用于演示链路**。真正部署一定要把两档指到不同底层模型。

温度可分别覆盖（`app.chat.cascade.cheap-temperature` / `strong-temperature`，yml 里配；留空各自沿用 `platform.gateway.temperature`，默认 0.7）。

---

## 5. 关键设计与坑

- **`CascadeChatModel implements dev.langchain4j.model.chat.ChatModel`**：它包裹 cheap + strong，`chat(ChatRequest)` 被 override 成级联逻辑，因此任何吃 `ChatModel` 的地方（`AiServices.builder(...)` / 直接 `chat()`）都能透明用上级联。
- **但它故意不注册成 Spring Bean。** 它是 `ChatModel` 类型，一旦成 Bean，容器里就有 2 个 `ChatModel` Bean（主 `chatModel` + 它），`langchain4j-spring` 的 `AiServicesAutoConfig` 按 `getBeanNamesForType(ChatModel.class)` 枚举、数量 >1 直接抛 `IllegalConfigurationException`，整个 `@AiService`（如 conversation 的 `Assistant`）装配崩掉。做法：cheap / strong / cascade 全在 `CascadeService`（非 `ChatModel` 类型的 Bean）内部持有，只暴露 `CascadeService`。与 vision、`AgentBrain` 的「私有模型不进容器」同套路。
- **两个底层模型都灌了全部 `ChatModelListener`**（`GatewayChatModelFactory.build` 内部注入）——token / 成本 / 每租户日配额照常计量**两条链**，级联不绕过任何横切治理。
- **`buildRater` 是 `temp=0` 确定性构建**：自评「同问同答、可复现」，对成本路由是可取的。
- **仅覆盖非流式**：当前 `/chat/cascade` 不做流式。流式下「先便宜后升级」需要先缓冲便宜输出判置信、再决定是否重开强模型流，属未来项。
- **指标降级**：`CascadeMetrics` 是轻量函数式接口（让 `platform-gateway-client` 保持零 micrometer 依赖）；conversation-service 提供 `MeterRegistry` 支撑的实现，无 registry 时降级 noop、不抛。

---

## 6. 指标

`llm.cascade{served=cheap|strong}` counter（`CascadeConfig` 用 conversation-service 的 `MeterRegistry` 打点）。Prometheus 里名为 `llm_cascade_total`。便宜模型命中率（≈ 省钱比例）：

```promql
sum(rate(llm_cascade_total{served="cheap"}[5m]))
  / sum(rate(llm_cascade_total[5m]))
```

命中率越高，被便宜模型消化的问题越多、强模型烧钱越少。可结合 `platform-metering` 的成本归因（`GET /actuator/cost`）观察级联开关前后每租户成本变化。

---

## 7. 怎么配 / 怎么跑

以本地 Ollama 两档为例，先 `ollama pull qwen2.5:3b` + `ollama pull qwen2.5:14b`，并按 §4 在 LiteLLM `model_list` 里加好 `chat-cheap` / `chat-strong`。

**启动 conversation-service（+ 边缘网关）**，用环境变量开启级联（多参数覆盖用环境变量，relaxed binding 稳，别堆 `-Dspring-boot.run.arguments` 逗号）：

```bash
CHAT_CASCADE_ENABLED=true \
CHAT_CASCADE_CHEAP_MODEL=chat-cheap \
CHAT_CASCADE_STRONG_MODEL=chat-strong \
mvn -pl conversation-service spring-boot:run
```

或整套本地栈：`docker compose -f deploy/docker-compose.yml up --build`（在对应 service 的 environment 里设上述变量）。

**简单问题——便宜模型应答得住 → `served=cheap`**（经网关 `:8080`）：

```bash
curl -s -X POST http://localhost:8080/chat/cascade \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"message":"1+1 等于几？"}'
# {"question":"1+1 等于几？","answer":"...","served":"cheap","cheapConfident":true,"tenantId":"acme"}
```

**便宜模型答不好 / 拒答 → 升级 `served=strong`**：

```bash
curl -s -X POST http://localhost:8080/chat/cascade \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"message":"用一段话严谨推导欧拉公式的几何直觉"}'
# {"question":"...","answer":"...","served":"strong","cheapConfident":false,"tenantId":"acme"}
```

**开自评**（对便宜答案额外多发一次 `temp=0` 调用，换更准的置信判断）：额外加 `CHAT_CASCADE_SELF_RATING=true`，并按需调 `CHAT_CASCADE_CONFIDENCE_THRESHOLD`。

> 直连调试（跳过网关鉴权）可打 `http://localhost:8081/chat/cascade`，仅限本地。经网关时该 api-key 必须绑定 `chat` scope。

---

## 8. 开关速查

配置前缀 `app.chat.cascade.*`（conversation-service `application.yml`），下表为对应环境变量与默认值：

| 环境变量 | 默认 | 作用 |
|---|---|---|
| `CHAT_CASCADE_ENABLED` | `false` | 总开关。关闭时 `CascadeConfig` / `ConfidenceGate` / `CascadeService` / 端点全不装配，零开销 |
| `CHAT_CASCADE_CHEAP_MODEL` | 空 | 便宜模型的 **LiteLLM 逻辑模型名**。留空 → 回退网关默认 `chat-default` |
| `CHAT_CASCADE_STRONG_MODEL` | 空 | 强模型的 LiteLLM 逻辑模型名。留空 → 回退 `chat-default`（此时便宜=强，仅演示不省钱） |
| `CHAT_CASCADE_CONFIDENCE_THRESHOLD` | `0.6` | 自评置信阈值，**仅 `self-rating=true` 时生效**；自评分 `<` 此值 → 升级 |
| `CHAT_CASCADE_MIN_ANSWER_CHARS` | `8` | 便宜答案短于此字符数 → 判低置信 → 升级（空答 / 截断兜底） |
| `CHAT_CASCADE_SELF_RATING` | `false` | 启发式之外再加一道 `temp=0` 自评（多一次便宜模型调用换精度）。默认关 |

仅可在 yml 里配（无独立环境变量映射）的进阶项：

| yml key（`app.chat.cascade.*`） | 默认 | 作用 |
|---|---|---|
| `cheap-temperature` | 空 → 用 `platform.gateway.temperature`（0.7） | 便宜模型作答温度 |
| `strong-temperature` | 空 → 用 `platform.gateway.temperature`（0.7） | 强模型作答温度 |
| `uncertainty-markers` | 内置中英标记表 | 命中即判低置信的拒答 / 不确定措辞；yml 里给完整 list 会整体覆盖内置默认 |

相关文档：Agent 编排与级联总览见 `agent-guide.md`；接口速查见 `api-reference.md`；运行 / 部署配置见 `operations.md`；架构与两层网关设计见 `架构文档.md`。

---

## 9. 未来项

- **多级级联**（cheap → mid → strong，N 段）而非两段。
- **按题型路由**：结合意图分类器，简单意图直接走 cheap、复杂意图直接 strong，省掉「先便宜再判」的第一跳。
- **流式级联**：先缓冲便宜输出判置信，再决定是否重开强模型流。
- **独立小判官自评**替代便宜模型自评（弱模型自评偏乐观）。
