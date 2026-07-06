package com.lrj.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台安全配置：内部 JWT 签名密钥 / TTL / header 名，以及边缘处 API key → 租户 的映射表。
 *
 * <p>前缀 {@code platform.security.*}。边缘（edge-gateway）用 {@link #apiKeys} 把外部 {@code X-Api-Key}
 * 换成内部 JWT；下游服务只认 JWT，不再持有 API key 表。
 */
@ConfigurationProperties(prefix = "platform.security")
public class InternalSecurityProperties {

    /** 内部 JWT 的 HS256 签名密钥（≥32 字节）。生产走 Vault/K8s Secret，切勿硬编码。 */
    private String jwtSecret = "dev-only-internal-secret-change-me-please-32b";

    /** 内部 JWT 有效期（短时，仅覆盖一次跨服务调用链）。 */
    private Duration jwtTtl = Duration.ofMinutes(5);

    /** 内部 JWT 承载的请求头名。 */
    private String internalHeader = "X-Internal-Token";

    /** 外部 API key header 名（仅边缘识别）。 */
    private String apiKeyHeader = "X-Api-Key";

    /** 边缘：apiKey -> 租户绑定。key = 明文 api key。 */
    private Map<String, KeyBinding> apiKeys = new LinkedHashMap<>();

    /** 下游服务是否也接受直连的 {@code X-Api-Key}（本地调试用；生产可关，只信 JWT）。 */
    private boolean allowApiKeyFallback = true;

    public boolean isAllowApiKeyFallback() { return allowApiKeyFallback; }
    public void setAllowApiKeyFallback(boolean allowApiKeyFallback) { this.allowApiKeyFallback = allowApiKeyFallback; }

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public Duration getJwtTtl() { return jwtTtl; }
    public void setJwtTtl(Duration jwtTtl) { this.jwtTtl = jwtTtl; }
    public String getInternalHeader() { return internalHeader; }
    public void setInternalHeader(String internalHeader) { this.internalHeader = internalHeader; }
    public String getApiKeyHeader() { return apiKeyHeader; }
    public void setApiKeyHeader(String apiKeyHeader) { this.apiKeyHeader = apiKeyHeader; }
    public Map<String, KeyBinding> getApiKeys() { return apiKeys; }
    public void setApiKeys(Map<String, KeyBinding> apiKeys) { this.apiKeys = apiKeys; }

    public static class KeyBinding {
        private String tenant;
        private String user;
        private List<String> scopes = List.of();

        public String getTenant() { return tenant; }
        public void setTenant(String tenant) { this.tenant = tenant; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public List<String> getScopes() { return scopes; }
        public void setScopes(List<String> scopes) { this.scopes = scopes; }
    }
}
