package com.lrj.platform.workflow.mapper;

/** 数据库读取投影，与领域/API对象分离；包装类型保留SQL NULL。 */
public class WorkflowIdempotencyStoreRow {
    /** REQUEST_HASH 的数据库值。 */
    public String requestHash;
    /** INSTANCE_ID 的数据库值。 */
    public String instanceId;
}
