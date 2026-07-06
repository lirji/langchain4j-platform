package com.lrj.platform.protocol.asynctask;

import java.time.Instant;
import java.util.Map;

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
