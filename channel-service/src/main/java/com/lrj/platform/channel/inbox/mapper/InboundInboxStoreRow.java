package com.lrj.platform.channel.inbox.mapper;

/** 数据库读取投影，与领域/API对象分离；包装类型保留SQL NULL。 */
public class InboundInboxStoreRow {
    /** PAYLOAD_HASH 的数据库值。 */
    public String payloadHash;
    /** INBOX_ID 的数据库值。 */
    public String inboxId;
    /** TENANT_ID 的数据库值。 */
    public String tenantId;
    /** SOURCE_NAME 的数据库值。 */
    public String sourceName;
    /** MESSAGE_ID 的数据库值。 */
    public String messageId;
    /** PAYLOAD_JSON 的数据库值。 */
    public String payloadJson;
    /** TRACE_ID 的数据库值。 */
    public String traceId;
    /** STATUS_CODE 的数据库值。 */
    public String statusCode;
    /** ATTEMPTS 的数据库值。 */
    public Integer attempts;
    /** LEASE_OWNER 的数据库值。 */
    public String leaseOwner;
    /** LEASE_EPOCH 的数据库值。 */
    public Long leaseEpoch;
    /** LEASE_UNTIL 的数据库值。 */
    public Long leaseUntil;
    /** NEXT_ATTEMPT_AT 的数据库值。 */
    public Long nextAttemptAt;
    /** CREATED_AT 的数据库值。 */
    public Long createdAt;
    /** UPDATED_AT 的数据库值。 */
    public Long updatedAt;
    /** ERROR_CODE 的数据库值。 */
    public String errorCode;
}
