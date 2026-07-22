package com.lrj.platform.edge;

import com.lrj.platform.security.InternalSecurityProperties;
import com.lrj.platform.security.InternalToken;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class InternalServiceTokenExchangeFilterTest {

    private final InternalSecurityProperties props = new InternalSecurityProperties();
    private final InternalToken tokens = new InternalToken(props.getJwtSecret(), Duration.ofMinutes(5));
    private final InternalServiceTokenExchangeFilter filter = new InternalServiceTokenExchangeFilter(props, tokens);

    @Test
    void exchangesValidServiceTokenAndMarksRequestVerified() {
        String jwt = tokens.mintService(new TenantContext.Tenant("acme", "alice", Set.of("eval")));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/rag/query")
                .header(props.getServiceTokenHeader(), jwt));
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        filter.filter(exchange, ex -> { captured.set(ex); return Mono.empty(); }).block();

        assertThat(captured.get().getRequest().getHeaders().getFirst(props.getServiceTokenHeader())).isNull();
        assertThat(captured.get().getRequest().getHeaders().getFirst(props.getInternalHeader())).isEqualTo(jwt);
        assertThat((Boolean) captured.get().getAttribute(InternalServiceTokenExchangeFilter.VERIFIED_ATTRIBUTE))
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    void rejectsInvalidServiceTokenBeforeCasdoorFallback() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/rag/query")
                .header(props.getServiceTokenHeader(), "forged"));

        filter.filter(exchange, ex -> Mono.error(new AssertionError("chain must not run"))).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsOrdinaryInternalTokenInServiceHeader() {
        String jwt = tokens.mint(new TenantContext.Tenant("acme", "alice", Set.of("eval")));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/rag/query")
                .header(props.getServiceTokenHeader(), jwt));

        filter.filter(exchange, ex -> Mono.error(new AssertionError("chain must not run"))).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
