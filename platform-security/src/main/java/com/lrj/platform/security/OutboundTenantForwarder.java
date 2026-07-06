package com.lrj.platform.security;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * 出站拦截器：service→service 的 REST 调用自动带上当前租户的内部 JWT。
 *
 * <p>把它挂到用于服务间调用的 {@code RestClient}/{@code RestTemplate} 上，即可让 ThreadLocal 的
 * {@link TenantContext} 跨网络跳到下游（下游 {@link InternalTokenAuthFilter} 重建）。anonymous 不带头。
 */
public class OutboundTenantForwarder implements ClientHttpRequestInterceptor {

    private final InternalToken tokens;
    private final String header;

    public OutboundTenantForwarder(InternalToken tokens, String header) {
        this.tokens = tokens;
        this.header = header;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        TenantContext.Tenant t = TenantContext.captureRaw();
        if (t != null && !TenantContext.ANONYMOUS.equals(t)) {
            request.getHeaders().set(header, tokens.mint(t));
        }
        return execution.execute(request, body);
    }
}
