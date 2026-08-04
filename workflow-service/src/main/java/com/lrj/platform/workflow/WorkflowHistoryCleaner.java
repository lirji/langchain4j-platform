package com.lrj.platform.workflow;

import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
import org.flowable.engine.HistoryService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Flowable 历史清理器（#4 历史表无限增长）。{@code ACT_HI_*} 默认无 TTL，跑几个月几千万行 →
 * 查询/备份双双拖垮（Flowable 运维头号问题）。本类定期删除<b>已结束且早于保留期</b>的历史实例，
 * 连带删 {@link WorkflowReplyStore} 里对应的 reply 行与 {@link WorkflowIdempotencyStore} 绑定。
 *
 * <p>审计留痕已落 {@code logs/audit.jsonl}（合规凭证在那），Flowable 历史只服务"查实例状态/reply"
 * 这类近期运维诉求，故可短留（默认 {@code app.workflow.history-retention=P30D}）。
 *
 * <p>跟 {@link ApprovalTimeoutSweeper} 同范式：{@code @Scheduled} + 逐条 try/catch（单条删除失败不阻断
 * 整轮，下轮还会再扫到）。整体 {@code @ConditionalOnProperty(app.workflow.enabled)}，默认关不装配。
 */
@Component
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class WorkflowHistoryCleaner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowHistoryCleaner.class);

    private final HistoryService historyService;
    private final WorkflowReplyStore replyStore;
    private final AuditLogger audit;
    private final WorkflowProperties props;
    private final WorkflowIdempotencyStore idempotencyStore;
    private final TransactionTemplate transaction;

    public WorkflowHistoryCleaner(HistoryService workflowHistoryService,
                                  WorkflowReplyStore replyStore,
                                  AuditLogger audit,
                                  WorkflowProperties props,
                                  WorkflowIdempotencyStore idempotencyStore,
                                  @Qualifier("workflowTransactionManager") PlatformTransactionManager transactionManager) {
        this.historyService = workflowHistoryService;
        this.replyStore = replyStore;
        this.audit = audit;
        this.props = props;
        this.idempotencyStore = idempotencyStore;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setName("workflow-history-prune");
    }

    @Scheduled(fixedDelayString = "${app.workflow.history-cleanup-interval-ms:3600000}", initialDelay = 3_600_000)
    public void prune() {
        Date cutoff = cutoff(Instant.now(), props.getHistoryRetention());
        List<HistoricProcessInstance> finished = historyService.createHistoricProcessInstanceQuery()
                .finished()
                .finishedBefore(cutoff)
                .list();
        if (finished.isEmpty()) {
            return;
        }
        int pruned = 0;
        for (HistoricProcessInstance hi : finished) {
            try {
                transaction.executeWithoutResult(status -> {
                    replyStore.delete(hi.getId());
                    idempotencyStore.deleteByInstance(hi.getId());
                    historyService.deleteHistoricProcessInstance(hi.getId());
                });
                pruned++;
            } catch (Exception e) {
                log.warn("history cleanup: 删除历史实例 {} 失败：{}", hi.getId(), e.toString());
            }
        }
        audit.record(AuditEventType.WORKFLOW_HISTORY_PRUNED, Map.of(
                "scanned", finished.size(), "pruned", pruned, "retention", props.getHistoryRetention().toString()));
        log.info("history cleanup: 扫到 {} 个超期已结束实例，删除 {}（保留期={}）",
                finished.size(), pruned, props.getHistoryRetention());
    }

    /** 保留期分界点 = now - retention。抽 static 纯函数便于单测。 */
    static Date cutoff(Instant now, Duration retention) {
        return Date.from(now.minus(retention));
    }
}
