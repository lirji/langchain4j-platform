package com.lrj.platform.agent.async;

import java.time.Instant;
import java.util.Map;

public record AgentTaskWebhookPayload(String taskId,
                                      String tenantId,
                                      String userId,
                                      AgentTaskStatus status,
                                      Map<String, Object> input,
                                      Object result,
                                      String error,
                                      Instant createdAt,
                                      Instant updatedAt,
                                      Instant finishedAt) {

    public static AgentTaskWebhookPayload from(AgentAsyncTask task) {
        return new AgentTaskWebhookPayload(
                task.taskId(),
                task.tenantId(),
                task.userId(),
                task.status(),
                task.input(),
                task.result(),
                task.error(),
                task.createdAt(),
                task.updatedAt(),
                task.finishedAt());
    }
}
