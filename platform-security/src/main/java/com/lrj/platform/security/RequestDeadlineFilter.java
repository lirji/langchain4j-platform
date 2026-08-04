package com.lrj.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

/** 解析并约束语言中立的绝对 deadline；过期/非法值在进入业务代码前稳定拒绝。 */
public final class RequestDeadlineFilter extends OncePerRequestFilter {

    private static final byte[] INVALID =
            "{\"error\":\"invalid request deadline\",\"code\":\"INVALID_REQUEST_DEADLINE\"}"
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] EXPIRED =
            "{\"error\":\"request deadline exceeded\",\"code\":\"REQUEST_DEADLINE_EXCEEDED\"}"
                    .getBytes(StandardCharsets.UTF_8);

    private final InternalSecurityProperties.HttpResilience properties;
    private final Clock clock;

    public RequestDeadlineFilter(InternalSecurityProperties.HttpResilience properties) {
        this(properties, Clock.systemUTC());
    }

    RequestDeadlineFilter(InternalSecurityProperties.HttpResilience properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long now = clock.millis();
        long defaultDeadline = safeAdd(now, positiveMillis(properties.getDefaultDeadline().toMillis()));
        String raw = request.getHeader(properties.getDeadlineHeader());
        long deadline = defaultDeadline;
        if (raw != null && !raw.isBlank()) {
            try {
                deadline = Long.parseLong(raw.trim());
            } catch (NumberFormatException invalid) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, INVALID);
                return;
            }
            if (deadline <= now) {
                writeError(response, HttpServletResponse.SC_GATEWAY_TIMEOUT, EXPIRED);
                return;
            }
            long maximum = safeAdd(now, positiveMillis(properties.getMaxInboundDeadline().toMillis()));
            deadline = Math.min(deadline, maximum);
        }
        RequestDeadlineContext.set(deadline);
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestDeadlineContext.clear();
        }
    }

    private static long positiveMillis(long value) {
        return Math.max(1L, value);
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static void writeError(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(body);
    }
}
