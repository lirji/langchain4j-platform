package com.lrj.platform.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/** Versioned HMAC envelope shared by async-task, workflow and A2A callbacks. */
public final class OutboundWebhookSigner {

    private static final String VERSION = "v1";

    private OutboundWebhookSigner() {
    }

    public static void requireStrongSecret(String secret) {
        requireSecret(secret);
    }

    public static SignedHeaders sign(
            String secret,
            String event,
            String deliveryId,
            Instant timestamp,
            String body) {
        requireSecret(secret);
        requireValue(event, "event");
        requireValue(deliveryId, "delivery-id");
        if (timestamp == null) throw new IllegalArgumentException("webhook timestamp is required");
        if (body == null) throw new IllegalArgumentException("webhook body is required");
        long epochSeconds = timestamp.getEpochSecond();
        String signature = VERSION + "=" + hmacHex(
                secret, signingInput(epochSeconds, deliveryId, event, body));
        return new SignedHeaders(epochSeconds, deliveryId, event, signature);
    }

    public static boolean verify(
            String secret,
            SignedHeaders headers,
            String body,
            Instant now,
            Duration maxSkew) {
        if (headers == null || body == null || now == null || maxSkew == null
                || maxSkew.isNegative() || !headers.signature().startsWith(VERSION + "=")) {
            return false;
        }
        try {
            requireSecret(secret);
            long skew = Math.abs(now.getEpochSecond() - headers.timestamp());
            if (skew > maxSkew.toSeconds()) return false;
            String expected = VERSION + "=" + hmacHex(
                    secret,
                    signingInput(headers.timestamp(), headers.deliveryId(), headers.event(), body));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII),
                    headers.signature().getBytes(StandardCharsets.US_ASCII));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String signingInput(long timestamp, String deliveryId, String event, String body) {
        return timestamp + "\n" + deliveryId + "\n" + event + "\n" + body;
    }

    private static String hmacHex(String secret, String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 signing failed", exception);
        }
    }

    private static void requireSecret(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("webhook signing secret must contain at least 32 bytes");
        }
    }

    private static void requireValue(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("webhook " + name + " is invalid");
        }
    }

    public record SignedHeaders(long timestamp, String deliveryId, String event, String signature) {
    }
}
