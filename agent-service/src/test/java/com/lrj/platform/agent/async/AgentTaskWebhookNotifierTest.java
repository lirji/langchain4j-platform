package com.lrj.platform.agent.async;

import com.lrj.platform.audit.AuditLogger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * AgentTaskWebhookNotifierTest：验证 {@link AgentTaskWebhookNotifier} 仅对终态任务向合法 webhook
 * 回调（带任务/租户请求头），失败按配置重试，非终态或非法 URL 不投递。
 */
class AgentTaskWebhookNotifierTest {

    @Test
    void sendsTerminalTaskWebhook() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AgentTaskWebhookNotifier notifier = new AgentTaskWebhookNotifier(
                restTemplate, properties(), mock(AuditLogger.class), Runnable::run);
        AgentAsyncTask task = task(AgentTaskStatus.SUCCEEDED, "http://callback.local/tasks");

        server.expect(once(), requestTo("http://callback.local/tasks"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Agent-Task-Id", "task-1"))
                .andExpect(header("X-Agent-Task-Status", "SUCCEEDED"))
                .andExpect(header("X-Tenant-Id", "acme"))
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        notifier.onTaskEvent(new AgentTaskEvent(task));

        server.verify();
    }

    @Test
    void retriesWebhookUntilSuccess() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AgentWebhookProperties properties = properties();
        properties.setMaxAttempts(2);
        properties.setBackoff(Duration.ZERO);
        AgentTaskWebhookNotifier notifier = new AgentTaskWebhookNotifier(
                restTemplate, properties, mock(AuditLogger.class), Runnable::run);
        AgentAsyncTask task = task(AgentTaskStatus.FAILED, "http://callback.local/tasks");

        server.expect(once(), requestTo("http://callback.local/tasks")).andRespond(withServerError());
        server.expect(once(), requestTo("http://callback.local/tasks")).andRespond(withSuccess());

        notifier.onTaskEvent(new AgentTaskEvent(task));

        server.verify();
    }

    @Test
    void ignoresNonTerminalOrInvalidWebhook() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AuditLogger audit = mock(AuditLogger.class);
        AgentTaskWebhookNotifier notifier = new AgentTaskWebhookNotifier(
                restTemplate, properties(), audit, Runnable::run);

        notifier.onTaskEvent(new AgentTaskEvent(task(AgentTaskStatus.RUNNING, "http://callback.local/tasks")));
        notifier.onTaskEvent(new AgentTaskEvent(task(AgentTaskStatus.SUCCEEDED, "file:///tmp/hook")));

        server.verify();
        verify(audit, never()).record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void attemptsConfiguredNumberOfDeliveries() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AgentWebhookProperties properties = properties();
        properties.setMaxAttempts(2);
        properties.setBackoff(Duration.ZERO);
        AgentTaskWebhookNotifier notifier = new AgentTaskWebhookNotifier(
                restTemplate, properties, mock(AuditLogger.class), Runnable::run);

        server.expect(times(2), requestTo("http://callback.local/tasks")).andRespond(withServerError());

        notifier.onTaskEvent(new AgentTaskEvent(task(AgentTaskStatus.SUCCEEDED, "http://callback.local/tasks")));

        server.verify();
    }

    private static AgentWebhookProperties properties() {
        AgentWebhookProperties properties = new AgentWebhookProperties();
        properties.setBackoff(Duration.ZERO);
        return properties;
    }

    private static AgentAsyncTask task(AgentTaskStatus status, String webhookUrl) {
        Instant now = Instant.now();
        return new AgentAsyncTask(
                "task-1",
                "acme",
                "alice",
                status,
                Map.of("goal", "goal", "webhookUrl", webhookUrl),
                status == AgentTaskStatus.SUCCEEDED ? Map.of("answer", "done") : null,
                status == AgentTaskStatus.FAILED ? "failed" : null,
                now,
                now,
                status.isTerminal() ? now : null);
    }
}
