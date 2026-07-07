package com.lrj.platform.asynctask;

import com.lrj.platform.protocol.asynctask.AsyncTask;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = "app.async-task.store", havingValue = "jdbc")
public class AsyncTaskWebhookOutboxEnqueuer {

    private final AsyncTaskWebhookProperties properties;
    private final AsyncTaskWebhookOutbox outbox;

    public AsyncTaskWebhookOutboxEnqueuer(AsyncTaskWebhookProperties properties, AsyncTaskWebhookOutbox outbox) {
        this.properties = properties;
        this.outbox = outbox;
    }

    @EventListener
    public void onTaskEvent(AsyncTaskEvent event) {
        AsyncTask task = event.task();
        // B1b：transport=kafka 时终态改由 AsyncTaskKafkaNotifier 发布事件，本地 outbox 让位。
        if (!properties.isEnabled() || properties.isKafkaTransport() || !task.status().isTerminal()) {
            return;
        }
        AsyncTaskWebhookNotifier.webhookUri(task.webhookUrl())
                .ifPresent(uri -> outbox.enqueue(task, uri.toString(), Instant.now().toEpochMilli()));
    }
}
