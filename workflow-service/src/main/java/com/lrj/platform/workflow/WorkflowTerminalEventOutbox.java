package com.lrj.platform.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

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
 * （引擎配了 {@code workflowTransactionManager}）。本类的 {@link JdbcTemplate} 用同一个 {@code workflowDataSource}，
 * 经 {@code DataSourceUtils} 取到线程绑定的同一连接 → INSERT 与 {@code ACT_*} 终态写、{@code WF_REPLY} 同一事务提交。
 * 于是「终态已提交 ⇔ 事件 outbox 行已写」原子成立，消除了「终态提交后、通知未落库」的丢失窗口。投递由
 * {@link WorkflowTerminalEventRelay} 从本表 relay 到 Kafka（至少一次 + 消费侧 eventId 去重）。
 */
@Component
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class WorkflowTerminalEventOutbox {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTerminalEventOutbox.class);

    private final JdbcTemplate jdbc;

    public WorkflowTerminalEventOutbox(DataSource workflowDataSource) {
        this.jdbc = new JdbcTemplate(workflowDataSource);
        init();
    }

    void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS WF_TERMINAL_EVENT_OUTBOX (
                  INSTANCE_ID VARCHAR(64) NOT NULL PRIMARY KEY,
                  TENANT_ID VARCHAR(64),
                  CHAT_ID VARCHAR(128),
                  OUTCOME VARCHAR(32),
                  WEBHOOK_URL VARCHAR(1024),
                  STATUS VARCHAR(16) NOT NULL,
                  ATTEMPTS INT NOT NULL DEFAULT 0,
                  NEXT_ATTEMPT_AT BIGINT NOT NULL,
                  LAST_ERROR VARCHAR(512),
                  CREATED_AT BIGINT NOT NULL,
                  UPDATED_AT BIGINT NOT NULL,
                  INDEX IDX_WF_EVT_OUTBOX_DUE (STATUS, NEXT_ATTEMPT_AT)
                )""");
        log.info("WF_TERMINAL_EVENT_OUTBOX 表就绪（终态 Kafka 事件事务性 outbox，B1b 收口）");
    }

    /**
     * 入队（或重置）一条待发布的终态事件：status=PENDING、attempts=0、立即可投。
     * 由 {@link WorkflowTerminalOutboxListener} 在 Flowable 终态事务内调用 → 与终态原子提交。
     */
    public void enqueue(String instanceId, String tenantId, String chatId, String outcome,
                        String webhookUrl, long now) {
        jdbc.update("""
                INSERT INTO WF_TERMINAL_EVENT_OUTBOX
                  (INSTANCE_ID, TENANT_ID, CHAT_ID, OUTCOME, WEBHOOK_URL, STATUS, ATTEMPTS, NEXT_ATTEMPT_AT, LAST_ERROR, CREATED_AT, UPDATED_AT)
                VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, NULL, ?, ?)
                ON DUPLICATE KEY UPDATE TENANT_ID=VALUES(TENANT_ID), CHAT_ID=VALUES(CHAT_ID), OUTCOME=VALUES(OUTCOME),
                  WEBHOOK_URL=VALUES(WEBHOOK_URL), STATUS='PENDING', ATTEMPTS=0,
                  NEXT_ATTEMPT_AT=VALUES(NEXT_ATTEMPT_AT), LAST_ERROR=NULL, UPDATED_AT=VALUES(UPDATED_AT)""",
                instanceId, tenantId, chatId, outcome, webhookUrl, now, now, now);
    }

    /** 取到期待发布行（STATUS=PENDING 且 NEXT_ATTEMPT_AT<=now），按到期时间升序，最多 limit 条。 */
    public List<Row> claimDue(long now, int limit) {
        return jdbc.query("""
                SELECT INSTANCE_ID, TENANT_ID, CHAT_ID, OUTCOME, WEBHOOK_URL, ATTEMPTS FROM WF_TERMINAL_EVENT_OUTBOX
                WHERE STATUS='PENDING' AND NEXT_ATTEMPT_AT <= ?
                ORDER BY NEXT_ATTEMPT_AT ASC LIMIT ?""",
                (rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6)),
                now, limit);
    }

    public void markDelivered(String instanceId, long now) {
        jdbc.update("UPDATE WF_TERMINAL_EVENT_OUTBOX SET STATUS='DELIVERED', UPDATED_AT=? WHERE INSTANCE_ID=?", now, instanceId);
    }

    public void markRetry(String instanceId, int attempts, long nextAttemptAt, String lastError, long now) {
        jdbc.update("UPDATE WF_TERMINAL_EVENT_OUTBOX SET ATTEMPTS=?, NEXT_ATTEMPT_AT=?, LAST_ERROR=?, UPDATED_AT=? WHERE INSTANCE_ID=?",
                attempts, nextAttemptAt, trunc(lastError), now, instanceId);
    }

    public void markDead(String instanceId, int attempts, String lastError, long now) {
        jdbc.update("UPDATE WF_TERMINAL_EVENT_OUTBOX SET STATUS='DEAD', ATTEMPTS=?, LAST_ERROR=?, UPDATED_AT=? WHERE INSTANCE_ID=?",
                attempts, trunc(lastError), now, instanceId);
    }

    /** 删除某实例的事件 outbox 行（PII 清除 #10 调用）。 */
    public void delete(String instanceId) {
        jdbc.update("DELETE FROM WF_TERMINAL_EVENT_OUTBOX WHERE INSTANCE_ID=?", instanceId);
    }

    private static String trunc(String s) {
        if (s == null) return null;
        return s.length() <= 512 ? s : s.substring(0, 512);
    }

    /** 一条待发布的终态事件（relay 据此重建 {@code WorkflowTerminalMessage}，reply 另从 WorkflowReplyStore 取）。 */
    public record Row(String instanceId, String tenantId, String chatId, String outcome, String webhookUrl, int attempts) {}
}
