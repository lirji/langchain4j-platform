package com.lrj.platform.agent.reflexion;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.lrj.platform.security.PublicPayloadRedactor;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ReflexionControllerTest {

    @Test
    void streamFailureUsesStableEnvelopeWithoutProviderDetail() {
        RecordingEmitter emitter = new RecordingEmitter();

        ReflexionController.fail(
                emitter,
                new IllegalStateException("model-provider-key=secret"));

        assertThat(emitter.data).contains(Map.of(
                "error", "agent reflexion failed",
                "code", "AGENT_REFLEXION_STREAM_FAILED"));
        assertThat(emitter.data.toString()).doesNotContain("model-provider-key");
        assertThat(emitter.completed).isTrue();
        assertThat(emitter.completedWithError).isFalse();
    }

    @Test
    void progressSinkRedactsNestedPiiBeforeSending() {
        RecordingEmitter emitter = new RecordingEmitter();
        PublicPayloadRedactor redactor = new PublicPayloadRedactor(
                JsonMapper.builder().findAndAddModules().build());

        ReflexionController.sink(emitter, new AtomicBoolean(), redactor)
                .emit("answer", Map.of(
                        "email", "alice@example.com",
                        "phone", "13812345678"));

        assertThat(emitter.data.toString())
                .contains("[REDACTED-email]", "[REDACTED-phone]")
                .doesNotContain("alice@example.com", "13812345678");
    }

    private static final class RecordingEmitter extends SseEmitter {
        private final List<Object> data = new ArrayList<>();
        private boolean completed;
        private boolean completedWithError;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            builder.build().forEach(item -> data.add(item.getData()));
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable error) {
            completedWithError = true;
        }
    }
}
