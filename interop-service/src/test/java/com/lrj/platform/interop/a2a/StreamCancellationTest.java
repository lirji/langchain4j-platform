package com.lrj.platform.interop.a2a;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class StreamCancellationTest {

    @Test
    void cancelClosesRegisteredUpstreamAndLateRegistration() {
        AtomicBoolean firstClosed = new AtomicBoolean();
        StreamCancellation cancellation = new StreamCancellation();
        ByteArrayInputStream first = closeTracking(firstClosed);

        assertThat(cancellation.register(first)).isTrue();
        cancellation.cancel();

        assertThat(cancellation.isCancelled()).isTrue();
        assertThat(firstClosed).isTrue();

        AtomicBoolean lateClosed = new AtomicBoolean();
        assertThat(cancellation.register(closeTracking(lateClosed))).isFalse();
        assertThat(lateClosed).isTrue();
    }

    @Test
    void normalFinishMakesEmitterCompletionANoOp() {
        AtomicBoolean closed = new AtomicBoolean();
        StreamCancellation cancellation = new StreamCancellation();
        cancellation.register(closeTracking(closed));

        cancellation.finish();
        cancellation.cancel();

        assertThat(cancellation.isCancelled()).isFalse();
        assertThat(closed).isFalse();
    }

    private static ByteArrayInputStream closeTracking(AtomicBoolean closed) {
        return new ByteArrayInputStream(new byte[0]) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
    }
}
