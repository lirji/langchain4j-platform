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

    /** 先读内容序号、再读有界候选；外部注册表和权限检查在数据库事务之外执行。 */
    ContentSnapshot contentSnapshot(String tenantId, String contentHash, int limit);

    /** 仅在锁外检查期间没有同内容新任务时原子创建；竞争失败返回空，由调用方重新检查。 */
    Optional<IngestionJob> createIfContentUnchanged(IngestionJob job, long generation);

    /** truncated 表示不能证明没有重复，调用方必须保守拒绝，不能悄悄漏检。 */
    record ContentSnapshot(long generation, List<IngestionJob> matches, boolean truncated) {
        public ContentSnapshot { matches = List.copyOf(matches); }
    }

    Optional<IngestionJob> find(String tenantId, String jobId);

    Optional<IngestionJob> findByIdempotency(String tenantId, String idempotencyKey);

    /**
     * 仅当当前 revision 等于 expectedRevision 时保存，并返回 revision+1 的新快照。
     */
    IngestionJob save(IngestionJob job, long expectedRevision);

    /** 事务性公平领取；仅存储实现可以在同一边界检查跨副本额度并获得租约。 */
    Optional<Claim> claim(ClaimRequest request);

    /** tenant/job 同为空表示公平领取下一条；指定任务的编程式调用仍受同一额度保护。 */
    record ClaimRequest(String tenantId, String jobId, Instant now, java.time.Duration leaseDuration,
                        int globalLimit, int tenantLimit) {
        public ClaimRequest {
            if ((tenantId == null) != (jobId == null) || now == null || leaseDuration == null
                    || leaseDuration.isNegative() || leaseDuration.isZero() || globalLimit < 1
                    || tenantLimit < 1 || tenantLimit > globalLimit)
                throw new IngestionResourceException(IngestionResourceError.ADMISSION_CONFIG);
        }
    }
    /** 原状态用于区分可安全准备的新任务与未绑定快照的旧 PROCESSING 任务。 */
    record Claim(IngestionJob original, IngestionJob leased) { }

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
