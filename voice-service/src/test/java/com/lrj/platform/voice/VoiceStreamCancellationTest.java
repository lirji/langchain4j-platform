package com.lrj.platform.voice;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceStreamCancellationTest {

    @Test
    void disconnectClosesActiveConversationResponse() {
        AtomicBoolean closed = new AtomicBoolean();
        VoiceStreamCancellation cancellation = new VoiceStreamCancellation();
        ByteArrayInputStream response = new ByteArrayInputStream(new byte[0]) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        assertThat(cancellation.register(response)).isTrue();
        cancellation.cancel();

        assertThat(cancellation.isCancelled()).isTrue();
        assertThat(closed).isTrue();
    }
}
