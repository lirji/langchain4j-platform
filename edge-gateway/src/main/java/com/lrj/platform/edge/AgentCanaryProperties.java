package com.lrj.platform.edge;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AgentScope 灰度路由配置。全量切换后静态基线默认也是 AgentScope；租户灰度只用于候选版本，
 * Java 回滚时需显式把基线标记改为 {@code legacy-java}。
 */
@Component
@ConfigurationProperties(prefix = "edge.agent-canary")
public class AgentCanaryProperties {

    private boolean enabled;
    private URI uri = URI.create("http://localhost:18085");
    private List<String> tenants = new ArrayList<>();
    private String baselineBackend = "agentscope";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public List<String> getTenants() {
        return tenants;
    }

    public void setTenants(List<String> tenants) {
        this.tenants = tenants == null ? new ArrayList<>() : new ArrayList<>(tenants);
    }

    public String getBaselineBackend() {
        return baselineBackend;
    }

    public void setBaselineBackend(String baselineBackend) {
        this.baselineBackend = baselineBackend;
    }

    Set<String> normalizedTenants() {
        Set<String> normalized = new LinkedHashSet<>();
        for (String tenant : tenants) {
            if (tenant != null && !tenant.isBlank()) {
                normalized.add(tenant.trim());
            }
        }
        return Set.copyOf(normalized);
    }

    @PostConstruct
    void validate() {
        if (!Set.of("agentscope", "legacy-java").contains(baselineBackend)) {
            throw new IllegalStateException(
                    "edge.agent-canary.baseline-backend 必须是 agentscope 或 legacy-java");
        }
        if (uri == null
                || uri.getScheme() == null
                || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath()))) {
            throw new IllegalStateException(
                    "edge.agent-canary.uri 必须是无凭据、query、fragment 或路径前缀的绝对 HTTP(S) 地址");
        }
    }
}
