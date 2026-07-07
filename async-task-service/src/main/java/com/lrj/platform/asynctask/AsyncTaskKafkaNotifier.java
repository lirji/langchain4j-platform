package com.lrj.platform.asynctask;

import com.lrj.platform.protocol.asynctask.AsyncTask;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * B1b：终态任务 → Kafka 生命周期事件的监听器。仅当 {@code app.async-task.webhook.transport=kafka}
 * 时装配（默认 http，本组件不加载，对现有 HTTP 投递路径零影响）。与既有 store 模式（in-memory/jdbc）正交。
 */
@Component
@ConditionalOnProperty(prefix = "app.async-task.webhook", name = "transport", havingValue = "kafka")
public class AsyncTaskKafkaNotifier {

    private final AsyncTaskWebhookProperties properties;
    private final AsyncTaskLifecycleEventPublisher publisher;

    public AsyncTaskKafkaNotifier(AsyncTaskWebhookProperties properties,
                                  AsyncTaskLifecycleEventPublisher publisher) {
        this.properties = properties;
        this.publisher = publisher;
    }

    @EventListener
    public void onTaskEvent(AsyncTaskEvent event) {
        AsyncTask task = event.task();
        if (!properties.isEnabled() || !task.status().isTerminal()) {
            return;
        }
        publisher.publish(task);
    }
}
