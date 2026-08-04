package com.lrj.platform.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PublicPayloadRedactorTest {

    private final PublicPayloadRedactor redactor =
            PublicPayloadRedactor.standalone();

    @Test
    void redactsPiiAcrossNestedJsonCompatiblePayload() {
        Object result = redactor.redact(Map.of(
                "email", "alice@example.com",
                "nested", List.of("13812345678", Map.of("id", "11010519491231002X"))));

        assertThat(result.toString())
                .contains("[REDACTED-email]", "[REDACTED-phone]", "[REDACTED-id-card]")
                .doesNotContain("alice@example.com", "13812345678", "11010519491231002X");
    }

    @Test
    void convertsRecordsBeforeRedacting() {
        Object result = redactor.redact(new Contact("alice@example.com", "safe"));

        assertThat(result).isEqualTo(Map.of("email", "[REDACTED-email]", "label", "safe"));
    }

    @Test
    void standaloneMapperKeepsWebCompatibleIsoTimestamps() {
        Object result = redactor.redact(new Dated(Instant.parse("2026-08-03T00:00:00Z")));

        assertThat(result).isEqualTo(Map.of("createdAt", "2026-08-03T00:00:00Z"));
    }

    private record Contact(String email, String label) {
    }

    private record Dated(Instant createdAt) {
    }
}
