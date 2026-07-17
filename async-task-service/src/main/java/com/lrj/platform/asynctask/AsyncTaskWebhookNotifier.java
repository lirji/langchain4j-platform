package com.lrj.platform.asynctask;

import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
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

    public AsyncTaskWebhookNotifier(@Qualifier("asyncTaskWebhookRestTemplate") RestTemplate restTemplate,
                                    AsyncTaskWebhookProperties properties,
                                    AuditLogger audit,
                                    @Qualifier("asyncTaskExecutor") Executor executor) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.audit = audit;
        this.executor = executor;
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
        int attempts = Math.max(1, properties.getMaxAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                restTemplate.postForEntity(target, new HttpEntity<>(task, headers(task)), Void.class);
                audit.record(AuditEventType.WEBHOOK_DELIVERED,
                        Map.of("taskId", task.taskId(), "status", task.status().name(), "target", target.toString()));
                return;
            } catch (RestClientException ex) {
                if (attempt == attempts) {
                    audit.record(AuditEventType.WEBHOOK_FAILED, Map.of(
                            "taskId", task.taskId(),
                            "status", task.status().name(),
                            "target", target.toString(),
                            "error", ex.getMessage() == null ? ex.toString() : ex.getMessage()));
                    log.warn("async task webhook failed taskId={} target={}: {}", task.taskId(), target, ex.toString());
                    return;
                }
                sleepBackoff();
            }
        }
    }

    static HttpHeaders headers(AsyncTask task) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Async-Task-Id", task.taskId());
        headers.set("X-Async-Task-Status", task.status().name());
        headers.set("X-Tenant-Id", task.tenantId());
        return headers;
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
            if (uri.getHost() == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return Optional.empty();
            }
            return Optional.of(uri);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
