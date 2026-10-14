package com.lrj.platform.metering;

import com.lrj.platform.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenBudgetGuardFilterTest {

    private final TokenBudgetProperties props = budget(100);
    private final InMemoryTokenBudgetTracker tracker = new InMemoryTokenBudgetTracker(props);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final TokenBudgetGuardFilter filter =
            new TokenBudgetGuardFilter(tracker, props.getGuard().getPaths(), meters);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void exhaustedTenantIsRejectedWith429BeforeReachingTheModel() throws Exception {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        tracker.consume("acme", 100);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(post("/chat"), response, chain);

        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(response.getHeader("Retry-After"))).isPositive();
        assertThat(response.getContentAsString()).contains("token_budget_exhausted");
        assertThat(meters.counter(TokenBudgetGuardFilter.METRIC, "outcome", "rejected").count()).isEqualTo(1.0);
    }

    @Test
    void tenantUnderBudgetPassesThrough() throws Exception {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        tracker.consume("acme", 99);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(post("/agent/run"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void budgetIsPerTenant() throws Exception {
        tracker.consume("acme", 100);
        TenantContext.set(new TenantContext.Tenant("globex", "bob", Set.of("chat")));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(post("/chat/stream"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("另一租户用满不影响本租户").isNotNull();
    }

    @Test
    void readsAndNonGenerationPathsAreNotGuarded() throws Exception {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        tracker.consume("acme", 1_000);

        for (MockHttpServletRequest request : new MockHttpServletRequest[] {
                new MockHttpServletRequest("GET", "/agent/tasks/t-1"),       // 任务状态查询
                new MockHttpServletRequest("DELETE", "/agent/tasks/t-1"),    // 取消任务必须仍可用
                post("/rag/query"),                                          // 检索
                post("/actuator/health")}) {
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).as(request.getMethod() + " " + request.getRequestURI()).isNotNull();
        }
    }

    @Test
    void everyDefaultGenerationEntryIsGuarded() throws Exception {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        tracker.consume("acme", 100);

        for (String path : new String[] {"/chat", "/chat/auto", "/chat/vision", "/extract", "/agent/dag/run/async",
                "/vision/caption", "/conversation/workflow/reply"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(post(path), response, new MockFilterChain());
            assertThat(response.getStatus()).as(path).isEqualTo(429);
        }
    }

    @Test
    void trackerFailureFailsOpenAndIsCounted() throws Exception {
        TokenBudgetTracker broken = mock(TokenBudgetTracker.class);
        when(broken.wouldExceed(anyString())).thenThrow(new IllegalStateException("redis down"));
        TokenBudgetGuardFilter guard = new TokenBudgetGuardFilter(broken, props.getGuard().getPaths(), meters);
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
        MockFilterChain chain = new MockFilterChain();

        guard.doFilter(post("/chat"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(meters.counter(TokenBudgetGuardFilter.METRIC, "outcome", "check_failed").count()).isEqualTo(1.0);
    }

    @Test
    void anonymousTenantGetsTheReducedBudget() throws Exception {
        tracker.consume(TenantContext.ANONYMOUS.tenantId(), 5); // 100 * 0.05
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(post("/chat"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
    }

    private static MockHttpServletRequest post(String path) {
        return new MockHttpServletRequest("POST", path);
    }

    private static TokenBudgetProperties budget(long daily) {
        TokenBudgetProperties p = new TokenBudgetProperties();
        p.getDailyTokens().setDefault(daily);
        p.getDailyTokens().setOverrides(Map.of());
        p.setTimezone("Asia/Shanghai");
        return p;
    }
}
