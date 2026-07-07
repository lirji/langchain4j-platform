package com.lrj.platform.asynctask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.eventbus.EventPublisher;
import com.lrj.platform.protocol.event.AsyncTaskLifecycleMessage;
import com.lrj.platform.protocol.event.EventTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

/**
 * 生命周期事件 Kafka relay（A1）。定时扫 {@link AsyncTaskLifecycleOutbox} 里到期的 PENDING 行，
 * 反序列化其快照并经 {@link EventPublisher} 发往 {@link EventTopics#ASYNCTASK_LIFECYCLE}（key=tenantId），
 * 成功后标 DELIVERED；失败按退避重投、耗尽进 DEAD。
 *
 * <p>outbox 行由 {@link JdbcAsyncTaskStore#update} 在终态更新<b>同一事务</b>内原子写入，故「终态提交 ⇔ 有 PENDING 行」；
 * 本 relay 保证至少一次发布，消费侧（channel-service）按稳定 eventId 去重 → 端到端 effective exactly-once。
 * 取代了原「{@code @EventListener} 提交后直发」——那条路径在提交后崩溃会丢事件且无兜底记录。
 *
 * <p>装配：由 {@link AsyncTaskJdbcConfig} 在 {@code store=jdbc + transport=kafka} 时以 {@code @Bean} 创建。
 */
public class AsyncTaskLifecycleRelay {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskLifecycleRelay.class);

    private final AsyncTaskLifecycleOutbox outbox;
    private final EventPublisher eventPublisher;
    private final ObjectMapper mapper;
    private final AsyncTaskWebhookProperties props;

    public AsyncTaskLifecycleRelay(AsyncTaskLifecycleOutbox outbox,
                                   EventPublisher eventPublisher,
                                   ObjectMapper mapper,
                                   AsyncTaskWebhookProperties props) {
        this.outbox = outbox;
        this.eventPublisher = eventPublisher;
        this.mapper = mapper;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${app.async-task.webhook.poll-interval-ms:30000}", initialDelay = 30_000)
    public void dispatch() {
        long now = System.currentTimeMillis();
        List<AsyncTaskLifecycleOutbox.Row> due = outbox.claimDue(now, Math.max(1, props.getBatchSize()));
        if (due.isEmpty()) {
            return;
        }
        int delivered = 0, dead = 0, retried = 0;
        for (AsyncTaskLifecycleOutbox.Row row : due) {
            try {
                switch (relayOne(row, now)) {
                    case DELIVERED -> delivered++;
                    case DEAD -> dead++;
                    case RETRY -> retried++;
                }
            } catch (Exception e) {
                log.warn("async lifecycle relay: eventId {} 发布异常：{}", row.eventId(), e.toString());
            }
        }
        log.info("async lifecycle relay: 到期 {} 条 → delivered={} retry={} dead={}", due.size(), delivered, retried, dead);
    }

    private Outcome relayOne(AsyncTaskLifecycleOutbox.Row row, long now) {
        try {
            AsyncTaskLifecycleMessage msg = mapper.readValue(row.payloadJson(), AsyncTaskLifecycleMessage.class);
            eventPublisher.publish(EventTopics.ASYNCTASK_LIFECYCLE, row.tenantId(), msg);
            outbox.markDelivered(row.eventId(), now);
            return Outcome.DELIVERED;
        } catch (Exception e) {
            int attemptsAfter = row.attempts() + 1;
            AsyncTaskLifecycleOutbox.Decision d = AsyncTaskLifecycleOutbox.schedule(
                    attemptsAfter, Math.max(1, props.getMaxAttempts()), now, Math.max(0, props.getBackoff().toMillis()));
            if (d.dead()) {
                outbox.markDead(row.eventId(), attemptsAfter, e.toString(), now);
                return Outcome.DEAD;
            }
            outbox.markRetry(row.eventId(), attemptsAfter, d.nextAttemptAt(), e.toString(), now);
            return Outcome.RETRY;
        }
    }

    private enum Outcome { DELIVERED, DEAD, RETRY }
}
