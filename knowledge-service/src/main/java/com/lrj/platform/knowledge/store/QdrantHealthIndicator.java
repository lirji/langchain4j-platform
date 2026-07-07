package com.lrj.platform.knowledge.store;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.QdrantOuterClass.HealthCheckReply;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.time.Duration;

/**
 * actuator health 指示器：qdrant provider 下汇报 Qdrant 连通性与版本（{@code /actuator/health}）。
 * 仅在 {@code app.rag.vector-store.provider=qdrant} 时注册。
 */
public class QdrantHealthIndicator implements HealthIndicator {

    private final QdrantClient client;
    private final Duration timeout;

    public QdrantHealthIndicator(QdrantClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
    }

    @Override
    public Health health() {
        try {
            HealthCheckReply reply = client.healthCheckAsync(timeout).get();
            return Health.up()
                    .withDetail("title", reply.getTitle())
                    .withDetail("version", reply.getVersion())
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down(e).build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.toString()).build();
        }
    }
}
