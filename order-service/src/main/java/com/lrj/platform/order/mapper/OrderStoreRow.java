package com.lrj.platform.order.mapper;

/** 数据库读取投影，与领域/API对象分离；包装类型保留SQL NULL。 */
public class OrderStoreRow {
    /** AMOUNT 的数据库值。 */
    public java.math.BigDecimal amount;
    /** CREATED_AT 的数据库值。 */
    public java.sql.Date createdAt;
    /** ID 的数据库值。 */
    public String id;
    /** CUSTOMER 的数据库值。 */
    public String customer;
    /** STATUS 的数据库值。 */
    public String status;
}
