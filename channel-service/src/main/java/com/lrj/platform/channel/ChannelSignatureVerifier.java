package com.lrj.platform.channel;

import com.lrj.platform.protocol.channel.ChannelInboundEvent;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class ChannelSignatureVerifier {

    private final ChannelProperties properties;

    public ChannelSignatureVerifier(ChannelProperties properties) {
        this.properties = properties;
    }

    public boolean verify(ChannelInboundEvent event, String signatureHeader) {
        if (!properties.isInboundSignatureEnabled()) {
            return true;
        }
        if (properties.getInboundSignatureSecret() == null || properties.getInboundSignatureSecret().isBlank()) {
            return false;
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        String expected = "sha256=" + hmacSha256(canonical(event), properties.getInboundSignatureSecret());
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
    }

    public String sign(ChannelInboundEvent event) {
        return "sha256=" + hmacSha256(canonical(event), properties.getInboundSignatureSecret());
    }

    private static String canonical(ChannelInboundEvent event) {
        return value(event.eventId()) + "|" + value(event.channel()) + "|" + value(event.source()) + "|" + value(event.eventType());
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String hmacSha256(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to calculate channel signature", ex);
        }
    }
}
