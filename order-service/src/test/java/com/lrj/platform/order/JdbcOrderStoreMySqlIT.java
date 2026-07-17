package com.lrj.platform.order;

import com.lrj.platform.protocol.order.OrderView;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JdbcOrderStore} 的<strong>真 MySQL</strong> 集成测（<strong>不用 H2</strong>）。
 * 用 {@code @EnabledIfEnvironmentVariable} 门控：只有设了 {@code ORDER_IT_MYSQL_URL} 才实跑，
 * 否则 JUnit 直接标记跳过 —— 保证无库环境（CI/本地）的 {@code mvn test} 不受影响。
 *
 * <p>跑法：
 * <pre>
 * docker compose -f deploy/docker-compose.yml up -d mysql
 * ORDER_IT_MYSQL_URL='jdbc:mysql://localhost:3306/order_it?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true' \
 *   ORDER_IT_MYSQL_USER=root ORDER_IT_MYSQL_PASSWORD=root mvn -pl order-service test
 * </pre>
 *
 * <p>用独立库自插已知数据（seed-demo 关），断言核心不变量：命中租户查得到、<strong>跨租户查不到</strong>、
 * 不存在返回空。结构照 {@code async-task-service} 的 {@code JdbcAsyncTaskStoreTest}，只把 H2 工厂换成 MySQL。
 */
@EnabledIfEnvironmentVariable(named = "ORDER_IT_MYSQL_URL", matches = ".+")
class JdbcOrderStoreMySqlIT {

    private JdbcTemplate jdbc;
    private JdbcOrderStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                System.getenv("ORDER_IT_MYSQL_URL"),
                envOr("ORDER_IT_MYSQL_USER", "root"),
                envOr("ORDER_IT_MYSQL_PASSWORD", "root"));
        jdbc = new JdbcTemplate(ds);
        store = new JdbcOrderStore(jdbc, false); // 关演示种子，自插已知数据
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM customers");
        jdbc.update("INSERT INTO customers (id, tenant_id, name) VALUES (?, ?, ?)", "1", "tenantA", "张三");
        jdbc.update("INSERT INTO orders (id, tenant_id, customer_id, amount, status, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                "101", "tenantA", "1", new BigDecimal("1200.00"), "已支付", java.sql.Date.valueOf("2026-05-03"));
        jdbc.update("INSERT INTO orders (id, tenant_id, customer_id, amount, status, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                "2001", "tenantB", "1001", new BigDecimal("9999.00"), "已退款", java.sql.Date.valueOf("2026-05-15"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void returnsOrderForOwningTenant() {
        TenantContext.set(new TenantContext.Tenant("tenantA", "analyst-a", java.util.Set.of()));

        Optional<OrderView> found = store.findByOrderNo("101");

        assertThat(found).isPresent();
        OrderView v = found.get();
        assertThat(v.orderNo()).isEqualTo("101");
        assertThat(v.status()).isEqualTo("已支付");
        assertThat(v.amount()).isEqualTo("1200.00");
        assertThat(v.customer()).isEqualTo("张三");
    }

    @Test
    void doesNotLeakAnotherTenantsOrder() {
        // tenantA 查 tenantB 的订单 2001 —— 即便知道订单号也应查不到（SQL 层租户隔离）
        TenantContext.set(new TenantContext.Tenant("tenantA", "analyst-a", java.util.Set.of()));

        assertThat(store.findByOrderNo("2001")).isEmpty();
    }

    @Test
    void returnsEmptyForUnknownOrder() {
        TenantContext.set(new TenantContext.Tenant("tenantA", "analyst-a", java.util.Set.of()));

        assertThat(store.findByOrderNo("999999")).isEmpty();
    }

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }
}
