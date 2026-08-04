package com.lrj.platform.asynctask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.security.AsyncTaskWorkerToken;
import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        properties = {
                "app.async-task.store=in-memory",
                "app.async-task.cleanup-initial-delay-ms=600000",
                "platform.security.jwt-secret=test-only-internal-secret-with-at-least-32-bytes",
                "platform.security.async-worker.secret=test-async-worker-secret-with-at-least-32-bytes",
                "platform.security.authentication-required=true"
        })
@AutoConfigureMockMvc
class AsyncTaskWorkerAuthorizationEndpointTest {

    private static final String WORKER_SECRET = "test-async-worker-secret-with-at-least-32-bytes";

    @Autowired
    private MockMvc http;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InternalToken internalTokens;

    @Autowired
    private InternalSecurityProperties security;

    @Test
    void ordinaryUserTokenCannotLeaseButBoundWorkerTokenCanWithoutCallerJwt() throws Exception {
        TenantContext.Tenant owner = new TenantContext.Tenant("acme", "alice", Set.of("agent"));
        String userToken = internalTokens.mint(owner);
        String created = http.perform(post("/async/tasks")
                        .header(security.getInternalHeader(), userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("kind", "agent.run"))))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String taskId = objectMapper.readTree(created).get("taskId").asText();

        http.perform(post("/async/tasks/{taskId}/lease", taskId)
                        .header(security.getInternalHeader(), userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("workerId", "agentscope-orchestrator", "leaseSeconds", 60))))
                .andExpect(status().isUnauthorized());

        AsyncTaskWorkerToken signer = new AsyncTaskWorkerToken(
                WORKER_SECRET,
                Duration.ofSeconds(60),
                Duration.ofSeconds(5),
                "platform-services",
                "async-task-worker",
                "async-task-worker-v1",
                "agentscope-orchestrator");
        String workerToken = signer.mint(owner, "agentscope-orchestrator", "lease", taskId);
        String leased = http.perform(post("/async/tasks/{taskId}/lease", taskId)
                        .header(security.getAsyncWorker().getHeader(), workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("workerId", "agentscope-orchestrator", "leaseSeconds", 60))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseOwnerId").value("agentscope-orchestrator"))
                .andExpect(jsonPath("$.leaseEpoch").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long leaseEpoch = objectMapper.readTree(leased).get("leaseEpoch").asLong();

        http.perform(patch("/async/tasks/{taskId}/status", taskId)
                        .header(security.getAsyncWorker().getHeader(), workerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "status", "SUCCEEDED",
                                "workerId", "agentscope-orchestrator",
                                "leaseEpoch", leaseEpoch))))
                .andExpect(status().isUnauthorized());

        String statusToken = signer.mint(owner, "agentscope-orchestrator", "status", taskId);
        http.perform(patch("/async/tasks/{taskId}/status", taskId)
                        .header(security.getAsyncWorker().getHeader(), statusToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "status", "SUCCEEDED",
                                "workerId", "workflow-service",
                                "leaseEpoch", leaseEpoch))))
                .andExpect(status().isForbidden());

        http.perform(patch("/async/tasks/{taskId}/status", taskId)
                        .header(security.getAsyncWorker().getHeader(), statusToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "status", "SUCCEEDED",
                                "workerId", "agentscope-orchestrator",
                                "leaseEpoch", leaseEpoch))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        TenantContext.Tenant otherOwner = new TenantContext.Tenant("acme", "bob", Set.of("agent"));
        String otherCreated = http.perform(post("/async/tasks")
                        .header(security.getInternalHeader(), internalTokens.mint(otherOwner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("kind", "agent.run"))))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String otherTaskId = objectMapper.readTree(otherCreated).get("taskId").asText();
        String wrongActorToken = signer.mint(
                owner, "agentscope-orchestrator", "lease", otherTaskId);
        http.perform(post("/async/tasks/{taskId}/lease", otherTaskId)
                        .header(security.getAsyncWorker().getHeader(), wrongActorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                Map.of("workerId", "agentscope-orchestrator", "leaseSeconds", 60))))
                .andExpect(status().isNotFound());

        assertThat(internalTokens.verify(workerToken)).isNull();
    }

    @Test
    void taskRegistrationRejectsPrivateWebhookTarget() throws Exception {
        TenantContext.Tenant owner = new TenantContext.Tenant("acme", "alice", Set.of("agent"));

        http.perform(post("/async/tasks")
                        .header(security.getInternalHeader(), internalTokens.mint(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "kind", "agent.run",
                                "webhookUrl", "http://127.0.0.1:8080/admin"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("webhookUrl is not allowed"));
    }
}
