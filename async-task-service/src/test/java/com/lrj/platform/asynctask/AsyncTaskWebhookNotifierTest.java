package com.lrj.platform.asynctask;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.asynctask.AsyncTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AsyncTaskWebhookNotifierTest {

    @Test
    void sendsTerminalTaskWebhook() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AsyncTaskWebhookNotifier notifier = new AsyncTaskWebhookNotifier(
                restTemplate, properties(), mock(AuditLogger.class), Runnable::run);
        AsyncTask task = task(AsyncTaskStatus.SUCCEEDED, "http://callback.local/tasks");

        server.expect(once(), requestTo("http://callback.local/tasks"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Async-Task-Id", "task-1"))
                .andExpect(header("X-Async-Task-Status", "SUCCEEDED"))
                .andExpect(header("X-Tenant-Id", "acme"))
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        notifier.onTaskEvent(new AsyncTaskEvent(task));

        server.verify();
    }

    @Test
    void retriesWebhookUntilSuccess() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AsyncTaskWebhookProperties properties = properties();
        properties.setMaxAttempts(2);
        AsyncTaskWebhookNotifier notifier = new AsyncTaskWebhookNotifier(
                restTemplate, properties, mock(AuditLogger.class), Runnable::run);
        AsyncTask task = task(AsyncTaskStatus.FAILED, "http://callback.local/tasks");

        server.expect(once(), requestTo("http://callback.local/tasks")).andRespond(withServerError());
        server.expect(once(), requestTo("http://callback.local/tasks")).andRespond(withSuccess());

        notifier.onTaskEvent(new AsyncTaskEvent(task));

        server.verify();
    }

    @Test
    void ignoresNonTerminalOrInvalidWebhook() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AuditLogger audit = mock(AuditLogger.class);
        AsyncTaskWebhookNotifier notifier = new AsyncTaskWebhookNotifier(
                restTemplate, properties(), audit, Runnable::run);

        notifier.onTaskEvent(new AsyncTaskEvent(task(AsyncTaskStatus.RUNNING, "http://callback.local/tasks")));
        notifier.onTaskEvent(new AsyncTaskEvent(task(AsyncTaskStatus.SUCCEEDED, "file:///tmp/hook")));

        server.verify();
        verify(audit, never()).record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void attemptsConfiguredNumberOfDeliveries() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AsyncTaskWebhookProperties properties = properties();
        properties.setMaxAttempts(2);
        AsyncTaskWebhookNotifier notifier = new AsyncTaskWebhookNotifier(
                restTemplate, properties, mock(AuditLogger.class), Runnable::run);

        server.expect(times(2), requestTo("http://callback.local/tasks")).andRespond(withServerError());

        notifier.onTaskEvent(new AsyncTaskEvent(task(AsyncTaskStatus.SUCCEEDED, "http://callback.local/tasks")));

        server.verify();
    }

    private static AsyncTaskWebhookProperties properties() {
        AsyncTaskWebhookProperties properties = new AsyncTaskWebhookProperties();
        properties.setBackoff(Duration.ZERO);
        return properties;
    }

    private static AsyncTask task(AsyncTaskStatus status, String webhookUrl) {
        Instant now = Instant.now();
        return new AsyncTask(
                "task-1",
                "acme",
                "alice",
                "agent.run",
                status,
                Map.of("goal", "goal"),
                status == AsyncTaskStatus.SUCCEEDED ? Map.of("answer", "done") : null,
                status == AsyncTaskStatus.FAILED ? "failed" : null,
                webhookUrl,
                now,
                now,
                status.isTerminal() ? now : null);
    }
}
