package com.lrj.platform.conversation.guardrail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingPiiRedactorTest {

    @Test
    void redactsEmailPhoneAndIdAcrossTokenBoundariesBeforeEmission() {
        StreamingPiiRedactor redactor = new StreamingPiiRedactor(
                new ConversationGuardrail(false, "block", true));

        assertThat(redactor.accept("邮箱 test@")).isEqualTo("邮箱 ");
        assertThat(redactor.accept("example.com，手机 138")).isEqualTo("[REDACTED-email]，手机 ");
        assertThat(redactor.accept("0013")).isEmpty();
        assertThat(redactor.accept("8000，身份证 11010519491231")).isEqualTo("[REDACTED-phone]，身份证 ");
        assertThat(redactor.accept("002X")).isEmpty();
        assertThat(redactor.finish()).isEqualTo("[REDACTED-id-card]");
    }

    @Test
    void disabledRedactionPreservesImmediateTokenStreaming() {
        StreamingPiiRedactor redactor = new StreamingPiiRedactor(
                new ConversationGuardrail(false, "block", false));

        assertThat(redactor.accept("test@example.com")).isEqualTo("test@example.com");
        assertThat(redactor.finish()).isEmpty();
    }
}
