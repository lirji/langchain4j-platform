package com.lrj.platform.knowledge.ingest.job;

/**
 * 从 S3-compatible 原文引用生成本次 worker 所需的切片与向量。
 */
@FunctionalInterface
public interface IngestionDocumentPreparer {

    PreparedIngestionDocument prepare(IngestionJob job) throws Exception;
}
