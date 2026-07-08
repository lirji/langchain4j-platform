package com.lrj.platform.knowledge.observability;

import com.lrj.platform.observability.TcpHealthProbe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Embedding 后端的 TCP 连通性 health check（迁移单体 {@code EmbeddingHealthIndicator}）：按
 * {@code app.rag.embedding.provider} 选中的后端探测，复用 {@link TcpHealthProbe}。
 *
 * <p>默认 {@code hash} provider 为进程内确定性实现、无网络端点 → {@code UNKNOWN}。
 * 暴露在 {@code GET /actuator/health} 的 {@code "embedding"} 节点。
 */
@Component("embedding")
public class EmbeddingHealthIndicator implements HealthIndicator {

    private final String provider;
    private final String ollamaBaseUrl;
    private final String gatewayBaseUrl;

    public EmbeddingHealthIndicator(
            @Value("${app.rag.embedding.provider:hash}") String provider,
            @Value("${app.rag.embedding.ollama.base-url:}") String ollamaBaseUrl,
            @Value("${platform.gateway.base-url:}") String gatewayBaseUrl) {
        this.provider = provider;
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.gatewayBaseUrl = gatewayBaseUrl;
    }

    @Override
    public Health health() {
        String target = switch (provider == null ? "hash" : provider.toLowerCase()) {
            case "ollama" -> ollamaBaseUrl;
            case "openai", "openai-compat", "gateway" -> gatewayBaseUrl;
            default -> null; // hash 等进程内实现无网络端点
        };
        if (target == null || target.isBlank()) {
            return Health.unknown()
                    .withDetail("provider", provider)
                    .withDetail("reason", "no network endpoint for this embedding provider")
                    .build();
        }
        return TcpHealthProbe.probeTcp(target, "provider", provider, "target", target);
    }
}
