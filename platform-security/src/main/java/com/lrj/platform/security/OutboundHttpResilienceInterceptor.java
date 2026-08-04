package com.lrj.platform.security;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 每 origin 的 fail-fast bulkhead + circuit breaker，并传播绝对 deadline。
 * 只对 5xx、I/O 和本地执行异常计失败；4xx 是可达的业务拒绝，不应熔断整个依赖。
 */
public final class OutboundHttpResilienceInterceptor implements ClientHttpRequestInterceptor {

    private final InternalSecurityProperties.HttpResilience properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public OutboundHttpResilienceInterceptor(InternalSecurityProperties.HttpResilience properties) {
        this(properties, Clock.systemUTC());
    }

    OutboundHttpResilienceInterceptor(InternalSecurityProperties.HttpResilience properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request,
                                        byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        long now = clock.millis();
        long deadline = deadline(now);
        if (deadline <= now) {
            throw rejected("deadline_exceeded");
        }
        request.getHeaders().set(properties.getDeadlineHeader(), Long.toString(deadline));

        State state = states.computeIfAbsent(origin(request.getURI()), ignored -> new State(maxConcurrent()));
        boolean probe = state.enter(now);
        boolean failed = false;
        try {
            ClientHttpResponse response = execution.execute(request, body);
            failed = response.getStatusCode().is5xxServerError();
            return response;
        } catch (IOException | RuntimeException error) {
            failed = true;
            throw error;
        } finally {
            state.leave(failed, probe, clock.millis(), threshold(), recoveryMillis());
        }
    }

    private long deadline(long now) {
        Long inherited = RequestDeadlineContext.captureRaw();
        long local = safeAdd(now, Math.max(1L, properties.getDefaultDeadline().toMillis()));
        return inherited == null ? local : Math.min(inherited, local);
    }

    private int maxConcurrent() {
        return Math.max(1, properties.getMaxConcurrentPerOrigin());
    }

    private int threshold() {
        return Math.max(1, properties.getCircuitFailureThreshold());
    }

    private long recoveryMillis() {
        return Math.max(1L, properties.getCircuitRecovery().toMillis());
    }

    private static String origin(URI uri) {
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        return String.format(Locale.ROOT, "%s://%s:%d",
                uri.getScheme(), uri.getHost(), port).toLowerCase(Locale.ROOT);
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static OutboundDependencyRejectedException rejected(String reason) {
        return new OutboundDependencyRejectedException("outbound dependency rejected: " + reason);
    }

    public static final class OutboundDependencyRejectedException extends ResourceAccessException {
        public OutboundDependencyRejectedException(String message) {
            super(message);
        }
    }

    private final class State {
        private final Semaphore permits;
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicLong openUntil = new AtomicLong();
        private final AtomicBoolean probeActive = new AtomicBoolean();

        private State(int maxConcurrent) {
            this.permits = new Semaphore(maxConcurrent);
        }

        private boolean enter(long now) {
            long open = openUntil.get();
            if (open > now) {
                throw rejected("circuit_open");
            }
            boolean probe = open > 0;
            if (probe && !probeActive.compareAndSet(false, true)) {
                throw rejected("circuit_open");
            }
            if (!permits.tryAcquire()) {
                if (probe) probeActive.set(false);
                throw rejected("bulkhead_full");
            }
            return probe;
        }

        private void leave(boolean failed, boolean probe, long now, int threshold, long recoveryMillis) {
            permits.release();
            if (probe) probeActive.set(false);
            if (!failed) {
                failures.set(0);
                openUntil.set(0);
                return;
            }
            if (failures.incrementAndGet() >= threshold) {
                openUntil.set(safeAdd(now, recoveryMillis));
            }
        }
    }
}
