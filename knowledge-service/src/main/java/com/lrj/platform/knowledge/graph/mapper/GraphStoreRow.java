package com.lrj.platform.knowledge.graph.mapper;

/** 数据库读取投影，与领域/API对象分离；包装类型保留SQL NULL。 */
public class GraphStoreRow {
    /** SUBJECT_TEXT 的数据库值。 */
    public String subjectText;
    /** RELATION_TEXT 的数据库值。 */
    public String relationText;
    /** OBJECT_TEXT 的数据库值。 */
    public String objectText;
    /** SOURCE_ID 的数据库值。 */
    public String sourceId;
    /** TENANT_ID 的数据库值。 */
    public String tenantId;
    /** CATEGORY 的数据库值。 */
    public String category;
}
