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
        if (!properties.isEnabled() || !task.status().isTerminal()) {
            return;
        }
        AsyncTaskWebhookNotifier.webhookUri(task.webhookUrl())
                .ifPresent(uri -> outbox.enqueue(task, uri.toString(), Instant.now().toEpochMilli()));
    }
}
