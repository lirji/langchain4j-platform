package com.lrj.platform.asynctask;

import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.security.OutboundCallbackPolicy;
import com.lrj.platform.security.OutboundWebhookSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JDBC 模式下的 webhook outbox 派发器（{@code app.async-task.store=jdbc}）。定时（{@code @Scheduled}）从
 * {@link AsyncTaskWebhookOutbox} 抢占到期行并 HTTP 投递：成功标记 delivered，4xx 视为客户端错误直接死信，
 * 5xx/网络错误按指数退避重试至上限后死信，同时清理过期的 delivered 行。每个实例用唯一 ownerId 认领，
 * 支持多实例并发派发。投递结果记审计（WEBHOOK_DELIVERED / WEBHOOK_FAILED）。
 */
@Component
@ConditionalOnProperty(name = "app.async-task.store", havingValue = "jdbc")
public class AsyncTaskWebhookOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskWebhookOutboxDispatcher.class);

    private final AsyncTaskWebhookOutbox outbox;
    private final AsyncTaskWebhookProperties properties;
    private final RestTemplate restTemplate;
    private final AuditLogger audit;
    private final OutboundCallbackPolicy callbackPolicy;
    private final String ownerId = "async-task-webhook-" + UUID.randomUUID();

    public AsyncTaskWebhookOutboxDispatcher(AsyncTaskWebhookOutbox outbox,
                                            AsyncTaskWebhookProperties properties,
                                            @Qualifier("asyncTaskWebhookRestTemplate") RestTemplate restTemplate,
                                            AuditLogger audit,
                                            OutboundCallbackPolicy callbackPolicy) {
        this.outbox = outbox;
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.audit = audit;
        this.callbackPolicy = callbackPolicy;
        if (properties.isEnabled() && !properties.isKafkaTransport()) {
            OutboundWebhookSigner.requireStrongSecret(properties.getHmacSecret());
        }
    }

    @Scheduled(fixedDelayString = "${app.async-task.webhook.poll-interval-ms:30000}", initialDelay = 30_000)
    public void dispatch() {
        if (!properties.isEnabled()) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        purgeDelivered(now);
        List<AsyncTaskWebhookOutbox.Row> due = outbox.claimDue(
                now,
                Math.max(1, properties.getBatchSize()),
                ownerId,
                claimTtlMillis());
        for (AsyncTaskWebhookOutbox.Row row : due) {
            deliver(row, now);
        }
    }

    private long claimTtlMillis() {
        Duration ttl = properties.getClaimTtl();
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return 120_000L;
        }
        return Math.max(1_000L, ttl.toMillis());
    }

    private void purgeDelivered(long now) {
        Duration retention = properties.getDeliveredRetention();
        if (retention == null || retention.isZero() || retention.isNegative()) {
            return;
        }
        outbox.purgeDeliveredBefore(now - retention.toMillis());
    }

    private void deliver(AsyncTaskWebhookOutbox.Row row, long now) {
        DeliveryResult result = send(row);
        if (result == DeliveryResult.SUCCESS) {
            if (outbox.markDelivered(row.outboxId(), row.claimOwner(), now)) {
                audit.record(AuditEventType.WEBHOOK_DELIVERED,
                        Map.of("taskId", row.taskId(), "status", row.taskStatus(), "target", row.targetUrl()));
            } else {
                staleClaim(row);
            }
            return;
        }
        int attemptsAfter = row.attempts() + 1;
        if (result == DeliveryResult.CLIENT_ERROR || result == DeliveryResult.POLICY_ERROR) {
            String reason = result == DeliveryResult.CLIENT_ERROR ? "client_4xx" : "callback_policy";
            if (outbox.markDead(row.outboxId(), row.claimOwner(), attemptsAfter, reason, now)) {
                audit.record(AuditEventType.WEBHOOK_FAILED, Map.of(
                        "taskId", row.taskId(),
                        "status", row.taskStatus(),
                        "target", row.targetUrl(),
                        "error", reason));
            } else {
                staleClaim(row);
            }
            return;
        }
        AsyncTaskWebhookOutbox.Decision decision = AsyncTaskWebhookOutbox.schedule(
                attemptsAfter,
                Math.max(1, properties.getMaxAttempts()),
                now,
                Math.max(0, properties.getBackoff().toMillis()));
        if (decision.dead()) {
            if (outbox.markDead(row.outboxId(), row.claimOwner(), attemptsAfter, result.name(), now)) {
                audit.record(AuditEventType.WEBHOOK_FAILED, Map.of(
                        "taskId", row.taskId(),
                        "status", row.taskStatus(),
                        "target", row.targetUrl(),
                        "error", result.name()));
            } else {
                staleClaim(row);
            }
            return;
        }
        if (outbox.markRetry(
                row.outboxId(),
                row.claimOwner(),
                attemptsAfter,
                decision.nextAttemptAt(),
                result.name(),
                now)) {
            log.warn("async task webhook retry scheduled taskId={} target={} attempts={} result={}",
                    row.taskId(), row.targetUrl(), attemptsAfter, result);
        } else {
            staleClaim(row);
        }
    }

    private void staleClaim(AsyncTaskWebhookOutbox.Row row) {
        log.warn("async task webhook completion ignored after claim loss outboxId={} owner={}",
                row.outboxId(), row.claimOwner());
    }

    private DeliveryResult send(AsyncTaskWebhookOutbox.Row row) {
        try {
            var target = callbackPolicy.requireAllowed(row.targetUrl());
            String deliveryId = row.outboxId();
            ResponseEntity<Void> response = restTemplate.postForEntity(
                    target,
                    new HttpEntity<>(row.payloadJson(), headers(row, deliveryId)),
                    Void.class);
            if (response.getStatusCode().is2xxSuccessful()) return DeliveryResult.SUCCESS;
            if (response.getStatusCode().is3xxRedirection()) return DeliveryResult.POLICY_ERROR;
            if (response.getStatusCode().is4xxClientError()) return DeliveryResult.CLIENT_ERROR;
            return DeliveryResult.SERVER_ERROR;
        } catch (OutboundCallbackPolicy.UnsafeCallbackException exception) {
            return DeliveryResult.POLICY_ERROR;
        } catch (HttpStatusCodeException ex) {
            return ex.getStatusCode().is4xxClientError() ? DeliveryResult.CLIENT_ERROR : DeliveryResult.SERVER_ERROR;
        } catch (RestClientException ex) {
            return DeliveryResult.NETWORK_ERROR;
        }
    }

    private HttpHeaders headers(AsyncTaskWebhookOutbox.Row row, String deliveryId) {
        OutboundWebhookSigner.SignedHeaders signed = OutboundWebhookSigner.sign(
                properties.getHmacSecret(),
                "async-task.finished",
                deliveryId,
                Instant.now(),
                row.payloadJson());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Async-Task-Id", row.taskId());
        headers.set("X-Async-Task-Status", row.taskStatus());
        headers.set("X-Agent-Task-Id", row.taskId());
        headers.set("X-Agent-Task-Status", row.taskStatus());
        headers.set("X-Tenant-Id", row.tenantId());
        headers.set("X-Webhook-Event", signed.event());
        headers.set("X-Webhook-Delivery", signed.deliveryId());
        headers.set("X-Webhook-Timestamp", Long.toString(signed.timestamp()));
        headers.set("X-Webhook-Signature", signed.signature());
        return headers;
    }

    private enum DeliveryResult {
        SUCCESS,
        CLIENT_ERROR,
        SERVER_ERROR,
        NETWORK_ERROR,
        POLICY_ERROR
    }
}
