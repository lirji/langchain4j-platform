package com.lrj.platform.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RequestDeadlineFilterTest {

    private static final Instant NOW = Instant.parse("2026-08-03T05:00:00Z");

    @AfterEach
    void clear() {
        RequestDeadlineContext.clear();
    }

    @Test
    void bindsInboundDeadlineAndAlwaysClearsThreadLocal() throws Exception {
        InternalSecurityProperties.HttpResilience properties = properties();
        RequestDeadlineFilter filter = new RequestDeadlineFilter(
                properties, Clock.fixed(NOW, ZoneOffset.UTC));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Deadline-Ms", NOW.plusSeconds(20).toEpochMilli());
        MockHttpServletResponse response = new MockHttpServletResponse();
        long[] observed = new long[1];

        filter.doFilter(request, response, (req, res) ->
                observed[0] = RequestDeadlineContext.captureRaw());

        assertThat(observed[0]).isEqualTo(NOW.plusSeconds(20).toEpochMilli());
        assertThat(RequestDeadlineContext.captureRaw()).isNull();
    }

    @Test
    void clampsFarFutureDeadline() throws Exception {
        InternalSecurityProperties.HttpResilience properties = properties();
        RequestDeadlineFilter filter = new RequestDeadlineFilter(
                properties, Clock.fixed(NOW, ZoneOffset.UTC));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Deadline-Ms", NOW.plusSeconds(600).toEpochMilli());
        long[] observed = new long[1];

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                observed[0] = RequestDeadlineContext.captureRaw());

        assertThat(observed[0]).isEqualTo(NOW.plusSeconds(60).toEpochMilli());
    }

    @Test
    void rejectsInvalidOrExpiredDeadlineBeforeBusinessCode() throws Exception {
        RequestDeadlineFilter filter = new RequestDeadlineFilter(
                properties(), Clock.fixed(NOW, ZoneOffset.UTC));

        MockHttpServletRequest invalid = new MockHttpServletRequest();
        invalid.addHeader("X-Request-Deadline-Ms", "not-a-number");
        MockHttpServletResponse invalidResponse = new MockHttpServletResponse();
        MockFilterChain invalidChain = new MockFilterChain();
        filter.doFilter(invalid, invalidResponse, invalidChain);
        assertThat(invalidResponse.getStatus()).isEqualTo(400);
        assertThat(invalidResponse.getContentAsString()).contains("INVALID_REQUEST_DEADLINE");
        assertThat(invalidChain.getRequest()).isNull();

        MockHttpServletRequest expired = new MockHttpServletRequest();
        expired.addHeader("X-Request-Deadline-Ms", NOW.minusMillis(1).toEpochMilli());
        MockHttpServletResponse expiredResponse = new MockHttpServletResponse();
        MockFilterChain expiredChain = new MockFilterChain();
        filter.doFilter(expired, expiredResponse, expiredChain);
        assertThat(expiredResponse.getStatus()).isEqualTo(504);
        assertThat(expiredResponse.getContentAsString()).contains("REQUEST_DEADLINE_EXCEEDED");
        assertThat(expiredChain.getRequest()).isNull();
    }

    private static InternalSecurityProperties.HttpResilience properties() {
        InternalSecurityProperties.HttpResilience properties = new InternalSecurityProperties.HttpResilience();
        properties.setDefaultDeadline(Duration.ofSeconds(30));
        properties.setMaxInboundDeadline(Duration.ofSeconds(60));
        return properties;
    }
}
