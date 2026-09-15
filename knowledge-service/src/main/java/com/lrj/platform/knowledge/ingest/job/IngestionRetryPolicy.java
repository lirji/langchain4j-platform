package com.lrj.platform.knowledge.ingest.job;

import java.time.Instant;

/** 有限指数退避；接口仅公开错误码，原始异常由 worker 日志保留。 */
public final class IngestionRetryPolicy {
    private final IngestionJobProperties properties;

    public IngestionRetryPolicy(IngestionJobProperties properties) {
        properties.validateRecovery();
        this.properties = properties;
    }

    /** 参数/权限/版本问题需要人工修复，其余失败按上限退避，避免无限自动重试。 */
    public IngestionJob failed(IngestionJob job, Exception failure, String stage, Instant now) {
        boolean permanent = failure instanceof IllegalArgumentException
                || failure instanceof IngestionAuthorizationException
                || failure instanceof IngestionJobConflictException;
        String code = permanent ? "INVALID_INPUT_OR_PERMISSION" : "DEPENDENCY_FAILURE";
        if (job.execution().retries() >= properties.getMaxRetries()) {
            permanent = true;
            code = "RETRY_EXHAUSTED";
        }
        long delay = Math.min(properties.getMaxRetryDelay().toMillis(),
                properties.getRetryDelay().toMillis() * (1L << Math.min(job.execution().retries(), 30)));
        var execution = new IngestionExecution(job.execution().retries(),
                permanent ? null : now.plusMillis(delay), code, stage, null, null);
        IngestionJob result = job.withExecution(execution);
        return permanent ? IngestionJobStateMachine.manualReview(result, now) : result;
    }
}
