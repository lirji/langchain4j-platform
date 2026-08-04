package com.lrj.platform.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundHttpResilienceInterceptorTest {

    @AfterEach
    void clearDeadline() {
        RequestDeadlineContext.clear();
    }

    @Test
    void propagatesTighterParentDeadline() throws Exception {
        MutableClock clock = new MutableClock(1_000L);
        OutboundHttpResilienceInterceptor interceptor = interceptor(properties(2, 5), clock);
        RequestDeadlineContext.set(11_000L);
        HttpRequest request = request("https://service.test/path");

        interceptor.intercept(request, new byte[0], (outbound, body) ->
                new MockClientHttpResponse(new byte[0], HttpStatus.OK));

        assertThat(request.getHeaders().getFirst("X-Request-Deadline-Ms")).isEqualTo("11000");
    }

    @Test
    void opensCircuitAfterThresholdAndRecoversWithProbe() throws Exception {
        MutableClock clock = new MutableClock(1_000L);
        OutboundHttpResilienceInterceptor interceptor = interceptor(properties(2, 2), clock);
        AtomicInteger calls = new AtomicInteger();
        HttpRequest request = request("https://service.test/path");

        for (int index = 0; index < 2; index++) {
            interceptor.intercept(request, new byte[0], (outbound, body) -> {
                calls.incrementAndGet();
                return new MockClientHttpResponse(new byte[0], HttpStatus.SERVICE_UNAVAILABLE);
            });
        }
        assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], (outbound, body) -> {
            calls.incrementAndGet();
            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        })).isInstanceOf(OutboundHttpResilienceInterceptor.OutboundDependencyRejectedException.class)
                .hasMessageContaining("circuit_open");
        assertThat(calls).hasValue(2);

        clock.advance(Duration.ofSeconds(11));
        interceptor.intercept(request, new byte[0], (outbound, body) -> {
            calls.incrementAndGet();
            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        });
        assertThat(calls).hasValue(3);
    }

    @Test
    void bulkheadRejectsSecondConcurrentCallWithoutExecutingIt() throws Exception {
        MutableClock clock = new MutableClock(1_000L);
        OutboundHttpResilienceInterceptor interceptor = interceptor(properties(1, 5), clock);
        HttpRequest request = request("http://service.test/path");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(() -> interceptor.intercept(request, new byte[0], (outbound, body) -> {
                calls.incrementAndGet();
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test interrupted", interrupted);
                }
                return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], (outbound, body) -> {
                calls.incrementAndGet();
                return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
            })).isInstanceOf(OutboundHttpResilienceInterceptor.OutboundDependencyRejectedException.class)
                    .hasMessageContaining("bulkhead_full");

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void expiredParentDeadlineFailsBeforeNetworkCall() {
        MutableClock clock = new MutableClock(10_000L);
        OutboundHttpResilienceInterceptor interceptor = interceptor(properties(2, 5), clock);
        RequestDeadlineContext.set(9_999L);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> interceptor.intercept(
                request("https://service.test/path"), new byte[0], (outbound, body) -> {
                    calls.incrementAndGet();
                    return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
                })).isInstanceOf(OutboundHttpResilienceInterceptor.OutboundDependencyRejectedException.class)
                .hasMessageContaining("deadline_exceeded");
        assertThat(calls).hasValue(0);
    }

    private static OutboundHttpResilienceInterceptor interceptor(
            InternalSecurityProperties.HttpResilience properties, Clock clock) {
        return new OutboundHttpResilienceInterceptor(properties, clock);
    }

    private static InternalSecurityProperties.HttpResilience properties(int concurrent, int threshold) {
        InternalSecurityProperties.HttpResilience properties = new InternalSecurityProperties.HttpResilience();
        properties.setDefaultDeadline(Duration.ofSeconds(30));
        properties.setMaxConcurrentPerOrigin(concurrent);
        properties.setCircuitFailureThreshold(threshold);
        properties.setCircuitRecovery(Duration.ofSeconds(10));
        return properties;
    }

    private static HttpRequest request(String uri) {
        return new HttpRequest() {
            private final HttpHeaders headers = new HttpHeaders();

            @Override
            public org.springframework.http.HttpMethod getMethod() {
                return org.springframework.http.HttpMethod.GET;
            }

            @Override
            public URI getURI() {
                return URI.create(uri);
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }

    private static final class MutableClock extends Clock {
        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(Duration duration) {
            millis += duration.toMillis();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }
}
