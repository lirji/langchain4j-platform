package com.lrj.platform.channel;

import com.lrj.platform.protocol.channel.ChannelInboundEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChannelSignatureVerifierTest：验证 {@link ChannelSignatureVerifier} 在入站签名关闭时全部放行、
 * 开启后自签能通过校验、以及伪造签名被拒。
 */
class ChannelSignatureVerifierTest {

    @Test
    void acceptsAllWhenSignatureDisabled() {
        ChannelSignatureVerifier verifier = new ChannelSignatureVerifier(new ChannelProperties());

        assertThat(verifier.verify(event(), null)).isTrue();
    }

    @Test
    void verifiesValidSignature() {
        ChannelProperties properties = new ChannelProperties();
        properties.setInboundSignatureEnabled(true);
        properties.setInboundSignatureSecret("secret");
        ChannelSignatureVerifier verifier = new ChannelSignatureVerifier(properties);

        assertThat(verifier.verify(event(), verifier.sign(event()))).isTrue();
    }

    @Test
    void rejectsInvalidSignature() {
        ChannelProperties properties = new ChannelProperties();
        properties.setInboundSignatureEnabled(true);
        properties.setInboundSignatureSecret("secret");
        ChannelSignatureVerifier verifier = new ChannelSignatureVerifier(properties);

        assertThat(verifier.verify(event(), "sha256=bad")).isFalse();
    }

    private ChannelInboundEvent event() {
        return new ChannelInboundEvent("event-1", "webhook", "sender", "message.created", Map.of(), Instant.now());
    }
}
