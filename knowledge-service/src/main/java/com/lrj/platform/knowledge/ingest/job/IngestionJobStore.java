package com.lrj.platform.knowledge.ingest.job;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Ingestion job 权威存储。所有读取都显式带 tenantId；保存使用 revision 做乐观并发控制。
 */
public interface IngestionJobStore {

    /**
     * 按 `(tenantId, idempotencyKey)` 幂等创建。相同键已存在时返回原 job，不启动第二次入库。
     */
    IngestionJob createOrGet(IngestionJob job);

    Optional<IngestionJob> find(String tenantId, String jobId);

    Optional<IngestionJob> findByIdempotency(String tenantId, String idempotencyKey);

    /**
     * 仅当当前 revision 等于 expectedRevision 时保存，并返回 revision+1 的新快照。
     */
    IngestionJob save(IngestionJob job, long expectedRevision);

    /** 找出已接收或已被恢复为 PROCESSING、可由 worker 抢占的任务。 */
    List<IngestionJob> findRunnable(int limit);

    /** 找出需要 reconcile 的 PARTIAL/FAILED 或超时 PROCESSING 任务。 */
    List<IngestionJob> findRecoverable(Instant processingStaleBefore, int limit);

    /** 恢复筛选在存储层先过滤到期任务，再限量，避免未到期任务阻塞后续队列。 */
    List<IngestionJob> findRecoverable(Instant processingStaleBefore, Instant now, int limit);

    /** 全局低基数运行指标，不暴露租户或文档内容。 */
    java.util.Map<IngestionStatus, Long> countsByStatus();
    Optional<Instant> oldestPending();
}
