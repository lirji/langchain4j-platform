package com.lrj.platform.workflow;

import com.lrj.platform.eventbus.EventPublisher;
import com.lrj.platform.observability.TraceIdFilter;
import com.lrj.platform.protocol.event.EventTopics;
import com.lrj.platform.protocol.event.WorkflowTerminalMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * B1b：终态 Kafka 发布路径（{@code app.workflow.terminal-notification.mode=kafka} 时启用）。
 *
 * <p>把终态状态提交（{@code WF_OUTBOX} 作为 DB 权威投递记录）与 Kafka 事件发布绑定进<b>同一事务</b>——
 * 当 {@code workflowKafkaChainedTransactionManager}（{@link WorkflowEventbusTransactionConfig} 提供，
 * 需配 {@code platform.eventbus.producer.transactional-id-prefix}）存在时，用 {@link TransactionTemplate}
 * 串接「Flowable/JDBC DataSource 事务 + KafkaTransactionManager」达到端到端 exactly-once；缺失时（默认/仅 Noop）
 * 直接顺序执行（Noop 发布器不触发任何 Kafka）。
 *
 * <p>发往 {@link EventTopics#WORKFLOW_TERMINAL}，key = tenantId（保证同租户有序）。eventId 用
 * {@code workflow:<instanceId>}（每个实例仅到达一次终态）→ 稳定去重键，消费侧重投也只处理一次。
 *
 * <p>本组件<b>始终装配</b>（依赖的 {@link EventPublisher} 默认为 Noop）；mode≠kafka 时
 * {@link WorkflowService} 根本不调它，故对现有 local/async-task 路径零影响。
 */
@Component
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class WorkflowTerminalEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTerminalEventPublisher.class);

    private final EventPublisher eventPublisher;
    private final WorkflowOutbox outbox;
    /** 可空：仅当配置了事务性生产者前缀时才由 {@link WorkflowEventbusTransactionConfig} 提供。 */
    private final PlatformTransactionManager terminalTransactionManager;

    @Autowired
    public WorkflowTerminalEventPublisher(
            EventPublisher eventPublisher,
            WorkflowOutbox outbox,
            @Qualifier("workflowKafkaChainedTransactionManager")
            ObjectProvider<PlatformTransactionManager> terminalTransactionManager) {
        this(eventPublisher, outbox, terminalTransactionManager.getIfAvailable());
    }

    /** 测试友好构造：直接传入（可为 null）事务管理器。 */
    WorkflowTerminalEventPublisher(EventPublisher eventPublisher,
                                   WorkflowOutbox outbox,
                                   PlatformTransactionManager terminalTransactionManager) {
        this.eventPublisher = eventPublisher;
        this.outbox = outbox;
        this.terminalTransactionManager = terminalTransactionManager;
    }

    /**
     * 发布一条终态事件。若 {@code webhookUrl} 非空则同事务写一条 {@code WF_OUTBOX} 权威投递记录，
     * 再发布 Kafka 事件；两步在同一（链式）事务内提交。
     */
    public void publishTerminal(String instanceId, String tenantId, String chatId,
                                String outcome, String reply, String webhookUrl) {
        long now = System.currentTimeMillis();
        WorkflowTerminalMessage message = message(instanceId, tenantId, chatId, outcome, reply);
        Runnable action = () -> {
            if (webhookUrl != null && !webhookUrl.isBlank()) {
                outbox.enqueue(instanceId, tenantId, webhookUrl, now);
            }
            eventPublisher.publish(EventTopics.WORKFLOW_TERMINAL, tenantId, message);
        };
        if (terminalTransactionManager != null) {
            new TransactionTemplate(terminalTransactionManager).executeWithoutResult(status -> action.run());
        } else {
            action.run();
        }
        log.debug("workflow terminal event published instanceId={} outcome={} eventId={}",
                instanceId, outcome, message.eventId());
    }

    /** 构造终态事件（静态纯函数，便于单测断言 topic/key/payload 映射）。 */
    static WorkflowTerminalMessage message(String instanceId, String tenantId, String chatId,
                                           String outcome, String reply) {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        return new WorkflowTerminalMessage(
                "workflow:" + instanceId,
                WorkflowTerminalMessage.CURRENT_SCHEMA_VERSION,
                tenantId,
                instanceId,
                chatId,
                outcome,
                WorkflowService.STATUS_COMPLETED,
                reply,
                Instant.now(),
                traceId);
    }
}
