package com.lrj.platform.gateway.tenant;

/**
 * 租户归因三档开关（{@code platform.gateway.tenant-attribution}）。控制每次 LLM 调用向 LiteLLM
 * 传递多少租户身份 —— LiteLLM 侧 spend 记账 / per-key 预算 / TPM·RPM 硬限流都以此为前提。
 *
 * <p>Spring relaxed binding：配置值写 {@code none} / {@code user} / {@code virtual-key}；
 * 非法值绑定失败 → 启动失败（宁可起不来，不可带错误归因偷跑）。
 */
public enum TenantAttributionMode {

    /** 不归因（默认）。请求体与凭证与接入前逐字一致 —— 共享 master key，LiteLLM 看不到租户。 */
    NONE,

    /**
     * 请求体 {@code user} 字段强制覆盖为可信 tenantId（取自 {@link TenantIdentityProvider}，
     * 调用方无法伪造）。仍用共享 key；LiteLLM 按 end-user 归集 spend，可配 per-customer 预算。
     */
    USER,

    /**
     * 在 {@link #USER} 基础上，HTTP {@code Authorization} 动态换成当前租户的 LiteLLM virtual key
     * —— key 级预算 / TPM·RPM / 模型白名单全部生效。key 缺失 fail-closed（调用 provider 前失败，
     * 绝不回退 master key）。
     */
    VIRTUAL_KEY
}
