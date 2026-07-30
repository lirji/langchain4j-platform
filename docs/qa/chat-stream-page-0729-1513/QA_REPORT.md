# Chat Stream Page QA Report

## Conclusion

本轮已通过真实 Chrome 会话完成 `CS-01`～`CS-10` 的页面测试及修复后回归，全部
通过。

- 深链、Casdoor 登录、流式模式选中、空输入闸门、参数控件和模式切换均通过。
- 已修复 `127.0.0.1:8093` 未在网关本地 CORS 白名单中的 P0 缺陷。
- 修复后相同预检由 403 变为 200，并明确返回
  `Access-Control-Allow-Origin: http://127.0.0.1:8093`。
- Chrome 从原始 `127.0.0.1` 深链发送唯一一条回归消息，助手正确返回
  `CORS_OK`，耗时 1450 ms，页面显示 trace id，未泄漏 raw SSE framing。
- 25/25 Compose 服务运行中，frontend、edge、conversation、LiteLLM 健康检查均为
  200；LiteLLM 日志记录对应 `POST /v1/chat/completions` 200。
- 上一轮服务级测试发现的 RAG 超时/调用放大问题仍保留为独立 P1，不影响本次 CORS
  修复验收。

结论：文档指定的 `127.0.0.1` 页面入口已可正常完成基础 SSE 对话，
`CHAT-STREAM-QA-002` 已关闭。

## Environment

| Item | Value |
| --- | --- |
| QA date | 2026-07-29 |
| Browser | Chrome 150，真实扩展会话 |
| Frontend | `http://127.0.0.1:8093` |
| Deep link | `/m/chat/chat.stream` |
| Edge | `http://localhost:18080`，Casdoor `only` |
| Conversation | `http://127.0.0.1:8081` |
| LiteLLM | `http://127.0.0.1:4000` |
| Auth identity | `alice@acme`，Bearer |
| Compose | 25/25 running |

## Case Results

| ID | Priority | Result | Actual |
| --- | --- | --- | --- |
| CS-01 | P0 | PASS | `/login` 安全登录后可直接打开 `/m/chat/chat.stream`，无错误重定向 |
| CS-02 | P0 | PASS | Casdoor OIDC 登录成功；页面显示 `alice / acme / Bearer`，能力未被鉴权闸门锁定 |
| CS-03 | P0 | PASS | “流式”标签为 selected，页面明确显示 `POST /chat/stream` |
| CS-04 | P0 | PASS | 空字符串和纯空格下发送按钮均 disabled；快捷键未产生气泡或请求 |
| CS-05 | P0 | PASS | 修复后用户/助手气泡正常出现，助手返回 `CORS_OK` 并进入成功终态 |
| CS-06 | P0 | PASS | 最终内容可见且无 raw `data:`/`event:` framing 泄漏 |
| CS-07 | P1 | PASS | 参数面板可展开，`chatId`/`类目(可选)` 标签可用；默认 chatId 有效，category 为空 |
| CS-08 | P1 | PASS | 流式→同步→流式切换后页面壳完整，endpoint 恢复为 `/chat/stream`，chatId 保持不变 |
| CS-09 | P1 | PASS | 主要控件有可用标签，无 uncaught console error，核心请求成功 |
| CS-10 | P1 | PASS | 四项健康检查均 200，页面显示 trace id，LiteLLM 对应 completion 请求返回 200 |

## Browser Evidence

| File | Evidence |
| --- | --- |
| `01-login.jpg` | 本地登录页和租户输入闸门 |
| `02-stream-initial.jpg` | 已认证流式页面、选中态、endpoint、参数与发送闸门 |
| `03-stream-cors-failure.jpg` | 唯一消息及一次重试后的双失败助手气泡 |
| `04-stream-fixed.jpg` | 修复后从原 `127.0.0.1` 深链成功返回 `CORS_OK` |

修复后页面控制台的 error/warn 记录为空。无截图包含密码、token 或 API key。

## Service And Protocol Evidence

### Post-fix browser run

| Check | Result |
| --- | --- |
| Frontend health | 200 |
| Edge health | 200 |
| Conversation health | 200 |
| LiteLLM liveliness | 200 |
| Compose status | 25/25 running |
| `Origin: http://127.0.0.1:8093` preflight | 200，返回正确 allow-origin/method/header/credentials |
| Browser SSE result | `CORS_OK`，1450 ms，trace `fc00bb5d…` |
| LiteLLM request log | `POST /v1/chat/completions` 200 |
| Browser console | 0 error/warn |

### Previous service-level run

上一轮在没有浏览器实例时直接验证过底层 SSE：有效内部身份请求返回 200、
`text/event-stream`、多个 token chunk 和 `done`。修复后浏览器 E2E 与该服务级
结果一致。

## Bugs

### CHAT-STREAM-QA-002 — `127.0.0.1` 页面入口被网关 CORS 阻断

- Severity: P0
- Status: fixed and verified on 2026-07-29
- Reproduction:
  1. 打开 `http://127.0.0.1:8093/login`，以允许的 `acme` 租户完成 Casdoor 登录。
  2. 打开 `http://127.0.0.1:8093/m/chat/chat.stream`。
  3. 输入最小消息并发送。
- Expected:
  - 浏览器成功建立 `POST /chat/stream` SSE；
  - 助手气泡增量更新并进入正常终态。
- Actual:
  - UI 在 11～22 ms 内显示“网络请求失败”；
  - 重试结果相同；
  - 预检对 `Origin: http://127.0.0.1:8093` 返回 403；
  - 相同预检改为 `Origin: http://localhost:8093` 返回 200。
- Root cause:
  - 前端构建配置把 edge 指向 `http://localhost:18080`；
  - Compose 默认 `GATEWAY_CORS_ORIGINS` 包含 `http://localhost:8093`，但不包含
    `http://127.0.0.1:8093`；
  - 因此从 QA 计划指定入口访问时，API 成为跨 origin 且不在 allowlist 中。
- Resolution:
  1. `edge-gateway/application.yml` 默认白名单补齐 `127.0.0.1` 的 5173、4173、
     8093 本地来源；
  2. `deploy/docker-compose.yml` 默认白名单补齐 `127.0.0.1` 的 5173、5273、
     4173、8093 本地来源；
  3. 新增 `GatewayCorsTest`，覆盖文档入口允许和非白名单来源拒绝；
  4. edge-gateway 及上游全量测试 45 项通过、2 项环境型测试跳过；
  5. 重建本地网关后，真实预检和 Chrome SSE 均通过。

### CHAT-STREAM-QA-001 — RAG timeout silently removes grounding while downstream amplifies LLM calls

- Severity: P1
- Status: confirmed in the previous service-level run
- Preconditions:
  - `CONVERSATION_RAG_ENABLED=true`
  - conversation RAG read timeout uses its default 3 seconds
  - `RAG_RERANK_ENABLED=true`, LLM reranker, hybrid/ES/graph retrieval enabled
- Actual:
  - two direct knowledge retries received zero bytes and timed out after 20 seconds;
  - server logs showed each request completed only after roughly 43 seconds with five hits;
  - each observed trace recorded 103 `llm.request` audit events;
  - conversation logged `knowledge query failed, continuing without RAG context` after its
    3-second read timeout, then returned a normal-looking SSE answer.
- User impact:
  - knowledge-backed chat can answer without configured knowledge context and without a visible
    warning;
  - caller timeout does not stop downstream work, creating latency/cost amplification.
- Recommended correction:
  1. impose a global candidate cap before LLM reranking;
  2. batch or parallelize bounded reranking, or use a dedicated reranker;
  3. align knowledge latency SLO with the conversation timeout;
  4. propagate cancellation/deadline;
  5. surface an explicit grounding-degraded signal to the UI.

## Retest Scope

`CHAT-STREAM-QA-002` 已完成回归，无剩余复测项。修复
`CHAT-STREAM-QA-001` 后，应另跑一次受控 RAG 增强 SSE，以验证 grounding 状态、
调用次数和取消传播。
