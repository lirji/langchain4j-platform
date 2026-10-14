package com.lrj.platform.conversation.memory.profile.mapper;

/** 数据库读取投影，与领域/API对象分离；包装类型保留SQL NULL。 */
public class UserProfileStoreRow {
    /** AUDIT_ID 的数据库值。 */
    public Long auditId;
    /** RECORD_ID 的数据库值。 */
    public Long recordId;
    /** ACTION_CODE 的数据库值。 */
    public String actionCode;
    /** REVISION 的数据库值。 */
    public Long revision;
    /** OCCURRED_AT 的数据库值。 */
    public Long occurredAt;
    /** GENERATION 的数据库值。 */
    public Long generation;
    /** MEMORY_ENABLED 的数据库值。 */
    public Boolean memoryEnabled;
    /** MEMORY_ID 的数据库值。 */
    public String memoryId;
    /** MEMORY_TEXT 的数据库值。 */
    public String memoryText;
    /** MEMORY_TYPE 的数据库值。 */
    public String memoryType;
    /** CREATED_AT 的数据库值。 */
    public Long createdAt;
    /** SOURCE_CHAT_ID 的数据库值。 */
    public String sourceChatId;
    /** MEMORY_STATE 的数据库值。 */
    public String memoryState;
    /** PINNED 的数据库值。 */
    public Boolean pinned;
    /** EXPIRES_AT 的数据库值。 */
    public Long expiresAt;
}
