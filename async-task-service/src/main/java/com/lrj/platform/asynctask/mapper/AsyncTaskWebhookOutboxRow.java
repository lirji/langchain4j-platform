package com.lrj.platform.asynctask.mapper;

/** 数据库读取投影，与领域/API对象分离；包装类型保留SQL NULL。 */
public class AsyncTaskWebhookOutboxRow {
    /** OUTBOX_ID 的数据库值。 */
    public String outboxId;
    /** TASK_ID 的数据库值。 */
    public String taskId;
    /** TENANT_ID 的数据库值。 */
    public String tenantId;
    /** TARGET_URL 的数据库值。 */
    public String targetUrl;
    /** TASK_STATUS 的数据库值。 */
    public String taskStatus;
    /** PAYLOAD_JSON 的数据库值。 */
    public String payloadJson;
    /** ATTEMPTS 的数据库值。 */
    public Integer attempts;
    /** CLAIMED_BY 的数据库值。 */
    public String claimedBy;
    /** STATUS 的数据库值。 */
    public String status;
    /** LAST_ERROR 的数据库值。 */
    public String lastError;
    /** CREATED_AT 的数据库值。 */
    public Long createdAt;
    /** UPDATED_AT 的数据库值。 */
    public Long updatedAt;
}
