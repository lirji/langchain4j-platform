package com.lrj.platform.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashThenMatchesRoundTrip() {
        String hash = hasher.hash("demo12345");
        assertThat(hash).isNotBlank().isNotEqualTo("demo12345");
        assertThat(hasher.matches("demo12345", hash)).isTrue();
        assertThat(hasher.matches("wrong", hash)).isFalse();
    }

    @Test
    void nullOrBlankInputsNeverMatch() {
        String hash = hasher.hash("x");
        assertThat(hasher.matches(null, hash)).isFalse();
        assertThat(hasher.matches("", hash)).isFalse();
        assertThat(hasher.matches("x", null)).isFalse();
        assertThat(hasher.matches("x", "  ")).isFalse();
    }
}
