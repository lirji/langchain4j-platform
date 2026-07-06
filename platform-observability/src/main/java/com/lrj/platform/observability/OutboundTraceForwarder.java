package com.lrj.platform.observability;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * 出站拦截器：把当前 MDC 里的 traceId 透传到下游服务的 {@code X-Trace-Id} 头，
 * 使一条跨服务调用链共享同一 traceId。挂到服务间 {@code RestClient}/{@code RestTemplate}。
 */
public class OutboundTraceForwarder implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId != null && !traceId.isBlank()) {
            request.getHeaders().set(TraceIdFilter.HEADER, traceId);
        }
        return execution.execute(request, body);
    }
}
