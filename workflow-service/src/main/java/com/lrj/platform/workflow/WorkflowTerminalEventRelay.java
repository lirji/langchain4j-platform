package com.lrj.platform.workflow;

import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.eventbus.EventPublisher;
import com.lrj.platform.protocol.event.EventTopics;
import com.lrj.platform.protocol.event.WorkflowTerminalMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 终态事件 Kafka relay（B1b 收口）。定时扫 {@link WorkflowTerminalEventOutbox} 里到期的 PENDING 行，
 * 逐条重建 {@link WorkflowTerminalMessage}（reply 从 {@link WorkflowReplyStore} 取）并经 {@link EventPublisher}
 * 发往 {@link EventTopics#WORKFLOW_TERMINAL}（key=tenantId 保同租户有序），成功后标 DELIVERED。
 *
 * <p><b>投递语义</b>：事件 outbox 行由 {@link WorkflowTerminalOutboxListener} 在 Flowable 终态事务内原子写入，
 * 故「终态提交 ⇔ 有 PENDING 行」；本 relay 保证「至少一次」发布（发布失败按退避重投、耗尽进 DEAD），
 * 消费侧（channel-service）用稳定 eventId {@code workflow:<instanceId>} 去重 → 端到端等价 exactly-once。
 * 这取代了旧的「终态提交后直接发 Kafka」——那条路径在提交后崩溃会丢事件且无兜底记录。
 *
 * <p><b>装配</b>：仅 {@code app.workflow.terminal-notification.mode=kafka} 时启用。默认/其它模式本 bean 不存在，
 * 事件 outbox 表保持为空（{@link WorkflowTerminalOutboxListener} 非 kafka 档 no-op）。
 */
@Component
@ConditionalOnProperty(name = "app.workflow.terminal-notification.mode", havingValue = "kafka")
public class WorkflowTerminalEventRelay {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTerminalEventRelay.class);

    private final WorkflowTerminalEventOutbox eventOutbox;
    private final WorkflowReplyStore replyStore;
    private final EventPublisher eventPublisher;
    private final WorkflowProperties props;
    private final AuditLogger audit;

    public WorkflowTerminalEventRelay(WorkflowTerminalEventOutbox eventOutbox,
                                      WorkflowReplyStore replyStore,
                                      EventPublisher eventPublisher,
                                      WorkflowProperties props,
                                      AuditLogger audit) {
        this.eventOutbox = eventOutbox;
        this.replyStore = replyStore;
        this.eventPublisher = eventPublisher;
        this.props = props;
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${app.workflow.outbox.poll-interval-ms:30000}", initialDelay = 30_000)
    public void dispatch() {
        long now = System.currentTimeMillis();
        WorkflowProperties.Outbox cfg = props.getOutbox();
        List<WorkflowTerminalEventOutbox.Row> due = eventOutbox.claimDue(now, cfg.getBatchSize());
        if (due.isEmpty()) {
            return;
        }
        int delivered = 0, dead = 0, retried = 0;
        for (WorkflowTerminalEventOutbox.Row row : due) {
            try {
                switch (relayOne(row, cfg, now)) {
                    case DELIVERED -> delivered++;
                    case DEAD -> dead++;
                    case RETRY -> retried++;
                }
            } catch (Exception e) {
                // 单条异常不阻断整轮（下轮还会再扫到 PENDING）
                log.warn("terminal event relay: 实例 {} 发布异常：{}", row.instanceId(), e.toString());
            }
        }
        log.info("terminal event relay: 到期 {} 条 → delivered={} retry={} dead={}", due.size(), delivered, retried, dead);
    }

    private Outcome relayOne(WorkflowTerminalEventOutbox.Row row, WorkflowProperties.Outbox cfg, long now) {
        try {
            WorkflowTerminalMessage message = message(row, replyStore.find(row.instanceId()));
            eventPublisher.publish(EventTopics.WORKFLOW_TERMINAL, row.tenantId(), message);
            eventOutbox.markDelivered(row.instanceId(), now);
            audit.record(AuditEventType.WORKFLOW_PUSH_DELIVERED, Map.of(
                    "instanceId", row.instanceId(), "attempts", row.attempts() + 1, "transport", "kafka"));
            return Outcome.DELIVERED;
        } catch (Exception e) {
            int attemptsAfter = row.attempts() + 1;
            WorkflowOutbox.Decision d = WorkflowOutbox.schedule(attemptsAfter, cfg.getMaxAttempts(), now, cfg.getBaseBackoffMs());
            if (d.dead()) {
                eventOutbox.markDead(row.instanceId(), attemptsAfter, e.toString(), now);
                audit.record(AuditEventType.WORKFLOW_PUSH_DEAD, Map.of(
                        "instanceId", row.instanceId(), "attempts", attemptsAfter, "reason", "max_attempts", "transport", "kafka"));
                return Outcome.DEAD;
            }
            eventOutbox.markRetry(row.instanceId(), attemptsAfter, d.nextAttemptAt(), e.toString(), now);
            audit.record(AuditEventType.WORKFLOW_PUSH_FAILED, Map.of(
                    "instanceId", row.instanceId(), "attempts", attemptsAfter, "reason", "publish_failed", "transport", "kafka"));
            return Outcome.RETRY;
        }
    }

    /** 从 outbox 行 + reply 重建终态事件（静态纯函数，便于单测断言 topic/key/payload 映射）。 */
    static WorkflowTerminalMessage message(WorkflowTerminalEventOutbox.Row row, String reply) {
        return new WorkflowTerminalMessage(
                "workflow:" + row.instanceId(),
                WorkflowTerminalMessage.CURRENT_SCHEMA_VERSION,
                row.tenantId(),
                row.instanceId(),
                row.chatId(),
                row.outcome(),
                WorkflowService.STATUS_COMPLETED,
                reply,
                Instant.now(),
                null);
    }

    private enum Outcome { DELIVERED, DEAD, RETRY }
}
