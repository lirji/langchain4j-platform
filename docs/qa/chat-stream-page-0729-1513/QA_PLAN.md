# Chat Stream Page QA Plan

## Target

- Page: `http://127.0.0.1:8093/m/chat/chat.stream`
- API chain: browser → edge `:18080` → conversation-service → LiteLLM → provider
- Scope: 页面深链、鉴权、表单闸门、SSE 正常流、终态展示、参数与基础可用性

## Approval And Safety

- 用户明确要求从该页面进入并执行服务内部功能自检，视为本轮 localhost live QA 批准。
- 只访问 localhost。
- 正常流最多发送一条最小模型请求，不做批量、质量评测或压力测试。
- 不修改生产 `AGENT_URI`、服务配置、业务数据或源码。
- 原始 token/API key 不写入截图、日志或报告。

## Preconditions

- Java Compose 25/25 services running.
- Frontend, edge, conversation-service and LiteLLM health checks return 200.
- Use an existing browser session when available; otherwise use the documented local demo
  login flow.

## Cases

| ID | Priority | Check | Expected |
| --- | --- | --- | --- |
| CS-01 | P0 | Open the deep link | Page loads or safely redirects to login and returns to the deep link |
| CS-02 | P0 | Authentication state | A valid local session enables the chat capability; no credential remains gated |
| CS-03 | P0 | Active capability | “流式” mode and `/chat/stream` are visibly selected |
| CS-04 | P0 | Empty input gate | Send action is disabled or no request is issued for blank input |
| CS-05 | P0 | Minimal SSE request | User/assistant bubbles appear and response reaches a non-error terminal state |
| CS-06 | P0 | SSE protocol/UI projection | Incremental or final content is visible; no raw SSE framing leaks into the bubble |
| CS-07 | P1 | Parameter controls | chatId/category controls open, are labeled, and preserve valid defaults |
| CS-08 | P1 | Navigation/mode stability | Switching chat modes and returning to stream does not lose the page shell |
| CS-09 | P1 | Accessibility/basic console health | Main controls have usable labels; no uncaught page error or failed core request |
| CS-10 | P1 | Backend correlation | Edge/conversation/LiteLLM stay healthy and logs show the expected request path |

## Evidence

- Browser screenshots under this directory.
- Visible page state and browser network/console evidence.
- Sanitized service health/log observations.
- Failures are retried once before being classified.
