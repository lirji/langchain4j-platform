package com.lrj.platform.asynctask.mapper;

/** 数据库读取投影，与领域/API对象分离；包装类型保留SQL NULL。 */
public class AsyncTaskLifecycleOutboxRow {
    /** eventId 的数据库值，保留空值语义。 */
    public String eventId;
    /** tenantId 的数据库值，保留空值语义。 */
    public String tenantId;
    /** payloadJson 的数据库值，保留空值语义。 */
    public String payloadJson;
    /** attempts 的数据库值，保留空值语义。 */
    public Integer attempts;
    /** claimedBy 的数据库值，保留空值语义。 */
    public String claimedBy;
}
