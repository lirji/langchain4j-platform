package com.lrj.platform.knowledge.ingest.job.mapper;

/** 数据库读取投影，与领域/API对象分离；包装类型保留SQL NULL。 */
public class IngestionJobStoreRow {
    /** 准备结果协议与引用；NULL 保留旧任务语义。 */
    public String preparationJson;
    /** SINKS_JSON 的数据库值。 */
    public String sinksJson;
    /** REQUIRED_SINKS_JSON 的数据库值。 */
    public String requiredSinksJson;
    /** JOB_ID 的数据库值。 */
    public String jobId;
    /** IDEMPOTENCY_KEY 的数据库值。 */
    public String idempotencyKey;
    /** TENANT_ID 的数据库值。 */
    public String tenantId;
    /** USER_ID 的数据库值。 */
    public String userId;
    /** SCOPES_JSON 的数据库值。 */
    public String scopesJson;
    /** DEPARTMENT 的数据库值。 */
    public String department;
    /** TRACE_ID 的数据库值。 */
    public String traceId;
    /** DOCUMENT_ID 的数据库值。 */
    public String documentId;
    /** DISPLAY_NAME 的数据库值。 */
    public String displayName;
    /** CATEGORY 的数据库值。 */
    public String category;
    /** DOCUMENT_VERSION 的数据库值。 */
    public Long documentVersion;
    /** NEW_DOCUMENT 的数据库值。 */
    public Boolean newDocument;
    /** REVISION 的数据库值。 */
    public Long revision;
    /** SOURCE_BUCKET 的数据库值。 */
    public String sourceBucket;
    /** SOURCE_OBJECT_KEY 的数据库值。 */
    public String sourceObjectKey;
    /** SOURCE_HASH 的数据库值。 */
    public String sourceHash;
    /** SOURCE_CONTENT_TYPE 的数据库值。 */
    public String sourceContentType;
    /** SOURCE_SIZE 的数据库值。 */
    public Long sourceSize;
    /** STATUS 的数据库值。 */
    public String status;
    /** ERROR_TEXT 的数据库值。 */
    public String errorText;
    /** CREATED_AT 的数据库值。 */
    public java.sql.Timestamp createdAt;
    /** UPDATED_AT 的数据库值。 */
    public java.sql.Timestamp updatedAt;
    /** RETRY_COUNT 的数据库值。 */
    public Integer retryCount;
    /** ERROR_CODE 的数据库值。 */
    public String errorCode;
    /** FAILED_STAGE 的数据库值。 */
    public String failedStage;
    /** LEASE_OWNER 的数据库值。 */
    public String leaseOwner;
    /** nextRetryAt 的数据库值，保留空值语义。 */
    public java.sql.Timestamp nextRetryAt;
    /** leaseUntil 的数据库值，保留空值语义。 */
    public java.sql.Timestamp leaseUntil;
    /** total 的数据库值，保留空值语义。 */
    public Long total;
}
