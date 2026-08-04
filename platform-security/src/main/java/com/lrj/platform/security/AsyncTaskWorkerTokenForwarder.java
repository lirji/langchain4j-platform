package com.lrj.platform.security;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Adds request-bound worker auth only to async-task lease/status/event calls. */
public final class AsyncTaskWorkerTokenForwarder implements ClientHttpRequestInterceptor {

    private static final String PREFIX = "/async/tasks/";

    private final AsyncTaskWorkerToken tokens;
    private final String workerHeader;
    private final String internalHeader;

    public AsyncTaskWorkerTokenForwarder(AsyncTaskWorkerToken tokens,
                                         String workerHeader,
                                         String internalHeader) {
        this.tokens = tokens;
        this.workerHeader = workerHeader;
        this.internalHeader = internalHeader;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request,
                                        byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        Target target = target(request);
        TenantContext.Tenant tenant = TenantContext.captureRaw();
        if (target != null && tenant != null && !TenantContext.ANONYMOUS.equals(tenant)) {
            request.getHeaders().set(
                    workerHeader,
                    tokens.mint(tenant, tokens.serviceId(), target.action(), target.taskId()));
            request.getHeaders().remove(internalHeader);
        }
        return execution.execute(request, body);
    }

    private static Target target(HttpRequest request) {
        String path = request.getURI().getPath();
        if (path == null || !path.startsWith(PREFIX)) return null;
        String method = request.getMethod().name();
        String action;
        String suffix;
        if ("POST".equals(method) && path.endsWith("/lease")) {
            action = "lease";
            suffix = "/lease";
        } else if ("PATCH".equals(method) && path.endsWith("/status")) {
            action = "status";
            suffix = "/status";
        } else if ("POST".equals(method) && path.endsWith("/events")) {
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
