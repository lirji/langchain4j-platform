# 语音闭环接入指南

本指南面向要把「语音客服」接进平台的开发者。对应服务是 **`voice-service`**（端口 `8091`），
它把音频前置的 **ASR（语音转文字）** 和后置的 **TTS（文字转语音）** 包在对话大脑外面，
中间那颗「脑子」复用 **`conversation-service`** 的 `/chat`。三个端点：

- `POST /voice/transcribe` —— 只做 ASR（纯转写 / 调试）。
- `POST /voice/chat` —— 完整轮次：ASR → conversation `/chat` → TTS，返回文字 + base64 语音。
- `POST /voice/chat/stream` —— SSE 半流式：整段 ASR → conversation `/chat/stream` 逐 token → 分句 TTS 逐句推回。

> **默认关。** 总开关 `VOICE_ENABLED=false`（对应 `app.voice.enabled`）。关闭时整个 `VoiceConfig` +
> `VoiceController` 都不装配（`@ConditionalOnProperty`），容器照常启动、health 绿，但**不映射任何 `/voice/**`**——
> 打过去得 404。这是刻意的「空壳容器」：不装配即零依赖、零网络。

> 端点约定：业务接口一律走 `edge-gateway`（`http://localhost:8080`，带 `X-Api-Key`）。
> 网关校验 api-key → 签发短时内部 JWT → 按 `Path=/voice,/voice/**` 路由到 `voice-service:8091`。
> `voice-service` 自身监听 `:8091`，仅供服务间直连或本地调试。下文 curl 统一走边缘网关。
> 调用方 api-key 需带 `voice` scope（示例里的 `dev-key-acme` 已含），跟 `/chat` 同一套鉴权/限流/审计链。

---

## 1. 做什么 / 核心洞察

**语音客服 = ASR 前置 + TTS 后置，中间夹的还是已经做好的对话大脑。**

关键洞察：语音 Agent 真正的「智能」不在语音本身，而在下游已落地的那套——**多轮对话 + 可选 RAG 增强 +
多租户 / 配额 / 审计**。所以 `voice-service` **不重写大脑**，只做两件新事：① 把音频转成文字喂进 `conversation`；
② 把 `conversation` 的文字回复合成语音播回去。

```text
音频(用户说话) ──ASR──▶ 文字 ──POST /chat──▶ conversation-service ──▶ 文字回复 ──TTS──▶ 音频(播给用户)
                                    │
                    （多轮记忆 / 可选 RAG 增强 / PII·注入护栏 全在下游复用）
```

`voice-service` 是**围着 `conversation` 大脑的一层薄 ASR/TTS 壳**，本身不做任何业务判断。它对下游只发两种请求：
一元 `POST /chat`（轮次）、SSE `POST /chat/stream`（流式）。租户身份、限流、token 配额、审计全部在 `conversation`
侧复用——`voice-service` 的出站 `RestTemplate` 装了 `OutboundTenantForwarder` + `OutboundTraceForwarder`，
把内部 JWT 与 traceId 原样透传到下游（无需在语音侧手搓 JWT）。`chatId` 隔离多轮会话记忆，语义同 `/chat`。

### 关于「意图路由 → 工作流」这颗更聪明的脑子

老单体里语音走的是一个 `CustomerServiceBrain`：文字进 → 意图分类（退款/投诉→工作流，其余→对话）→ 文字出。
**新平台里没有这个类。** 语音侧刻意只调 `conversation` 的**普通 `/chat`**，所以返回的 `route` 只有 `CHAT` / `NONE`
两种，**不会**从语音端直接触发工作流。

平台里「意图路由 → 工作流审批」这颗更重的脑子分散在别处，可按需接：

- `conversation` 的 `/chat/auto`（意图路由，需 `CONVERSATION_ROUTER_ENABLED=true`）；
- 渠道入站桥（钉钉 `/channel/dingtalk/events`、飞书 `/channel/feishu/events`）+ `workflow-service`（Flowable 退款审批）。

把语音大脑收敛到 `/chat/auto` 或渠道桥，是明确的**未来项**（对齐老文档 V4「大脑收敛」）——当前 `voice-service`
只贴着最朴素的 `/chat`，好处是行为可预测、零耦合，退款这类需要转人工的场景仍走文本渠道。

---

## 2. 为什么是 turn-based 而不是实时全双工

实时全双工语音（边说边听、打断 barge-in、流式 ASR/TTS）要 WebSocket/WebRTC + VAD + 回声消除，工程量大、依赖重。
对齐平台「先打地基、被信号证明不够再加」的取向，先做 **turn-based**：一段完整音频上传 → 一段完整回复。
这已覆盖「网页/小程序按住说话」「IVR 录一句转一句」的主流客服场景，且完全可测、可灰度。

| | turn-based（`/voice/chat`） | SSE 半流式（`/voice/chat/stream`） | 实时全双工（未来） |
| --- | --- | --- | --- |
| 上行 | HTTP 上传一段音频 | HTTP 上传一段音频 | WebSocket/WebRTC 流 |
| 下行 | 等整段（ASR+脑+TTS 串行）→ 一段音频 | SSE 逐句 TTS（边生成边推） | 边说边出、可打断 |
| 依赖 | 一个 ASR/TTS provider | 同左 | + VAD / 回声消除 / 流式编解码 |
| 适用 | 网页/小程序语音、IVR 录音转写 | 想要更低首句延迟 | 电话实时坐席替身 |

> `/voice/chat/stream` 是**半双工流式**（上行整段、下行流式分句 TTS）：回复边生成边播，首句延迟大降，但边说边打断
> （barge-in）仍要 WebSocket/WebRTC + VAD，留到后续。电话 IVR（SIP / 呼叫中心 webhook）+ 终态语音回拨也是独立集成项，不预先做。

---

## 3. 三个端点

所有请求都是 `multipart/form-data`，音频字段名固定为 **`audio`**；`chatId` 是可选的 **query 参数**（不是表单字段），
用于隔离多轮会话记忆，缺省时自动生成 `voice-<uuid>`。

### 3.1 `POST /voice/transcribe` —— 只做 ASR

纯转写，不进对话、不合成语音。用于调试或「只要文字」的场景。**不接受 `chatId`**。

```bash
curl -X POST 'http://localhost:8080/voice/transcribe' \
  -H 'X-Api-Key: dev-key-acme' \
  -F 'audio=@question.mp3'
```

响应：

```json
{ "transcript": "我想查一下我的订单到哪了" }
```

### 3.2 `POST /voice/chat` —— 完整轮次（返回语音）

ASR → `conversation` `/chat` → TTS。返回文字 transcript、对话回复文本、以及 base64 编码的 TTS 语音。

```bash
curl -X POST 'http://localhost:8080/voice/chat?chatId=u1' \
  -H 'X-Api-Key: dev-key-acme' \
  -F 'audio=@question.mp3'
```

响应（`VoiceReply`，五个字段一个不多）：

```json
{
  "transcript": "我想查一下我的订单到哪了",
  "reply": "您的订单已发货，预计明天送达。[doc=orders#3]",
  "route": "CHAT",
  "audioBase64": "SUQzBAAAAAA...（TTS 语音，base64）",
  "audioContentType": "audio/mpeg"
}
```

- `transcript`：ASR 转写的用户原话。
- `reply`：对话回复**文字**，**保留**引用标记（如 `[doc=orders#3]`，文字侧仍可点引用）。
- `route`：命中路由，只有 `CHAT`（正常对话）或 `NONE`（空转写兜底，见下）。
- `audioBase64`：TTS 语音回复，base64；解码即得音频文件。**合成前已剥掉引用标记**（`[doc=...]` 念出来很怪）。
- `audioContentType`：音频 content-type，由 `VOICE_TTS_FORMAT` 决定（`mp3`→`audio/mpeg`，`wav`→`audio/wav`，`opus`→`audio/opus`…）。

把 `audioBase64` 解码存盘即可播放：

```bash
curl -s -X POST 'http://localhost:8080/voice/chat?chatId=u1' \
  -H 'X-Api-Key: dev-key-acme' -F 'audio=@question.mp3' \
  | python3 -c 'import sys,json,base64; d=json.load(sys.stdin); open("reply.mp3","wb").write(base64.b64decode(d["audioBase64"])); print(d["transcript"],"→",d["reply"])'
```

**空转写兜底**：ASR 没听清（转写为空）时，**不进对话、不烧 token**，直接合成一句「抱歉，我没有听清，请您再说一遍。」
返回，`route` 为 `NONE`、`transcript` 为空。

### 3.3 `POST /voice/chat/stream` —— SSE 半流式

上行一次整段音频，下行 SSE 流式推回，客户端边收边播。事件顺序：

1. `transcript` 事件：data 为 ASR 转写纯文本（一次）。
2. 多个 `audio-chunk` 事件：每句一个，data 为 JSON `{"text","audioContentType","audioBase64"}`。
3. `done` 事件：data 为空，收口。

```bash
curl -N -X POST 'http://localhost:8080/voice/chat/stream?chatId=u1' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Accept: text/event-stream' \
  -F 'audio=@question.mp3'
```

响应（示意，回复被切成两句 → 两个 `audio-chunk`）：

```text
event:transcript
data:我想查一下我的订单到哪了

event:audio-chunk
data:{"text":"您的订单已发货。","audioContentType":"audio/mpeg","audioBase64":"..."}

event:audio-chunk
data:{"text":"预计明天送达。","audioContentType":"audio/mpeg","audioBase64":"..."}

event:done
data:
```

底层是**真 token 流式**：`voice-service` 消费 `conversation` `/chat/stream` 的 SSE，token 一到就喂
`SentenceChunker` 攒句，凑够一整句立即 TTS 发一个 `audio-chunk`——首句延迟随生成推进，而非等整段回复。
`audio-chunk` 的 `text` 保留引用标记（文字侧用），`audioBase64` 合成前已剥掉。客户端断连（`onCompletion`/
`onTimeout`/`onError`）会置 `cancelled`、停止后续 TTS 省算力；上游 SSE 无中断句柄，仍会读完（与 `/chat/stream` 同）。

**分句规则**（`SentenceChunker`）：遇句末标点（`。！？!?…` 或换行）且当前累计字数 ≥ `VOICE_STREAM_MIN_CHARS`（默认 8）
才切一句。`min-chars` 阈值防止「好。」这种过短句各自单独 TTS——既费调用又听感碎。不足一句的尾巴在收口时并成最后一段。

> 空转写在流式里同样兜底：先发一个 `audio-chunk`（「抱歉，我没有听清…」）再 `done`。

---

## 4. 配置

`voice-service` 的 `application.yml` 把 `app.voice.*` 绑定到一组环境变量，默认值如下（**先读源码 `application.yml`，此处与之对齐**）：

```yaml
app:
  voice:
    enabled: ${VOICE_ENABLED:false}                 # 总开关，默认关 → 不装配任何 voice bean
    provider: ${VOICE_PROVIDER:openai}              # 目前仅 openai 兼容协议
    base-url: ${VOICE_BASE_URL:https://api.openai.com/v1}   # 可指云 OpenAI / Azure / 本地网关
    api-key: ${VOICE_API_KEY:${OPENAI_API_KEY:}}    # 未配走本地网关时可留空
    asr-model: ${VOICE_ASR_MODEL:whisper-1}         # 或 gpt-4o-transcribe / 本地模型名
    tts-model: ${VOICE_TTS_MODEL:tts-1}             # 或 gpt-4o-mini-tts
    tts-voice: ${VOICE_TTS_VOICE:alloy}             # 音色
    tts-format: ${VOICE_TTS_FORMAT:mp3}             # mp3 | wav | opus | aac | flac | pcm
    language: ${VOICE_LANGUAGE:}                    # ASR 语言提示（如 zh），留空自动检测
    timeout-seconds: ${VOICE_TIMEOUT_SECONDS:30}    # ASR/TTS provider 调用超时
    stream-sentence-min-chars: ${VOICE_STREAM_MIN_CHARS:8}  # 半流式切句最小字数
    max-audio-bytes: ${VOICE_MAX_AUDIO_BYTES:26214400}      # 上传音频上限（25MB），超限 400
    conversation-base-url: ${VOICE_CONVERSATION_BASE_URL:http://localhost:8081}  # 大脑地址
    conversation-connect-timeout: ${VOICE_CONVERSATION_CONNECT_TIMEOUT:1s}
    conversation-read-timeout: ${VOICE_CONVERSATION_READ_TIMEOUT:60s}
```

要点：

- **provider 抽象**：`SpeechService` 接口 + `OpenAiSpeechService` 实现（JDK `HttpClient`，零新依赖）。ASR 走
  `POST {base-url}/audio/transcriptions`（multipart，字段 `file`+`model`[+`language`]），TTS 走
  `POST {base-url}/audio/speech`（JSON `model/voice/input/response_format`）。**`base-url` 一换即可指**
  云 OpenAI / Azure / 本地 `faster-whisper` + `openedai-speech` / 任意 OpenAI 兼容语音网关。接别家在 `SpeechService` 加实现。
- **没有内置 stub/内存实现**：跟平台多数「默认内存实现、开箱即跑」的能力不同，语音一旦 `VOICE_ENABLED=true`，
  **必须有一个可达的 OpenAI 兼容 ASR/TTS 后端**——否则 ASR/TTS 调用直接抛错。本地想不掏云费用，就起一套本地
  whisper+tts 网关，把 `VOICE_BASE_URL` 指过去、`VOICE_API_KEY` 留空。
- **两处大小限制**：`VOICE_MAX_AUDIO_BYTES`（业务层，超限返回 400）与 Spring 的 multipart 上限
  `VOICE_MAX_UPLOAD`（默认 25MB，`spring.servlet.multipart.max-file-size`）应保持一致。
- **api-key 空会 401**：连云 OpenAI 却没配 `VOICE_API_KEY`/`OPENAI_API_KEY` 时，启动日志会 warn，运行时 ASR/TTS 401。

---

## 5. 怎么跑 / 部署

### 5.1 本地单跑

```bash
# 起一个 OpenAI 兼容语音后端（云 OpenAI，或本地 faster-whisper + openedai-speech），并确保 conversation-service 在 :8081
VOICE_ENABLED=true \
VOICE_BASE_URL=https://api.openai.com/v1 \
VOICE_API_KEY=sk-... \
VOICE_CONVERSATION_BASE_URL=http://localhost:8081 \
  mvn -pl voice-service -am spring-boot:run     # 监听 :8091
```

本地直连 `:8091` 调试时需自带内部 JWT；走 `edge-gateway`（`:8080`）用 `X-Api-Key: dev-key-acme` 更省事（网关代签 JWT）。

### 5.2 整套栈

`voice-service` 已进 `deploy/docker-compose.yml`（`:8091`，`edge-gateway` 的 `VOICE_URI` 已指向它、`depends_on`
已挂）与 Helm chart（`deploy/helm/platform/values.yaml`，`VOICE_ENABLED: "false"`）。**两处默认都关**，
所以整栈起来时 `voice-service` 是个空壳容器（health 绿、无 `/voice/**`）。要启用：

```bash
# docker-compose：显式开开关 + 给 provider 凭据
VOICE_ENABLED=true VOICE_API_KEY=sk-... \
  docker compose -f deploy/docker-compose.yml up --build voice-service edge-gateway conversation-service
```

compose 里 `voice-service` 的 `VOICE_CONVERSATION_BASE_URL` 已固定为 `http://conversation-service:8081`。
Helm 生产启用时把 `VOICE_ENABLED` 置 `true`，`VOICE_API_KEY` 走 Secret / External Secrets Operator（勿提交真值）。
运行配置总览见 [`operations.md`](../参考/operations.md)，部署细节见 [`deployment-guide.md`](../平台工程/deployment-guide.md)。

---

## 6. 决策记录 / 坑 / 故意不做

**决策：**
- **`voice-service` 只是薄壳，脑子在 `conversation`**：ASR/TTS 与对话逻辑解耦，语音端零业务判断，行为可预测、可单测
  （`SentenceChunker`、`VoiceConversationService` 都是纯逻辑，测试用 mock 的 `SpeechService`/`ConversationClient`）。
- **provider 用 OpenAI 兼容协议**：跟平台 chat/embedding 一个路子，`base-url` 一换即换后端，不锁厂商、零新依赖。
- **引用标记只在语音侧剥、文字侧留**：`reply`/`audio-chunk.text` 保留 `[doc=...]`（可点引用），TTS 前统一 `stripCitations`。

**坑：**
- **ASR 错字会放大下游**：口音/噪声把关键词转错（「退款」听成「推广」）会误导对话。可配 `VOICE_LANGUAGE=zh` 给语言提示提准；进一步的置信度阈值 + 复述确认是未来项。
- **`route` 只有 CHAT/NONE**：别指望语音端直接触发工作流转人工——那是文本渠道（钉钉/飞书）+ `workflow-service` 的活，见第 1 节。
- **半流式上游不可中断**：客户端断连只停下游 TTS，`conversation` 的 SSE 仍会读完（token 已在生成）。
- **多租户 / PII**：音频也是用户数据，走同一鉴权链；审计会落 ASR 文字，敏感场景按需脱敏（下游 `conversation` 侧已有 PII/注入护栏）。

**故意不做：**

| 项 | 为什么 |
| --- | --- |
| 实时全双工 / barge-in | WebRTC+VAD+回声消除工程量大，turn-based + 半流式已覆盖主流；按信号再上 |
| 电话 IVR（SIP / 呼叫中心 webhook）+ 终态语音回拨 | 要对接 telephony provider（Twilio / 阿里云呼叫中心），独立集成项 |
| 语音端意图路由 → 工作流 | 复用 `/chat/auto` + 渠道桥 + `workflow-service` 即可，语音大脑收敛留作后续小重构 |
| 自训 ASR/TTS、声纹/情绪识别 | 超出客服核心闭环，provider 即够，按业务信号再加 |

---

## 7. 开关速查

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `VOICE_ENABLED` | `false` | 总开关。关 → 不装配任何 voice bean，`/voice/**` 返回 404 |
| `VOICE_PROVIDER` | `openai` | ASR/TTS provider，目前仅 openai 兼容协议 |
| `VOICE_BASE_URL` | `https://api.openai.com/v1` | provider base-url，可指云 OpenAI / Azure / 本地网关 |
| `VOICE_API_KEY` | 空（回退 `OPENAI_API_KEY`） | provider 凭据；连云却留空 → 401 |
| `VOICE_ASR_MODEL` | `whisper-1` | ASR 模型（或 `gpt-4o-transcribe` / 本地模型名） |
| `VOICE_TTS_MODEL` | `tts-1` | TTS 模型（或 `gpt-4o-mini-tts`） |
| `VOICE_TTS_VOICE` | `alloy` | TTS 音色 |
| `VOICE_TTS_FORMAT` | `mp3` | TTS 输出格式，决定回复 content-type（mp3/wav/opus/aac/flac/pcm） |
| `VOICE_LANGUAGE` | 空（自动检测） | ASR 语言提示，如 `zh` |
| `VOICE_TIMEOUT_SECONDS` | `30` | ASR/TTS provider 调用超时 |
| `VOICE_STREAM_MIN_CHARS` | `8` | 半流式切句最小字数，防过短句碎念 |
| `VOICE_MAX_AUDIO_BYTES` | `26214400`（25MB） | 上传音频业务上限，超限 400 |
| `VOICE_MAX_UPLOAD` | `25MB` | Spring multipart 上限，应与上一项一致 |
| `VOICE_CONVERSATION_BASE_URL` | `http://localhost:8081` | 大脑地址（conversation-service） |
| `VOICE_CONVERSATION_CONNECT_TIMEOUT` | `1s` | 调对话的连接超时 |
| `VOICE_CONVERSATION_READ_TIMEOUT` | `60s` | 调对话的读超时（含流式） |

---

## 关联文档

- 对话大脑 / RAG 增强（语音的下游） → [`rag-guide.md`](../对话与检索/rag-guide.md)
- 文本渠道（同一大脑的兄弟入口） → [`dingtalk-guide.md`](../互操作渠道/dingtalk-guide.md)
- 运行配置总览 → [`operations.md`](../参考/operations.md)；部署 → [`deployment-guide.md`](../平台工程/deployment-guide.md)
- 接口速查 → [`api-reference.md`](../参考/api-reference.md)；架构总览 → [`架构文档.md`](../参考/架构文档.md)
