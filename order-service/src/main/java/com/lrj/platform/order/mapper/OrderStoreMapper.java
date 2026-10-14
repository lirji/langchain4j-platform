package com.lrj.platform.order.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 类型化持久化入口；SQL在同名XML，调用者不能传入SQL文本。 */
public interface OrderStoreMapper {
    /** 保留原readInit1操作的条件与影响行数契约。 */
    List<OrderStoreRow> readInit1();
    /** 保留原readInit2操作的条件与影响行数契约。 */
    List<OrderStoreRow> readInit2();
    /** 保留原readSeedDemoDataIfEmpty1操作的条件与影响行数契约。 */
    List<Integer> readSeedDemoDataIfEmpty1();
    /** 保留原readFindByOrderNo1操作的条件与影响行数契约。 */
    List<OrderStoreRow> readFindByOrderNo1(
        @Param("tenantId") String tenantId,
        @Param("id") String id,
        @Param("tenantId2") String tenantId2);
    /** 绑定参数执行insertCustomer，保持既有条件和事务边界。 */
    int insertCustomer(@Param("id") String id, @Param("tenantId") String tenantId, @Param("name") String name);
    /** 绑定参数执行insertOrder，保持既有条件和事务边界。 */
    int insertOrder(
        @Param("id") String id,
        @Param("tenantId") String tenantId,
        @Param("customerId") String customerId,
        @Param("amount") java.math.BigDecimal amount,
        @Param("status") String status,
        @Param("createdAt") String createdAt);
}
