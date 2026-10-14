package com.lrj.platform.edge;

import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入站内部头剥离：单 filter 语义 + Casdoor 关闭（{@code EDGE_CASDOOR_ENABLED=false} 回滚路径）时的整条认证链。
 */
class InboundInternalHeaderStripFilterTest {

    private final InternalSecurityProperties props = new InternalSecurityProperties();
    private final InternalToken tokens = new InternalToken(props.getJwtSecret(), Duration.ofMinutes(5));
    private final InboundInternalHeaderStripFilter filter = new InboundInternalHeaderStripFilter(props);

    @Test
    void stripsClientSuppliedInternalHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/chat")
                .header(props.getInternalHeader(), "forged")
                .header(props.getApiKeyHeader(), "k"));
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        filter.filter(exchange, ex -> { captured.set(ex); return Mono.empty(); }).block();

        assertThat(captured.get().getRequest().getHeaders().containsKey(props.getInternalHeader())).isFalse();
        assertThat(captured.get().getRequest().getHeaders().getFirst(props.getApiKeyHeader())).isEqualTo("k");
    }

    @Test
    void runsBeforeEveryCredentialFilter() {
        assertThat(filter.getOrder())
                .isLessThan(new InternalServiceTokenExchangeFilter(props, tokens).getOrder())
                .isLessThan(new SessionBearerAuthFilter(props, tokens).getOrder())
                .isLessThan(new ApiKeyToInternalTokenFilter(props, tokens).getOrder())
                .isLessThan(-120); // CasdoorTokenExchangeFilter
    }

    @Test
    void casdoorDisabledChainRejectsForgedInternalToken() {
        // 用与下游同一把密钥签出的「合法」内部 JWT：HS256 + 公开 dev secret 时攻击者正是能造出它。
        String forged = tokens.mint(new TenantContext.Tenant("victim", "mallory", Set.of("chat", "ingest")));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/rag/query")
                .header(props.getInternalHeader(), forged));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        casdoorDisabledChain(forwarded).filter(exchange).block();

        assertThat(forwarded.get()).as("伪造头不得转发到下游").isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void casdoorDisabledChainStillAcceptsSessionBearer() {
        InternalToken session = new InternalToken(props.getSession().getJwtSecret(), Duration.ofMinutes(5));
        String bearer = session.mint(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/chat")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .header(props.getInternalHeader(), "forged"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        casdoorDisabledChain(forwarded).filter(exchange).block();

        List<String> internal = forwarded.get().getRequest().getHeaders().get(props.getInternalHeader());
        assertThat(internal).as("只剩 edge 换发的那一个值").hasSize(1);
        assertThat(tokens.verify(internal.get(0)).tenantId()).isEqualTo("acme");
    }

    @Test
    void casdoorDisabledChainStillAcceptsServiceToken() {
        String serviceJwt = tokens.mintService(new TenantContext.Tenant("acme", "worker", Set.of("eval")));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/rag/query")
                .header(props.getServiceTokenHeader(), serviceJwt));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        casdoorDisabledChain(forwarded).filter(exchange).block();

        assertThat(forwarded.get().getRequest().getHeaders().getFirst(props.getInternalHeader())).isEqualTo(serviceJwt);
    }

    /** 按 order 串起 Casdoor 关闭时装配的全部认证 filter，末端记录被转发的请求。 */
    private GatewayFilterChain casdoorDisabledChain(AtomicReference<ServerWebExchange> forwarded) {
        List<GlobalFilter> filters = new java.util.ArrayList<>(List.of(
                new ApiKeyToInternalTokenFilter(props, tokens),
                new SessionBearerAuthFilter(props, tokens),
                new InternalServiceTokenExchangeFilter(props, tokens),
                filter));
        filters.sort(Comparator.comparingInt(f -> ((Ordered) f).getOrder()));
        return chainFrom(filters, 0, forwarded);
    }

    private static GatewayFilterChain chainFrom(List<GlobalFilter> filters, int index,
                                                AtomicReference<ServerWebExchange> forwarded) {
        if (index == filters.size()) {
            return ex -> { forwarded.set(ex); return Mono.empty(); };
        }
        return ex -> filters.get(index).filter(ex, chainFrom(filters, index + 1, forwarded));
    }
}
