package com.lrj.platform.edge;

import com.lrj.platform.security.InternalSecurityProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 边缘第一道全局过滤器：无条件剥离客户端带进来的内部 JWT 头（{@code X-Internal-Token}）。
 *
 * <p>内部头只能由本链的认证 filter（服务令牌 -130 / Casdoor -120 / 会话 -110 / api-key -100）换发写入。
 * 剥离此前只在 {@link CasdoorTokenExchangeFilter} 里做，而该 filter 受 {@code edge.casdoor.enabled} 控制——
 * 按文档回滚关掉 Casdoor 后，客户端自带的内部头会原样穿过 edge。本 filter 不依赖任何开关，order
 * <strong>-140</strong> 早于所有认证 filter。
 */
@Component
public class InboundInternalHeaderStripFilter implements GlobalFilter, Ordered {

    private final InternalSecurityProperties props;

    public InboundInternalHeaderStripFilter(InternalSecurityProperties props) {
        this.props = props;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String header = props.getInternalHeader();
        if (!exchange.getRequest().getHeaders().containsKey(header)) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.remove(header))
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return -140; // 早于 InternalServiceTokenExchangeFilter(-130)
    }
}
