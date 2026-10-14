package com.lrj.platform.workflow.mapper;

/** 数据库读取投影，与领域/API对象分离；包装类型保留SQL NULL。 */
public class WorkflowTerminalEventOutboxRow {
    /** instanceId 的数据库值，保留空值语义。 */
    public String instanceId;
    /** tenantId 的数据库值，保留空值语义。 */
    public String tenantId;
    /** chatId 的数据库值，保留空值语义。 */
    public String chatId;
    /** outcome 的数据库值，保留空值语义。 */
    public String outcome;
    /** webhookUrl 的数据库值，保留空值语义。 */
    public String webhookUrl;
    /** attempts 的数据库值，保留空值语义。 */
    public Integer attempts;
    /** claimedBy 的数据库值，保留空值语义。 */
    public String claimedBy;
}
