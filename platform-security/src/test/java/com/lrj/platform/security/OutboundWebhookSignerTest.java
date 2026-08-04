package com.lrj.platform.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundWebhookSignerTest {

    private static final String SECRET = "test-callback-signing-secret-with-at-least-32-bytes";

    @Test
    void bindsTimestampDeliveryEventAndExactBody() {
        Instant now = Instant.parse("2026-08-03T00:00:00Z");
        OutboundWebhookSigner.SignedHeaders headers = OutboundWebhookSigner.sign(
                SECRET, "async-task.finished", "delivery-1", now, "{\"ok\":true}");

        assertTrue(OutboundWebhookSigner.verify(
                SECRET, headers, "{\"ok\":true}", now.plusSeconds(10), Duration.ofMinutes(5)));
        assertFalse(OutboundWebhookSigner.verify(
                SECRET, headers, "{\"ok\":false}", now.plusSeconds(10), Duration.ofMinutes(5)));
        assertFalse(OutboundWebhookSigner.verify(
                "different-callback-signing-secret-with-at-least-32-bytes",
                headers,
                "{\"ok\":true}",
                now.plusSeconds(10),
                Duration.ofMinutes(5)));
        assertFalse(OutboundWebhookSigner.verify(
                SECRET, headers, "{\"ok\":true}", now.plusSeconds(301), Duration.ofMinutes(5)));
    }

    @Test
    void rejectsWeakSecret() {
        assertThrows(IllegalArgumentException.class, () -> OutboundWebhookSigner.sign(
                "short", "workflow.completed", "delivery-1", Instant.now(), "{}"));
    }
}
