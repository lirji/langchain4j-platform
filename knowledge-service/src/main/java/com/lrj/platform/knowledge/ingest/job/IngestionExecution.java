package com.lrj.platform.knowledge.ingest.job;

import java.time.Instant;

/** 可持久化的恢复与租约信息；空值兼容迁移前的任务。 */
public record IngestionExecution(int retries, Instant nextRetryAt, String errorCode,
                                 String failedStage, String leaseOwner, Instant leaseUntil) {
    public IngestionExecution {
        if (retries < 0 || (leaseOwner == null) != (leaseUntil == null)) {
            throw new IllegalArgumentException("invalid ingestion recovery state");
        }
    }

    public static final IngestionExecution EMPTY = new IngestionExecution(0, null, null, null, null, null);

    /** 修改租约不改变失败诊断，便于恢复过程继续观测。 */
    public IngestionExecution lease(String owner, Instant until) {
        return new IngestionExecution(retries, nextRetryAt, errorCode, failedStage, owner, until);
    }
}
