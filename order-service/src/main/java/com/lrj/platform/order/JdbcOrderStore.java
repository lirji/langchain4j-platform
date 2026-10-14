package com.lrj.platform.order;

import com.lrj.platform.order.mapper.OrderStoreMapper;
import com.lrj.platform.order.mapper.OrderStoreRow;
import com.lrj.platform.persistence.PlusMapperFactory;
import com.lrj.platform.persistence.Rows;

import com.lrj.platform.protocol.order.OrderView;
import com.lrj.platform.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 订单只读存储。使用 {@code MyBatis-Plus Mapper} 直连持久化 MySQL；schema 由发布前的
 * {@code database-migrations} Job 管理，本类启动时只验证 contract。
 *
 * <p><strong>租户隔离</strong>：{@link #findByOrderNo(String)} 用参数化 {@code WHERE id = ? AND tenant_id = ?}，
 * tenant 取自过滤器链还原的 {@link TenantContext}。绑定参数的 PreparedStatement 天然防注入 ——
 * 不需要 analytics 那套给「LLM 生成的 SQL」兜底的 SqlGuard。别的租户就算知道订单号也查不到（0 行）。
 *
 * <p><strong>幂等种子</strong>：{@code app.order.seed-demo=true}（默认）且表为空时插一批演示订单（tenantA/tenantB，
 * 与 analytics-service 的 nl2sql-demo 数据对齐，便于跨租户隔离演示）。只在表空时插入，
 * 保证持久化数据跨重启保留。
 */
@Component
public class JdbcOrderStore implements OrderStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcOrderStore.class);

    private final OrderStoreMapper db;

    private final boolean seedDemo;

    public JdbcOrderStore(DataSource dataSource,
                          @Value("${app.order.seed-demo:true}") boolean seedDemo) {
        this.db = PlusMapperFactory.create(dataSource, OrderStoreMapper.class);
        this.seedDemo = seedDemo;
        init();
    }

    private void init() {
        db.readInit1();
        db.readInit2();
        log.info("orders/customers schema verified");
        if (seedDemo) {
            seedDemoDataIfEmpty();
        }
    }

    /** 表空才插演示数据（幂等，不 DROP）；已有数据则跳过，保证持久化不被重启覆盖。 */
    private void seedDemoDataIfEmpty() {
        Integer count = Rows.one(db.readSeedDemoDataIfEmpty1());
        if (count != null && count > 0) {
            return;
        }
        // tenantA 客户 + 订单（对齐 analytics-demo migration，便于两服务演示同一批数据）
        for (CustomerSeed row : List.of(
                        new CustomerSeed("1", "tenantA", "张三"),
                        new CustomerSeed("2", "tenantA", "李四"),
                        new CustomerSeed("3", "tenantA", "王五"),
                        new CustomerSeed("4", "tenantA", "赵六"),
                        new CustomerSeed("5", "tenantA", "钱七"),
                        new CustomerSeed("1001", "tenantB", "ACME-A"),
                        new CustomerSeed("1002", "tenantB", "ACME-B"))) db.insertCustomer(row.id(), row.tenant(), row.name());
        for (OrderSeed row : List.of(
                        new OrderSeed("101", "tenantA", "1", new BigDecimal("1200.00"), "已支付", "2026-05-03"),
                        new OrderSeed("102", "tenantA", "1", new BigDecimal("800.00"), "已退款", "2026-05-06"),
                        new OrderSeed("103", "tenantA", "2", new BigDecimal("2500.00"), "已发货", "2026-05-09"),
                        new OrderSeed("104", "tenantA", "3", new BigDecimal("450.00"), "已退款", "2026-05-12"),
                        new OrderSeed("105", "tenantA", "3", new BigDecimal("3200.00"), "已支付", "2026-05-20"),
                        new OrderSeed("106", "tenantA", "4", new BigDecimal("150.00"), "已取消", "2026-05-22"),
                        new OrderSeed("107", "tenantA", "4", new BigDecimal("5400.00"), "已退款", "2026-05-25"),
                        new OrderSeed("108", "tenantA", "5", new BigDecimal("990.00"), "已支付", "2026-04-28"),
                        new OrderSeed("109", "tenantA", "2", new BigDecimal("1750.00"), "已退款", "2026-04-15"),
                        // tenantB：演示隔离——tenantA 的查询不该看到这些
                        new OrderSeed("2001", "tenantB", "1001", new BigDecimal("9999.00"), "已退款", "2026-05-15"),
                        new OrderSeed("2002", "tenantB", "1002", new BigDecimal("8888.00"), "已支付", "2026-05-18"))) db.insertOrder(
                                row.id(),
                                row.tenant(),
                                row.customer(),
                                row.amount(),
                                row.status(),
                                row.date());
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
        List<OrderView> rows = Rows.map(db.readFindByOrderNo1(tenantId, orderNo.trim(), tenantId), ORDER_MAPPER);
        return rows.stream().findFirst();
    }

    private static final java.util.function.BiFunction<OrderStoreRow, Integer, OrderView> ORDER_MAPPER = (OrderStoreRow rs, Integer rowNum) -> {
        BigDecimal amount = rs.amount;
        java.sql.Date created = rs.createdAt;
        return new OrderView(
                rs.id,
                rs.customer,
                amount == null ? null : amount.toPlainString(),
                rs.status,
                created == null ? null : created.toString());
    };
    /** 演示种子的输入类型，避免位置数组造成金额/日期错位。 */
    private record CustomerSeed(String id, String tenant, String name) {}
    private record OrderSeed(String id, String tenant, String customer, BigDecimal amount, String status, String date) {}
}
