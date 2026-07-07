package com.lrj.platform.asynctask;

import com.lrj.platform.protocol.asynctask.AsyncTask;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 终态任务 → Kafka 生命周期事件的<b>提交后直发</b>监听器（仅 {@code transport=kafka} 时装配）。
 *
 * <p><b>A1 后的职责</b>：当存在事务性 outbox（{@link AsyncTaskLifecycleOutbox}，即 {@code store=jdbc}）时，
 * 终态事件已由 {@link JdbcAsyncTaskStore} 在更新事务内原子写入 + {@link AsyncTaskLifecycleRelay} relay，
 * 本组件<b>跳过</b>以避免双投。仅当无事务性 outbox（{@code store=memory} 的 dev/test）时，退化为提交后 best-effort 直发。
 */
@Component
@ConditionalOnProperty(prefix = "app.async-task.webhook", name = "transport", havingValue = "kafka")
public class AsyncTaskKafkaNotifier {

    private final AsyncTaskWebhookProperties properties;
    private final AsyncTaskLifecycleEventPublisher publisher;
    /** 存在即代表启用了事务性 outbox（store=jdbc），本直发监听器让位。 */
    private final AsyncTaskLifecycleOutbox lifecycleOutbox;

    public AsyncTaskKafkaNotifier(AsyncTaskWebhookProperties properties,
                                  AsyncTaskLifecycleEventPublisher publisher,
                                  ObjectProvider<AsyncTaskLifecycleOutbox> lifecycleOutbox) {
        this.properties = properties;
        this.publisher = publisher;
        this.lifecycleOutbox = lifecycleOutbox.getIfAvailable();
    }

    @EventListener
    public void onTaskEvent(AsyncTaskEvent event) {
        if (lifecycleOutbox != null) {
            return; // 事务性 outbox + relay 负责，跳过提交后直发避免双投
        }
        AsyncTask task = event.task();
        if (!properties.isEnabled() || !task.status().isTerminal()) {
            return;
        }
        publisher.publish(task);
    }
}
