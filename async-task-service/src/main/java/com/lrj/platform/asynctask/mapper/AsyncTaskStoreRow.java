package com.lrj.platform.asynctask.mapper;

/** 数据库读取投影，与领域/API对象分离；包装类型保留SQL NULL。 */
public class AsyncTaskStoreRow {
    /** KIND 的数据库值。 */
    public String kind;
    /** STATUS 的数据库值。 */
    public String status;
    /** LEASE_OWNER_ID 的数据库值。 */
    public String leaseOwnerId;
    /** OPEN_TASKS 的数据库值。 */
    public Long openTasks;
    /** TASK_ID 的数据库值。 */
    public String taskId;
    /** TENANT_ID 的数据库值。 */
    public String tenantId;
    /** USER_ID 的数据库值。 */
    public String userId;
    /** INPUT_JSON 的数据库值。 */
    public String inputJson;
    /** RESULT_JSON 的数据库值。 */
    public String resultJson;
    /** ERROR_TEXT 的数据库值。 */
    public String errorText;
    /** WEBHOOK_URL 的数据库值。 */
    public String webhookUrl;
    /** CREATED_AT 的数据库值。 */
    public Long createdAt;
    /** UPDATED_AT 的数据库值。 */
    public Long updatedAt;
    /** LEASE_EPOCH 的数据库值。 */
    public Long leaseEpoch;
    /** finishedAt 的数据库值，保留空值语义。 */
    public Long finishedAt;
    /** leaseExpiresAt 的数据库值，保留空值语义。 */
    public Long leaseExpiresAt;
}
