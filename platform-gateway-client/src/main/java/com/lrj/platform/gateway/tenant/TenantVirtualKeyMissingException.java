package com.lrj.platform.gateway.tenant;

/**
 * {@link TenantAttributionMode#VIRTUAL_KEY} 下当前租户无 virtual key —— fail-closed，在发起
 * provider 调用前抛出。<strong>消息只含 tenantId，绝不含任何 key 内容。</strong>
 *
 * <p>这是应用配置错误（该租户未在 {@code platform.gateway.tenant-virtual-keys.*} 或自定义
 * {@link TenantVirtualKeyResolver} 中开通），与 LiteLLM 返回的预算/限流拒绝（OpenAI 兼容非 2xx）区分。
 */
public class TenantVirtualKeyMissingException extends IllegalStateException {

    private final String tenantId;

    public TenantVirtualKeyMissingException(String tenantId) {
        super("tenant '" + tenantId + "' has no LiteLLM virtual key configured"
                + " (platform.gateway.tenant-attribution=virtual-key is fail-closed)");
        this.tenantId = tenantId;
    }

    public String tenantId() {
        return tenantId;
    }
}
