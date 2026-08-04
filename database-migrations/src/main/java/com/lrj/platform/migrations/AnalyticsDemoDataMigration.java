package com.lrj.platform.migrations;

import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/** Idempotent local-demo data. This migration is disabled in the production Helm values. */
final class AnalyticsDemoDataMigration implements JavaMigration {

    @Override
    public MigrationVersion getVersion() {
        return MigrationVersion.fromVersion("2");
    }

    @Override
    public String getDescription() {
        return "seed analytics demo data";
    }

    @Override
    public Integer getChecksum() {
        return "analytics-demo-data-v2:2026-08-03".hashCode();
    }

    @Override
    public boolean canExecuteInTransaction() {
        return true;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        insertCustomers(connection);
        insertOrders(connection);
        insertRefunds(connection);
    }

    private static void insertCustomers(Connection connection) throws SQLException {
        List<Object[]> rows = List.of(
                row(1, "tenantA", "张三", "华东", "2026-01-10"),
                row(2, "tenantA", "李四", "华北", "2026-02-15"),
                row(3, "tenantA", "王五", "华南", "2026-03-20"),
                row(4, "tenantA", "赵六", "华东", "2026-04-01"),
                row(5, "tenantA", "钱七", "西南", "2026-04-18"),
                row(1001, "tenantB", "ACME-A", "华北", "2026-03-01"),
                row(1002, "tenantB", "ACME-B", "华东", "2026-03-05"));
        insertIgnoringDuplicate(connection,
                "INSERT INTO customers (id, tenant_id, name, region, created_at) VALUES (?, ?, ?, ?, ?)", rows);
    }

    private static void insertOrders(Connection connection) throws SQLException {
        List<Object[]> rows = List.of(
                row(101, "tenantA", 1, "1200.00", "已支付", "2026-05-03"),
                row(102, "tenantA", 1, "800.00", "已退款", "2026-05-06"),
                row(103, "tenantA", 2, "2500.00", "已发货", "2026-05-09"),
                row(104, "tenantA", 3, "450.00", "已退款", "2026-05-12"),
                row(105, "tenantA", 3, "3200.00", "已支付", "2026-05-20"),
                row(106, "tenantA", 4, "150.00", "已取消", "2026-05-22"),
                row(107, "tenantA", 4, "5400.00", "已退款", "2026-05-25"),
                row(108, "tenantA", 5, "990.00", "已支付", "2026-04-28"),
                row(109, "tenantA", 2, "1750.00", "已退款", "2026-04-15"),
                row(2001, "tenantB", 1001, "9999.00", "已退款", "2026-05-15"),
                row(2002, "tenantB", 1002, "8888.00", "已支付", "2026-05-18"));
        insertIgnoringDuplicate(connection,
                "INSERT INTO orders (id, tenant_id, customer_id, amount, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", rows);
    }

    private static void insertRefunds(Connection connection) throws SQLException {
        List<Object[]> rows = List.of(
                row(201, "tenantA", 102, 1, "800.00", "商品损坏", "approved", "2026-05-07"),
                row(202, "tenantA", 104, 3, "450.00", "尺码不符", "approved", "2026-05-13"),
                row(203, "tenantA", 107, 4, "5400.00", "七天无理由", "approved", "2026-05-26"),
                row(204, "tenantA", 109, 2, "1750.00", "质量问题", "approved", "2026-04-16"),
                row(205, "tenantA", 105, 3, "300.00", "部分退款", "pending", "2026-05-21"),
                row(206, "tenantA", 103, 2, "120.00", "运费补偿", "rejected", "2026-05-10"),
                row(3001, "tenantB", 2001, 1001, "9999.00", "大额退款", "approved", "2026-05-16"));
        insertIgnoringDuplicate(connection,
                "INSERT INTO refunds (id, tenant_id, order_id, customer_id, amount, reason, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", rows);
    }

    private static Object[] row(Object... values) {
        return values;
    }

    private static void insertIgnoringDuplicate(Connection connection,
                                                String sql,
                                                List<Object[]> rows) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Object[] row : rows) {
                for (int i = 0; i < row.length; i++) {
                    Object value = row[i];
                    if (value instanceof String text && text.matches("\\d+\\.\\d{2}")) {
                        statement.setBigDecimal(i + 1, new BigDecimal(text));
                    } else if (value instanceof String text && text.matches("\\d{4}-\\d{2}-\\d{2}")) {
                        statement.setDate(i + 1, Date.valueOf(text));
                    } else {
                        statement.setObject(i + 1, value);
                    }
                }
                try {
                    statement.executeUpdate();
                } catch (SQLException duplicate) {
                    String state = duplicate.getSQLState();
                    if (state == null || !state.startsWith("23")) {
                        throw duplicate;
                    }
                }
            }
        }
    }
}
