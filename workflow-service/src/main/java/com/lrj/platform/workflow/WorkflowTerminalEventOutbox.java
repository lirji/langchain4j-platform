package com.lrj.platform.workflow;

import com.lrj.platform.workflow.mapper.WorkflowTerminalEventOutboxMapper;
import com.lrj.platform.persistence.PlusMapperFactory;
import com.lrj.platform.persistence.Rows;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

/**
 * 终态 Kafka 事件的<b>事务性 outbox</b>（B1b 收口）。建在 Flowable 同一个 {@code workflowDataSource} 上的
 * {@code WF_TERMINAL_EVENT_OUTBOX} 表（启动自动 DDL），每个流程实例终态最多一条（{@code INSTANCE_ID} 主键）。
 *
 * <p><b>为什么单独一张表</b>：{@code WF_OUTBOX}（{@link WorkflowOutbox}）是 HTTP-webhook 专用
 * （{@code TARGET_URL NOT NULL}、由 {@code WorkflowOutboxDispatcher} 做 HTTP POST 投递）；Kafka relay 的投递目标
 * 是 topic 而非 URL，字段与投递语义都不同，故隔离成独立表，不污染已验证的 HTTP 路径。
 *
 * <p><b>原子性（收口关键）</b>：{@link #enqueue} 由 {@link WorkflowTerminalOutboxListener} 在 BPMN
 * {@code end} 事件的 ExecutionListener 里调用——该监听器运行在 Flowable 引擎命令的 Spring 事务内
 * （引擎配了 {@code workflowTransactionManager}）。本类的 {@code MyBatis-Plus Mapper} 用同一个 {@code workflowDataSource}，
 * 经 {@code DataSourceUtils} 取到线程绑定的同一连接 → INSERT 与 {@code ACT_*} 终态写、{@code WF_REPLY} 同一事务提交。
 * 于是「终态已提交 ⇔ 事件 outbox 行已写」原子成立，消除了「终态提交后、通知未落库」的丢失窗口。投递由
 * {@link WorkflowTerminalEventRelay} 从本表 relay 到 Kafka（至少一次 + 消费侧 eventId 去重）。
 */
@Component
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class WorkflowTerminalEventOutbox {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTerminalEventOutbox.class);

    private final WorkflowTerminalEventOutboxMapper db;

    public WorkflowTerminalEventOutbox(DataSource workflowDataSource) {
        this.db = PlusMapperFactory.create(workflowDataSource, WorkflowTerminalEventOutboxMapper.class);
        init();
    }

    void init() {
        db.readContract1();
        log.info("WF_TERMINAL_EVENT_OUTBOX schema verified");
    }

    /**
     * 入队（或重置）一条待发布的终态事件：status=PENDING、attempts=0、立即可投。
     * 由 {@link WorkflowTerminalOutboxListener} 在 Flowable 终态事务内调用 → 与终态原子提交。
     */
    public void enqueue(String instanceId, String tenantId, String chatId, String outcome,
                        String webhookUrl, long now) {
        db.writeEnqueue1(instanceId, tenantId, chatId, outcome, webhookUrl, now, now, now);
    }

    /** 取到期待发布行（STATUS=PENDING 且 NEXT_ATTEMPT_AT<=now），按到期时间升序，最多 limit 条。 */
    public List<Row> claimDue(long now, int limit) {
        return claimDue(now, limit, UUID.randomUUID().toString(), 120_000L);
    }

    List<Row> claimDue(long now, int limit, String ownerId, long claimTtlMs) {
        int boundedLimit = Math.max(1, limit);
        long claimedUntil = now + Math.max(1_000L, claimTtlMs);
        List<String> candidateIds = db.readClaimDue1(now, now, boundedLimit);
        if (candidateIds.isEmpty()) {
            return List.of();
        }
        db.claim(candidateIds, ownerId, claimedUntil, now);
        return Rows.map(db.claimed(candidateIds, ownerId, boundedLimit), (rs, n) -> new Row(rs.instanceId, rs.tenantId, rs.chatId, rs.outcome, rs.webhookUrl, Rows.intValue(rs.attempts), rs.claimedBy));
    }

    public boolean markDelivered(String instanceId, String claimOwner, long now) {
        return db.writeMarkDelivered1(now, instanceId, claimOwner, now) == 1;
    }

    public boolean markRetry(
            String instanceId,
            String claimOwner,
            int attempts,
            long nextAttemptAt,
            String lastError,
            long now) {
        return db.writeMarkRetry1(attempts, nextAttemptAt, trunc(lastError), now, instanceId, claimOwner, now) == 1;
    }

    public boolean markDead(
            String instanceId,
            String claimOwner,
            int attempts,
            String lastError,
            long now) {
        return db.writeMarkDead1(attempts, trunc(lastError), now, instanceId, claimOwner, now) == 1;
    }

    /** 删除某实例的事件 outbox 行（PII 清除 #10 调用）。 */
    public void delete(String instanceId) {
        db.writeDelete1(instanceId);
    }

    private static String trunc(String s) {
        if (s == null) return null;
        return s.length() <= 512 ? s : s.substring(0, 512);
    }

    /** 一条待发布的终态事件（relay 据此重建 {@code WorkflowTerminalMessage}，reply 另从 WorkflowReplyStore 取）。 */
    public record Row(
            String instanceId,
            String tenantId,
            String chatId,
            String outcome,
            String webhookUrl,
            int attempts,
            String claimOwner) {}
}
