package com.lrj.platform.workflow.mapper;

/** 数据库读取投影，与领域/API对象分离；包装类型保留SQL NULL。 */
public class WorkflowOutboxRow {
    /** instanceId 的数据库值，保留空值语义。 */
    public String instanceId;
    /** tenantId 的数据库值，保留空值语义。 */
    public String tenantId;
    /** targetUrl 的数据库值，保留空值语义。 */
    public String targetUrl;
    /** attempts 的数据库值，保留空值语义。 */
    public Integer attempts;
    /** claimedBy 的数据库值，保留空值语义。 */
    public String claimedBy;
}
