package com.lrj.platform.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundServiceTokenForwarderTest {

    private static final String SECRET = "test-secret-at-least-32-bytes-long-000";
    private final InternalToken tokens = new InternalToken(SECRET, Duration.ofMinutes(5));
    private final OutboundServiceTokenForwarder forwarder = new OutboundServiceTokenForwarder(
            tokens, "X-Platform-Service-Token", List.of("http://edge-gateway:8080"));

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void forwardsDedicatedTokenOnlyToAllowedOrigin() throws Exception {
        TenantContext.Tenant tenant = new TenantContext.Tenant("acme", "evaluator", Set.of("eval"));
        TenantContext.set(tenant);
        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.POST, URI.create("http://edge-gateway:8080/rag/query"));

        forwarder.intercept(request, new byte[0], (req, body) ->
                new MockClientHttpResponse(new byte[0], 200));

        String token = request.getHeaders().getFirst("X-Platform-Service-Token");
        assertThat(tokens.verifyService(token)).isEqualTo(tenant);
    }

    @Test
    void doesNotLeakTokenToUntrustedTarget() throws Exception {
        TenantContext.set(new TenantContext.Tenant("acme", "evaluator", Set.of("eval")));
        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.POST, URI.create("https://attacker.example/rag/query"));

        forwarder.intercept(request, new byte[0], (req, body) ->
                new MockClientHttpResponse(new byte[0], 200));

        assertThat(request.getHeaders().getFirst("X-Platform-Service-Token")).isNull();
    }
}
