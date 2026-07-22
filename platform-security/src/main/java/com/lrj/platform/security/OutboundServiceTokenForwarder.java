package com.lrj.platform.security;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * 内部服务回调 edge 的身份转发器。令牌与普通内部 JWT 使用相同短 TTL/签名，但放在独立 header，
 * 由 edge 显式验签后再注入下游 header；外部传入的 {@code X-Internal-Token} 仍会被 edge 剥离。
 */
public class OutboundServiceTokenForwarder implements ClientHttpRequestInterceptor {

    private final InternalToken tokens;
    private final String header;
    private final List<Origin> allowedOrigins;

    public OutboundServiceTokenForwarder(InternalToken tokens, String header, List<String> allowedOrigins) {
        this.tokens = tokens;
        this.header = header;
        this.allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .map(Origin::parse)
                .toList();
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        TenantContext.Tenant tenant = TenantContext.captureRaw();
        if (tenant != null && !TenantContext.ANONYMOUS.equals(tenant) && isAllowed(request.getURI())) {
            request.getHeaders().set(header, tokens.mintService(tenant));
        }
        return execution.execute(request, body);
    }

    private boolean isAllowed(URI target) {
        Origin targetOrigin = Origin.parse(target);
        return allowedOrigins.contains(targetOrigin);
    }

    private record Origin(String scheme, String host, int port) {
        static Origin parse(String raw) {
            try {
                return parse(URI.create(raw));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("invalid service-token allowed origin: " + raw, e);
            }
        }

        static Origin parse(URI uri) {
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                throw new IllegalArgumentException("service-token target must be an absolute HTTP(S) URI: " + uri);
            }
            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
                throw new IllegalArgumentException("service-token target must use HTTP(S): " + uri);
            }
            int port = uri.getPort();
            if (port < 0) port = "https".equals(normalizedScheme) ? 443 : 80;
            return new Origin(normalizedScheme, host.toLowerCase(Locale.ROOT), port);
        }
    }
}
