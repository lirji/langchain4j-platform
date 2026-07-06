package com.lrj.platform.agent.async;

import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
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

@Component
public class AgentTaskWebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskWebhookNotifier.class);

    private final RestTemplate restTemplate;
    private final AgentWebhookProperties properties;
    private final ExternalAsyncTaskProperties externalProperties;
    private final AuditLogger audit;
    private final Executor executor;

    public AgentTaskWebhookNotifier(@Qualifier("agentWebhookRestTemplate") RestTemplate agentWebhookRestTemplate,
                                    AgentWebhookProperties properties,
                                    AuditLogger audit,
                                    @Qualifier("agentTaskExecutor") Executor executor) {
        this(agentWebhookRestTemplate, properties, new ExternalAsyncTaskProperties(), audit, executor);
    }

    @Autowired
    public AgentTaskWebhookNotifier(@Qualifier("agentWebhookRestTemplate") RestTemplate agentWebhookRestTemplate,
                                    AgentWebhookProperties properties,
                                    ExternalAsyncTaskProperties externalProperties,
                                    AuditLogger audit,
                                    @Qualifier("agentTaskExecutor") Executor executor) {
        this.restTemplate = agentWebhookRestTemplate;
        this.properties = properties;
        this.externalProperties = externalProperties;
        this.audit = audit;
        this.executor = executor;
    }

    @EventListener
    public void onTaskEvent(AgentTaskEvent event) {
        AgentAsyncTask task = event.task();
        if (!properties.isEnabled() || !task.status().isTerminal() || centralWebhookOwnsDelivery()) {
            return;
        }
        webhookUri(task).ifPresent(uri -> executor.execute(() -> deliver(task, uri)));
    }

    private boolean centralWebhookOwnsDelivery() {
        return externalProperties != null
                && externalProperties.isEnabled()
                && externalProperties.isAuthoritative()
                && externalProperties.isMirrorWebhook();
    }

    private void deliver(AgentAsyncTask task, URI target) {
        int attempts = Math.max(1, properties.getMaxAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                restTemplate.postForEntity(target, new HttpEntity<>(AgentTaskWebhookPayload.from(task), headers(task)), Void.class);
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
                    log.warn("agent task webhook failed taskId={} target={}: {}", task.taskId(), target, ex.toString());
                    return;
                }
                sleepBackoff();
            }
        }
    }

    private static HttpHeaders headers(AgentAsyncTask task) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Agent-Task-Id", task.taskId());
        headers.set("X-Agent-Task-Status", task.status().name());
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

    private static Optional<URI> webhookUri(AgentAsyncTask task) {
        Object raw = task.input().get("webhookUrl");
        if (!(raw instanceof String value) || value.isBlank()) {
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
