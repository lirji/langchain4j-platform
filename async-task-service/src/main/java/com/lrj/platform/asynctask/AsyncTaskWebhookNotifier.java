package com.lrj.platform.asynctask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.security.OutboundCallbackPolicy;
import com.lrj.platform.security.OutboundWebhookSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 内存存储模式下的 webhook 通知器（{@code app.async-task.store=in-memory}）。监听 {@link AsyncTaskEvent}，
 * 任务进入终态时向其 {@code webhookUrl} 异步 HTTP 直投（带重试/退避，经 {@code asyncTaskExecutor} 线程池），
 * 并记审计。当 webhook {@code transport=kafka} 时让位给 Kafka 事件通道（见 {@code isKafkaTransport} 守卫）。
 * JDBC 模式则改由 {@link AsyncTaskWebhookOutbox} + {@link AsyncTaskWebhookOutboxDispatcher} 做事务性投递。
 */
@Component
@ConditionalOnProperty(name = "app.async-task.store", havingValue = "in-memory", matchIfMissing = true)
public class AsyncTaskWebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskWebhookNotifier.class);

    private final RestTemplate restTemplate;
    private final AsyncTaskWebhookProperties properties;
    private final AuditLogger audit;
    private final Executor executor;
    private final ObjectMapper mapper;
    private final OutboundCallbackPolicy callbackPolicy;

    public AsyncTaskWebhookNotifier(@Qualifier("asyncTaskWebhookRestTemplate") RestTemplate restTemplate,
                                    AsyncTaskWebhookProperties properties,
                                    AuditLogger audit,
                                    @Qualifier("asyncTaskExecutor") Executor executor) {
        this(restTemplate, properties, audit, executor,
                new ObjectMapper().findAndRegisterModules(), null);
    }

    @Autowired
    public AsyncTaskWebhookNotifier(
            @Qualifier("asyncTaskWebhookRestTemplate") RestTemplate restTemplate,
            AsyncTaskWebhookProperties properties,
            AuditLogger audit,
            @Qualifier("asyncTaskExecutor") Executor executor,
            ObjectMapper mapper,
            OutboundCallbackPolicy callbackPolicy) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.audit = audit;
        this.executor = executor;
        this.mapper = mapper;
        this.callbackPolicy = callbackPolicy;
        if (properties.isEnabled() && !properties.isKafkaTransport()) {
            OutboundWebhookSigner.requireStrongSecret(properties.getHmacSecret());
        }
    }

    @EventListener
    public void onTaskEvent(AsyncTaskEvent event) {
        AsyncTask task = event.task();
        // B1b：transport=kafka 时终态改由 AsyncTaskKafkaNotifier 发布事件，HTTP 直投让位。
        if (!properties.isEnabled() || properties.isKafkaTransport() || !task.status().isTerminal()) {
            return;
        }
        webhookUri(task.webhookUrl()).ifPresent(uri -> executor.execute(() -> deliver(task, uri)));
    }

    private void deliver(AsyncTask task, URI target) {
        final String body;
        try {
            body = mapper.writeValueAsString(AsyncTaskWebhookPayloadFactory.payload(task));
        } catch (Exception exception) {
            recordFailure(task, target, "payload_serialization");
            return;
        }
        String deliveryId = UUID.randomUUID().toString();
        int attempts = Math.max(1, properties.getMaxAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                URI checked = callbackPolicy == null ? target : callbackPolicy.requireAllowed(target.toString());
                ResponseEntity<Void> response = restTemplate.postForEntity(
                        checked, new HttpEntity<>(body, signedHeaders(task, body, deliveryId)), Void.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    audit.record(AuditEventType.WEBHOOK_DELIVERED,
                            Map.of("taskId", task.taskId(), "status", task.status().name(),
                                    "target", target.toString()));
                    return;
                }
                if (response.getStatusCode().is3xxRedirection()
                        || response.getStatusCode().is4xxClientError()) {
                    recordFailure(task, target, response.getStatusCode().is3xxRedirection()
                            ? "redirect_rejected" : "client_4xx");
                    return;
                }
            } catch (RestClientException ex) {
                if (attempt == attempts) {
                    recordFailure(task, target, "delivery_failed");
                    log.warn("async task webhook failed taskId={} target={}: {}", task.taskId(), target, ex.toString());
                    return;
                }
            } catch (OutboundCallbackPolicy.UnsafeCallbackException exception) {
                recordFailure(task, target, "callback_policy");
                return;
            }
            if (attempt < attempts) sleepBackoff();
        }
    }

    static HttpHeaders headers(AsyncTask task) {
        return AsyncTaskWebhookPayloadFactory.headers(task);
    }

    private HttpHeaders signedHeaders(AsyncTask task, String body, String deliveryId) {
        OutboundWebhookSigner.SignedHeaders signed = OutboundWebhookSigner.sign(
                properties.getHmacSecret(), "async-task.finished", deliveryId, Instant.now(), body);
        HttpHeaders headers = headers(task);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Webhook-Event", signed.event());
        headers.set("X-Webhook-Delivery", signed.deliveryId());
        headers.set("X-Webhook-Timestamp", Long.toString(signed.timestamp()));
        headers.set("X-Webhook-Signature", signed.signature());
        return headers;
    }

    private void recordFailure(AsyncTask task, URI target, String reason) {
        audit.record(AuditEventType.WEBHOOK_FAILED, Map.of(
                "taskId", task.taskId(),
                "status", task.status().name(),
                "target", target.toString(),
                "error", reason));
    }

    private void sleepBackoff() {
        try {
            Thread.sleep(Math.max(0, properties.getBackoff().toMillis()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    static Optional<URI> webhookUri(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null
                    || uri.getUserInfo() != null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return Optional.empty();
            }
            return Optional.of(uri);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
