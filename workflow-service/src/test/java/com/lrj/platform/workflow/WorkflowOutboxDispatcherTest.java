package com.lrj.platform.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.OutboundCallbackPolicy;
import com.lrj.platform.security.OutboundWebhookSigner;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowOutboxDispatcherTest {

    private static final String SECRET =
            "test-workflow-webhook-signing-secret-with-at-least-32-bytes";

    @Test
    void deliversExactBodyWithVersionedSignature() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, String> headers = new ConcurrentHashMap<>();
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/hook", exchange -> {
            try (InputStream input = exchange.getRequestBody()) {
                body.set(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.getRequestHeaders().forEach((key, value) ->
                    headers.put(key.toLowerCase(Locale.ROOT), value.getFirst()));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            String target = localUrl(server, "/hook");
            WorkflowOutbox outbox = mock(WorkflowOutbox.class);
            WorkflowReplyStore replies = mock(WorkflowReplyStore.class);
            when(outbox.claimDue(anyLong(), eq(50), anyString(), anyLong()))
                    .thenReturn(List.of(new WorkflowOutbox.Row(
                            "wf-1", "acme", target, 0, "owner-1")));
            when(outbox.markDelivered(anyString(), anyString(), anyLong())).thenReturn(true);
            when(replies.find("wf-1")).thenReturn("approved");

            dispatcher(outbox, replies, target).dispatch();

            verify(outbox).markDelivered(eq("wf-1"), eq("owner-1"), anyLong());
            assertThat(body.get()).contains("\"instanceId\":\"wf-1\"")
                    .contains("\"tenantId\":\"acme\"")
                    .contains("\"reply\":\"approved\"");
            assertThat(headers.get("x-webhook-event")).isEqualTo("workflow.completed");
            String delivery = headers.get("x-webhook-delivery");
            assertThat(delivery).isEqualTo("workflow:wf-1");
            long timestamp = Long.parseLong(headers.get("x-webhook-timestamp"));
            assertThat(OutboundWebhookSigner.verify(
                    SECRET,
                    new OutboundWebhookSigner.SignedHeaders(
                            timestamp, delivery, headers.get("x-webhook-event"),
                            headers.get("x-webhook-signature")),
                    body.get(), Instant.now(), Duration.ofSeconds(10)))
                    .isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void redirectBecomesPolicyDeadLetterWithoutFollowing() throws Exception {
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
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            String target = localUrl(server, "/redirect");
            WorkflowOutbox outbox = mock(WorkflowOutbox.class);
            WorkflowReplyStore replies = mock(WorkflowReplyStore.class);
            when(outbox.claimDue(anyLong(), eq(50), anyString(), anyLong()))
                    .thenReturn(List.of(new WorkflowOutbox.Row(
                            "wf-2", "acme", target, 0, "owner-1")));
            when(outbox.markDead(anyString(), anyString(), eq(1), anyString(), anyLong()))
                    .thenReturn(true);

            dispatcher(outbox, replies, target).dispatch();

            assertThat(redirectHits).hasValue(1);
            assertThat(sinkHits).hasValue(0);
            verify(outbox).markDead(
                    eq("wf-2"), eq("owner-1"), eq(1), eq("callback_policy"), anyLong());
            verify(outbox, never()).markRetry(
                    eq("wf-2"), eq("owner-1"), eq(1), anyLong(), eq("POLICY_ERROR"), anyLong());
        } finally {
            server.stop(0);
        }
    }

    private static WorkflowOutboxDispatcher dispatcher(
            WorkflowOutbox outbox, WorkflowReplyStore replies, String trustedUrl) throws Exception {
        WorkflowProperties properties = new WorkflowProperties();
        properties.getOutbox().setHmacSecret(SECRET);
        properties.getOutbox().setTimeout(Duration.ofSeconds(2));
        InternalSecurityProperties.Callback callback = new InternalSecurityProperties.Callback();
        callback.setRequireAllowedOrigin(true);
        callback.setTrustedInternalUrls(List.of(trustedUrl));
        OutboundCallbackPolicy policy = new OutboundCallbackPolicy(
                callback, host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        return new WorkflowOutboxDispatcher(
                outbox,
                replies,
                properties,
                mock(AuditLogger.class),
                new ObjectMapper(),
                policy);
    }

    private static String localUrl(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }
}
