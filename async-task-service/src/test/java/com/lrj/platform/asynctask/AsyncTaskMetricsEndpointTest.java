package com.lrj.platform.asynctask;

import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.TenantContext;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.async-task.store=in-memory",
                "app.async-task.cleanup-initial-delay-ms=600000",
                "management.server.port=0",
                "management.prometheus.metrics.export.enabled=true",
                "management.endpoints.web.exposure.include=health,info,prometheus",
                "platform.security.jwt-secret=test-only-internal-secret-with-at-least-32-bytes",
                "platform.security.authentication-required=true"
        })
class AsyncTaskMetricsEndpointTest {

    @Autowired
    private TestRestTemplate http;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private AsyncTaskMetrics metrics;

    @Autowired
    private AsyncTaskStore store;

    @Autowired
    private InternalToken tokens;

    @Autowired
    private InternalSecurityProperties securityProperties;

    @Test
    void prometheusEndpointExportsAsyncTaskMetricsOnTheManagementPort() {
        assertThat(securityProperties.getJwtTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(securityProperties.getJwt().getClockSkew()).isEqualTo(Duration.ofSeconds(5));
        assertThat(securityProperties.getJwt().getIssuer()).isEqualTo("langchain4j-platform");
        assertThat(securityProperties.getJwt().getAudience()).isEqualTo("platform-internal");
        assertThat(securityProperties.getJwt().getKeyId()).isEqualTo("platform-internal-v1");
        metrics.orphanFailed("agent.run");
        AsyncTask pending = task(AsyncTaskStatus.PENDING);
        AsyncTask running = task(AsyncTaskStatus.PENDING);
        store.put(pending);
        store.put(running);
        assertThat(store.lease(
                running.taskId(),
                "metrics-worker",
                Instant.now().plusSeconds(30),
                null).acquired()).isTrue();
        // 内部 JWT 仍必须可签发校验（业务面鉴权不受指标面搬迁影响）
        TenantContext.Tenant tenant =
                new TenantContext.Tenant("qa", "metrics-scraper", Set.of("metrics"));
        assertThat(tokens.verify(tokens.mint(tenant))).isEqualTo(tenant);

        // 指标只在 management 端口暴露，无需凭据；业务端口不再服务 actuator（见 AsyncTaskManagementPortTest）
        ResponseEntity<String> response = http.getForEntity(
                "http://localhost:" + managementPort + "/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("async_task_orphan_failed_total")
                .contains("kind=\"agent.run\"")
                .contains("async_task_backlog 1.0")
                .contains("async_task_inflight 1.0");
    }

    private static AsyncTask task(AsyncTaskStatus status) {
        Instant now = Instant.now();
        return new AsyncTask(
                UUID.randomUUID().toString(),
                "qa",
                "metrics-scraper",
                "agent.run",
                status,
                Map.of("goal", "metrics"),
                null,
                null,
                null,
                now,
                now,
                null,
                null,
                null,
                0);
    }
}
