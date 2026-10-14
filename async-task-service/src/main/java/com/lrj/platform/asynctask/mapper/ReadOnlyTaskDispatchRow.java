package com.lrj.platform.asynctask.mapper;

/** 数据库读取投影，与领域/API对象分离；包装类型保留SQL NULL。 */
public class ReadOnlyTaskDispatchRow {
    /** department 的数据库值，保留空值语义。 */
    public String department;
    /** traceId 的数据库值，保留空值语义。 */
    public String traceId;
}
