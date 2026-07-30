package com.lrj.platform.analytics;

import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuardedSqlExecutorTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final GuardedSqlExecutor executor = new GuardedSqlExecutor(
            jdbc,
            new SqlGuard(List.of("orders"), List.of("orders"), 100, true));

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void rejectsPlannerSqlWithoutTrustedTenantPredicateBeforeJdbc() {
        TenantContext.set(new TenantContext.Tenant(
                "acme", "alice", Set.of("analytics")));

        var result = executor.execute(
                "订单数", "SELECT count(*) FROM orders");

        assertThat(result.executed()).isFalse();
        assertThat(result.rejectionReason()).contains("tenant filter");
        verify(jdbc, never()).queryForList(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void executesGuardedSqlWithReadOnlyJdbcAndForcedLimit() {
        TenantContext.set(new TenantContext.Tenant(
                "acme", "alice", Set.of("analytics")));
        String guarded = "SELECT id FROM orders WHERE tenant_id='acme' LIMIT 100";
        when(jdbc.queryForList(guarded)).thenReturn(List.of(Map.of("id", 7)));

        var result = executor.execute(
                "订单", "SELECT id FROM orders WHERE tenant_id=:tenantId");

        assertThat(result.executed()).isTrue();
        assertThat(result.sql()).isEqualTo(guarded);
        assertThat(result.rows()).containsExactly(Map.of("id", 7));
        verify(jdbc).queryForList(guarded);
    }
}
