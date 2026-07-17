package com.lrj.platform.gateway;

import com.lrj.platform.gateway.tenant.TenantAttributionMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 指向外部 AI 网关（LiteLLM Proxy）的配置。前缀 {@code platform.gateway}。
 *
 * <p>所有服务的 ChatModel 统一走 LiteLLM 的 OpenAI 兼容端点；provider/model 选择与跨 provider
 * failover 下沉到 LiteLLM 的 {@code config.yaml}（model_list + fallbacks）。本地默认指向
 * docker-compose 里的 litellm 容器。
 */
@ConfigurationProperties(prefix = "platform.gateway")
public class GatewayClientProperties {

    /** LiteLLM OpenAI-compat base-url。 */
    private String baseUrl = "http://localhost:4000/v1";

    /** LiteLLM master key / 虚拟 key。 */
    private String apiKey = "sk-litellm-master";

    /** 逻辑模型名（对应 LiteLLM config.yaml 里 model_list 的 model_name）。 */
    private String modelName = "chat-default";

    private Double temperature = 0.7;
    private Duration timeout = Duration.ofSeconds(60);
    private int maxRetries = 3;
    private boolean logRequests = false;
    private boolean logResponses = false;

    /**
     * 租户归因三档开关：{@code none}（默认，与接入前逐字一致）/ {@code user}（请求体 user=tenantId，
     * LiteLLM 按 end-user 记账）/ {@code virtual-key}（另加 per-tenant key，LiteLLM 预算/限流硬保底）。
     * 非法值绑定失败 → 启动失败。virtual key 本身<strong>不在</strong>本 bean 承载（避免
     * configprops 暴露），见 {@code EnvironmentTenantVirtualKeyResolver}。
     */
    private TenantAttributionMode tenantAttribution = TenantAttributionMode.NONE;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public boolean isLogRequests() { return logRequests; }
    public void setLogRequests(boolean logRequests) { this.logRequests = logRequests; }
    public boolean isLogResponses() { return logResponses; }
    public void setLogResponses(boolean logResponses) { this.logResponses = logResponses; }
    public TenantAttributionMode getTenantAttribution() { return tenantAttribution; }
    public void setTenantAttribution(TenantAttributionMode tenantAttribution) { this.tenantAttribution = tenantAttribution; }
}
