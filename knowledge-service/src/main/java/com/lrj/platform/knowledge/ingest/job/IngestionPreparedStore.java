package com.lrj.platform.knowledge.ingest.job;

/** 准备结果存储端口；生产必须持久化，绑定引用后不能自动退化为重新 prepare。 */
public interface IngestionPreparedStore {
    /** 完整写入内容寻址对象；任务引用由 worker 另用租约/revision 绑定。 */
    IngestionPreparation save(IngestionJob job, PreparedIngestionDocument prepared);
    /** 验证身份、完整性与格式后还原本次执行独享的框架对象。 */
    PreparedIngestionDocument load(IngestionJob job, IngestionPreparation preparation);
}
