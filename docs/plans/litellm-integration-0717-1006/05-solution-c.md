# 05 — 候选方案 C：LiteLLM 自定义鉴权集中映射

## 1. 核心思想

Java 不持有每租户 virtual key。`TenantAwareChatModel` 在三档中：`none` 原样、`user` 写标准 `user`、`virtual-key` 把平台签名的短时租户凭证放入自定义 header。LiteLLM 自定义 auth callback 验证该凭证，并在网关侧映射/创建对应 team/user/key policy。

## 2. 架构与模块职责

```text
Java gateway-client
  -> user=tenantId
  -> X-Platform-LiteLLM-Token: short-lived signed token
LiteLLM custom auth plugin (Python)
  -> verify signature/aud/exp/tenant
  -> resolve DB user/team/key policy
  -> authorize model + budget + rpm/tpm
PostgreSQL
  -> central identity-policy mapping + spend
```

- platform-security/edge：签发面向 LiteLLM 的独立 audience token，不能复用缺少明确 audience 的任意内部 token。
- LiteLLM custom plugin：验签、tenant 映射、fail-closed。
- Java 应用：无需分发 virtual key，只持有签名能力或由现有内部 token 派生。
- DB/cache/fallback/OTel 与其他方案一致。

## 3. 核心流程

1. 应用取得 `TenantContext`。
2. virtual-key 档为本次调用生成分钟级 token，包含 tenant、subject、audience、jti。
3. LiteLLM plugin 验证并查 PostgreSQL/Redis auth cache。
4. plugin 返回 LiteLLM 认可的 user/team/key 权限上下文。
5. spend 与硬限额全部在 LiteLLM 中央执行。

## 4. 改动范围

明显超出当前任务给出的主要范围：

- 新增 Python custom auth 文件与镜像构建，而当前 LiteLLM 直接使用官方镜像。
- platform-security 需要新的 token audience/issuer 配置和测试。
- 可能需要部署 JWKS/密钥轮换和 plugin 审计。
- 需要维护 LiteLLM plugin API 与版本兼容。

## 5. 安全、并发、幂等

- 优点是应用不持有长期租户 key，泄漏面显著缩小。
- 必须防止 token 重放；短 TTL + audience + nonce/jti 是否需要 Redis 去重取决于风险级别。
- 首次租户映射若自动创建，多个 pod 并发会产生幂等键/唯一约束竞争；应使用显式 control-plane provisioning，而非请求路径创建。
- auth plugin/DB 故障必须 fail-closed，会把 LiteLLM 变成更强的单点。

## 6. 扩展性

- 最适合大型平台统一身份与策略，不需要向所有工作负载下发 virtual key。
- 可自然承接 Casdoor/OIDC 或 service account。
- 可把模型授权、team、预算 tier 统一放在网关。

## 7. 实施成本与弱点

实施成本：高。

弱点：

- 引入仓库当前没有的 Python 插件开发、镜像供应链和 JWT 信任边界。
- LiteLLM plugin API 升级兼容风险高于 OpenAI 标准协议。
- 自动映射到 LiteLLM virtual-key 的具体 callback 合同依赖固定版本，当前仓库没有可复用实现，全部“待验证”。
- 已超出用户列明的主要代码范围，不能作为本轮直接交付而不扩权。

## 8. 适用结论

长期安全架构最优，但不是本次最稳妥实现。可把“避免分发长期 virtual key”作为后续里程碑，不应在本轮借机扩大到自定义鉴权平台。
