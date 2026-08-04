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

    /** 内部 JWT 算法与非对称密钥配置（前缀 {@code platform.security.jwt.*}）。默认 HS256，沿用 {@link #jwtSecret}。 */
    private final Jwt jwt = new Jwt();

    /** async-task worker 数据面的独立短时 JWT（不得复用内部 JWT key）。 */
    private final AsyncWorker asyncWorker = new AsyncWorker();

    /** 用户可控出站 callback/webhook 的统一 SSRF 策略。 */
    private final Callback callback = new Callback();

    /** 同步服务间 HTTP 的 deadline、bulkhead 与 circuit-breaker。 */
    private final HttpResilience httpResilience = new HttpResilience();

    /**
     * 面向终端用户的会话令牌配置（前缀 {@code platform.security.session.*}）。
     * auth-service 用它签发登录后的会话访问 JWT，edge-gateway 用同一密钥验签后换发内部 JWT。
     * 与 {@link #jwtSecret}（内部令牌密钥）刻意分离，缩小密钥轮转爆炸半径。
     */
    private final Session session = new Session();

    /** 内部 JWT 有效期（短时，仅覆盖一次跨服务调用链）。 */
    private Duration jwtTtl = Duration.ofMinutes(5);

    /** 内部 JWT 承载的请求头名。 */
    private String internalHeader = "X-Internal-Token";

    /** 外部 API key header 名（仅边缘识别）。 */
    private String apiKeyHeader = "X-Api-Key";

    /** 内部服务回调 edge 时使用的短时签名令牌头；edge 校验后换成 {@link #internalHeader}。 */
    private String serviceTokenHeader = "X-Platform-Service-Token";

    /** 可接收服务回调令牌的可信 origin；防止自定义外部 URL 收到栈内凭据。 */
    private List<String> serviceTokenAllowedOrigins = List.of("http://edge-gateway:8080");

    /** 边缘：apiKey -> 租户绑定。key = 明文 api key。 */
    private Map<String, KeyBinding> apiKeys = new LinkedHashMap<>();

    /** 下游服务是否也接受直连的 {@code X-Api-Key}（仅显式本地调试时开启）。 */
    private boolean allowApiKeyFallback = false;

    /** 下游业务路径是否必须有有效内部 JWT；健康探针始终保持开放。 */
    private boolean authenticationRequired = true;

    public boolean isAllowApiKeyFallback() { return allowApiKeyFallback; }
    public void setAllowApiKeyFallback(boolean allowApiKeyFallback) { this.allowApiKeyFallback = allowApiKeyFallback; }
    public boolean isAuthenticationRequired() { return authenticationRequired; }
    public void setAuthenticationRequired(boolean authenticationRequired) { this.authenticationRequired = authenticationRequired; }

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public Jwt getJwt() { return jwt; }
    public AsyncWorker getAsyncWorker() { return asyncWorker; }
    public Callback getCallback() { return callback; }
    public HttpResilience getHttpResilience() { return httpResilience; }
    public Session getSession() { return session; }
    public Duration getJwtTtl() { return jwtTtl; }
    public void setJwtTtl(Duration jwtTtl) { this.jwtTtl = jwtTtl; }
    public String getInternalHeader() { return internalHeader; }
    public void setInternalHeader(String internalHeader) { this.internalHeader = internalHeader; }
    public String getApiKeyHeader() { return apiKeyHeader; }
    public void setApiKeyHeader(String apiKeyHeader) { this.apiKeyHeader = apiKeyHeader; }
    public String getServiceTokenHeader() { return serviceTokenHeader; }
    public void setServiceTokenHeader(String serviceTokenHeader) { this.serviceTokenHeader = serviceTokenHeader; }
    public List<String> getServiceTokenAllowedOrigins() { return serviceTokenAllowedOrigins; }
    public void setServiceTokenAllowedOrigins(List<String> serviceTokenAllowedOrigins) {
        this.serviceTokenAllowedOrigins = serviceTokenAllowedOrigins;
    }
    public Map<String, KeyBinding> getApiKeys() { return apiKeys; }
    public void setApiKeys(Map<String, KeyBinding> apiKeys) { this.apiKeys = apiKeys; }

    /**
     * 内部 JWT 算法与非对称密钥。前缀 {@code platform.security.jwt.*}。
     *
     * <p>{@code algorithm=HS256}（默认）沿用 {@link InternalSecurityProperties#jwtSecret} 对称密钥；
     * {@code algorithm=RS256} 时 edge-gateway 用 {@link #privateKey} 签发、下游用 {@link #publicKey} 验签。
     * 密钥支持 PEM（含头尾）或纯 base64：私钥 PKCS#8、公钥 X.509。
     */
    public static class Jwt {
        private String algorithm = "HS256";
        private String privateKey;
        private String publicKey;
        private String issuer = "langchain4j-platform";
        private String audience = "platform-internal";
        private String keyId = "platform-internal-v1";
        private Duration clockSkew = Duration.ofSeconds(5);

        public String getAlgorithm() { return algorithm; }
        public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = audience; }
        public String getKeyId() { return keyId; }
        public void setKeyId(String keyId) { this.keyId = keyId; }
        public Duration getClockSkew() { return clockSkew; }
        public void setClockSkew(Duration clockSkew) { this.clockSkew = clockSkew; }
    }

    public static class AsyncWorker {
        private String header = "X-Async-Worker-Token";
        private String secret = "dev-only-async-worker-secret-change-me-32b";
        private String issuer = "platform-services";
        private String audience = "async-task-worker";
        private String keyId = "async-task-worker-v1";
        private Duration ttl = Duration.ofSeconds(60);
        private Duration clockSkew = Duration.ofSeconds(5);
        private String serviceId;

        public String getHeader() { return header; }
        public void setHeader(String header) { this.header = header; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = audience; }
        public String getKeyId() { return keyId; }
        public void setKeyId(String keyId) { this.keyId = keyId; }
        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }
        public Duration getClockSkew() { return clockSkew; }
        public void setClockSkew(Duration clockSkew) { this.clockSkew = clockSkew; }
        public String getServiceId() { return serviceId; }
        public void setServiceId(String serviceId) { this.serviceId = serviceId; }
    }

    public static class Callback {
        /** 生产应开启：仅允许预登记的精确 origin。 */
        private boolean requireAllowedOrigin = false;
        /** 本地开发可开启；生产外部 callback 应只允许 HTTPS。 */
        private boolean allowHttp = true;
        /** 允许的公网精确 origin（scheme + host + effective port）。 */
        private List<String> allowedOrigins = List.of();
        /** 明确可信的栈内精确 URL；只有这些完整目标可以解析到非公网地址。 */
        private List<String> trustedInternalUrls = List.of();
        private int maxUrlLength = 2048;

        public boolean isRequireAllowedOrigin() { return requireAllowedOrigin; }
        public void setRequireAllowedOrigin(boolean requireAllowedOrigin) {
            this.requireAllowedOrigin = requireAllowedOrigin;
        }
        public boolean isAllowHttp() { return allowHttp; }
        public void setAllowHttp(boolean allowHttp) { this.allowHttp = allowHttp; }
        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins;
        }
        public List<String> getTrustedInternalUrls() { return trustedInternalUrls; }
        public void setTrustedInternalUrls(List<String> trustedInternalUrls) {
            this.trustedInternalUrls = trustedInternalUrls == null ? List.of() : trustedInternalUrls;
        }
        public int getMaxUrlLength() { return maxUrlLength; }
        public void setMaxUrlLength(int maxUrlLength) { this.maxUrlLength = maxUrlLength; }
    }

    public static class HttpResilience {
        private boolean enabled = true;
        private String deadlineHeader = "X-Request-Deadline-Ms";
        private Duration defaultDeadline = Duration.ofSeconds(30);
        private Duration maxInboundDeadline = Duration.ofMinutes(5);
        private int maxConcurrentPerOrigin = 64;
        private int circuitFailureThreshold = 5;
        private Duration circuitRecovery = Duration.ofSeconds(15);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDeadlineHeader() { return deadlineHeader; }
        public void setDeadlineHeader(String deadlineHeader) { this.deadlineHeader = deadlineHeader; }
        public Duration getDefaultDeadline() { return defaultDeadline; }
        public void setDefaultDeadline(Duration defaultDeadline) { this.defaultDeadline = defaultDeadline; }
        public Duration getMaxInboundDeadline() { return maxInboundDeadline; }
        public void setMaxInboundDeadline(Duration maxInboundDeadline) { this.maxInboundDeadline = maxInboundDeadline; }
        public int getMaxConcurrentPerOrigin() { return maxConcurrentPerOrigin; }
        public void setMaxConcurrentPerOrigin(int maxConcurrentPerOrigin) {
            this.maxConcurrentPerOrigin = maxConcurrentPerOrigin;
        }
        public int getCircuitFailureThreshold() { return circuitFailureThreshold; }
        public void setCircuitFailureThreshold(int circuitFailureThreshold) {
            this.circuitFailureThreshold = circuitFailureThreshold;
        }
        public Duration getCircuitRecovery() { return circuitRecovery; }
        public void setCircuitRecovery(Duration circuitRecovery) { this.circuitRecovery = circuitRecovery; }
    }

    /**
     * 会话令牌配置。前缀 {@code platform.security.session.*}。
     *
     * <p>{@link #jwtSecret} 为会话访问 JWT 的 HS256 密钥（≥32 字节）；{@link #accessTtl} 是访问令牌有效期
     * （短时，前端内存持有）；{@link #refreshTtl} 是刷新令牌有效期（httpOnly cookie，支持静默续期）。
     */
    public static class Session {
        /** 会话访问 JWT 的 HS256 密钥（≥32 字节）。生产走 env/Vault/K8s Secret，切勿硬编码。 */
        private String jwtSecret = "dev-only-session-secret-change-me-please-32b";
        /** 会话访问 JWT 有效期（前端内存持有；过期由刷新令牌静默续期）。 */
        private Duration accessTtl = Duration.ofMinutes(60);
        /** 刷新令牌有效期（httpOnly cookie；轮转续期）。 */
        private Duration refreshTtl = Duration.ofDays(7);

        public String getJwtSecret() { return jwtSecret; }
        public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
        public Duration getAccessTtl() { return accessTtl; }
        public void setAccessTtl(Duration accessTtl) { this.accessTtl = accessTtl; }
        public Duration getRefreshTtl() { return refreshTtl; }
        public void setRefreshTtl(Duration refreshTtl) { this.refreshTtl = refreshTtl; }
    }

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
