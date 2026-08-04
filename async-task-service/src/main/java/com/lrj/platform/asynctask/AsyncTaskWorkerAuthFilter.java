package com.lrj.platform.asynctask;

import com.lrj.platform.security.AsyncTaskWorkerToken;
import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalTokenAuthFilter;
import com.lrj.platform.security.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Authenticates the lease/status/event worker data plane before ordinary tenant JWT auth. */
final class AsyncTaskWorkerAuthFilter extends OncePerRequestFilter {

    static final String PRINCIPAL_ATTRIBUTE =
            "com.lrj.platform.asynctask.AsyncTaskWorkerAuthFilter.principal";

    private static final String PREFIX = "/async/tasks/";

    private final AsyncTaskWorkerToken tokens;
    private final String header;

    AsyncTaskWorkerAuthFilter(AsyncTaskWorkerToken tokens, InternalSecurityProperties security) {
        this.tokens = tokens;
        this.header = security.getAsyncWorker().getHeader();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return target(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Target target = target(request);
        if (target == null) {
            chain.doFilter(request, response);
            return;
        }
        AsyncTaskWorkerToken.Principal principal = tokens.verify(
                request.getHeader(header), target.action(), target.taskId(), null);
        if (principal == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"valid async worker authentication is required\"}");
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        request.setAttribute(
                InternalTokenAuthFilter.PREAUTHENTICATED_TENANT_ATTRIBUTE,
                new TenantContext.Tenant(
                        principal.tenantId(),
                        principal.actorUserId(),
                        Set.of("async.task.worker")));
        chain.doFilter(request, response);
    }

    private static Target target(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(PREFIX)) return null;
        String action;
        String suffix;
        if ("POST".equals(request.getMethod()) && path.endsWith("/lease")) {
            action = "lease";
            suffix = "/lease";
        } else if ("PATCH".equals(request.getMethod()) && path.endsWith("/status")) {
            action = "status";
            suffix = "/status";
        } else if ("POST".equals(request.getMethod()) && path.endsWith("/events")) {
            action = "event";
            suffix = "/events";
        } else {
            return null;
        }
        String encodedTaskId = path.substring(PREFIX.length(), path.length() - suffix.length());
        if (encodedTaskId.isBlank() || encodedTaskId.contains("/")) return null;
        return new Target(action, UriUtils.decode(encodedTaskId, StandardCharsets.UTF_8));
    }

    private record Target(String action, String taskId) {
    }
}
