package com.lrj.platform.knowledge.ingest.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.util.List;

/** 数据源只能由服务端配置，调用者不能用同步接口指定任意URL或冒用其他租户。 */
@ConfigurationProperties("app.rag.http-sync")
public record KnowledgeSyncProperties(@DefaultValue("true") boolean enabled, List<Source> sources) {
    public KnowledgeSyncProperties {
        sources = sources == null ? List.of() : List.copyOf(sources);
        if (sources.size() > 20) throw new IllegalArgumentException("at most 20 sync sources allowed");
        var identities = new java.util.HashSet<String>();
        for (Source source : sources) {
            source.validate();
            if (!identities.add(source.tenantId() + "\n" + source.id()))
                throw new IllegalArgumentException("duplicate sync source");
        }
    }

    /** 绑定的服务身份由部署管理员授予；令牌只用于外部源，禁止持久化或输出。 */
    public record Source(String id, String tenantId, String userId, String department,
                         URI endpoint, String bearerToken, @DefaultValue("false") boolean allowInsecureHttp, List<String> operatorUserIds) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Source { operatorUserIds = operatorUserIds == null ? List.of() : List.copyOf(operatorUserIds); }
        public Source(String id, String tenantId, String userId, String department, URI endpoint, String bearerToken, boolean allowInsecureHttp) {
            this(id, tenantId, userId, department, endpoint, bearerToken, allowInsecureHttp, List.of());
        }
        /** 显式运营授权限定在来源租户内；不能借授权名单更改后台执行身份。 */
        public void validate() {
            if (operatorUserIds.size() > 50 || operatorUserIds.stream().anyMatch(v -> !validIdentity(v) || "anonymous".equals(v)))
                throw new IllegalArgumentException("invalid source operators");
            if (id == null || !id.matches("[a-zA-Z0-9_-]{1,64}") || !validIdentity(tenantId)
                    || !validIdentity(userId) || "__public__".equals(tenantId) || tenantId.contains("/") || tenantId.contains("\\")
                    || tenantId.equals(".") || tenantId.equals("..") || "anonymous".equals(tenantId) || "anonymous".equals(userId)
                    || department != null && !validIdentity(department))
                throw new IllegalArgumentException("invalid sync source identity");
            if (endpoint == null || endpoint.toASCIIString().length() > 2048 || endpoint.getHost() == null || endpoint.getUserInfo() != null
                    || endpoint.getFragment() != null || endpoint.getQuery() != null
                    || !("https".equals(endpoint.getScheme())
                    || allowInsecureHttp && "http".equals(endpoint.getScheme())))
                throw new IllegalArgumentException("sync endpoint requires HTTPS without query, fragment or credentials");
            if (bearerToken != null && (bearerToken.length() > 8192 || bearerToken.contains("\r") || bearerToken.contains("\n")))
                throw new IllegalArgumentException("invalid source token");
        }
        private static boolean validIdentity(String value) {
            return value != null && !value.isBlank() && value.equals(value.trim()) && value.length() <= 256
                    && value.chars().noneMatch(Character::isISOControl);
        }
        @Override public String toString() { return "Source[id=" + id + ", credentials=REDACTED]"; }
    }
}
