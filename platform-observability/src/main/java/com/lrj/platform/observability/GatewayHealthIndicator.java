package com.lrj.platform.observability;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Actuator HealthIndicator：对 LLM 网关（LiteLLM，{@code platform.gateway.base-url}）做 TCP 连通性探测。
 * v2 里 provider 路由都在网关，故这一个探测即覆盖所有下游 LLM 调用的网络就绪。
 *
 * <p>暴露在 {@code GET /actuator/health}，出现 {@code "gateway": {"status": "UP", ...}} 节点。
 * 未配 base-url → {@code UNKNOWN}。
 */
public class GatewayHealthIndicator implements HealthIndicator {

    private final String baseUrl;

    public GatewayHealthIndicator(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public Health health() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Health.unknown().withDetail("reason", "platform.gateway.base-url not configured").build();
        }
        return TcpHealthProbe.probeTcp(baseUrl, "target", baseUrl);
    }
}
