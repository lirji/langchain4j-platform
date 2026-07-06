package com.lrj.platform.asynctask;

import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.async-task.store", havingValue = "jdbc")
public class AsyncTaskWebhookOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskWebhookOutboxDispatcher.class);

    private final AsyncTaskWebhookOutbox outbox;
    private final AsyncTaskWebhookProperties properties;
    private final RestTemplate restTemplate;
    private final AuditLogger audit;

    public AsyncTaskWebhookOutboxDispatcher(AsyncTaskWebhookOutbox outbox,
                                            AsyncTaskWebhookProperties properties,
                                            @Qualifier("asyncTaskWebhookRestTemplate") RestTemplate restTemplate,
                                            AuditLogger audit) {
        this.outbox = outbox;
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${app.async-task.webhook.poll-interval-ms:30000}", initialDelay = 30_000)
    public void dispatch() {
        if (!properties.isEnabled()) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        List<AsyncTaskWebhookOutbox.Row> due = outbox.claimDue(now, Math.max(1, properties.getBatchSize()));
        for (AsyncTaskWebhookOutbox.Row row : due) {
            deliver(row, now);
        }
    }

    private void deliver(AsyncTaskWebhookOutbox.Row row, long now) {
        DeliveryResult result = send(row);
        if (result == DeliveryResult.SUCCESS) {
            outbox.markDelivered(row.outboxId(), now);
            audit.record(AuditEventType.WEBHOOK_DELIVERED,
                    Map.of("taskId", row.taskId(), "status", row.taskStatus(), "target", row.targetUrl()));
            return;
        }
        int attemptsAfter = row.attempts() + 1;
        if (result == DeliveryResult.CLIENT_ERROR) {
            outbox.markDead(row.outboxId(), attemptsAfter, "client_4xx", now);
            audit.record(AuditEventType.WEBHOOK_FAILED, Map.of(
                    "taskId", row.taskId(),
                    "status", row.taskStatus(),
                    "target", row.targetUrl(),
                    "error", "client_4xx"));
            return;
        }
        AsyncTaskWebhookOutbox.Decision decision = AsyncTaskWebhookOutbox.schedule(
                attemptsAfter,
                Math.max(1, properties.getMaxAttempts()),
                now,
                Math.max(0, properties.getBackoff().toMillis()));
        if (decision.dead()) {
            outbox.markDead(row.outboxId(), attemptsAfter, result.name(), now);
            audit.record(AuditEventType.WEBHOOK_FAILED, Map.of(
                    "taskId", row.taskId(),
                    "status", row.taskStatus(),
                    "target", row.targetUrl(),
                    "error", result.name()));
            return;
        }
        outbox.markRetry(row.outboxId(), attemptsAfter, decision.nextAttemptAt(), result.name(), now);
        log.warn("async task webhook retry scheduled taskId={} target={} attempts={} result={}",
                row.taskId(), row.targetUrl(), attemptsAfter, result);
    }

    private DeliveryResult send(AsyncTaskWebhookOutbox.Row row) {
        try {
            restTemplate.postForEntity(row.targetUrl(), new HttpEntity<>(row.payloadJson(), headers(row)), Void.class);
            return DeliveryResult.SUCCESS;
        } catch (HttpStatusCodeException ex) {
            return ex.getStatusCode().is4xxClientError() ? DeliveryResult.CLIENT_ERROR : DeliveryResult.SERVER_ERROR;
        } catch (RestClientException ex) {
            return DeliveryResult.NETWORK_ERROR;
        }
    }

    private static HttpHeaders headers(AsyncTaskWebhookOutbox.Row row) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Async-Task-Id", row.taskId());
        headers.set("X-Async-Task-Status", row.taskStatus());
        headers.set("X-Tenant-Id", row.tenantId());
        return headers;
    }

    private enum DeliveryResult {
        SUCCESS,
        CLIENT_ERROR,
        SERVER_ERROR,
        NETWORK_ERROR
    }
}
