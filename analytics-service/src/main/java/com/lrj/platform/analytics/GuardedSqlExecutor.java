package com.lrj.platform.analytics;

import com.lrj.platform.protocol.analytics.AnalyticsSqlPlanReply;
import com.lrj.platform.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Java 拥有的 SQL 副作用边界。外部 planner 只能提交候选 SQL；allowlist、tenant predicate、
 * LIMIT、只读连接和 statement timeout 均保留在 analytics-service。
 */
public class GuardedSqlExecutor {

    private static final Logger log = LoggerFactory.getLogger(GuardedSqlExecutor.class);

    private final JdbcTemplate readOnlyJdbc;
    private final SqlGuard guard;

    public GuardedSqlExecutor(JdbcTemplate readOnlyJdbc, SqlGuard guard) {
        this.readOnlyJdbc = Objects.requireNonNull(readOnlyJdbc);
        this.guard = Objects.requireNonNull(guard);
    }

    public AnalyticsSqlPlanReply execute(String question, String candidateSql) {
        String tenantId = TenantContext.current().tenantId();
        String boundSql = bindTrustedTenant(candidateSql, tenantId);
        SqlGuard.GuardResult checked = guard.check(boundSql, tenantId);
        if (!checked.allowed()) {
            log.warn("external analytics plan rejected tenant={} reason={}",
                    tenantId, checked.reason());
            return new AnalyticsSqlPlanReply(
                    question, null, 0, List.of(), false, checked.reason());
        }
        try {
            List<Map<String, Object>> rows = readOnlyJdbc.queryForList(checked.sql());
            return new AnalyticsSqlPlanReply(
                    question, checked.sql(), rows.size(), rows, true, null);
        } catch (RuntimeException ex) {
            log.warn("external analytics plan execution failed tenant={} type={}",
                    tenantId, ex.getClass().getSimpleName());
            return new AnalyticsSqlPlanReply(
                    question, checked.sql(), 0, List.of(), false,
                    "query execution failed");
        }
    }

    private static String bindTrustedTenant(String candidateSql, String tenantId) {
        if (candidateSql == null || tenantId == null) {
            return candidateSql;
        }
        String escaped = tenantId.replace("'", "''");
        return candidateSql.replace(":tenantId", "'" + escaped + "'");
    }
}
