package com.lrj.platform.knowledge.operations.mapper;

/** 数据库读取投影，与领域/API对象分离；包装类型保留SQL NULL。 */
public class KnowledgeOperationsStoreRow {
    /** REQUEST_HASH 的数据库值。 */
    public String requestHash;
    /** BODY_JSON 的数据库值。 */
    public String bodyJson;
    /** ID 的数据库值。 */
    public Long id;
    /** CANDIDATE_ID 的数据库值。 */
    public String candidateId;
    /** ACTOR 的数据库值。 */
    public String actor;
    /** ACTION_CODE 的数据库值。 */
    public String actionCode;
    /** REVISION 的数据库值。 */
    public Long revision;
    /** OCCURRED_AT 的数据库值。 */
    public Long occurredAt;
    /** reasonCode 的数据库值，保留空值语义。 */
    public String reasonCode;
    /** total 的数据库值，保留空值语义。 */
    public Long total;
}
