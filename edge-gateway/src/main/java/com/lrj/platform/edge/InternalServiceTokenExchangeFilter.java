package com.lrj.platform.edge;

import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 栈内服务回调 edge 的显式信任接缝。只接受短时签名的 service-token header，验签成功后换成普通
 * {@code X-Internal-Token}；Casdoor-only 仅跳过这条已验签的内部调用，不重新开放 legacy API key。
 */
@Component
public class InternalServiceTokenExchangeFilter implements GlobalFilter, Ordered {

    static final String VERIFIED_ATTRIBUTE = InternalServiceTokenExchangeFilter.class.getName() + ".verified";

    private final InternalSecurityProperties props;
    private final InternalToken tokens;

    public InternalServiceTokenExchangeFilter(InternalSecurityProperties props, InternalToken tokens) {
        this.props = props;
        this.tokens = tokens;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String serviceToken = exchange.getRequest().getHeaders().getFirst(props.getServiceTokenHeader());
        if (serviceToken == null || serviceToken.isBlank()) {
            return chain.filter(exchange);
        }
        TenantContext.Tenant tenant = tokens.verifyService(serviceToken);
        if (tenant == null || TenantContext.ANONYMOUS.equals(tenant)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(props.getServiceTokenHeader());
                    headers.remove(HttpHeaders.AUTHORIZATION);
                    headers.remove(props.getApiKeyHeader());
                    headers.set(props.getInternalHeader(), serviceToken);
                })
                .build();
        ServerWebExchange verified = exchange.mutate().request(request).build();
        verified.getAttributes().put(VERIFIED_ATTRIBUTE, Boolean.TRUE);
        return chain.filter(verified);
    }

    @Override
    public int getOrder() {
        return -130;
    }
}
