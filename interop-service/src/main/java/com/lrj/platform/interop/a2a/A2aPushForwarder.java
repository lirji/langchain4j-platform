package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.interop.InteropProperties;
import com.lrj.platform.interop.a2a.MessageSendParams.PushNotificationConfig;
import com.lrj.platform.protocol.agent.AgentTaskView;
import com.lrj.platform.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

    public A2aPushForwarder(A2aPushNotificationStore store,
                            A2aAgentGateway gateway,
                            A2aTaskMapper mapper,
                            ObjectMapper json,
                            InteropProperties props,
                            @Qualifier("interopStreamExecutor") Executor executor) {
        this.store = store;
        this.gateway = gateway;
        this.mapper = mapper;
        this.json = json;
        this.props = props;
        this.executor = executor;
        this.http = HttpClient.newBuilder()
                .connectTimeout(props.getA2a().getPushConnectTimeout())
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
            deliver(mapper.toA2aTask(task.get()), cfg);
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
        String secret = props.getA2a().getPushHmacSecret();
        String signature = (secret != null && !secret.isBlank()) ? hmacSha256Hex(secret, body) : null;

        int attempts = Math.max(1, props.getA2a().getPushMaxRetries() + 1);
        long backoffMs = props.getA2a().getPushBackoff().toMillis();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Outcome outcome = sendOnce(cfg, body, signature, deliveryId);
            if (outcome == Outcome.SUCCESS) {
                log.info("A2A push delivered task={} deliveryId={} attempt={} url={}",
                        task.id(), deliveryId, attempt, cfg.url());
                return;
            }
            if (outcome == Outcome.CLIENT_ERROR) {
                log.warn("A2A push failed (client 4xx, no retry) task={} deliveryId={} url={}",
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

    private Outcome sendOnce(PushNotificationConfig cfg, String body, String signature, String deliveryId) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.url()))
                    .timeout(props.getA2a().getPushReadTimeout())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-Webhook-Event", "a2a.task.finished")
                    .header("X-Webhook-Delivery", deliveryId);
            if (signature != null) {
                b.header("X-Webhook-Signature", signature);
            }
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
            if (code >= 400 && code < 500) {
                return Outcome.CLIENT_ERROR;
            }
            return Outcome.SERVER_ERROR;
        } catch (Exception e) {
            log.debug("A2A push attempt failed url={}: {}", cfg.url(), e.toString());
            return Outcome.NETWORK_ERROR;
        }
    }

    static String hmacSha256Hex(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte x : raw) {
                sb.append(Character.forDigit((x >> 4) & 0xF, 16));
                sb.append(Character.forDigit(x & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 signing failed", e);
        }
    }

    private enum Outcome { SUCCESS, CLIENT_ERROR, SERVER_ERROR, NETWORK_ERROR }
}
