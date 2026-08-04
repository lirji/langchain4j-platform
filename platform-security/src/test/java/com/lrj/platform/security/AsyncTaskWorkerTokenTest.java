package com.lrj.platform.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AsyncTaskWorkerTokenTest {

    private static final String SECRET = "test-async-worker-secret-with-at-least-32-bytes";
    private final AsyncTaskWorkerToken tokens = new AsyncTaskWorkerToken(
            SECRET,
            Duration.ofSeconds(60),
            Duration.ofSeconds(5),
            "platform-services",
            "async-task-worker",
            "async-task-worker-v1",
            "agentscope-orchestrator");

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void roundTripsStrictOperationTaskAndWorkerIdentity() {
        TenantContext.Tenant tenant = new TenantContext.Tenant("acme", "alice", Set.of("agent"));
        String token = tokens.mint(tenant, "agentscope-orchestrator", "lease", "task-1");

        assertEquals(
                new AsyncTaskWorkerToken.Principal(
                        "agentscope-orchestrator", "acme", "alice", "agentscope-orchestrator"),
                tokens.verify(token, "lease", "task-1", "agentscope-orchestrator"));
        assertNull(tokens.verify(token, "status", "task-1", "agentscope-orchestrator"));
        assertNull(tokens.verify(token, "lease", "task-2", "agentscope-orchestrator"));
        assertNull(tokens.verify(token, "lease", "task-1", "other-worker"));
    }

    @Test
    void serviceMayMintForOneUniqueReplicaOwnerWithoutImpersonatingAnotherService() {
        TenantContext.Tenant tenant = new TenantContext.Tenant("acme", "alice", Set.of("agent"));
        String replica = "agentscope-orchestrator.pod-a";
        String token = tokens.mint(tenant, replica, "lease", "task-1");

        assertEquals(
                new AsyncTaskWorkerToken.Principal(
                        "agentscope-orchestrator", "acme", "alice", replica),
                tokens.verify(token, "lease", "task-1", replica));
        assertNull(tokens.verify(token, "lease", "task-1", "agentscope-orchestrator.pod-b"));
        assertThrows(IllegalArgumentException.class,
                () -> tokens.mint(tenant, "workflow-service.pod-a", "lease", "task-1"));
    }

    @Test
    void rejectsWorkerIdImpersonationAndUnsupportedAction() {
        TenantContext.Tenant tenant = new TenantContext.Tenant("acme", "alice", Set.of("agent"));

        assertThrows(IllegalArgumentException.class,
                () -> tokens.mint(tenant, "other-worker", "lease", "task-1"));
        assertThrows(IllegalArgumentException.class,
                () -> tokens.mint(tenant, "agentscope-orchestrator", "cancel", "task-1"));
    }

    @Test
    void wrongKeyAndOrdinaryInternalJwtAreRejected() {
        TenantContext.Tenant tenant = new TenantContext.Tenant("acme", "alice", Set.of("agent"));
        String token = tokens.mint(tenant, "agentscope-orchestrator", "event", "task-1");
        AsyncTaskWorkerToken wrongKey = new AsyncTaskWorkerToken(
                "different-async-worker-secret-with-at-least-32-bytes",
                Duration.ofSeconds(60),
                Duration.ofSeconds(5),
                "platform-services",
                "async-task-worker",
                "async-task-worker-v1",
                "agentscope-orchestrator");

        assertNull(wrongKey.verify(token, "event", "task-1", "agentscope-orchestrator"));
        String internal = new InternalToken(
                "different-internal-secret-with-at-least-32-bytes", Duration.ofMinutes(5)).mint(tenant);
        assertNull(tokens.verify(internal, "event", "task-1", "agentscope-orchestrator"));
    }

    @Test
    void forwarderReplacesCallerJwtOnlyForWorkerDataPlane() throws Exception {
        TenantContext.Tenant tenant = new TenantContext.Tenant("acme", "alice", Set.of("agent"));
        TenantContext.set(tenant);
        AsyncTaskWorkerTokenForwarder forwarder = new AsyncTaskWorkerTokenForwarder(
                tokens, "X-Async-Worker-Token", "X-Internal-Token");
        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.POST, URI.create("http://async-task:8086/async/tasks/task-1/lease"));
        request.getHeaders().set("X-Internal-Token", "raw-caller-token");

        forwarder.intercept(request, new byte[0], (req, body) ->
                new MockClientHttpResponse(new byte[0], 200));

        assertNull(request.getHeaders().getFirst("X-Internal-Token"));
        String token = request.getHeaders().getFirst("X-Async-Worker-Token");
        assertEquals(
                new AsyncTaskWorkerToken.Principal(
                        "agentscope-orchestrator", "acme", "alice", "agentscope-orchestrator"),
                tokens.verify(token, "lease", "task-1", null));
    }

    @Test
    void tenantForwarderSkipsInternalJwtWhenWorkerCredentialIsAlreadyPresent() throws Exception {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("agent")));
        InternalToken internalTokens = mock(InternalToken.class);
        OutboundTenantForwarder tenantForwarder = new OutboundTenantForwarder(
                internalTokens, "X-Internal-Token", "X-Async-Worker-Token");
        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.PATCH, URI.create("http://async-task:8086/async/tasks/task-1/status"));
        request.getHeaders().set("X-Async-Worker-Token", "operation-bound-worker-token");
        request.getHeaders().set("X-Internal-Token", "raw-caller-token");

        tenantForwarder.intercept(request, new byte[0], (req, body) ->
                new MockClientHttpResponse(new byte[0], 200));

        assertNull(request.getHeaders().getFirst("X-Internal-Token"));
        verifyNoInteractions(internalTokens);
    }
}
