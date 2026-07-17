package com.lrj.platform.protocol.asynctask;

import java.time.Instant;
import java.util.Map;

/**
 * 通用异步任务的跨服务实体契约：承载任务 id、租户/用户归因、任务类型 {@code kind}、状态
 * {@link AsyncTaskStatus}、输入/结果/错误、回调地址与各时间戳，以及可选的租约字段
 * ({@code leaseOwnerId}/{@code leaseExpiresAt}) 供 worker 抢占执行。async-task-service 任务中心的核心模型。
 */
public record AsyncTask(String taskId,
                        String tenantId,
                        String userId,
                        String kind,
                        AsyncTaskStatus status,
                        Map<String, Object> input,
                        Object result,
                        String error,
                        String webhookUrl,
                        Instant createdAt,
                        Instant updatedAt,
                        Instant finishedAt,
                        String leaseOwnerId,
                        Instant leaseExpiresAt) {

    public AsyncTask(String taskId,
                     String tenantId,
                     String userId,
                     String kind,
                     AsyncTaskStatus status,
                     Map<String, Object> input,
                     Object result,
                     String error,
                     String webhookUrl,
                     Instant createdAt,
                     Instant updatedAt,
                     Instant finishedAt) {
        this(taskId, tenantId, userId, kind, status, input, result, error, webhookUrl, createdAt, updatedAt, finishedAt, null, null);
    }

    public AsyncTask {
        input = input == null ? Map.of() : Map.copyOf(input);
    }
}
