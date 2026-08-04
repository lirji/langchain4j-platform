# Webhook / Callback 安全接入

平台的 async-task 终态通知、Workflow 终态通知和 A2A push 共用同一套出站安全协议。该协议同时约束目标地址、HTTP 行为、消息签名和重试幂等；业务身份 JWT 不会发送给外部 callback。

## 1. 目标地址策略

生产部署默认要求预登记，且公网 callback 只允许 HTTPS：

- `PLATFORM_SECURITY_CALLBACK_REQUIRE_ALLOWED_ORIGIN=true`
- `PLATFORM_SECURITY_CALLBACK_ALLOW_HTTP=false`
- `PLATFORM_SECURITY_CALLBACK_ALLOWED_ORIGINS=https://hooks.example.com,https://partner.example.net:8443`

`allowed-origins` 是精确的 `scheme + host + effective port`，不能包含路径、query、userinfo 或 fragment。同一已登记 origin 下可以使用不同 callback 路径。未登记目标只能使用 80/443；生产开启预登记后该兼容规则不会放宽白名单。

注册 callback 和每次实际投递前都会重新校验：

- URL 必须是绝对 HTTP(S) URL，禁止 userinfo 与 fragment；
- DNS 必须可解析，且全部解析结果都必须是公网地址；混合公网/私网答案也拒绝；
- loopback、RFC1918、link-local、CGNAT、文档/基准网段、IPv6 ULA 等非公网地址拒绝；
- HTTP 客户端不跟随 3xx，3xx 直接按策略错误终止，不把请求重定向到新目标。

async-task 有一个单独的栈内例外：`http://interop-service:8088/interop/a2a/push-callback`。它以完整 URL 登记，只注入 async-task-service；同 origin 的 `/admin` 等其它路径仍会拒绝。Workflow 和 Interop 不接收该例外。

> DNS 校验与连接之间仍存在系统调用级 TOCTOU 窗口。生产必须同时使用 Kubernetes NetworkPolicy、出站防火墙或固定 egress proxy，禁止业务 Pod 访问云 metadata、控制面和非必要私网；应用校验不是网络层隔离的替代品。

Compose 为三条链路提供独立配置：`ASYNC_TASK_CALLBACK_ALLOWED_ORIGINS`、`WORKFLOW_CALLBACK_ALLOWED_ORIGINS`、`INTEROP_CALLBACK_ALLOWED_ORIGINS`，以及对应的 `*_CALLBACK_ALLOW_HTTP` / `*_CALLBACK_REQUIRE_ALLOWED_ORIGIN`。Helm 使用 `config.*_CALLBACK_ALLOWED_ORIGINS`，再只映射给对应发送服务。

## 2. 签名协议 v1

每次 HTTP POST 都携带：

| Header | 含义 |
|---|---|
| `X-Webhook-Event` | `async-task.finished`、`workflow.completed` 或 `a2a.task.finished` |
| `X-Webhook-Delivery` | 逻辑投递 ID；同一事件重试时保持不变 |
| `X-Webhook-Timestamp` | Unix epoch seconds |
| `X-Webhook-Signature` | `v1=<64 位小写 hex>` |

签名输入必须按下列顺序拼接，换行是单个 `\n`，`body` 是收到的原始 UTF-8 字节对应文本，不能先反序列化再序列化：

```text
<timestamp>\n<delivery-id>\n<event>\n<exact-body>
```

签名算法是 `HMAC-SHA256(secret, signing-input)`。接收方应：

1. 拒绝未知版本和缺失 header；
2. 以常量时间比较签名；
3. 限制时间偏差（建议不超过 5 分钟）；
4. 以 `X-Webhook-Delivery` 做持久化去重，并在业务提交成功后记录；
5. 验签和去重成功后才执行副作用。

三条链路使用至少 32 字节且互不复用的密钥：

- `ASYNC_TASK_WEBHOOK_HMAC_SECRET`：只注入 async-task-service；
- `WORKFLOW_OUTBOX_HMAC_SECRET`：只注入 workflow-service；
- `INTEROP_A2A_PUSH_HMAC_SECRET`：只注入 interop-service。

它们也不得复用内部 JWT、async worker JWT、AgentScope confirmation 或 downstream delegation key。服务在 HTTP 投递启用而密钥过短时启动失败。

## 3. 重试、上线与回滚

- 2xx 成功；3xx 和 callback policy 错误不重试；普通 4xx 不重试；5xx/网络错误按各服务配置有限重试。
- JDBC async-task 与 Workflow outbox 的 delivery id 从持久化事件标识派生，重启和重试后保持稳定。
- 上线前先把接收方升级为支持 v1 验签与 delivery 去重，再启用新发送方；密钥经 Vault/ESO 分发，不写入仓库。
- 回滚优先切 Kafka/轮询或暂时关闭 HTTP webhook。不要通过关闭 allowlist、允许公网 HTTP、放开私网地址或接受无签名请求来回滚。
- 若接收方需要兼容窗口，只能短时同时验证旧/新格式，并设置移除旧格式的截止时间；发送方密钥与目标地址策略保持严格。

静态生产门禁：

```bash
./deploy/test-production-cutover-config.sh
```

该脚本会检查三个发送服务的 HTTPS allowlist、精确内网例外、密钥长度/不复用，以及 Helm Secret 的最小挂载范围。
