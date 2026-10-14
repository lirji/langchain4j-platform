package com.lrj.platform.asynctask.mapper;

/** 数据库读取投影，与领域/API对象分离；包装类型保留SQL NULL。 */
public class AsyncTaskEventJournalRow {
    /** TASK_ID 的数据库值。 */
    public String taskId;
    /** SEQUENCE 的数据库值。 */
    public Long sequence;
    /** EVENT_KEY 的数据库值。 */
    public String eventKey;
    /** EVENT_NAME 的数据库值。 */
    public String eventName;
    /** DATA_JSON 的数据库值。 */
    public String dataJson;
    /** CREATED_AT 的数据库值。 */
    public Long createdAt;
    /** WORKER_ID 的数据库值。 */
    public String workerId;
}
