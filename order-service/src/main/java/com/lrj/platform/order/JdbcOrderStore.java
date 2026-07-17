package com.lrj.platform.order;

import com.lrj.platform.protocol.order.OrderView;
import com.lrj.platform.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 订单只读存储。用裸 {@link JdbcTemplate} 直连持久化 MySQL —— 与平台其它持久化一致，
 * 表结构靠 {@code CREATE TABLE IF NOT EXISTS} / {@code ALTER TABLE ADD COLUMN} 字面量在 {@link #init()} 内演进
 * （无 Flyway/JPA），照抄 {@code JdbcAsyncTaskStore} / {@code JdbcGraphStore}。
 *
 * <p><strong>租户隔离</strong>：{@link #findByOrderNo(String)} 用参数化 {@code WHERE id = ? AND tenant_id = ?}，
 * tenant 取自过滤器链还原的 {@link TenantContext}。绑定参数的 PreparedStatement 天然防注入 ——
 * 不需要 analytics 那套给「LLM 生成的 SQL」兜底的 SqlGuard。别的租户就算知道订单号也查不到（0 行）。
 *
 * <p><strong>幂等种子</strong>：{@code app.order.seed-demo=true}（默认）且表为空时插一批演示订单（tenantA/tenantB，
 * 与 analytics-service 的 nl2sql-demo 数据对齐，便于跨租户隔离演示）。用「表空才插」而非 {@code DROP TABLE}，
 * 保证持久化数据跨重启保留。
 */
@Component
public class JdbcOrderStore implements OrderStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcOrderStore.class);

    private final JdbcTemplate jdbc;
    private final boolean seedDemo;

    public JdbcOrderStore(JdbcTemplate jdbcTemplate,
                          @Value("${app.order.seed-demo:true}") boolean seedDemo) {
        this.jdbc = jdbcTemplate;
        this.seedDemo = seedDemo;
        init();
    }

    private void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS orders (
                  id          VARCHAR(64)   NOT NULL PRIMARY KEY,
                  tenant_id   VARCHAR(64)   NOT NULL,
                  customer_id VARCHAR(64),
                  amount      DECIMAL(12,2) NOT NULL,
                  status      VARCHAR(16)   NOT NULL,
                  created_at  DATE          NOT NULL,
                  INDEX IDX_ORDERS_TENANT (tenant_id)
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS customers (
                  id          VARCHAR(64)  NOT NULL PRIMARY KEY,
                  tenant_id   VARCHAR(64)  NOT NULL,
                  name        VARCHAR(128) NOT NULL,
                  INDEX IDX_CUSTOMERS_TENANT (tenant_id)
                )""");
        log.info("orders/customers tables ready");
        if (seedDemo) {
            seedDemoDataIfEmpty();
        }
    }

    /** 表空才插演示数据（幂等，不 DROP）；已有数据则跳过，保证持久化不被重启覆盖。 */
    private void seedDemoDataIfEmpty() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        // tenantA 客户 + 订单（对齐 analytics-service db/nl2sql-demo.sql，便于两服务演示同一批数据）
        jdbc.batchUpdate("INSERT INTO customers (id, tenant_id, name) VALUES (?, ?, ?)",
                List.of(
                        new Object[]{"1", "tenantA", "张三"},
                        new Object[]{"2", "tenantA", "李四"},
                        new Object[]{"3", "tenantA", "王五"},
                        new Object[]{"4", "tenantA", "赵六"},
                        new Object[]{"5", "tenantA", "钱七"},
                        new Object[]{"1001", "tenantB", "ACME-A"},
                        new Object[]{"1002", "tenantB", "ACME-B"}));
        jdbc.batchUpdate("INSERT INTO orders (id, tenant_id, customer_id, amount, status, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                List.of(
                        new Object[]{"101", "tenantA", "1", new BigDecimal("1200.00"), "已支付", "2026-05-03"},
                        new Object[]{"102", "tenantA", "1", new BigDecimal("800.00"), "已退款", "2026-05-06"},
                        new Object[]{"103", "tenantA", "2", new BigDecimal("2500.00"), "已发货", "2026-05-09"},
                        new Object[]{"104", "tenantA", "3", new BigDecimal("450.00"), "已退款", "2026-05-12"},
                        new Object[]{"105", "tenantA", "3", new BigDecimal("3200.00"), "已支付", "2026-05-20"},
                        new Object[]{"106", "tenantA", "4", new BigDecimal("150.00"), "已取消", "2026-05-22"},
                        new Object[]{"107", "tenantA", "4", new BigDecimal("5400.00"), "已退款", "2026-05-25"},
                        new Object[]{"108", "tenantA", "5", new BigDecimal("990.00"), "已支付", "2026-04-28"},
                        new Object[]{"109", "tenantA", "2", new BigDecimal("1750.00"), "已退款", "2026-04-15"},
                        // tenantB：演示隔离——tenantA 的查询不该看到这些
                        new Object[]{"2001", "tenantB", "1001", new BigDecimal("9999.00"), "已退款", "2026-05-15"},
                        new Object[]{"2002", "tenantB", "1002", new BigDecimal("8888.00"), "已支付", "2026-05-18"}));
        log.info("orders demo data seeded (tenantA 101-109, tenantB 2001-2002)");
    }

    /**
     * 按订单号查当前租户的订单详情。tenant 取自 {@link TenantContext}，作为 SQL 谓词强制隔离。
     * 查不到（不存在或属于别的租户）返回 {@link Optional#empty()}。
     */
    @Override
    public Optional<OrderView> findByOrderNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return Optional.empty();
        }
        String tenantId = TenantContext.current().tenantId();
        List<OrderView> rows = jdbc.query(
                """
                SELECT o.id, c.name AS customer, o.amount, o.status, o.created_at
                FROM orders o
                LEFT JOIN customers c ON o.customer_id = c.id AND c.tenant_id = ?
                WHERE o.id = ? AND o.tenant_id = ?
                """,
                ORDER_MAPPER, tenantId, orderNo.trim(), tenantId);
        return rows.stream().findFirst();
    }

    private static final RowMapper<OrderView> ORDER_MAPPER = (ResultSet rs, int rowNum) -> {
        BigDecimal amount = rs.getBigDecimal("amount");
        java.sql.Date created = rs.getDate("created_at");
        return new OrderView(
                rs.getString("id"),
                rs.getString("customer"),
                amount == null ? null : amount.toPlainString(),
                rs.getString("status"),
                created == null ? null : created.toString());
    };
}
