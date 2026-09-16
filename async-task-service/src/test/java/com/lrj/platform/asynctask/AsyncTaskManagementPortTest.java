package com.lrj.platform.asynctask;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AsyncTaskManagementPortTest：验证独立 management 端口的采集口径。指标面统一后需要 Prometheus 能抓取，
 * 但业务端口上的 {@code /actuator/prometheus} 被内部 JWT filter 拦住，而内部 JWT 只活 5 分钟，无法配成
 * 静态抓取凭据。这里断言把 actuator 挪到独立 management 端口后：该端口不经过父上下文注册的租户 filter
 * （可无凭据抓取，只在内网暴露），业务端口不再暴露 actuator，且业务接口的鉴权不受影响。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.async-task.store=in-memory",
                "app.async-task.cleanup-initial-delay-ms=600000",
                "management.server.port=0",
                "management.endpoints.web.exposure.include=health,info,prometheus",
                "platform.security.jwt-secret=test-only-internal-secret-with-at-least-32-bytes",
                "platform.security.authentication-required=true"
        })
class AsyncTaskManagementPortTest {

    @Autowired
    private TestRestTemplate http;

    @LocalServerPort
    private int businessPort;

    @LocalManagementPort
    private int managementPort;

    @Test
    void managementPortServesPrometheusWithoutTenantCredentials() {
        assertThat(managementPort).isNotEqualTo(businessPort);

        ResponseEntity<String> metrics = http.getForEntity(
                "http://localhost:" + managementPort + "/actuator/prometheus", String.class);

        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metrics.getBody()).contains("async_task_backlog");
    }

    @Test
    void managementPortServesHealthProbesWithoutTenantCredentials() {
        ResponseEntity<String> readiness = http.getForEntity(
                "http://localhost:" + managementPort + "/actuator/health/readiness", String.class);

        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void businessPortNeverServesMetrics() {
        ResponseEntity<String> onBusinessPort = http.getForEntity(
                "http://localhost:" + businessPort + "/actuator/prometheus", String.class);

        // 租户 filter 在路由之前执行，所以业务端口不是 404 而是 401：actuator 已不在该端口，
        // 且连「这个路径存不存在」都不泄露。
        assertThat(onBusinessPort.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(onBusinessPort.getBody()).doesNotContain("async_task_backlog");
    }

    @Test
    void businessPortStillRequiresInternalCredentials() {
        ResponseEntity<String> tasks = http.getForEntity(
                "http://localhost:" + businessPort + "/async/tasks", String.class);

        assertThat(tasks.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
