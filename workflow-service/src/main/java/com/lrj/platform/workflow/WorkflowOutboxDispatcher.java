package com.lrj.platform.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.audit.AuditEventType;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.security.OutboundCallbackPolicy;
import com.lrj.platform.security.OutboundWebhookSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 终态回推 outbox 的重投调度器（#8）。定时扫 {@link WorkflowOutbox} 里到期的 PENDING 行，逐条 HTTP 投递：
 * <ul>
 *   <li>2xx → {@code DELIVERED}（投递成功，落库后即便重启也不会重复投）；</li>
 *   <li>4xx → 直接 {@code DEAD}（客户端拒收，再投也没用，进 DLQ 等人工）；</li>
 *   <li>5xx / 网络错误 → {@link WorkflowOutbox#schedule} 算下次重投时间，累计尝试到
 *       {@code app.workflow.outbox.max-attempts} 仍失败 → {@code DEAD}（DLQ）。</li>
 * </ul>
 *
 * <p>跟 {@code WebhookDispatcher} 的区别 = <b>可靠性</b>：那个是内存 {@code Thread.sleep} 重试，进程一挂就丢；
 * 这个把待投递落库，重投状态机驱动在调度线程，重启后从库里接着投。签名使用统一的
 * {@link OutboundWebhookSigner} 协议。
 *
 * <p>payload = {@code {instanceId, status, reply, tenantId}}，reply 从 {@link WorkflowReplyStore} 取。
 * 调度线程不过过滤器链，无租户上下文——投递只读已落库数据、不调 LLM，无需重建 {@code TenantContext}。
 */
@Component
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class WorkflowOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WorkflowOutboxDispatcher.class);

    private final WorkflowOutbox outbox;
    private final WorkflowReplyStore replyStore;
    private final WorkflowProperties props;
    private final AuditLogger audit;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final OutboundCallbackPolicy callbackPolicy;
    private final String ownerId = "workflow-http-outbox-" + UUID.randomUUID();

    public WorkflowOutboxDispatcher(WorkflowOutbox outbox,
                                    WorkflowReplyStore replyStore,
                                    WorkflowProperties props,
                                    AuditLogger audit,
                                    ObjectMapper mapper,
                                    OutboundCallbackPolicy callbackPolicy) {
        this.outbox = outbox;
        this.replyStore = replyStore;
        this.props = props;
        this.audit = audit;
        this.mapper = mapper;
        this.callbackPolicy = callbackPolicy;
        OutboundWebhookSigner.requireStrongSecret(props.getOutbox().getHmacSecret());
        this.http = HttpClient.newBuilder()
                .connectTimeout(props.getOutbox().getTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Scheduled(fixedDelayString = "${app.workflow.outbox.poll-interval-ms:30000}", initialDelay = 30_000)
    public void dispatch() {
        long now = System.currentTimeMillis();
        WorkflowProperties.Outbox cfg = props.getOutbox();
        List<WorkflowOutbox.Row> due = outbox.claimDue(
                now, cfg.getBatchSize(), ownerId, claimTtlMillis(cfg));
        if (due.isEmpty()) {
            return;
        }
        int delivered = 0, dead = 0, retried = 0, stale = 0;
        for (WorkflowOutbox.Row row : due) {
            try {
                Outcome o = deliverOne(row, cfg, now);
                switch (o) {
                    case DELIVERED -> delivered++;
                    case DEAD -> dead++;
                    case RETRY -> retried++;
                    case STALE -> stale++;
                }
            } catch (Exception e) {
                // 单条异常不阻断整轮（下轮还会再扫到 PENDING）
                log.warn("outbox dispatch: 实例 {} 投递异常：{}", row.instanceId(), e.toString());
            }
        }
        log.info("outbox dispatch: 到期 {} 条 → delivered={} retry={} dead={} stale={}",
                due.size(), delivered, retried, dead, stale);
    }

    private Outcome deliverOne(WorkflowOutbox.Row row, WorkflowProperties.Outbox cfg, long now) {
        String body = payload(row);
        DeliveryResult r = send(row.targetUrl(), body, "workflow:" + row.instanceId());
        if (r == DeliveryResult.SUCCESS) {
            if (outbox.markDelivered(row.instanceId(), row.claimOwner(), now)) {
                audit.record(AuditEventType.WORKFLOW_PUSH_DELIVERED, Map.of(
                        "instanceId", row.instanceId(), "attempts", row.attempts() + 1));
                return Outcome.DELIVERED;
            }
            return Outcome.STALE;
        }
        int attemptsAfter = row.attempts() + 1;
        if (r == DeliveryResult.CLIENT_ERROR || r == DeliveryResult.POLICY_ERROR) {
            String reason = r == DeliveryResult.CLIENT_ERROR ? "client_4xx" : "callback_policy";
            if (outbox.markDead(row.instanceId(), row.claimOwner(), attemptsAfter, reason, now)) {
                audit.record(AuditEventType.WORKFLOW_PUSH_DEAD, Map.of(
                        "instanceId", row.instanceId(), "attempts", attemptsAfter, "reason", reason));
                return Outcome.DEAD;
            }
            return Outcome.STALE;
        }
        WorkflowOutbox.Decision d = WorkflowOutbox.schedule(attemptsAfter, cfg.getMaxAttempts(), now, cfg.getBaseBackoffMs());
        if (d.dead()) {
            if (outbox.markDead(row.instanceId(), row.claimOwner(), attemptsAfter, r.name(), now)) {
                audit.record(AuditEventType.WORKFLOW_PUSH_DEAD, Map.of(
                        "instanceId", row.instanceId(), "attempts", attemptsAfter, "reason", "max_attempts"));
                return Outcome.DEAD;
            }
            return Outcome.STALE;
        }
        if (outbox.markRetry(
                row.instanceId(), row.claimOwner(), attemptsAfter, d.nextAttemptAt(), r.name(), now)) {
            audit.record(AuditEventType.WORKFLOW_PUSH_FAILED, Map.of(
                    "instanceId", row.instanceId(), "attempts", attemptsAfter, "reason", r.name()));
            return Outcome.RETRY;
        }
        return Outcome.STALE;
    }

    private String payload(WorkflowOutbox.Row row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId", row.instanceId());
        m.put("tenantId", row.tenantId());
        m.put("status", WorkflowService.STATUS_COMPLETED);
        m.put("reply", replyStore.find(row.instanceId()));
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{\"instanceId\":\"" + row.instanceId() + "\"}";
        }
    }

    private DeliveryResult send(String url, String body, String deliveryId) {
        try {
            URI target = callbackPolicy.requireAllowed(url);
            OutboundWebhookSigner.SignedHeaders signed = OutboundWebhookSigner.sign(
                    props.getOutbox().getHmacSecret(),
                    "workflow.completed",
                    deliveryId,
                    Instant.now(),
                    body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(target)
                    .timeout(props.getOutbox().getTimeout())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-Webhook-Signature", signed.signature())
                    .header("X-Webhook-Event", signed.event())
                    .header("X-Webhook-Delivery", signed.deliveryId())
                    .header("X-Webhook-Timestamp", Long.toString(signed.timestamp()))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            int code = http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
            if (code >= 200 && code < 300) return DeliveryResult.SUCCESS;
            if (code >= 300 && code < 400) return DeliveryResult.POLICY_ERROR;
            if (code >= 400 && code < 500) return DeliveryResult.CLIENT_ERROR;
            return DeliveryResult.SERVER_ERROR;
        } catch (OutboundCallbackPolicy.UnsafeCallbackException e) {
            return DeliveryResult.POLICY_ERROR;
        } catch (Exception e) {
            return DeliveryResult.NETWORK_ERROR;
        }
    }

    private enum DeliveryResult { SUCCESS, CLIENT_ERROR, SERVER_ERROR, NETWORK_ERROR, POLICY_ERROR }

    private static long claimTtlMillis(WorkflowProperties.Outbox cfg) {
        Duration ttl = cfg.getClaimTtl();
        return ttl == null || ttl.isZero() || ttl.isNegative()
                ? 120_000L
                : Math.max(1_000L, ttl.toMillis());
    }

    private enum Outcome { DELIVERED, DEAD, RETRY, STALE }
}
