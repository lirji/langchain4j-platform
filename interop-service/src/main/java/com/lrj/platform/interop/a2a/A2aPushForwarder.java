package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.interop.InteropProperties;
import com.lrj.platform.interop.a2a.MessageSendParams.PushNotificationConfig;
import com.lrj.platform.protocol.agent.AgentTaskView;
import com.lrj.platform.security.OutboundCallbackPolicy;
import com.lrj.platform.security.OutboundWebhookSigner;
import com.lrj.platform.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * A2A push 中继：agent 任务终态回调到 interop 后，若该 task 在 {@link A2aPushNotificationStore} 登记过
 * push 配置，就拉取任务、映射成 **A2A Task 信封**，POST 回客户端 url（HMAC 签名 + {@code X-A2A-Notification-Token}
 * + 重试）。移植自单体 {@code A2aPushNotifier} 的投递逻辑，平台化为 interop 侧中继（agent webhook 触发）。
 */
@Component
public class A2aPushForwarder {

    private static final Logger log = LoggerFactory.getLogger(A2aPushForwarder.class);

    private final A2aPushNotificationStore store;
    private final A2aAgentGateway gateway;
    private final A2aTaskMapper mapper;
    private final ObjectMapper json;
    private final InteropProperties props;
    private final Executor executor;
    private final HttpClient http;
    private final OutboundCallbackPolicy callbackPolicy;

    public A2aPushForwarder(A2aPushNotificationStore store,
                            A2aAgentGateway gateway,
                            A2aTaskMapper mapper,
                            ObjectMapper json,
                            InteropProperties props,
                            @Qualifier("interopStreamExecutor") Executor executor,
                            OutboundCallbackPolicy callbackPolicy) {
        this.store = store;
        this.gateway = gateway;
        this.mapper = mapper;
        this.json = json;
        this.props = props;
        this.executor = executor;
        this.callbackPolicy = callbackPolicy;
        OutboundWebhookSigner.requireStrongSecret(props.getA2a().getPushHmacSecret());
        this.http = HttpClient.newBuilder()
                .connectTimeout(props.getA2a().getPushConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * agent 任务终态回调入口。登记过 push 的 task 才处理；未登记直接忽略（非 A2A push 任务）。
     * 异步执行，回调线程立刻返回，不被客户端投递/重试阻塞。
     */
    public void onTerminal(String taskId, String tenantId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        Optional<PushNotificationConfig> cfg = store.get(tenantId, taskId);
        if (cfg.isEmpty()) {
            return;
        }
        executor.execute(() -> relay(taskId, tenantId, cfg.get()));
    }

    private void relay(String taskId, String tenantId, PushNotificationConfig cfg) {
        // 回调不带内部 JWT（agent webhook 面向任意 URL），据 X-Tenant-Id 还原租户身份供下游透传。
        TenantContext.set(new TenantContext.Tenant(
                tenantId == null ? "" : tenantId, "a2a-push", Set.of()));
        try {
            Optional<AgentTaskView> task = gateway.getTask(taskId);
            if (task.isEmpty()) {
                log.warn("A2A push: task {} not found on terminal callback", taskId);
                return;
            }
            String contextId = store.contextId(tenantId, taskId).orElse(taskId);
            deliver(mapper.toA2aTask(task.get(), contextId), cfg);
            store.remove(tenantId, taskId);
        } catch (Exception e) {
            log.warn("A2A push relay failed task={}", taskId, e);
        } finally {
            TenantContext.clear();
        }
    }

    private void deliver(A2aTask task, PushNotificationConfig cfg) {
        String deliveryId = UUID.randomUUID().toString();
        String body;
        try {
            body = json.writeValueAsString(task);
        } catch (Exception e) {
            log.warn("A2A push payload serialization failed task={}", task.id(), e);
            return;
        }
        int attempts = Math.max(1, props.getA2a().getPushMaxRetries() + 1);
        long backoffMs = props.getA2a().getPushBackoff().toMillis();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Outcome outcome = sendOnce(cfg, body, deliveryId);
            if (outcome == Outcome.SUCCESS) {
                log.info("A2A push delivered task={} deliveryId={} attempt={} url={}",
                        task.id(), deliveryId, attempt, cfg.url());
                return;
            }
            if (outcome == Outcome.CLIENT_ERROR || outcome == Outcome.POLICY_ERROR) {
                log.warn("A2A push failed (client/policy, no retry) task={} deliveryId={} url={}",
                        task.id(), deliveryId, cfg.url());
                return;
            }
            if (attempt < attempts) {
                try {
                    Thread.sleep(Math.max(0, backoffMs * attempt)); // 线性退避
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.warn("A2A push FAILED after {} attempts task={} deliveryId={} url={}",
                attempts, task.id(), deliveryId, cfg.url());
    }

    private Outcome sendOnce(PushNotificationConfig cfg, String body, String deliveryId) {
        try {
            var target = callbackPolicy.requireAllowed(cfg.url());
            OutboundWebhookSigner.SignedHeaders signed = OutboundWebhookSigner.sign(
                    props.getA2a().getPushHmacSecret(),
                    "a2a.task.finished",
                    deliveryId,
                    Instant.now(),
                    body);
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(target)
                    .timeout(props.getA2a().getPushReadTimeout())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-Webhook-Event", signed.event())
                    .header("X-Webhook-Delivery", signed.deliveryId())
                    .header("X-Webhook-Timestamp", Long.toString(signed.timestamp()))
                    .header("X-Webhook-Signature", signed.signature());
            if (cfg.token() != null && !cfg.token().isBlank()) {
                b.header("X-A2A-Notification-Token", cfg.token());
            }
            HttpResponse<String> resp = http.send(
                    b.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                return Outcome.SUCCESS;
            }
            if (code >= 300 && code < 400) {
                return Outcome.POLICY_ERROR;
            }
            if (code >= 400 && code < 500) {
                return Outcome.CLIENT_ERROR;
            }
            return Outcome.SERVER_ERROR;
        } catch (OutboundCallbackPolicy.UnsafeCallbackException e) {
            return Outcome.POLICY_ERROR;
        } catch (Exception e) {
            log.debug("A2A push attempt failed url={}: {}", cfg.url(), e.toString());
            return Outcome.NETWORK_ERROR;
        }
    }

    private enum Outcome { SUCCESS, CLIENT_ERROR, SERVER_ERROR, NETWORK_ERROR, POLICY_ERROR }
}
