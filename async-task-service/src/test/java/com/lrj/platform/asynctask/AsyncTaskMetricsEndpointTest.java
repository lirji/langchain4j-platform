package com.lrj.platform.asynctask;

import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.async-task.store=in-memory",
                "app.async-task.cleanup-initial-delay-ms=600000",
                "platform.security.jwt-secret=test-only-internal-secret-with-at-least-32-bytes",
                "platform.security.authentication-required=true"
        })
class AsyncTaskMetricsEndpointTest {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private AsyncTaskMetrics metrics;

    @Autowired
    private InternalToken tokens;

    @Test
    void prometheusEndpointRequiresAuthenticationAndExportsAsyncTaskMetrics() {
        metrics.orphanFailed("agent.run");
        assertThat(http.getForEntity("/actuator/prometheus", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", tokens.mint(
                new TenantContext.Tenant("qa", "metrics-scraper", Set.of("metrics"))));

        ResponseEntity<String> response = http.exchange(
                "/actuator/prometheus",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("async_task_orphan_failed_total")
                .contains("kind=\"agent.run\"");
    }
}
