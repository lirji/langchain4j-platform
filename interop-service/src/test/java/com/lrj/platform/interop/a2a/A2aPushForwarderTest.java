package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.interop.InteropProperties;
import com.lrj.platform.interop.a2a.MessageSendParams.PushNotificationConfig;
import com.lrj.platform.protocol.agent.AgentTaskView;
import com.lrj.platform.security.TenantContext;
import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.OutboundCallbackPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2aPushForwarderTest：验证 {@link A2aPushForwarder} 的任务终态 push 中继——统一 HMAC 签名、
 * 未登记 push 的任务被忽略、投递 A2A Task 信封（含 {@code X-A2A-Notification-Token} 与 HMAC 签名头）
 * 后从 store 清理且不泄漏 {@link TenantContext}。
 */
class A2aPushForwarderTest {

    private final ObjectMapper json = new ObjectMapper();
    private final A2aTaskMapper mapper = new A2aTaskMapper();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void unregisteredTaskIsIgnoredWithoutFetchingTask() {
        RecordingGateway gateway = new RecordingGateway(null);
        A2aPushForwarder forwarder = new A2aPushForwarder(
                new A2aPushNotificationStore(), gateway, mapper, json, new InteropProperties(), Runnable::run,
                callbackPolicy(null));

        forwarder.onTerminal("t1", "acme"); // 未登记 push → 直接忽略

        assertThat(gateway.getTaskCalls).isZero();
    }

    @Test
    void deliversA2aEnvelopeWithSignatureAndTokenThenClears() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, String> headers = new ConcurrentHashMap<>();
        AtomicReference<String> bodyRef = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(1);
        server.createContext("/hook", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                bodyRef.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            received.countDown();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";

            A2aPushNotificationStore store = new A2aPushNotificationStore();
            store.put("acme", "t1", new PushNotificationConfig(url, "tok-123", "cfg-1"));

            RecordingGateway gateway = new RecordingGateway(new AgentTaskView(
                    "t1", "acme", "alice", "SUCCEEDED", Map.of(),
                    Map.of("finalAnswer", "done"), null,
                    "2026-07-08T00:00:00Z", "2026-07-08T00:00:01Z", "2026-07-08T00:00:01Z"));

            InteropProperties props = new InteropProperties();
            props.getA2a().setPushHmacSecret(
                    "test-a2a-webhook-signing-secret-with-at-least-32-bytes");

            A2aPushForwarder forwarder = new A2aPushForwarder(
                    store, gateway, mapper, json, props, Runnable::run, callbackPolicy(url));

            forwarder.onTerminal("t1", "acme");

            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
            // A2A Task 信封（非 agent 原生快照）：kind=task、含 taskId 与 answer 文本
            assertThat(bodyRef.get()).contains("\"kind\":\"task\"").contains("t1").contains("done");
            assertThat(headers.get("x-a2a-notification-token")).isEqualTo("tok-123");
            assertThat(headers.get("x-webhook-event")).isEqualTo("a2a.task.finished");
            assertThat(headers.get("x-webhook-signature")).startsWith("v1=").hasSize(67);
            assertThat(headers.get("x-webhook-timestamp")).isNotBlank();
            // 投递后从 store 清理
            assertThat(store.get("acme", "t1")).isEmpty();
            // 中继线程结束后不泄漏租户
            assertThat(TenantContext.current()).isEqualTo(TenantContext.ANONYMOUS);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsRedirectWithoutFollowingOrRetrying() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger redirectHits = new AtomicInteger();
        AtomicInteger sinkHits = new AtomicInteger();
        server.createContext("/redirect", exchange -> {
            redirectHits.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "/sink");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/sink", exchange -> {
            sinkHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect";
            A2aPushNotificationStore store = new A2aPushNotificationStore();
            store.put("acme", "t1", new PushNotificationConfig(url, null, null));
            RecordingGateway gateway = new RecordingGateway(new AgentTaskView(
                    "t1", "acme", "alice", "SUCCEEDED", Map.of(), Map.of("finalAnswer", "done"),
                    null, "2026-07-08T00:00:00Z", "2026-07-08T00:00:01Z", "2026-07-08T00:00:01Z"));
            InteropProperties props = new InteropProperties();
            props.getA2a().setPushMaxRetries(3);
            props.getA2a().setPushBackoff(java.time.Duration.ZERO);

            A2aPushForwarder forwarder = new A2aPushForwarder(
                    store, gateway, mapper, json, props, Runnable::run, callbackPolicy(url));
            forwarder.onTerminal("t1", "acme");

            assertThat(redirectHits).hasValue(1);
            assertThat(sinkHits).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    private static OutboundCallbackPolicy callbackPolicy(String trustedUrl) {
        InternalSecurityProperties.Callback properties = new InternalSecurityProperties.Callback();
        if (trustedUrl != null) {
            properties.setTrustedInternalUrls(List.of(trustedUrl));
        }
        return new OutboundCallbackPolicy(
                properties,
                host -> new java.net.InetAddress[]{java.net.InetAddress.getByName("127.0.0.1")});
    }

    private static final class RecordingGateway implements A2aAgentGateway {
        private final AgentTaskView task;
        int getTaskCalls = 0;

        RecordingGateway(AgentTaskView task) {
            this.task = task;
        }

        @Override
        public String chat(String text) {
            return "";
        }

        @Override
        public AgentTaskView submitTask(String goal, String webhookUrl) {
            return null;
        }

        @Override
        public Optional<AgentTaskView> getTask(String taskId) {
            getTaskCalls++;
            return Optional.ofNullable(task);
        }

        @Override
        public boolean cancelTask(String taskId) {
            return false;
        }
    }
}
