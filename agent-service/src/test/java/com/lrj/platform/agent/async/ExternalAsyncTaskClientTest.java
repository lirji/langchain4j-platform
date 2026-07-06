package com.lrj.platform.agent.async;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ExternalAsyncTaskClientTest {

    @Test
    void createsCentralTaskWithAgentTaskIdAndWithoutWebhookByDefault() {
        RestTemplate restTemplate = new RestTemplateBuilder().rootUri("http://async.local").build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ExternalAsyncTaskClient client = new ExternalAsyncTaskClient(restTemplate, new ExternalAsyncTaskProperties());

        server.expect(once(), requestTo("http://async.local/async/tasks"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.kind").value("agent.task"))
                .andExpect(jsonPath("$.input.goal").value("goal"))
                .andExpect(jsonPath("$.input.webhookUrl").doesNotExist())
                .andRespond(withSuccess());

        client.create(task(AgentTaskStatus.PENDING));

        server.verify();
    }

    @Test
    void mirrorsTerminalStatusUpdate() {
        RestTemplate restTemplate = new RestTemplateBuilder().rootUri("http://async.local").build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ExternalAsyncTaskProperties properties = new ExternalAsyncTaskProperties();
        properties.setWorkerId("agent-worker-1");
        ExternalAsyncTaskClient client = new ExternalAsyncTaskClient(restTemplate, properties);

        server.expect(once(), requestTo("http://async.local/async/tasks/task-1/status"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.answer").value("done"))
                .andExpect(jsonPath("$.workerId").value("agent-worker-1"))
                .andRespond(withSuccess());

        client.update(task(AgentTaskStatus.SUCCEEDED));

        server.verify();
    }

    @Test
    void leasesCentralTaskForConfiguredWorker() {
        RestTemplate restTemplate = new RestTemplateBuilder().rootUri("http://async.local").build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ExternalAsyncTaskProperties properties = new ExternalAsyncTaskProperties();
        properties.setWorkerId("agent-worker-1");
        properties.setLeaseSeconds(120);
        ExternalAsyncTaskClient client = new ExternalAsyncTaskClient(restTemplate, properties);

        server.expect(once(), requestTo("http://async.local/async/tasks/task-1/lease"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.workerId").value("agent-worker-1"))
                .andExpect(jsonPath("$.leaseSeconds").value(120))
                .andRespond(withSuccess());

        assertThat(client.lease("task-1")).isTrue();
        server.verify();
    }

    @Test
    void canDelegateWebhookToCentralTaskServiceWhenEnabled() {
        RestTemplate restTemplate = new RestTemplateBuilder().rootUri("http://async.local").build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ExternalAsyncTaskProperties properties = new ExternalAsyncTaskProperties();
        properties.setMirrorWebhook(true);
        ExternalAsyncTaskClient client = new ExternalAsyncTaskClient(restTemplate, properties);

        server.expect(once(), requestTo("http://async.local/async/tasks"))
                .andExpect(jsonPath("$.webhookUrl").value("http://callback.local/tasks"))
                .andRespond(withSuccess());

        client.create(task(AgentTaskStatus.PENDING));

        server.verify();
    }

    @Test
    void readsCentralTaskAndMapsToAgentTaskShape() {
        RestTemplate restTemplate = new RestTemplateBuilder().rootUri("http://async.local").build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ExternalAsyncTaskClient client = new ExternalAsyncTaskClient(restTemplate, new ExternalAsyncTaskProperties());

        server.expect(once(), requestTo("http://async.local/async/tasks/task-1"))
                .andRespond(withSuccess("""
                        {
                          "taskId":"task-1",
                          "tenantId":"acme",
                          "userId":"alice",
                          "kind":"agent.task",
                          "status":"SUCCEEDED",
                          "input":{"goal":"goal"},
                          "result":{"answer":"done"},
                          "createdAt":"2026-07-06T00:00:00Z",
                          "updatedAt":"2026-07-06T00:00:01Z",
                          "finishedAt":"2026-07-06T00:00:01Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        AgentAsyncTask task = client.get("task-1").orElseThrow();

        assertThat(task.status()).isEqualTo(AgentTaskStatus.SUCCEEDED);
        assertThat(task.input()).containsEntry("goal", "goal");
        assertThat(task.input()).containsEntry("asyncTaskKind", "agent.task");
        assertThat(task.result()).isEqualTo(Map.of("answer", "done"));
        server.verify();
    }

    @Test
    void listsAndCancelsCentralTasks() {
        RestTemplate restTemplate = new RestTemplateBuilder().rootUri("http://async.local").build();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ExternalAsyncTaskClient client = new ExternalAsyncTaskClient(restTemplate, new ExternalAsyncTaskProperties());

        server.expect(once(), requestTo("http://async.local/async/tasks"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{
                          "taskId":"task-1",
                          "tenantId":"acme",
                          "userId":"alice",
                          "kind":"agent.task",
                          "status":"PENDING",
                          "input":{"goal":"goal"},
                          "createdAt":"2026-07-06T00:00:00Z",
                          "updatedAt":"2026-07-06T00:00:00Z"
                        }]
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://async.local/async/tasks/task-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());

        assertThat(client.listMine()).hasSize(1);
        assertThat(client.cancel("task-1")).isTrue();
        server.verify();
    }

    private static AgentAsyncTask task(AgentTaskStatus status) {
        Instant now = Instant.now();
        return new AgentAsyncTask(
                "task-1",
                "acme",
                "alice",
                status,
                Map.of("goal", "goal", "webhookUrl", "http://callback.local/tasks"),
                status == AgentTaskStatus.SUCCEEDED ? Map.of("answer", "done") : null,
                null,
                now,
                now,
                status.isTerminal() ? now : null);
    }
}
