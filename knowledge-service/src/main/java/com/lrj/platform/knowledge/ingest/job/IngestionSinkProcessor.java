package com.lrj.platform.knowledge.ingest.job;

/**
 * Java 领域 sink 执行端口。实现必须按 documentVersion 做幂等 upsert，且不得从模型输出读取身份。
 */
@FunctionalInterface
public interface IngestionSinkProcessor {

    void process(
            IngestionJob job,
            PreparedIngestionDocument prepared,
            IngestionSink sink
    ) throws Exception;

    /** 分批副作用检查执行权，既有简单实现仍由 worker 在阶段边界保护。 */
    default void process(IngestionJob job, PreparedIngestionDocument prepared, IngestionSink sink,
                         Runnable checkpoint) throws Exception {
        checkpoint.run(); process(job,prepared,sink);
    }
}
