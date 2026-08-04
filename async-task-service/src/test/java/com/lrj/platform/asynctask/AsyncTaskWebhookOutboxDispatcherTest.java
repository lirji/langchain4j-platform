package com.lrj.platform.asynctask;

import com.lrj.platform.audit.AuditLogger;
import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.OutboundCallbackPolicy;
import com.lrj.platform.security.OutboundWebhookSigner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncTaskWebhookOutboxDispatcherTest {

    private static final String SECRET =
            "test-async-webhook-signing-secret-with-at-least-32-bytes";

    @Test
    void sendsVersionedSignatureOverExactPersistedBody() throws Exception {
        String target = "http://callback.local:8080/hook";
        AsyncTaskWebhookOutbox.Row row = row(target);
        AsyncTaskWebhookOutbox outbox = mock(AsyncTaskWebhookOutbox.class);
        RestTemplate rest = mock(RestTemplate.class);
        when(outbox.claimDue(anyLong(), anyInt(), anyString(), anyLong()))
                .thenReturn(List.of(row));
        when(rest.postForEntity(any(URI.class), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.noContent().build());

        dispatcher(outbox, rest, target).dispatch();

        verify(outbox).markDelivered(eq("outbox-1"), eq("owner-1"), anyLong());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(rest).postForEntity(eq(URI.create(target)), entity.capture(), eq(Void.class));
        assertThat(entity.getValue().getBody()).isEqualTo(row.payloadJson());
        var headers = entity.getValue().getHeaders();
        String delivery = headers.getFirst("X-Webhook-Delivery");
        assertThat(delivery).isEqualTo("outbox-1");
        long timestamp = Long.parseLong(headers.getFirst("X-Webhook-Timestamp"));
        assertThat(headers.getFirst("X-Webhook-Event")).isEqualTo("async-task.finished");
        assertThat(OutboundWebhookSigner.verify(
                SECRET,
                new OutboundWebhookSigner.SignedHeaders(
                        timestamp,
                        delivery,
                        headers.getFirst("X-Webhook-Event"),
                        headers.getFirst("X-Webhook-Signature")),
                row.payloadJson(),
                Instant.now(),
                Duration.ofSeconds(10)))
                .isTrue();
    }

    @Test
    void redirectIsDeadLetteredWithoutRetry() throws Exception {
        String target = "http://callback.local:8080/hook";
        AsyncTaskWebhookOutbox outbox = mock(AsyncTaskWebhookOutbox.class);
        RestTemplate rest = mock(RestTemplate.class);
        when(outbox.claimDue(anyLong(), anyInt(), anyString(), anyLong()))
                .thenReturn(List.of(row(target)));
        when(rest.postForEntity(any(URI.class), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.FOUND).build());

        dispatcher(outbox, rest, target).dispatch();

        verify(outbox).markDead(
                eq("outbox-1"), eq("owner-1"), eq(1), eq("callback_policy"), anyLong());
        verify(outbox, never()).markRetry(
                eq("outbox-1"), eq("owner-1"), eq(1), anyLong(), eq("POLICY_ERROR"), anyLong());
    }

    private static AsyncTaskWebhookOutboxDispatcher dispatcher(
            AsyncTaskWebhookOutbox outbox, RestTemplate rest, String trustedUrl) throws Exception {
        AsyncTaskWebhookProperties properties = new AsyncTaskWebhookProperties();
        properties.setHmacSecret(SECRET);
        InternalSecurityProperties.Callback callback = new InternalSecurityProperties.Callback();
        callback.setRequireAllowedOrigin(true);
        callback.setTrustedInternalUrls(List.of(trustedUrl));
        OutboundCallbackPolicy policy = new OutboundCallbackPolicy(
                callback, host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        return new AsyncTaskWebhookOutboxDispatcher(
                outbox, properties, rest, mock(AuditLogger.class), policy);
    }

    private static AsyncTaskWebhookOutbox.Row row(String target) {
        return new AsyncTaskWebhookOutbox.Row(
                "outbox-1",
                "task-1",
                "acme",
                target,
                "SUCCEEDED",
                "{\"taskId\":\"task-1\",\"status\":\"SUCCEEDED\"}",
                0,
                "owner-1");
    }
}
