package com.lrj.platform.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.FilterChain;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InternalTokenAuthFilterTest {

    private static final String SECRET = "test-internal-secret-at-least-32-bytes";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void rejectsAnonymousBusinessRequestWhenAuthenticationIsRequired() throws Exception {
        InternalSecurityProperties props = properties();
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(token(), props, false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("POST", "/chat"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("internal authentication");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void acceptsValidInternalTokenAndBindsTenantForTheRequest() throws Exception {
        InternalSecurityProperties props = properties();
        InternalToken token = token();
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(token, props, false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/chat");
        request.addHeader(props.getInternalHeader(),
                token.mint(new TenantContext.Tenant("acme", "alice", Set.of("chat"))));
        MockHttpServletResponse response = new MockHttpServletResponse();
        final TenantContext.Tenant[] seen = new TenantContext.Tenant[1];
        FilterChain chain = (req, res) -> seen[0] = TenantContext.current();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen[0].tenantId()).isEqualTo("acme");
        assertThat(TenantContext.current()).isEqualTo(TenantContext.ANONYMOUS);
    }

    @Test
    void healthProbeRemainsOpenWithoutCredentials() throws Exception {
        InternalTokenAuthFilter filter = new InternalTokenAuthFilter(token(), properties(), false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health/readiness"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static InternalSecurityProperties properties() {
        InternalSecurityProperties props = new InternalSecurityProperties();
        props.setJwtSecret(SECRET);
        props.setAuthenticationRequired(true);
        return props;
    }

    private static InternalToken token() {
        return new InternalToken(SECRET, Duration.ofMinutes(5));
    }
}
