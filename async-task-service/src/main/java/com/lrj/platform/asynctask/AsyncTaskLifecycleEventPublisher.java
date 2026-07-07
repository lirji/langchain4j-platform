package com.lrj.platform.asynctask;

import com.lrj.platform.eventbus.EventPublisher;
import com.lrj.platform.observability.TraceIdFilter;
import com.lrj.platform.protocol.asynctask.AsyncTask;
import com.lrj.platform.protocol.event.AsyncTaskLifecycleMessage;
import com.lrj.platform.protocol.event.EventTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * B1b：异步任务生命周期 Kafka 发布（{@code app.async-task.webhook.transport=kafka} 时启用）。
 *
 * <p>把终态任务映射为 {@link AsyncTaskLifecycleMessage}，发往 {@link EventTopics#ASYNCTASK_LIFECYCLE}，
 * key = tenantId。eventId 用 {@code asynctask:<taskId>:<status>}（同一任务同一终态仅一次）→ 稳定去重键。
 *
 * <p>无 Flowable，事务仅涉及 Kafka：当 {@code eventbusKafkaTransactionManager}（配了
 * {@code platform.eventbus.producer.transactional-id-prefix} 时由 platform-eventbus 提供）存在时，
 * 用 {@link TransactionTemplate} 包住 send，保证事务性生产者不因「无事务」抛错；缺失时（默认/仅 Noop）直接发布。
 * 端到端 exactly-once 由消费侧 {@code ProcessedEventStore} 去重兜底。
 */
@Component
public class AsyncTaskLifecycleEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskLifecycleEventPublisher.class);

    private final EventPublisher eventPublisher;
    /** 可空：仅当配置了事务性生产者前缀时由 platform-eventbus 提供。 */
    private final PlatformTransactionManager kafkaTransactionManager;

    @Autowired
    public AsyncTaskLifecycleEventPublisher(
            EventPublisher eventPublisher,
            @Qualifier("eventbusKafkaTransactionManager")
            ObjectProvider<PlatformTransactionManager> kafkaTransactionManager) {
        this(eventPublisher, kafkaTransactionManager.getIfAvailable());
    }

    /** 测试友好构造：直接传入（可为 null）事务管理器。 */
    AsyncTaskLifecycleEventPublisher(EventPublisher eventPublisher,
                                     PlatformTransactionManager kafkaTransactionManager) {
        this.eventPublisher = eventPublisher;
        this.kafkaTransactionManager = kafkaTransactionManager;
    }

    public void publish(AsyncTask task) {
        AsyncTaskLifecycleMessage message = message(task);
        Runnable action = () -> eventPublisher.publish(EventTopics.ASYNCTASK_LIFECYCLE, task.tenantId(), message);
        if (kafkaTransactionManager != null) {
            new TransactionTemplate(kafkaTransactionManager).executeWithoutResult(status -> action.run());
        } else {
            action.run();
        }
        log.debug("async task lifecycle event published taskId={} status={} eventId={}",
                task.taskId(), task.status(), message.eventId());
    }

    /** 构造生命周期事件（静态纯函数，便于单测断言 topic/key/payload 映射）。 */
    static AsyncTaskLifecycleMessage message(AsyncTask task) {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        return new AsyncTaskLifecycleMessage(
                "asynctask:" + task.taskId() + ":" + task.status().name(),
                AsyncTaskLifecycleMessage.CURRENT_SCHEMA_VERSION,
                task.tenantId(),
                task.taskId(),
                task.kind(),
                task.status().name(),
                task.result(),
                task.error(),
                task.webhookUrl(),
                Instant.now(),
                traceId);
    }
}
