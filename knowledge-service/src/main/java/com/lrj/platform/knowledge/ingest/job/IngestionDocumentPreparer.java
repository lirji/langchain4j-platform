package com.lrj.platform.knowledge.ingest.job;

/**
 * 从 S3-compatible 原文引用生成本次 worker 所需的切片与向量。
 */
@FunctionalInterface
public interface IngestionDocumentPreparer {

    PreparedIngestionDocument prepare(IngestionJob job) throws Exception;

    /** 兼容既有实现；支持分批的实现必须在外部调用前后执行检查点。 */
    default PreparedIngestionDocument prepare(IngestionJob job, Runnable checkpoint) throws Exception {
        checkpoint.run(); return prepare(job);
    }
}
